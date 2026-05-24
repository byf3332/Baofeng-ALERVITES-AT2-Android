package com.byf3332.at2ht.widget

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var minScale = 1f
    private var maxScale = 4f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = drawMatrix
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            post { resetToFit() }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && dragging) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    drawMatrix.postTranslate(dx, dy)
                    constrainTranslation()
                    imageMatrix = drawMatrix
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun resetToFit() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        drawMatrix.reset()
        val scale = min(width.toFloat() / d.intrinsicWidth.toFloat(), height.toFloat() / d.intrinsicHeight.toFloat())
        minScale = scale
        val dx = (width - d.intrinsicWidth * scale) * 0.5f
        val dy = (height - d.intrinsicHeight * scale) * 0.5f
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun constrainTranslation() {
        val d = drawable ?: return
        val scale = currentScale()
        val scaledWidth = d.intrinsicWidth * scale
        val scaledHeight = d.intrinsicHeight * scale
        drawMatrix.getValues(matrixValues)
        var tx = matrixValues[Matrix.MTRANS_X]
        var ty = matrixValues[Matrix.MTRANS_Y]

        val minTx = if (scaledWidth > width) width - scaledWidth else (width - scaledWidth) * 0.5f
        val maxTx = if (scaledWidth > width) 0f else minTx
        val minTy = if (scaledHeight > height) height - scaledHeight else (height - scaledHeight) * 0.5f
        val maxTy = if (scaledHeight > height) 0f else minTy

        tx = max(minTx, min(maxTx, tx))
        ty = max(minTy, min(maxTy, ty))
        matrixValues[Matrix.MTRANS_X] = tx
        matrixValues[Matrix.MTRANS_Y] = ty
        drawMatrix.setValues(matrixValues)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val d = drawable ?: return false
            val current = currentScale()
            val target = (current * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = target / current
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            constrainTranslation()
            imageMatrix = drawMatrix
            return d.intrinsicWidth > 0 && d.intrinsicHeight > 0
        }
    }
}
