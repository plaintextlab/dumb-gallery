package com.dumbgallery

import android.graphics.Insets
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A single album: Metro page title + plain grid. */
class AlbumActivity : MetroActivity() {
    companion object {
        const val EXTRA_ALBUM = "album"
    }

    private var albumId = 0L
    private lateinit var column: LinearLayout
    private lateinit var title: TextView
    private lateinit var grid: PhotoGrid
    private lateinit var appBar: AppBar
    private val onData: () -> Unit = { render() }

    override fun build(root: FrameLayout) {
        albumId = intent.getLongExtra(EXTRA_ALBUM, 0L)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val appTitle = label("PHOTOS", 14f, face = Metro.bold).apply { setPadding(dp(24), dp(14), 0, dp(2)) }
        title = label("", 46f, face = Metro.light).apply {
            setPadding(dp(22), 0, dp(24), dp(10))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        grid = PhotoGrid(this, sections = false, onOpen = { i -> go(ViewerActivity.intent(this, albumId, i)) })
        column.addView(appTitle)
        column.addView(title)
        column.addView(grid.view, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        appBar = AppBar(this).button("slideshow", Glyph.PLAY) { go(ViewerActivity.intent(this, albumId, 0, true)) }
        root.addView(appBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        MediaRepo.listen(onData)
        if (MediaRepo.loaded) render() else MediaRepo.refresh(this)
    }

    override fun onInsets(i: Insets) {
        column.setPadding(i.left, i.top, i.right, 0)
        appBar.insets(i.left, i.right, i.bottom)
        grid.view.setPadding(grid.view.paddingLeft, 0, grid.view.paddingRight, dp(64) + i.bottom)
    }

    override fun onRestart() {
        super.onRestart()
        if (ViewerActivity.lastId != 0L) grid.reveal(ViewerActivity.lastId)
    }

    override fun onBack(): Boolean {
        if (appBar.expanded) { appBar.collapse(); return true }
        return false
    }

    override fun onDestroy() {
        MediaRepo.unlisten(onData)
        super.onDestroy()
    }

    private fun render() {
        val a = MediaRepo.albums.firstOrNull { it.id == albumId }
        if (a == null) { finish(); return }
        title.text = a.name.lowercase()
        grid.submit(a.items)
    }
}
