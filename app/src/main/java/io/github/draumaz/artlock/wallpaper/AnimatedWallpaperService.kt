package io.github.draumaz.artlock.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import io.github.draumaz.artlock.App
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.data.model.WallpaperContentType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import java.io.File

class AnimatedWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return AnimatedWallpaperEngine()
    }

    inner class AnimatedWallpaperEngine : Engine() {

        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private lateinit var playerManager: WallpaperPlayerManager
        private val imageRenderer = WallpaperImageRenderer()

        private var observeContentJob: Job? = null
        private var observeDimJob: Job? = null
        private var observeBlurJob: Job? = null
        private var observeDefaultJob: Job? = null
        private var transitionJob: Job? = null

        private var currentHolder: SurfaceHolder? = null
        private var surfaceReady = false
        private var lastContent: WallpaperContent? = null

        private var currentBitmap: Bitmap? = null
        private var currentPath: String? = null
        private var pendingBitmap: Bitmap? = null
        private var pendingPath: String? = null

        private var wasUsingCanvas = false
        private var currentDimLevel = 30
        private var currentBlurLevel = 0
        private var currentBlurToApply = 0
        private var currentDimToApply = 0
        private var defaultWallpaperPath: String? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            playerManager = WallpaperPlayerManager(applicationContext) {
                val content = lastContent ?: return@WallpaperPlayerManager
                val app = application as App
                if (content.contentType == WallpaperContentType.ANIMATED && content.staticImagePath != null) {
                    engineScope.launch {
                        app.wallpaperStateStore.save(content.copy(contentType = WallpaperContentType.STATIC, animatedUrl = null))
                    }
                }
            }

            val app = application as App

            observeContentJob = engineScope.launch {
                // Merge DataStore for persistence/startup and live flow for instant skip triggers
                merge(app.wallpaperStateStore.contentFlow, app.liveWallpaperFlow).collectLatest { content ->
                    if (content.updatedAt >= (lastContent?.updatedAt ?: 0L)) {
                        lastContent = content
                        renderContent(content)
                    }
                }
            }

            observeDimJob = engineScope.launch {
                app.wallpaperStateStore.dimLevelFlow.collectLatest { level ->
                    currentDimLevel = level
                    lastContent?.let { renderContent(it) }
                }
            }

            observeBlurJob = engineScope.launch {
                app.wallpaperStateStore.blurLevelFlow.collectLatest { level ->
                    currentBlurLevel = level
                    lastContent?.let { renderContent(it) }
                }
            }

            observeDefaultJob = engineScope.launch {
                app.wallpaperStateStore.defaultWallpaperFlow.collectLatest { path ->
                    defaultWallpaperPath = path
                    val content = lastContent
                    if (content == null || content.contentType == WallpaperContentType.NONE || (content.contentType == WallpaperContentType.STATIC && content.trackTitle.isNullOrBlank())) {
                        renderContent(content ?: WallpaperContent(contentType = WallpaperContentType.NONE))
                    }
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentHolder = holder
            surfaceReady = true
            playerManager.attachSurface(holder)
            lastContent?.let { renderContent(it) }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            currentHolder = holder
            lastContent?.let { renderContent(it) }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val content = lastContent ?: return
            when {
                visible && content.contentType == WallpaperContentType.ANIMATED -> playerManager.resume()
                !visible && content.contentType == WallpaperContentType.ANIMATED -> playerManager.pause()
                visible && content.contentType == WallpaperContentType.STATIC -> renderContent(content)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            currentHolder = null
            playerManager.detachSurface()
        }

        override fun onDestroy() {
            super.onDestroy()
            observeContentJob?.cancel()
            observeDimJob?.cancel()
            observeBlurJob?.cancel()
            observeDefaultJob?.cancel()
            transitionJob?.cancel()
            playerManager.release()
            imageRenderer.release()
            
            currentBitmap?.recycle()
            currentBitmap = null
            pendingBitmap?.recycle()
            pendingBitmap = null
            
            engineScope.cancel()
        }

        private fun renderContent(content: WallpaperContent) {
            val holder = currentHolder ?: return
            
            when (content.contentType) {
                WallpaperContentType.ANIMATED -> {
                    transitionJob?.cancel()
                    if (wasUsingCanvas) {
                        wasUsingCanvas = false
                        val w = holder.surfaceFrame.width()
                        val h = holder.surfaceFrame.height()
                        if (w > 0 && h > 0) {
                            holder.setFixedSize(w, h - 1)
                            holder.setSizeFromLayout()
                        }
                        return
                    }
                    if (!surfaceReady) return
                    val url = content.animatedUrl ?: return
                    playerManager.attachSurface(holder)
                    playerManager.play(url)
                }

                WallpaperContentType.STATIC -> {
                    val prevWasCanvas = wasUsingCanvas
                    wasUsingCanvas = true
                    if (!surfaceReady) return
                    val path = content.staticImagePath ?: return
                    val targetBlur = if (path == defaultWallpaperPath) 0 else currentBlurLevel
                    val targetDim = if (path == defaultWallpaperPath) 0 else currentDimLevel

                    if (transitionJob?.isActive != true && currentBitmap != null && currentPath == path) {
                        currentBlurToApply = targetBlur
                        currentDimToApply = targetDim
                        imageRenderer.draw(holder, currentBitmap!!, targetDim, targetBlur)
                        return
                    }
                    
                    transitionJob?.cancel()
                    transitionJob = engineScope.launch {
                        val frame = holder.surfaceFrame
                        val targetWidth = frame.width().coerceAtLeast(1)
                        val targetHeight = frame.height().coerceAtLeast(1)

                        val newBitmap = if (pendingPath == path && pendingBitmap != null) {
                            pendingBitmap
                        } else {
                            pendingPath = path
                            pendingBitmap = null
                            withContext(Dispatchers.IO) {
                                decodeSampledBitmap(path, targetWidth, targetHeight)
                            }
                        }

                        if (newBitmap == null) {
                            pendingBitmap = null
                            pendingPath = null
                            currentBitmap?.let { imageRenderer.draw(holder, it, currentDimToApply, currentBlurToApply) }
                            return@launch
                        }
                        
                        pendingBitmap = newBitmap
                        if (!prevWasCanvas) playerManager.stopAndClearSurface()

                        val oldBitmap = currentBitmap
                        val sourceBlur = currentBlurToApply
                        val sourceDim = currentDimToApply
                        currentBitmap = newBitmap
                        currentPath = path

                        if (oldBitmap == null || oldBitmap == newBitmap) {
                            imageRenderer.draw(holder, newBitmap, targetDim, targetBlur)
                            currentBlurToApply = targetBlur
                            currentDimToApply = targetDim
                            pendingBitmap = null
                            pendingPath = null
                            return@launch
                        }

                        withContext(Dispatchers.Default) {
                            val duration = 300L
                            val startTime = System.currentTimeMillis()
                            while (isActive && isVisible) {
                                val elapsed = System.currentTimeMillis() - startTime
                                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                                imageRenderer.drawCrossfade(holder, oldBitmap, newBitmap, progress, sourceDim, targetDim, sourceBlur, targetBlur)
                                if (progress >= 1f) break
                                delay(16)
                            }
                        }
                        oldBitmap.recycle()
                        currentBlurToApply = targetBlur
                        currentDimToApply = targetDim
                        pendingBitmap = null
                        pendingPath = null
                    }
                }

                WallpaperContentType.NONE -> {
                    val defaultPath = defaultWallpaperPath
                    if (defaultPath != null && File(defaultPath).exists()) {
                        renderContent(WallpaperContent(
                            contentType = WallpaperContentType.STATIC,
                            staticImagePath = defaultPath,
                            updatedAt = content.updatedAt
                        ))
                        return
                    }

                    transitionJob?.cancel()
                    transitionJob = engineScope.launch {
                        pendingBitmap = null
                        pendingPath = null
                        if (currentBitmap == null) {
                             val canvas = holder.lockHardwareCanvas()
                             if (canvas != null) {
                                 canvas.drawColor(android.graphics.Color.BLACK)
                                 holder.unlockCanvasAndPost(canvas)
                             }
                             return@launch
                        }
                        withContext(Dispatchers.Default) {
                            val duration = 600L
                            val startTime = System.currentTimeMillis()
                            val blurToApply = currentBlurToApply
                            val dimToApply = currentDimToApply
                            val bitmapToFade = currentBitmap ?: return@withContext
                            while (isActive && isVisible) {
                                val elapsed = System.currentTimeMillis() - startTime
                                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                                imageRenderer.drawFadeOut(holder, bitmapToFade, progress, dimToApply, blurToApply)
                                if (progress >= 1f) break
                                delay(16)
                            }
                        }
                        currentBitmap?.recycle()
                        currentBitmap = null
                        currentPath = null
                        currentDimToApply = 0
                        currentBlurToApply = 0
                    }
                }
            }
        }

        private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                val file = File(path)
                if (!file.exists()) return null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(path, options)
            } catch (e: Exception) {
                Log.e("AnimatedWallpaper", "Error decoding bitmap", e)
                null
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }
}
