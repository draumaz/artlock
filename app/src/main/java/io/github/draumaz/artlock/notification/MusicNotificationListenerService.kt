package io.github.draumaz.artlock.notification

import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.draumaz.artlock.App
import io.github.draumaz.artlock.data.local.StaticArtworkStorage
import io.github.draumaz.artlock.data.model.LookupResult
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.data.model.WallpaperContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MusicNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parser by lazy { NotificationMediaParser(this) }

    private var resolutionJob: Job? = null
    private var lastProcessedTrackKey: String? = null
    private var lastSavedContent: WallpaperContent? = null
    private var lastUpdateTime: Long = 0L
    // True when the current track ended up with no real artwork (missing, or only a placeholder)
    private var lastArtworkMissing = false

    private var supportedPackages: Set<String> = SupportedMusicApps.packages

    companion object {
        /** System artwork smaller than this (shortest side) is replaced by iTunes artwork when available. */
        private const val MIN_SYSTEM_ARTWORK_PX = 600

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
            (application as App).wallpaperStateStore.supportedPackagesFlow.collect {
                supportedPackages = it
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName !in supportedPackages) return

        val notification = sbn.notification ?: return
        val trackInfo = parser.parse(notification, packageName) ?: return
        Log.d("MusicNotification", "onNotificationPosted: playing=${trackInfo.isPlaying}, title=${trackInfo.title}")

        val app = application as App
        val staticArtworkStorage = StaticArtworkStorage(applicationContext)

        val trackKey = "${trackInfo.title}-${trackInfo.artist}"

        if (trackInfo.isPlaying) {
            // Apps like Spotify post a placeholder first and the real cover in a later update, so
            // re-resolve the same track if we have no real artwork yet and the update brings some
            val artworkUpgrade = trackKey == lastProcessedTrackKey && lastSavedContent != null &&
                lastArtworkMissing && trackInfo.staticArtworkBitmap?.looksLikePlaceholder() == false
            if (trackKey == lastProcessedTrackKey && !artworkUpgrade) {
                if (lastSavedContent == null) {
                    Log.d("MusicNotification", "Resolution already in progress for $trackKey")
                    return
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > 5000) {
                        lastUpdateTime = now
                        serviceScope.launch {
                            app.wallpaperStateStore.save(lastSavedContent!!.copy(updatedAt = now))
                        }
                    }
                }
                return
            }

            lastProcessedTrackKey = trackKey
            lastSavedContent = null
            resolutionJob?.cancel()
            
            Log.d("MusicNotification", if (artworkUpgrade) "Real artwork arrived for $trackKey, re-resolving" else "Starting resolution for $trackKey")
            resolutionJob = serviceScope.launch {
                // Prefer the artwork Android supplies with the media session/notification
                val systemContent = getImmediateContent(trackInfo, staticArtworkStorage)
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
                    systemContent.copy(contentType = WallpaperContentType.NONE, staticImagePath = null)
                } else {
                    systemContent
                }
                val finalContent = if (systemBitmap != null && systemArtworkIssue == null) {
                    Log.d("MusicNotification", "Using system artwork (${systemBitmap.width}x${systemBitmap.height}) for $trackKey")
                    systemContent
                } else {
                    // Missing, low-res or placeholder system artwork: show what we have now, then try iTunes
                    Log.d("MusicNotification", "System artwork is $systemArtworkIssue for $trackKey, querying iTunes")
                    app.liveWallpaperFlow.emit(fallbackContent)
                    resolveRemoteContent(app, trackInfo, staticArtworkStorage) ?: fallbackContent
                }

                lastArtworkMissing = finalContent.staticImagePath == null
                lastSavedContent = finalContent
                lastUpdateTime = System.currentTimeMillis()
                Log.d("MusicNotification", "Finished resolution for $trackKey")
                
                // Direct emit for instant update
                app.liveWallpaperFlow.emit(finalContent)
                // Persistence
                app.wallpaperStateStore.save(finalContent)
            }
        }
        else {
            lastProcessedTrackKey = null
            lastSavedContent = null
            resolutionJob?.cancel()
            serviceScope.launch {
                applyDefaultWallpaper(app)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName !in supportedPackages) return

        Log.d("MusicNotification", "onNotificationRemoved: $packageName")
        val app = application as App
        lastProcessedTrackKey = null
        lastSavedContent = null
        resolutionJob?.cancel()
        serviceScope.launch {
            applyDefaultWallpaper(app)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resolutionJob?.cancel()
        serviceScope.cancel()
    }

    private suspend fun applyDefaultWallpaper(app: App) {
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
        val sample = Bitmap.createScaledBitmap(this, COLOR_SAMPLE_SIZE, COLOR_SAMPLE_SIZE, true)
        val pixels = IntArray(COLOR_SAMPLE_SIZE * COLOR_SAMPLE_SIZE)
        sample.getPixels(pixels, 0, COLOR_SAMPLE_SIZE, 0, 0, COLOR_SAMPLE_SIZE, COLOR_SAMPLE_SIZE)
        if (sample !== this) sample.recycle()
        // Keep the top 4 bits of each RGB channel
        pixels.mapTo(HashSet()) { (it shr 4) and 0x0F0F0F }.size < MIN_ARTWORK_COLORS
    } catch (e: Exception) {
        // e.g. hardware bitmaps can't be read back; assume it's real artwork
        false
    }

    private suspend fun resolveRemoteContent(app: App, trackInfo: io.github.draumaz.artlock.data.model.TrackInfo, storage: StaticArtworkStorage): WallpaperContent? {
        val result = app.artworkRepository.resolveArtwork(trackInfo) as? LookupResult.StaticHighRes ?: return null
        val path = storage.downloadAndSave(result.imageUrl, trackInfo) ?: return null
        return WallpaperContent(
            trackTitle = trackInfo.title,
            trackArtist = trackInfo.artist,
            trackAlbum = trackInfo.album,
            sourcePackage = trackInfo.packageName,
            contentType = WallpaperContentType.STATIC,
            animatedUrl = null,
            staticImagePath = path,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun getImmediateContent(trackInfo: io.github.draumaz.artlock.data.model.TrackInfo, storage: StaticArtworkStorage): WallpaperContent {
        val bitmap = trackInfo.staticArtworkBitmap
        return if (bitmap != null) {
            val path = storage.save(bitmap, trackInfo)
            WallpaperContent(trackTitle = trackInfo.title, trackArtist = trackInfo.artist, trackAlbum = trackInfo.album, sourcePackage = trackInfo.packageName, contentType = WallpaperContentType.STATIC, animatedUrl = null, staticImagePath = path, updatedAt = System.currentTimeMillis())
        } else {
            WallpaperContent(trackTitle = trackInfo.title, trackArtist = trackInfo.artist, trackAlbum = trackInfo.album, sourcePackage = trackInfo.packageName, contentType = WallpaperContentType.NONE, animatedUrl = null, staticImagePath = null, updatedAt = System.currentTimeMillis())
        }
    }
}
