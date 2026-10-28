package io.github.draumaz.artlock.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.SurfaceHolder
import androidx.core.graphics.scale

/** Draws a bitmap center-cropped to the wallpaper surface, with optional blur and dimming. */
class WallpaperImageRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()

    // Pre-allocated to avoid allocations while animating
    private val srcRect = Rect()
    private val dstRect = Rect()

    // Android 12+: blur with a RenderEffect on a RenderNode. Two nodes, so a crossfade can draw
    // two differently blurred images in the same frame. Typed as Any because RenderNode is API 29+.
    private val blurNodes = arrayOfNulls<Any>(2)

    // Older versions: blur by drawing a downscaled copy back up. Cached per (bitmap, blur level)
    // for the two bitmaps of a crossfade, since rescaling every frame is expensive.
    private val scaledCache = arrayOfNulls<ScaledBlur>(2)

    private class ScaledBlur(val source: Bitmap, val blurPercentage: Int, val scaled: Bitmap)

    fun draw(holder: SurfaceHolder, bitmap: Bitmap, dimPercentage: Int = 0, blurPercentage: Int = 0) {
        drawFrame(holder) { canvas ->
            drawBitmap(canvas, bitmap, blurPercentage, 255, slot = 0)
            drawDimming(canvas, dimPercentage)
        }
    }

    fun drawCrossfade(
        holder: SurfaceHolder,
        fromBitmap: Bitmap,
        toBitmap: Bitmap,
        progress: Float,
        fromDimPercentage: Int,
        toDimPercentage: Int,
        fromBlurPercentage: Int,
        toBlurPercentage: Int
    ) {
        drawFrame(holder) { canvas ->
            drawBitmap(canvas, fromBitmap, fromBlurPercentage, 255, slot = 0)
            drawBitmap(canvas, toBitmap, toBlurPercentage, (progress * 255).toInt(), slot = 1)
            drawDimming(canvas, (fromDimPercentage + (toDimPercentage - fromDimPercentage) * progress).toInt())
        }
    }

    fun drawFadeOut(holder: SurfaceHolder, bitmap: Bitmap, progress: Float, dimPercentage: Int, blurPercentage: Int) {
        drawFrame(holder) { canvas ->
            drawBitmap(canvas, bitmap, blurPercentage, ((1f - progress) * 255).toInt(), slot = 0)
            // Fade out the dimming as well
            drawDimming(canvas, (dimPercentage * (1f - progress)).toInt())
        }
    }

    fun release() {
        scaledCache.forEachIndexed { i, entry ->
            entry?.scaled?.recycle()
            scaledCache[i] = null
        }
        blurNodes.fill(null)
    }

    private inline fun drawFrame(holder: SurfaceHolder, draw: (Canvas) -> Unit) {
        val canvas = holder.lockHardwareCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            draw(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, blurPercentage: Int, alpha: Int, slot: Int) {
        if (bitmap.isRecycled) return
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        setCenterCropRect(bitmap.width, bitmap.height, canvas.width, canvas.height)
        paint.alpha = alpha

        when {
            blurPercentage <= 0 -> canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated ->
                drawBlurredWithRenderEffect(canvas, bitmap, blurPercentage, slot)
            else -> drawBlurredWithScaling(canvas, bitmap, blurPercentage, slot)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawBlurredWithRenderEffect(canvas: Canvas, bitmap: Bitmap, blurPercentage: Int, slot: Int) {
        // 0-100% maps to a 0-250px blur radius
        val radius = blurPercentage * 2.5f
        val node = blurNodes[slot] as? RenderNode ?: RenderNode("ArtlockBlur$slot").also { blurNodes[slot] = it }
        node.setPosition(0, 0, canvas.width, canvas.height)
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        val recordingCanvas = node.beginRecording()
        recordingCanvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        node.endRecording()
        canvas.drawRenderNode(node)
    }

    private fun drawBlurredWithScaling(canvas: Canvas, bitmap: Bitmap, blurPercentage: Int, slot: Int) {
        val cached = scaledCache[slot]
        val scaled = if (cached != null && cached.source === bitmap && cached.blurPercentage == blurPercentage && !cached.scaled.isRecycled) {
            cached.scaled
        } else {
            // Stronger blur = smaller copy; bilinear upscaling smooths it out
            val scale = 1f / (1f + blurPercentage * 0.2f)
            bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1)
            ).also { newScaled ->
                cached?.scaled?.takeIf { it !== newScaled }?.recycle()
                scaledCache[slot] = ScaledBlur(bitmap, blurPercentage, newScaled)
            }
        }
        // Same aspect ratio as the source, so it fills the same center-crop rectangle
        canvas.drawBitmap(scaled, null, dstRect, paint)
    }

    private fun drawDimming(canvas: Canvas, dimPercentage: Int) {
        if (dimPercentage <= 0) return
        dimPaint.color = Color.argb((dimPercentage.coerceIn(0, 100) / 100f * 255).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), dimPaint)
    }

    /** Sets [dstRect] so the bitmap fills the canvas without distortion (cropping the overflow). */
    private fun setCenterCropRect(bitmapWidth: Int, bitmapHeight: Int, canvasWidth: Int, canvasHeight: Int) {
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight
        val canvasRatio = canvasWidth.toFloat() / canvasHeight
        if (bitmapRatio > canvasRatio) {
            val scaledWidth = (canvasHeight * bitmapRatio).toInt()
            val left = (canvasWidth - scaledWidth) / 2
            dstRect.set(left, 0, left + scaledWidth, canvasHeight)
        } else {
            val scaledHeight = (canvasWidth / bitmapRatio).toInt()
            val top = (canvasHeight - scaledHeight) / 2
            dstRect.set(0, top, canvasWidth, top + scaledHeight)
        }
    }
}
