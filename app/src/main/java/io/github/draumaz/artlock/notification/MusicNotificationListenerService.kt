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
            
            // FAST PATH: Emit immediate fallback (notification bitmap) to the engine
            serviceScope.launch {
                val immediateContent = getImmediateContent(trackInfo, staticArtworkStorage)
                app.liveWallpaperFlow.emit(immediateContent)
            }

            Log.d("MusicNotification", "Starting background resolution for $trackKey")
            resolutionJob = serviceScope.launch {
                val finalContent = when (val result = app.artworkRepository.resolveArtwork(trackInfo)) {
                    is LookupResult.StaticHighRes -> {
                        val path = staticArtworkStorage.downloadAndSave(result.imageUrl, trackInfo)
                        if (path != null) {
                            WallpaperContent(
                                trackTitle = trackInfo.title,
                                trackArtist = trackInfo.artist,
                                trackAlbum = trackInfo.album,
                                sourcePackage = trackInfo.packageName,
                                contentType = WallpaperContentType.STATIC,
                                animatedUrl = null,
                                staticImagePath = path,
                                updatedAt = System.currentTimeMillis()
                            )
                        } else {
                            getImmediateContent(trackInfo, staticArtworkStorage)
                        }
                    }
                    else -> getImmediateContent(trackInfo, staticArtworkStorage)
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
