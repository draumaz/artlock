package io.github.draumaz.artlock.notification

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

    private var supportedPackages: Set<String> = SupportedMusicApps.packages

    companion object {
        /** System artwork smaller than this (shortest side) is replaced by iTunes artwork when available. */
        private const val MIN_SYSTEM_ARTWORK_PX = 600
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
            if (trackKey == lastProcessedTrackKey) {
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
            
            Log.d("MusicNotification", "Starting resolution for $trackKey")
            resolutionJob = serviceScope.launch {
                // Prefer the artwork Android supplies with the media session/notification
                val systemContent = getImmediateContent(trackInfo, staticArtworkStorage)
                val systemBitmap = trackInfo.staticArtworkBitmap
                val finalContent = if (systemBitmap != null && minOf(systemBitmap.width, systemBitmap.height) >= MIN_SYSTEM_ARTWORK_PX) {
                    Log.d("MusicNotification", "Using system artwork (${systemBitmap.width}x${systemBitmap.height}) for $trackKey")
                    systemContent
                } else {
                    // Missing or low-res system artwork: show what we have now, then try iTunes
                    Log.d("MusicNotification", "System artwork ${systemBitmap?.let { "${it.width}x${it.height}" } ?: "missing"} for $trackKey, querying iTunes")
                    app.liveWallpaperFlow.emit(systemContent)
                    resolveRemoteContent(app, trackInfo, staticArtworkStorage) ?: systemContent
                }

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
