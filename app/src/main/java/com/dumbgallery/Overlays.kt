package com.dumbgallery

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Full-screen tile overlay (jump list, accent picker) that zooms in over the page. */
class TileOverlay(private val host: FrameLayout) {
    private var view: View? = null
    val isShowing get() = view != null

    fun show(content: View) {
        view?.let { host.removeView(it) }
        val scroll = ScrollView(host.context).apply {
            setBackgroundColor(0xF2000000.toInt())
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isClickable = true
            addView(content)
        }
        host.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        scroll.alpha = 0f
        scroll.scaleX = 1.08f
        scroll.scaleY = 1.08f
        scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Metro.ease).start()
        view = scroll
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(150)
            .withEndAction { host.removeView(v) }.start()
    }
}

fun Context.tile(text: String, color: Int, enabled: Boolean, outline: Boolean = false, onClick: () -> Unit): TextView =
    label(text, 18f, if (enabled) Metro.FG else Metro.DIM).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        setPadding(dp(8), 0, 0, dp(6))
        background = GradientDrawable().apply {
            setColor(color)
            if (outline) setStroke(dp(3), Metro.FG)
        }
        if (enabled) {
            setOnClickListener { onClick() }
            tilt()
        }
    }

fun Context.tileGrid(tiles: List<View>, cols: Int, size: Int, gap: Int): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tiles.chunked(cols).forEach { chunk ->
            val row = LinearLayout(this@tileGrid).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { t ->
                row.addView(t, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, gap, gap, gap) })
            }
            addView(row)
        }
    }
