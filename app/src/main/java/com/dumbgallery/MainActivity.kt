package com.dumbgallery

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Insets
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.text.DateFormatSymbols
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.min

/** Photos hub: a Metro pivot with "collection" and "albums". */
class MainActivity : MetroActivity() {
    private lateinit var column: LinearLayout
    private lateinit var header: HorizontalScrollView
    private val titles = ArrayList<TextView>()
    private lateinit var pager: ViewPager2
    private lateinit var grid: PhotoGrid
    private lateinit var albumsAdapter: AlbumsAdapter
    private lateinit var albumsList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var permView: View
    private lateinit var appBar: AppBar
    private lateinit var tileOverlay: TileOverlay
    private val onData: () -> Unit = { render() }

    @SuppressLint("ClickableViewAccessibility")
    override fun build(root: FrameLayout) {
        val dm = resources.displayMetrics
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val appTitle = label("PHOTOS", 14f, face = Metro.bold).apply {
            setPadding(dp(24), dp(14), 0, dp(2))
        }

        // Pivot headers. The trailing ghost "collection" mimics the wrap-around pivot strip.
        header = object : HorizontalScrollView(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent) = false
            override fun onTouchEvent(ev: MotionEvent) = false
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dm.widthPixels, 0)
        }
        listOf("collection", "albums", "collection").forEachIndexed { i, s ->
            val t = label(s, 46f, face = Metro.light).apply {
                setPadding(dp(6), 0, dp(14), dp(6))
                alpha = if (i == 0) 1f else 0.35f
                setOnClickListener { pager.currentItem = if (i == 2) 0 else i }
            }
            titles += t
            strip.addView(t)
        }
        header.addView(strip)

        grid = PhotoGrid(this, sections = true, onOpen = { i -> open(MediaRepo.ALL, i) }, onHeader = { showJumpList() })

        albumsAdapter = AlbumsAdapter(this) { a ->
            go(Intent(this, AlbumActivity::class.java).putExtra(AlbumActivity.EXTRA_ALBUM, a.id))
        }
        val albumCols = maxOf(2, (dm.widthPixels / dm.density / 180).toInt())
        albumsList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, albumCols)
            adapter = albumsAdapter
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(14), dp(4), dp(14), 0)
        }

        emptyView = label(
            "nothing here yet. photos and videos you take or save will show up here.",
            20f, Metro.SUBTLE, Metro.light,
        ).apply {
            setPadding(dp(24), dp(16), dp(24), 0)
            visibility = View.GONE
        }
        permView = buildPermView()

        val collectionPage = FrameLayout(this).apply {
            addView(grid.view, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyView, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(permView, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        pager = ViewPager2(this).apply {
            adapter = StaticPages(listOf(collectionPage, albumsList))
            offscreenPageLimit = 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    syncHeader(position, positionOffset)
                }
            })
        }

        column.addView(appTitle)
        column.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        appBar = AppBar(this)
            .button("camera", Glyph.CAMERA) { openCamera() }
            .button("slideshow", Glyph.PLAY) { if (MediaRepo.items.isNotEmpty()) open(MediaRepo.ALL, 0, true) }
            .item("accent colour") { showAccents() }
            .item("refresh") { MediaRepo.refresh(this) }
        root.addView(appBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        tileOverlay = TileOverlay(root)
        MediaRepo.listen(onData)
        render()

        if (!MediaRepo.hasAccess(this) && !prefs().getBoolean("asked", false)) requestAccess()
    }

    override fun onInsets(i: Insets) {
        column.setPadding(i.left, i.top, i.right, 0)
        appBar.insets(i.left, i.right, i.bottom)
        val bottom = dp(64) + i.bottom
        grid.view.setPadding(grid.view.paddingLeft, 0, grid.view.paddingRight, bottom)
        albumsList.setPadding(albumsList.paddingLeft, albumsList.paddingTop, albumsList.paddingRight, bottom)
    }

    override fun onResume() {
        super.onResume()
        val ok = MediaRepo.hasAccess(this)
        permView.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok && !MediaRepo.loaded) MediaRepo.refresh(this)
    }

    override fun onRestart() {
        super.onRestart()
        if (ViewerActivity.lastId != 0L && pager.currentItem == 0) grid.reveal(ViewerActivity.lastId)
    }

    override fun onDestroy() {
        MediaRepo.unlisten(onData)
        super.onDestroy()
    }

    override fun onBack(): Boolean = when {
        tileOverlay.isShowing -> { tileOverlay.dismiss(); true }
        appBar.expanded -> { appBar.collapse(); true }
        pager.currentItem != 0 -> { pager.currentItem = 0; true }
        else -> false
    }

    private fun render() {
        if (!MediaRepo.loaded) return
        grid.submit(MediaRepo.items)
        albumsAdapter.albums = MediaRepo.albums
        emptyView.visibility = if (MediaRepo.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun syncHeader(position: Int, offset: Float) {
        val a = titles[position].left
        val b = titles.getOrNull(position + 1)?.left ?: a
        header.scrollTo((a + (b - a) * offset - titles[0].left).toInt(), 0)
        val p = position + offset
        titles.forEachIndexed { i, t -> t.alpha = 1f - 0.65f * min(1f, abs(p - i)) }
    }

    private fun open(album: Long, index: Int, slideshow: Boolean = false) =
        go(ViewerActivity.intent(this, album, index, slideshow))

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (e: ActivityNotFoundException) {
            // no camera app
        }
    }

    // --- permission ---------------------------------------------------------------------

    private fun prefs() = getSharedPreferences("metro", Context.MODE_PRIVATE)

    private fun buildPermView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Metro.BG)
        isClickable = true
        setPadding(dp(24), dp(12), dp(24), 0)
        addView(label("to show your photos and videos, this app needs permission to read them.", 20f, face = Metro.light).apply {
            setLineSpacing(0f, 1.15f)
        })
        addView(metroButton("allow access") { requestAccess() }, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(24)
        })
        visibility = View.GONE
    }

    private fun requestAccess() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val asked = prefs().getBoolean("asked", false)
        if (asked && perms.none { shouldShowRequestPermissionRationale(it) } && !MediaRepo.hasAccess(this)) {
            // Permanently denied: the only way left is the system settings page.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        prefs().edit().putBoolean("asked", true).apply()
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = MediaRepo.hasAccess(this)
        permView.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok) MediaRepo.refresh(this)
    }

    // --- overlays -----------------------------------------------------------------------

    private fun tileSize(cols: Int, pad: Int, gap: Int): Int =
        (root.width - bars.left - bars.right - pad * 2 - gap * 2 * cols) / cols

    /** Semantic-zoom jump list: tap a month header, pick any month. */
    private fun showJumpList() {
        val secs = grid.adapter.sectionList
        if (secs.isEmpty()) return
        val pad = dp(14)
        val gap = dp(4)
        val size = tileSize(4, pad, gap)
        val months = DateFormatSymbols.getInstance().shortMonths
        val byKey = secs.associateBy { it.year * 12 + it.month }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + bars.left, bars.top + dp(8), pad + bars.right, bars.bottom + dp(24))
            setOnClickListener { tileOverlay.dismiss() }
        }
        secs.map { it.year }.distinct().forEach { y ->
            content.addView(label(y.toString(), 32f, face = Metro.light).apply { setPadding(gap, dp(18), 0, dp(6)) })
            val tiles = (0 until 12).map { mo ->
                val s = byKey[y * 12 + mo]
                tile(months[mo].lowercase(), if (s != null) Metro.accent else Metro.EMPTY, s != null) {
                    tileOverlay.dismiss()
                    if (s != null) grid.layout.scrollToPositionWithOffset(s.row, 0)
                }
            }
            content.addView(tileGrid(tiles, 4, size, gap))
        }
        tileOverlay.show(content)
    }

    private fun showAccents() {
        val pad = dp(14)
        val gap = dp(4)
        val size = tileSize(4, pad, gap)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + bars.left, bars.top + dp(14), pad + bars.right, bars.bottom + dp(24))
            setOnClickListener { tileOverlay.dismiss() }
        }
        content.addView(label("ACCENT COLOUR", 14f, face = Metro.bold).apply { setPadding(dp(10), 0, 0, 0) })
        content.addView(label("accents", 46f, face = Metro.light).apply { setPadding(dp(6), 0, 0, dp(12)) })
        val tiles = Metro.ACCENTS.mapIndexed { i, c ->
            tile(Metro.ACCENT_NAMES[i], c, true, outline = c == Metro.accent) {
                Metro.setAccent(this, c)
                grid.adapter.notifyItemRangeChanged(0, grid.adapter.itemCount)
                tileOverlay.dismiss()
            }
        }
        content.addView(tileGrid(tiles, 4, size, gap))
        tileOverlay.show(content)
    }
}

/** ViewPager2 adapter over a fixed set of pre-built pages. */
class StaticPages(private val pages: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = pages.size
    override fun getItemViewType(position: Int) = position
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = pages[viewType]
        v.layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        return object : RecyclerView.ViewHolder(v) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}

class AlbumsAdapter(private val ctx: Context, private val onOpen: (Album) -> Unit) :
    RecyclerView.Adapter<AlbumsAdapter.VH>() {

    var albums: List<Album> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(v) { field = v; notifyDataSetChanged() }

    private val numbers = NumberFormat.getInstance()

    init { setHasStableIds(true) }

    class VH(root: View, val img: TileImageView, val name: TextView, val count: TextView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = albums.size
    override fun getItemId(position: Int) = albums[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val g = ctx.dp(6)
        val img = TileImageView(ctx)
        val name = ctx.label("", 17f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(6), 0, 0)
        }
        val count = ctx.label("", 13f, Metro.SUBTLE).apply { setPadding(0, ctx.dp(3), 0, 0) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(g, g, g, ctx.dp(14)) }
            addView(img, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(name)
            addView(count)
        }
        val vh = VH(root, img, name, count)
        root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onOpen(albums[p])
        }
        root.tilt()
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = albums[position]
        holder.name.text = a.name
        holder.count.text = numbers.format(a.items.size)
        ThumbLoader.load(holder.img, a.items[0], big = true)
    }

    override fun onViewRecycled(holder: VH) = ThumbLoader.cancel(holder.img)
}
