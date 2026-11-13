package com.dumbgallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

enum class Glyph { CAMERA, PLAY, SHARE, INFO, DELETE }

/** Round, outlined app bar icon drawn in code (no icon assets). Fills with accent while pressed. */
class CircleButton(ctx: Context, private val glyph: Glyph, private val sizeDp: Int = 46) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
        color = Metro.FG
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1.6f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Metro.FG
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Metro.FG }
    private val press = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    init { isClickable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = dp(sizeDp)
        setMeasuredDimension(s, s)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - ring.strokeWidth
        if (isPressed) {
            press.color = Metro.accent
            c.drawCircle(cx, cy, r, press)
        }
        c.drawCircle(cx, cy, r, ring)
        val u = r / 10f
        line.strokeWidth = maxOf(dp(1.6f).toFloat(), u * 0.9f)
        path.rewind()
        when (glyph) {
            Glyph.PLAY -> {
                path.moveTo(cx - 2.5f * u, cy - 4f * u)
                path.lineTo(cx + 4f * u, cy)
                path.lineTo(cx - 2.5f * u, cy + 4f * u)
                path.close()
                c.drawPath(path, fill)
            }
            Glyph.CAMERA -> {
                rect.set(cx - 5f * u, cy - 3f * u, cx + 5f * u, cy + 4f * u)
                c.drawRoundRect(rect, u * 0.6f, u * 0.6f, line)
                path.moveTo(cx - 2f * u, cy - 3f * u)
                path.lineTo(cx - 1.2f * u, cy - 4.6f * u)
                path.lineTo(cx + 1.2f * u, cy - 4.6f * u)
                path.lineTo(cx + 2f * u, cy - 3f * u)
                c.drawPath(path, line)
                c.drawCircle(cx, cy + 0.5f * u, 2f * u, line)
            }
            Glyph.SHARE -> {
                val ax = cx + 3f * u; val ay = cy - 3.5f * u
                val bx = cx - 3.5f * u; val by = cy
                val dx = cx + 3f * u; val dy = cy + 3.5f * u
                c.drawLine(ax, ay, bx, by, line)
                c.drawLine(bx, by, dx, dy, line)
                c.drawCircle(ax, ay, 1.5f * u, fill)
                c.drawCircle(bx, by, 1.5f * u, fill)
                c.drawCircle(dx, dy, 1.5f * u, fill)
            }
            Glyph.INFO -> {
                c.drawCircle(cx, cy - 3.6f * u, 0.9f * u, fill)
                c.drawLine(cx, cy - 1f * u, cx, cy + 4.5f * u, line)
            }
            Glyph.DELETE -> {
                c.drawLine(cx - 4.5f * u, cy - 3.5f * u, cx + 4.5f * u, cy - 3.5f * u, line)
                path.moveTo(cx - 1.5f * u, cy - 3.5f * u)
                path.lineTo(cx - 1.5f * u, cy - 5f * u)
                path.lineTo(cx + 1.5f * u, cy - 5f * u)
                path.lineTo(cx + 1.5f * u, cy - 3.5f * u)
                path.moveTo(cx - 3.4f * u, cy - 3.5f * u)
                path.lineTo(cx - 2.7f * u, cy + 5f * u)
                path.lineTo(cx + 2.7f * u, cy + 5f * u)
                path.lineTo(cx + 3.4f * u, cy - 3.5f * u)
                c.drawPath(path, line)
                c.drawLine(cx, cy - 1.5f * u, cx, cy + 3f * u, line)
            }
        }
    }
}

/**
 * Windows Phone application bar: a row of round icons plus "•••".
 * Tapping "•••" (or the bar) slides it up to reveal icon labels and the text menu.
 */
class AppBar(ctx: Context) : LinearLayout(ctx) {
    private val row = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    }
    private val menu = LinearLayout(ctx).apply {
        orientation = VERTICAL
        visibility = GONE
        setPadding(0, 0, 0, ctx.dp(10))
    }
    private val labels = ArrayList<TextView>()

    var expanded = false
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(Metro.CHROME)
        isClickable = true
        val top = FrameLayout(ctx)
        top.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        val more = ctx.label("•••", 13f).apply {
            setPadding(dp(18), dp(8), dp(18), dp(14))
            setOnClickListener { toggle() }
        }
        top.addView(more, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP))
        addView(top, LayoutParams(MATCH, WRAP))
        addView(menu, LayoutParams(MATCH, WRAP))
        setOnClickListener { toggle() }
    }

    fun insets(left: Int, right: Int, bottom: Int) = setPadding(left, 0, right, bottom)

    fun button(text: String, glyph: Glyph, onClick: () -> Unit): AppBar {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val b = CircleButton(context, glyph).apply {
            setOnClickListener { collapse(); onClick() }
            contentDescription = text
        }
        val t = context.label(text, 11f).apply {
            visibility = GONE
            setPadding(0, dp(4), 0, 0)
        }
        labels += t
        col.addView(b)
        col.addView(t)
        row.addView(col, LayoutParams(dp(78), WRAP))
        return this
    }

    fun item(text: String, onClick: () -> Unit): AppBar {
        val t = context.label(text, 20f).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0xFF3A3A3A.toInt()))
            }
            setOnClickListener { collapse(); onClick() }
        }
        menu.addView(t, LayoutParams(MATCH, WRAP))
        return this
    }

    fun collapse() { if (expanded) toggle() }

    fun toggle() {
        val before = height
        expanded = !expanded
        labels.forEach { it.visibility = if (expanded) VISIBLE else GONE }
        menu.visibility = if (expanded && menu.childCount > 0) VISIBLE else GONE
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                translationY = (height - before).toFloat()
                animate().translationY(0f).setDuration(220).setInterpolator(Metro.ease).start()
                return true
            }
        })
    }
}
