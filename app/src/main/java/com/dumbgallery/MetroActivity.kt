package com.dumbgallery

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Metro.init(this)
        ThumbLoader.init(this)
        // Start the MediaStore query before the first activity is even inflated.
        if (MediaRepo.hasAccess(this)) MediaRepo.refresh(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ThumbLoader.trim(level)
    }
}

/** Edge-to-edge black page with code-built views and Metro page transitions. */
abstract class MetroActivity : Activity() {
    lateinit var root: FrameLayout
        private set
    var bars: Insets = Insets.NONE
        private set
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        root = FrameLayout(this)
        root.setBackgroundColor(Metro.BG)
        root.setOnApplyWindowInsetsListener { _, ins ->
            bars = ins.bars()
            onInsets(bars)
            ins
        }
        setContentView(root)
        build(root)
        animateIn()
    }

    abstract fun build(root: FrameLayout)
    open fun onInsets(i: Insets) {}
    /** Return true if back was consumed (overlay / menu closed). */
    open fun onBack(): Boolean = false

    open fun animateIn() = root.turnstileIn()
    open fun animateOut(end: () -> Unit) = root.turnstileOut(end)

    override fun onRestart() {
        super.onRestart()
        leaving = false
        animateIn()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onBack() || leaving) return
        leaving = true
        animateOut {
            finish()
            noAnim()
        }
    }

    fun go(intent: Intent) {
        startActivity(intent)
        noAnim()
    }

    @Suppress("DEPRECATION")
    fun noAnim() = overridePendingTransition(0, 0)

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
