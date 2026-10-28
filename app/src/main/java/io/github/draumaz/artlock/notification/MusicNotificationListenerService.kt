package io.github.draumaz.artlock.notification

import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.scale
import io.github.draumaz.artlock.App
import io.github.draumaz.artlock.data.local.StaticArtworkStorage
import io.github.draumaz.artlock.data.model.ArtworkSource
import io.github.draumaz.artlock.data.model.LookupResult
import io.github.draumaz.artlock.data.model.TrackInfo
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.data.model.WallpaperContentType
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MusicNotificationListenerService : NotificationListenerService() {

    // Runs on the main thread, like the NotificationListenerService callbacks, so the state below is
    // only ever touched from one thread. Blocking work (disk, network) switches dispatcher inside.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Artwork resolution failed", e) }
    )
    private val parser by lazy { NotificationMediaParser(this) }
    private val artworkStorage by lazy { StaticArtworkStorage(applicationContext) }
    private val app get() = application as App

    private var resolutionJob: Job? = null
    // The app whose track is currently shown; other apps pausing or dismissing their notification
    // must not reset the wallpaper
    private var activePackage: String? = null
    private var lastProcessedTrackKey: String? = null
    private var lastSavedContent: WallpaperContent? = null
    private var lastUpdateTime: Long = 0L
    // True when the current track ended up with no real artwork (missing, or only a placeholder)
    private var lastArtworkMissing = false

    private var supportedPackages: Set<String> = SupportedMusicApps.packages

    companion object {
        private const val TAG = "MusicNotification"

        /** System artwork smaller than this (shortest side) is replaced by iTunes artwork when available. */
        private const val MIN_SYSTEM_ARTWORK_PX = 600

        /** How often an unchanged, still-playing track refreshes its saved timestamp. */
        private const val REFRESH_INTERVAL_MS = 5_000L

        /**
         * Apps like Spotify supply a generic flat icon (e.g. a grey disc) when a track has no art.
         * Real covers downsampled to 24x24 have dozens of distinct 4-bit colors; placeholders have
         * only a handful. A false positive just costs an iTunes lookup, since we fall back to it.
         */
        private const val MIN_ARTWORK_COLORS = 16
        private const val COLOR_SAMPLE_SIZE = 24
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            app.wallpaperStateStore.supportedPackagesFlow.collect { supportedPackages = it }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName !in supportedPackages) return

        val trackInfo = parser.parse(sbn.notification, packageName) ?: return
        Log.d(TAG, "onNotificationPosted: playing=${trackInfo.isPlaying}, title=${trackInfo.title}")

        if (!trackInfo.isPlaying) {
            if (activePackage == null || activePackage == packageName) clearTrack()
            return
        }

        activePackage = packageName
        val trackKey = "${trackInfo.title}-${trackInfo.artist}"

        // Apps like Spotify post a placeholder first and the real cover in a later update, so
        // re-resolve the same track if we have no real artwork yet and the update brings some
        val savedContent = lastSavedContent
        val artworkUpgrade = trackKey == lastProcessedTrackKey && savedContent != null &&
            lastArtworkMissing && trackInfo.staticArtworkBitmap?.looksLikePlaceholder() == false
        if (trackKey == lastProcessedTrackKey && !artworkUpgrade) {
            // Same track: resolution is either in progress (no saved content yet) or done
            val now = System.currentTimeMillis()
            if (savedContent != null && now - lastUpdateTime > REFRESH_INTERVAL_MS) {
                lastUpdateTime = now
                serviceScope.launch { app.wallpaperStateStore.save(savedContent.copy(updatedAt = now)) }
            }
            return
        }

        lastProcessedTrackKey = trackKey
        lastSavedContent = null
        resolutionJob?.cancel()
        Log.d(TAG, if (artworkUpgrade) "Real artwork arrived for $trackKey, re-resolving" else "Starting resolution for $trackKey")
        resolutionJob = serviceScope.launch { resolve(trackInfo, trackKey) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName !in supportedPackages) return
        Log.d(TAG, "onNotificationRemoved: $packageName")
        if (activePackage == null || activePackage == packageName) clearTrack()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun resolve(trackInfo: TrackInfo, trackKey: String) {
        // Prefer the artwork Android supplies with the media session/notification
        val systemContent = getSystemContent(trackInfo)
        val systemBitmap = trackInfo.staticArtworkBitmap
        val systemArtworkIssue = when {
            systemBitmap == null -> "missing"
            minOf(systemBitmap.width, systemBitmap.height) < MIN_SYSTEM_ARTWORK_PX -> "${systemBitmap.width}x${systemBitmap.height}"
            systemBitmap.looksLikePlaceholder() -> "a placeholder"
            else -> null
        }
        val isPlaceholder = systemArtworkIssue == "a placeholder"
        // A placeholder is treated as no artwork, so the wallpaper shows the default background
        val fallbackContent = if (isPlaceholder) {
            systemContent.copy(contentType = WallpaperContentType.NONE, staticImagePath = null, artworkSource = null)
        } else {
            systemContent
        }
        val resolvedContent = if (systemArtworkIssue == null && systemContent.staticImagePath != null) {
            Log.d(TAG, "Using system artwork (${systemBitmap?.width}x${systemBitmap?.height}) for $trackKey")
            systemContent
        } else {
            // Missing, low-res or placeholder system artwork: show what we have now, then try iTunes
            Log.d(TAG, "System artwork is $systemArtworkIssue for $trackKey, querying iTunes")
            app.liveWallpaperFlow.emit(fallbackContent)
            resolveRemoteContent(trackInfo) ?: fallbackContent
        }
        // Record what the app supplied, for the details shown in the settings screen
        val finalContent = resolvedContent.copy(
            systemArtworkSize = systemBitmap?.let { "${it.width}×${it.height}" },
            systemArtworkPlaceholder = isPlaceholder
        )

        lastArtworkMissing = finalContent.staticImagePath == null
        lastSavedContent = finalContent
        lastUpdateTime = System.currentTimeMillis()
        Log.d(TAG, "Finished resolution for $trackKey")

        // Emit directly for an instant update, then persist
        app.liveWallpaperFlow.emit(finalContent)
        app.wallpaperStateStore.save(finalContent)
    }

    /** Forget the current track and fall back to the default background. */
    private fun clearTrack() {
        activePackage = null
        lastProcessedTrackKey = null
        lastSavedContent = null
        resolutionJob?.cancel()
        serviceScope.launch { applyDefaultWallpaper() }
    }

    private suspend fun applyDefaultWallpaper() {
        val defaultImagePath = app.wallpaperStateStore.defaultWallpaperFlow.first()
        val content = if (defaultImagePath != null) {
            WallpaperContent(trackTitle = "", contentType = WallpaperContentType.STATIC, staticImagePath = defaultImagePath, updatedAt = System.currentTimeMillis())
        } else {
            WallpaperContent(contentType = WallpaperContentType.NONE, updatedAt = System.currentTimeMillis())
        }
        app.liveWallpaperFlow.emit(content)
        app.wallpaperStateStore.save(content)
    }

    private fun Bitmap.looksLikePlaceholder(): Boolean = try {
        val sample = scale(COLOR_SAMPLE_SIZE, COLOR_SAMPLE_SIZE)
        val pixels = IntArray(COLOR_SAMPLE_SIZE * COLOR_SAMPLE_SIZE)
        sample.getPixels(pixels, 0, COLOR_SAMPLE_SIZE, 0, 0, COLOR_SAMPLE_SIZE, COLOR_SAMPLE_SIZE)
        if (sample !== this) sample.recycle()
        // Keep the top 4 bits of each RGB channel
        pixels.mapTo(HashSet()) { (it shr 4) and 0x0F0F0F }.size < MIN_ARTWORK_COLORS
    } catch (_: Exception) {
        // e.g. hardware bitmaps can't be read back; assume it's real artwork
        false
    }

    private suspend fun resolveRemoteContent(trackInfo: TrackInfo): WallpaperContent? {
        val result = app.artworkRepository.resolveArtwork(trackInfo) as? LookupResult.StaticHighRes ?: return null
        val path = artworkStorage.downloadAndSave(result.imageUrl, trackInfo) ?: return null
        return trackContent(trackInfo).copy(
            contentType = WallpaperContentType.STATIC,
            staticImagePath = path,
            artworkSource = ArtworkSource.ITUNES
        )
    }

    /** Content for the track using the artwork the music app supplied, if any could be saved. */
    private suspend fun getSystemContent(trackInfo: TrackInfo): WallpaperContent {
        val path = trackInfo.staticArtworkBitmap?.let { artworkStorage.save(it, trackInfo) }
        return if (path != null) {
            trackContent(trackInfo).copy(
                contentType = WallpaperContentType.STATIC,
                staticImagePath = path,
                artworkSource = ArtworkSource.SYSTEM
            )
        } else {
            trackContent(trackInfo)
        }
    }

    private fun trackContent(trackInfo: TrackInfo) = WallpaperContent(
        trackTitle = trackInfo.title,
        trackArtist = trackInfo.artist,
        trackAlbum = trackInfo.album,
        sourcePackage = trackInfo.packageName,
        updatedAt = System.currentTimeMillis()
    )
}
