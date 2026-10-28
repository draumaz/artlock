package io.github.draumaz.artlock.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import io.github.draumaz.artlock.App
import io.github.draumaz.artlock.data.model.WallpaperContent
import io.github.draumaz.artlock.data.model.WallpaperContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The live wallpaper. (The "Animated" name is historical; it's kept because renaming the service
 * would change its component name and unset the wallpaper for everyone who has it applied.)
 */
class AnimatedWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ArtworkEngine()

    inner class ArtworkEngine : Engine() {

        // All drawing and state changes happen on the main thread; only bitmap decoding is off-thread.
        // (Drawing from two threads at once can lock the surface twice, which throws.)
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val imageRenderer = WallpaperImageRenderer()

        private var transitionJob: Job? = null

        private var currentHolder: SurfaceHolder? = null
        private var surfaceReady = false
        private var lastContent: WallpaperContent? = null

        private var currentBitmap: Bitmap? = null
        private var currentPath: String? = null

        private var currentDimLevel = 30
        private var currentBlurLevel = 0
        private var currentBlurToApply = 0
        private var currentDimToApply = 0
        private var defaultWallpaperPath: String? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val store = (application as App).wallpaperStateStore

            engineScope.launch {
                // DataStore for persistence/startup, plus the live flow for instant track changes
                merge(store.contentFlow, (application as App).liveWallpaperFlow).collectLatest { content ->
                    if (content.updatedAt >= (lastContent?.updatedAt ?: 0L)) {
                        lastContent = content
                        renderContent(content)
                    }
                }
            }
            engineScope.launch {
                store.dimLevelFlow.collectLatest { level ->
                    currentDimLevel = level
                    lastContent?.let { renderContent(it) }
                }
            }
            engineScope.launch {
                store.blurLevelFlow.collectLatest { level ->
                    currentBlurLevel = level
                    lastContent?.let { renderContent(it) }
                }
            }
            engineScope.launch {
                store.defaultWallpaperFlow.collectLatest { path ->
                    defaultWallpaperPath = path
                    val content = lastContent
                    if (content == null || content.contentType == WallpaperContentType.NONE || content.trackTitle.isNullOrBlank()) {
                        renderContent(content ?: WallpaperContent(contentType = WallpaperContentType.NONE))
                    }
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentHolder = holder
            surfaceReady = true
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
            if (visible && content.contentType == WallpaperContentType.STATIC) renderContent(content)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            currentHolder = null
        }

        override fun onDestroy() {
            super.onDestroy()
            engineScope.cancel()
            imageRenderer.release()
            currentBitmap?.recycle()
            currentBitmap = null
        }

        private fun renderContent(content: WallpaperContent) {
            val holder = currentHolder ?: return
            when (content.contentType) {
                WallpaperContentType.STATIC -> renderStatic(holder, content.staticImagePath ?: return)
                WallpaperContentType.NONE -> renderNone(holder, content)
            }
        }

        private fun renderStatic(holder: SurfaceHolder, path: String) {
            if (!surfaceReady) return
            // The user's own background is shown as-is; effects apply to album art only
            val targetBlur = if (path == defaultWallpaperPath) 0 else currentBlurLevel
            val targetDim = if (path == defaultWallpaperPath) 0 else currentDimLevel

            // Same image: just redraw with the current effect levels
            val shownBitmap = currentBitmap
            if (transitionJob?.isActive != true && shownBitmap != null && currentPath == path) {
                currentBlurToApply = targetBlur
                currentDimToApply = targetDim
                imageRenderer.draw(holder, shownBitmap, targetDim, targetBlur)
                return
            }

            transitionJob?.cancel()
            transitionJob = engineScope.launch {
                val frame = holder.surfaceFrame
                val newBitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(path, frame.width().coerceAtLeast(1), frame.height().coerceAtLeast(1))
                }
                if (newBitmap == null) {
                    currentBitmap?.let { imageRenderer.draw(holder, it, currentDimToApply, currentBlurToApply) }
                    return@launch
                }

                val oldBitmap = currentBitmap
                val sourceBlur = currentBlurToApply
                val sourceDim = currentDimToApply
                currentBitmap = newBitmap
                currentPath = path

                if (oldBitmap != null) {
                    try {
                        animate(CROSSFADE_MS) { progress ->
                            imageRenderer.drawCrossfade(holder, oldBitmap, newBitmap, progress, sourceDim, targetDim, sourceBlur, targetBlur)
                        }
                    } finally {
                        // No longer referenced, even if the crossfade was cut short by another change
                        oldBitmap.recycle()
                    }
                }
                imageRenderer.draw(holder, newBitmap, targetDim, targetBlur)
                currentBlurToApply = targetBlur
                currentDimToApply = targetDim
            }
        }

        private fun renderNone(holder: SurfaceHolder, content: WallpaperContent) {
            // Nothing playing: show the user's background if they set one
            val defaultPath = defaultWallpaperPath
            if (defaultPath != null && File(defaultPath).exists()) {
                renderContent(WallpaperContent(contentType = WallpaperContentType.STATIC, staticImagePath = defaultPath, updatedAt = content.updatedAt))
                return
            }

            // Otherwise fade the current image out to black
            transitionJob?.cancel()
            transitionJob = engineScope.launch {
                val bitmapToFade = currentBitmap
                if (bitmapToFade == null) {
                    holder.lockHardwareCanvas()?.let { canvas ->
                        canvas.drawColor(Color.BLACK)
                        holder.unlockCanvasAndPost(canvas)
                    }
                    return@launch
                }
                val blur = currentBlurToApply
                val dim = currentDimToApply
                animate(FADE_OUT_MS) { progress -> imageRenderer.drawFadeOut(holder, bitmapToFade, progress, dim, blur) }
                bitmapToFade.recycle()
                currentBitmap = null
                currentPath = null
                currentDimToApply = 0
                currentBlurToApply = 0
            }
        }

        /** Calls [frame] with progress 0..1 about every 16ms for [durationMs], while visible. */
        private suspend fun animate(durationMs: Long, frame: (Float) -> Unit) {
            val startTime = System.currentTimeMillis()
            while (currentCoroutineIsActive() && isVisible) {
                val progress = ((System.currentTimeMillis() - startTime).toFloat() / durationMs).coerceIn(0f, 1f)
                frame(progress)
                if (progress >= 1f) break
                delay(16)
            }
        }

        private suspend fun currentCoroutineIsActive() = kotlinx.coroutines.currentCoroutineContext().isActive

        private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? = try {
            if (!File(path).exists()) {
                null
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(path, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap", e)
            null
        }

        private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
            var inSampleSize = 1
            while (height / (inSampleSize * 2) >= reqHeight && width / (inSampleSize * 2) >= reqWidth) {
                inSampleSize *= 2
            }
            return inSampleSize
        }
    }

    private companion object {
        const val TAG = "ArtlockWallpaper"
        const val CROSSFADE_MS = 300L
        const val FADE_OUT_MS = 600L
    }
}
