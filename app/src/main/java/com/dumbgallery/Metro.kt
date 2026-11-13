package com.dumbgallery

import android.animation.TimeInterpolator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Insets
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView

/** Metro design tokens: black theme, one accent colour, light typography, quick ease-out motion. */
object Metro {
    const val BG = 0xFF000000.toInt()
    const val FG = 0xFFFFFFFF.toInt()
    const val SUBTLE = 0xFF9A9A9A.toInt()
    const val DIM = 0xFF4A4A4A.toInt()
    const val CHROME = 0xFF1F1F1F.toInt()
    const val EMPTY = 0xFF1A1A1A.toInt()

    /** The 20 Windows Phone 8 accent colours. */
    val ACCENTS: IntArray = longArrayOf(
        0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2,
        0xFF0050EF, 0xFF6A00FF, 0xFFAA00FF, 0xFFF472D0, 0xFFD80073,
        0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A, 0xFFE3C800,
        0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E,
    ).map { it.toInt() }.toIntArray()

    val ACCENT_NAMES = arrayOf(
        "lime", "green", "emerald", "teal", "cyan",
        "cobalt", "indigo", "violet", "pink", "magenta",
        "crimson", "red", "orange", "amber", "yellow",
        "brown", "olive", "steel", "mauve", "taupe",
    )

    var accent = ACCENTS[4]
        private set

    val light: Typeface by lazy { Typeface.create("sans-serif-light", Typeface.NORMAL) }
    val normal: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    val bold: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    /** Exponential-style ease-out, the signature Metro motion curve. */
    val ease: TimeInterpolator = PathInterpolator(0.1f, 0.9f, 0.2f, 1f)

    fun init(ctx: Context) {
        accent = ctx.getSharedPreferences("metro", Context.MODE_PRIVATE).getInt("accent", ACCENTS[4])
    }

    fun setAccent(ctx: Context, color: Int) {
        accent = color
        ctx.getSharedPreferences("metro", Context.MODE_PRIVATE).edit().putInt("accent", color).apply()
    }
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

const val MATCH = -1 // ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = -2  // ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.label(
    text: CharSequence = "",
    sp: Float = 15f,
    color: Int = Metro.FG,
    face: Typeface = Metro.normal,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    typeface = face
    includeFontPadding = false
}

/** Classic WP button: white outline, fills with accent while pressed. */
@SuppressLint("ClickableViewAccessibility")
fun Context.metroButton(text: String, onClick: () -> Unit): TextView = label(text, 18f).apply {
    setPadding(dp(20), dp(10), dp(20), dp(12))
    val bg = GradientDrawable().apply { setStroke(dp(2), Metro.FG); setColor(0) }
    background = bg
    setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> bg.setColor(Metro.accent)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> bg.setColor(0)
        }
        false
    }
    setOnClickListener { onClick() }
}

/** Metro "tilt" press feedback: the tile leans toward the finger. */
@SuppressLint("ClickableViewAccessibility")
fun View.tilt() {
    var px = 0f
    var py = 0f
    val press = Runnable {
        cameraDistance = 6000 * resources.displayMetrics.density
        val dx = (px / width.coerceAtLeast(1) - 0.5f).coerceIn(-0.5f, 0.5f)
        val dy = (py / height.coerceAtLeast(1) - 0.5f).coerceIn(-0.5f, 0.5f)
        animate().rotationY(-dx * 14f).rotationX(dy * 14f).scaleX(0.96f).scaleY(0.96f)
            .setDuration(90).setInterpolator(Metro.ease).start()
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                px = e.x; py = e.y
                v.postDelayed(press, 40)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(press)
                v.animate().rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(160).setInterpolator(Metro.ease).start()
            }
        }
        false
    }
}

/** Turnstile page transition: the page swings in around its left edge. */
fun View.turnstileIn(from: Float = -70f) {
    val dm = resources.displayMetrics
    cameraDistance = 9000 * dm.density
    pivotX = 0f
    pivotY = dm.heightPixels / 2f
    rotationY = from
    alpha = 0f
    animate().rotationY(0f).alpha(1f).setDuration(320).setInterpolator(Metro.ease)
        .withLayer().start()
}

fun View.turnstileOut(end: () -> Unit) {
    val dm = resources.displayMetrics
    cameraDistance = 9000 * dm.density
    pivotX = 0f
    pivotY = dm.heightPixels / 2f
    animate().rotationY(70f).alpha(0f).setDuration(170).setInterpolator(AccelerateInterpolator(1.5f))
        .withLayer().withEndAction(end).start()
}

/** System bar insets that stay put while bars are hidden (so chrome doesn't jump). */
@Suppress("DEPRECATION")
fun WindowInsets.bars(): Insets =
    if (Build.VERSION.SDK_INT >= 30)
        getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    else stableInsets

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
