package com.dumbgallery

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Insets
import android.view.View
import android.widget.Toast
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A single album: Metro page title + plain grid. */
class AlbumActivity : MetroActivity() {
    companion object {
        const val EXTRA_ALBUM = "album"
        private const val REQ_PICK = 3
    }

    private var albumId = 0L
    private lateinit var column: LinearLayout
    private lateinit var title: TextView
    private lateinit var grid: PhotoGrid
    private lateinit var appBar: AppBar
    private var emptyView: View? = null
    private val isCloud get() = albumId == CloudStore.ID
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
        val body = FrameLayout(this)
        body.addView(grid.view, FrameLayout.LayoutParams(MATCH, MATCH))
        if (isCloud) {
            val e = buildCloudEmpty()
            emptyView = e
            body.addView(e, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        column.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        appBar = AppBar(this)
        if (isCloud) appBar.button("add", Glyph.ADD) { pick() }
        appBar.button("slideshow", Glyph.PLAY) {
            if (MediaRepo.albumItems(albumId).isNotEmpty()) go(ViewerActivity.intent(this, albumId, 0, true))
        }
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
        emptyView?.visibility = if (a.items.isEmpty()) View.VISIBLE else View.GONE
    }

    // --- Google Photos album ----------------------------------------------------------------

    private fun buildCloudEmpty(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        visibility = View.GONE
        addView(label(
            "pick photos and videos from google photos to keep them here.",
            20f, face = Metro.light,
        ).apply { setLineSpacing(0f, 1.15f) })
        addView(metroButton("add photos") { pick() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20) })
        addView(label(
            "don't see google photos in the picker? open the picker's menu, choose browse, and set google photos as the cloud media app.",
            14f, Metro.SUBTLE,
        ).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(24), 0, 0)
        })
    }

    private fun pick() {
        try {
            startActivityForResult(CloudStore.pickIntent(), REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "no photo picker on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) MediaRepo.addCloud(this, CloudStore.urisFrom(data))
    }
}
