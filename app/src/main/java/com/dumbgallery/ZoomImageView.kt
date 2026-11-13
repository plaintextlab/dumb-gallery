package com.dumbgallery

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Pinch / double-tap zoom image. The fit transform (base) is kept separate from the user's
 * zoom/pan (user), so swapping the instant thumbnail for the full-res bitmap is seamless.
 */
class ZoomImageView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val base = Matrix()
    private val user = Matrix()
    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val vals = FloatArray(9)
    private val r = RectF()
    private var lastX = 0f
    private var anim: ValueAnimator? = null

    var onTap: (() -> Unit)? = null

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (scale() < 1f) animateTo(1f, width / 2f, height / 2f)
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateTo(if (scale() > 1.05f) 1f else 2.5f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scale() > 1.01f && !scaler.isInProgress) {
                user.postTranslate(-distanceX, -distanceY)
                clamp()
                invalidate()
            }
            return true
        }
    })

    fun setBitmap(b: Bitmap?, keepZoom: Boolean) {
        bmp = b
        if (!keepZoom) { anim?.cancel(); user.reset() }
        fitBase()
        invalidate()
    }

    fun resetZoom() {
        anim?.cancel()
        user.reset()
        invalidate()
    }

    private fun fitBase() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val s = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        base.setScale(s, s)
        base.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fitBase()
        user.reset()
    }

    private fun scale(): Float {
        user.getValues(vals)
        return vals[Matrix.MSCALE_X]
    }

    private fun displayRect(): RectF {
        val b = bmp
        if (b == null) { r.setEmpty(); return r }
        r.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.set(base)
        m.postConcat(user)
        m.mapRect(r)
        return r
    }

    private fun clamp() {
        if (bmp == null) return
        val d = displayRect()
        val dx = when {
            d.width() <= width -> (width - d.width()) / 2f - d.left
            d.left > 0 -> -d.left
            d.right < width -> width - d.right
            else -> 0f
        }
        val dy = when {
            d.height() <= height -> (height - d.height()) / 2f - d.top
            d.top > 0 -> -d.top
            d.bottom < height -> height - d.bottom
            else -> 0f
        }
        user.postTranslate(dx, dy)
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val cur = scale()
        val target = (cur * f).coerceIn(0.7f, 8f)
        val ff = target / cur
        user.postScale(ff, ff, fx, fy)
        clamp()
        invalidate()
    }

    private fun animateTo(target: Float, fx: Float, fy: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(scale(), target).apply {
            duration = 240
            interpolator = Metro.ease
            addUpdateListener {
                val f = (it.animatedValue as Float) / scale()
                user.postScale(f, f, fx, fy)
                clamp()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        m.set(base)
        m.postConcat(user)
        canvas.drawBitmap(b, m, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        val zoomed = scale() > 1.01f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(zoomed)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                if (zoomed && event.pointerCount == 1 && !scaler.isInProgress) {
                    // At an edge and pulling past it: hand the gesture back to the pager.
                    val dx = event.x - lastX
                    val d = displayRect()
                    if ((dx > 0 && d.left >= -0.5f) || (dx < 0 && d.right <= width + 0.5f)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                lastX = event.x
            }
        }
        return true
    }
}
