package io.github.draumaz.artlock.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.SurfaceHolder

class WallpaperImageRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    
    // Pre-allocated Rects to avoid GC pressure during animation
    private val srcRect = Rect()
    private val dstRect = Rect()

    fun drawFromPath(holder: SurfaceHolder, path: String, dimPercentage: Int = 0, blurPercentage: Int = 0) {
        val bitmap = BitmapFactory.decodeFile(path) ?: return
        draw(holder, bitmap, dimPercentage, blurPercentage)
    }

    fun draw(holder: SurfaceHolder, bitmap: Bitmap, dimPercentage: Int = 0, blurPercentage: Int = 0, alpha: Int = 255) {
        val canvas = lockCanvas(holder) ?: return
        try {
            nextIndex = 0
            canvas.drawColor(Color.BLACK)
            drawBitmapToCanvas(canvas, bitmap, blurPercentage, alpha)
            drawDimmingToCanvas(canvas, dimPercentage)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun lockCanvas(holder: SurfaceHolder): android.graphics.Canvas? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            holder.lockHardwareCanvas()
        } else {
            holder.lockCanvas()
        }
    }

    private var blurBuffer1: Bitmap? = null
    private var blurBuffer2: Bitmap? = null
    private var blurNode1: Any? = null
    private var blurNode2: Any? = null
    private var nextIndex = 0

    fun release() {
        blurBuffer1?.recycle()
        blurBuffer1 = null
        blurBuffer2?.recycle()
        blurBuffer2 = null
        blurNode1 = null
        blurNode2 = null
    }

    private fun drawBitmapToCanvas(canvas: android.graphics.Canvas, bitmap: Bitmap, blurPercentage: Int = 0, alpha: Int = 255) {
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        val dst = calculateCenterCropRect(
            bitmap.width,
            bitmap.height,
            canvas.width,
            canvas.height
        )
        dstRect.set(dst)
        paint.alpha = alpha

        if (blurPercentage > 0) {
            drawBlurredBitmap(canvas, bitmap, blurPercentage)
        } else {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
    }

    private fun drawBlurredBitmap(canvas: android.graphics.Canvas, bitmap: Bitmap, blurPercentage: Int) {
        val index = nextIndex
        nextIndex = (nextIndex + 1) % 2

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
            drawBlurredBitmapWithRenderEffect(canvas, bitmap, blurPercentage, index)
        } else {
            drawBlurredBitmapWithScaling(canvas, bitmap, blurPercentage, index)
        }
    }

    private fun drawBlurredBitmapWithRenderEffect(canvas: android.graphics.Canvas, bitmap: Bitmap, blurPercentage: Int, index: Int) {
        // Map 0-100% to a much more intense blur radius (0-150)
        val radius = blurPercentage * 2.5f
        if (radius < 0.1f) {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val node = if (index == 0) {
                (blurNode1 as? RenderNode) ?: RenderNode("BlurNode1").also { blurNode1 = it }
            } else {
                (blurNode2 as? RenderNode) ?: RenderNode("BlurNode2").also { blurNode2 = it }
            }
            node.setPosition(0, 0, canvas.width, canvas.height)
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            
            val recordingCanvas = node.beginRecording()
            recordingCanvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            node.endRecording()
            
            canvas.drawRenderNode(node)
        }
    }

    private fun drawBlurredBitmapWithScaling(canvas: android.graphics.Canvas, bitmap: Bitmap, blurPercentage: Int, index: Int) {
        // More intense exponential scale: scale = 1.0 / (1.0 + percentage * 0.2)
        val scale = 1f / (1f + blurPercentage * 0.2f)
        
        val targetWidth = (canvas.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (canvas.height * scale).toInt().coerceAtLeast(1)

        var buffer = if (index == 0) blurBuffer1 else blurBuffer2
        if (buffer == null || buffer.width != targetWidth || buffer.height != targetHeight || buffer.isRecycled) {
            buffer?.recycle()
            buffer = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            if (index == 0) blurBuffer1 = buffer else blurBuffer2 = buffer
        }

        val tempCanvas = android.graphics.Canvas(buffer!!)
        paint.isFilterBitmap = true
        
        // Multi-pass scaling approach for a softer 'Gaussian' feel
        var currentBitmap = bitmap
        var nextW = bitmap.width
        var nextH = bitmap.height
        
        // Downscale in steps of at most 2x to maintain smoothness
        val stepBitmaps = mutableListOf<Bitmap>()
        while (nextW > targetWidth * 2 && nextH > targetHeight * 2) {
            nextW /= 2
            nextH /= 2
            val next = Bitmap.createScaledBitmap(currentBitmap, nextW, nextH, true)
            if (currentBitmap !== bitmap) {
                stepBitmaps.add(next)
            }
            currentBitmap = next
        }
        
        tempCanvas.drawBitmap(currentBitmap, null, Rect(0, 0, targetWidth, targetHeight), paint)
        
        // Cleanup intermediate bitmaps
        stepBitmaps.forEach { if (it !== currentBitmap) it.recycle() }
        if (currentBitmap !== bitmap && !stepBitmaps.contains(currentBitmap)) {
             currentBitmap.recycle()
        }
        
        // Draw the blurred buffer back to the main canvas
        canvas.drawBitmap(buffer, null, dstRect, paint)
    }

    private fun drawDimmingToCanvas(canvas: android.graphics.Canvas, dimPercentage: Int) {
        if (dimPercentage > 0) {
            val safeDim = dimPercentage.coerceIn(0, 100)
            val dimAlpha = (safeDim / 100f * 255).toInt()
            dimPaint.color = Color.argb(dimAlpha, 0, 0, 0)
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), dimPaint)
        }
    }

    fun drawCrossfade(
        holder: SurfaceHolder,
        fromBitmap: Bitmap?,
        toBitmap: Bitmap,
        progress: Float,
        fromDimPercentage: Int,
        toDimPercentage: Int,
        fromBlurPercentage: Int,
        toBlurPercentage: Int
    ) {
        val canvas = lockCanvas(holder) ?: return
        try {
            canvas.drawColor(Color.BLACK)

            if (fromBitmap != null) {
                nextIndex = 0
                drawBitmapToCanvas(canvas, fromBitmap, fromBlurPercentage, 255)
            }

            val toAlpha = (progress * 255).toInt()
            nextIndex = 1
            drawBitmapToCanvas(canvas, toBitmap, toBlurPercentage, toAlpha)

            val currentDim = fromDimPercentage + (toDimPercentage - fromDimPercentage) * progress
            drawDimmingToCanvas(canvas, currentDim.toInt())
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    fun drawFadeOut(
        holder: SurfaceHolder,
        bitmap: Bitmap,
        progress: Float,
        dimPercentage: Int,
        blurPercentage: Int
    ) {
        val canvas = lockCanvas(holder) ?: return
        try {
            nextIndex = 0
            canvas.drawColor(Color.BLACK)
            val alpha = ((1.0f - progress) * 255).toInt()
            drawBitmapToCanvas(canvas, bitmap, blurPercentage, alpha)
            
            // Fade out the dimming as well
            val currentDim = (dimPercentage * (1.0f - progress)).toInt()
            drawDimmingToCanvas(canvas, currentDim)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // Matemática para a foto não ficar esticada ou achatada
    private fun calculateCenterCropRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int
    ): Rect {
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val canvasRatio = canvasWidth.toFloat() / canvasHeight.toFloat()

        return if (bitmapRatio > canvasRatio) {
            val scaledWidth = (canvasHeight * bitmapRatio).toInt()
            val left = (canvasWidth - scaledWidth) / 2
            Rect(left, 0, left + scaledWidth, canvasHeight)
        } else {
            val scaledHeight = (canvasWidth / bitmapRatio).toInt()
            val top = (canvasHeight - scaledHeight) / 2
            Rect(0, top, canvasWidth, top + scaledHeight)
        }
    }
}
