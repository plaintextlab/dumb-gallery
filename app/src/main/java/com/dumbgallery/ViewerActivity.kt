package com.dumbgallery

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Insets
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.Executors

/**
 * Full-screen viewer. Each page shows the cached grid thumbnail instantly,
 * then swaps in a screen-sized decode without resetting zoom.
 */
class ViewerActivity : MetroActivity() {
    companion object {
        /** Last photo shown, so the grid can scroll to it on return. */
        var lastId = 0L

        private const val REQ_DELETE = 7
        private const val REQ_DELETE_LEGACY = 8

        fun intent(ctx: Context, album: Long, index: Int, slideshow: Boolean = false): Intent =
            Intent(ctx, ViewerActivity::class.java)
                .putExtra("album", album)
                .putExtra("index", index)
                .putExtra("slideshow", slideshow)
    }

    private val items = ArrayList<MediaItem>()
    private lateinit var pager: ViewPager2
    private lateinit var pages: Pages
    private lateinit var top: LinearLayout
    private lateinit var date: TextView
    private lateinit var info: TextView
    private lateinit var appBar: AppBar
    private var chrome = true
    private var slideshow = false
    private var pendingDelete: MediaItem? = null
    private var maxDim = 2048

    private val decoder = Executors.newFixedThreadPool(2)
    private val full = object : LruCache<Long, Bitmap>(96 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount / 1024
    }
    private val onData: () -> Unit = { if (items.isEmpty()) load() }
    private val tick = Runnable { advance() }

    override fun build(root: FrameLayout) {
        val dm = resources.displayMetrics
        maxDim = (maxOf(dm.widthPixels, dm.heightPixels) * 1.3f).toInt().coerceAtMost(3072)

        pages = Pages()
        pager = ViewPager2(this).apply {
            adapter = pages
            offscreenPageLimit = 1
            setPageTransformer(MarginPageTransformer(dp(18)))
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = onPage(position)
                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) stopSlideshow()
                }
            })
        }
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        date = label("", 15f)
        info = label("", 13f, Metro.SUBTLE).apply {
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            addView(date)
            addView(info)
        }
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        appBar = AppBar(this)
            .button("share", Glyph.SHARE) { share() }
            .button("info", Glyph.INFO) { info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            .button("delete", Glyph.DELETE) { delete() }
            .item("open with") { current?.let { openWith(it) } }
            .item("set as") { setAs() }
            .item("slideshow") { startSlideshow() }
        appBar.setBackgroundColor(0xCC1F1F1F.toInt())
        root.addView(appBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        slideshow = intent.getBooleanExtra("slideshow", false)
        MediaRepo.listen(onData)
        load()
    }

    override fun animateIn() {
        root.alpha = 0f
        root.scaleX = 0.92f
        root.scaleY = 0.92f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Metro.ease).withLayer().start()
    }

    override fun animateOut(end: () -> Unit) {
        root.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(150).withLayer().withEndAction(end).start()
    }

    override fun onInsets(i: Insets) {
        top.setPadding(dp(24) + i.left, i.top + dp(10), dp(24) + i.right, dp(12))
        appBar.insets(i.left, i.right, i.bottom)
    }

    override fun onBack(): Boolean {
        if (appBar.expanded) { appBar.collapse(); return true }
        return false
    }

    override fun onPause() {
        super.onPause()
        stopSlideshow()
    }

    override fun onDestroy() {
        MediaRepo.unlisten(onData)
        decoder.shutdownNow()
        super.onDestroy()
    }

    private val current get() = items.getOrNull(pager.currentItem)

    private fun load() {
        if (!MediaRepo.loaded) { MediaRepo.refresh(this); return }
        val list = MediaRepo.albumItems(intent.getLongExtra("album", MediaRepo.ALL))
        if (list.isEmpty()) { finish(); return }
        items.clear()
        items.addAll(list)
        @Suppress("NotifyDataSetChanged")
        pages.notifyDataSetChanged()
        val idx = intent.getIntExtra("index", 0).coerceIn(0, items.size - 1)
        pager.setCurrentItem(idx, false)
        onPage(idx)
        if (slideshow) startSlideshow()
    }

    private fun onPage(pos: Int) {
        val m = items.getOrNull(pos) ?: return
        lastId = m.id
        date.text = DateUtils.formatDateTime(
            this, m.date,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME,
        ).lowercase()
        val parts = ArrayList<String>()
        if (m.width > 0 && m.height > 0) parts += "${m.width} × ${m.height}"
        if (m.size > 0) parts += Formatter.formatShortFileSize(this, m.size)
        if (m.isVideo) parts += fmtDuration(m.duration)
        info.text = buildString {
            append(m.name)
            if (parts.isNotEmpty()) append('\n').append(parts.joinToString("  ·  "))
            append('\n').append(m.bucket)
        }
        // Pages that scrolled away go back to fit.
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i)) as? PageVH ?: continue
            if (vh.bindingAdapterPosition != pos) vh.zoom.resetZoom()
        }
    }

    // --- chrome ---------------------------------------------------------------------------

    private fun tapped() {
        if (slideshow) { stopSlideshow(); setChrome(true) } else setChrome(!chrome)
    }

    private fun setChrome(show: Boolean) {
        chrome = show
        appBar.collapse()
        for (v in listOf<View>(top, appBar)) {
            v.animate().cancel()
            if (show) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(160).start()
            } else {
                v.animate().alpha(0f).setDuration(160).withEndAction { if (!chrome) v.visibility = View.INVISIBLE }.start()
            }
        }
        systemBars(show)
    }

    @Suppress("DEPRECATION")
    private fun systemBars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (show) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (show) base else base or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    // --- slideshow ------------------------------------------------------------------------

    private fun startSlideshow() {
        if (items.isEmpty()) return
        slideshow = true
        setChrome(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeCallbacks(tick)
        root.postDelayed(tick, 3000)
    }

    private fun stopSlideshow() {
        if (!slideshow) return
        slideshow = false
        root.removeCallbacks(tick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun advance() {
        if (!slideshow || items.isEmpty()) return
        val next = pager.currentItem + 1
        if (next >= items.size) pager.setCurrentItem(0, false) else pager.setCurrentItem(next, true)
        root.postDelayed(tick, 3500)
    }

    // --- actions --------------------------------------------------------------------------

    private fun safeStart(i: Intent) {
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "no app can do that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share() {
        val m = current ?: return
        val send = Intent(Intent.ACTION_SEND).setType(m.mime)
            .putExtra(Intent.EXTRA_STREAM, m.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        safeStart(Intent.createChooser(send, null))
    }

    private fun openWith(m: MediaItem) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        safeStart(Intent.createChooser(view, null))
    }

    private fun playVideo(m: MediaItem) {
        safeStart(
            Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun setAs() {
        val m = current ?: return
        val attach = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(m.uri, m.mime)
            .putExtra("mimeType", m.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        safeStart(Intent.createChooser(attach, "set as"))
    }

    private fun delete() {
        val m = current ?: return
        if (m.bucketId == CloudStore.ID) {
            // Picked from Google Photos: we can only forget it, not delete the original.
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("remove")
                .setMessage("remove from this album? it stays in google photos.")
                .setPositiveButton("remove") { _, _ ->
                    MediaRepo.removeCloud(this, m)
                    onDeleted(m)
                }
                .setNegativeButton("cancel", null)
                .show()
            return
        }
        pendingDelete = m
        if (Build.VERSION.SDK_INT >= 30) {
            // System shows its own confirmation.
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(m.uri))
            startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
        } else {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("delete")
                .setMessage(if (m.isVideo) "delete this video?" else "delete this photo?")
                .setPositiveButton("delete") { _, _ -> deleteLegacy(m) }
                .setNegativeButton("cancel", null)
                .show()
        }
    }

    private fun deleteLegacy(m: MediaItem) {
        try {
            if (contentResolver.delete(m.uri, null, null) > 0) onDeleted(m)
        } catch (e: RecoverableSecurityException) {
            startIntentSenderForResult(e.userAction.actionIntent.intentSender, REQ_DELETE_LEGACY, null, 0, 0, 0)
        } catch (e: SecurityException) {
            Toast.makeText(this, "can't delete this", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val m = pendingDelete ?: return
        if (resultCode != RESULT_OK) return
        pendingDelete = null
        when (requestCode) {
            REQ_DELETE -> onDeleted(m)
            REQ_DELETE_LEGACY -> deleteLegacy(m)
        }
    }

    private fun onDeleted(m: MediaItem) {
        val idx = items.indexOfFirst { it.id == m.id }
        if (idx < 0) return
        items.removeAt(idx)
        full.remove(m.id)
        pages.notifyItemRemoved(idx)
        if (items.isEmpty()) { finish(); return }
        onPage(pager.currentItem)
    }

    // --- pages ----------------------------------------------------------------------------

    private fun requestFull(h: PageVH, m: MediaItem) {
        decoder.execute {
            val b = if (m.isVideo) {
                try { contentResolver.loadThumbnail(m.uri, Size(maxDim, maxDim), null) } catch (e: Exception) { null }
            } else decodeFull(m)
            if (b != null) runOnUiThread {
                full.put(m.id, b)
                if (h.id == m.id) h.zoom.setBitmap(b, keepZoom = true)
            }
        }
    }

    private fun decodeFull(m: MediaItem): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, m.uri)) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val big = maxOf(w, h)
            if (big > maxDim) {
                val s = maxDim / big.toFloat()
                dec.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private inner class PageVH(frame: FrameLayout, val zoom: ZoomImageView, val play: CircleButton) :
        RecyclerView.ViewHolder(frame) {
        var id = 0L
    }

    private inner class Pages : RecyclerView.Adapter<PageVH>() {
        init { setHasStableIds(true) }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val ctx = this@ViewerActivity
            val zoom = ZoomImageView(ctx).apply { onTap = { tapped() } }
            val play = CircleButton(ctx, Glyph.PLAY, 76)
            val frame = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                addView(zoom, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(play, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }
            val vh = PageVH(frame, zoom, play)
            play.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let { playVideo(it) } }
            return vh
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val m = items[position]
            holder.id = m.id
            val hi = full.get(m.id)
            holder.zoom.setBitmap(hi ?: ThumbLoader.cached(m), keepZoom = false)
            holder.play.visibility = if (m.isVideo) View.VISIBLE else View.GONE
            if (hi == null) requestFull(holder, m)
        }
    }
}
