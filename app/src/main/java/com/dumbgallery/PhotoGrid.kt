package com.dumbgallery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Square tile that skips the layout pass when its bitmap changes (tiles never change size). */
class TileImageView(ctx: Context) : ImageView(ctx) {
    var video = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var duration: String? = null
    private var blockLayout = false
    private val tri = Path()

    init {
        scaleType = ScaleType.CENTER_CROP
        setBackgroundColor(Metro.EMPTY)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    fun setBitmapFast(b: Bitmap?) {
        blockLayout = true
        setImageBitmap(b)
        blockLayout = false
    }

    override fun requestLayout() {
        if (!blockLayout) super.requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!video) return
        val u = width / 24f
        val h = height.toFloat()
        canvas.drawRect(0f, h - u * 6, width.toFloat(), h, shade)
        tri.rewind()
        tri.moveTo(u * 1.8f, h - u * 4.6f)
        tri.lineTo(u * 5f, h - u * 3f)
        tri.lineTo(u * 1.8f, h - u * 1.4f)
        tri.close()
        canvas.drawPath(tri, white)
        duration?.let {
            white.textSize = u * 3.4f
            canvas.drawText(it, u * 6.5f, h - u * 1.7f, white)
        }
    }

    companion object {
        private val shade = Paint().apply { color = 0x88000000.toInt() }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Metro.FG }
    }
}

/** Grid of photos, optionally broken into month sections ("november 2026"). */
class PhotoGridAdapter(
    private val ctx: Context,
    private val sections: Boolean,
    private val onOpen: (Int) -> Unit,
    private val onHeader: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class Section(val label: String, val row: Int, val year: Int, val month: Int)

    var items: List<MediaItem> = emptyList()
        private set
    var sectionList: List<Section> = emptyList()
        private set

    /** row -> item index, or -(sectionIndex + 1) for a header row. */
    private var rows = IntArray(0)
    private val gap = ctx.dp(2)

    init { setHasStableIds(true) }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<MediaItem>) {
        items = list
        if (!sections) {
            rows = IntArray(list.size) { it }
            sectionList = emptyList()
        } else {
            val cal = Calendar.getInstance()
            val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            val secs = ArrayList<Section>()
            var arr = IntArray(list.size + 64)
            var n = 0
            var lastKey = -1
            for (i in list.indices) {
                cal.timeInMillis = list[i].date
                val y = cal.get(Calendar.YEAR)
                val mo = cal.get(Calendar.MONTH)
                val k = y * 12 + mo
                if (n + 2 >= arr.size) arr = arr.copyOf(arr.size * 2)
                if (k != lastKey) {
                    lastKey = k
                    secs += Section(fmt.format(cal.time).lowercase(), n, y, mo)
                    arr[n++] = -secs.size
                }
                arr[n++] = i
            }
            rows = arr.copyOf(n)
            sectionList = secs
        }
        notifyDataSetChanged()
    }

    fun isHeader(p: Int) = p in rows.indices && rows[p] < 0
    fun itemAt(p: Int): MediaItem? = if (p in rows.indices && rows[p] >= 0) items[rows[p]] else null

    fun rowOf(index: Int): Int {
        var r = index
        while (r < rows.size && rows[r] != index) r++
        return if (r < rows.size) r else -1
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] < 0) 1 else 0
    override fun getItemId(position: Int): Long {
        val v = rows[position]
        if (v >= 0) return items[v].id
        val s = sectionList[-v - 1]
        return -1L - (s.year * 12 + s.month)
    }

    class PhotoVH(val img: TileImageView) : RecyclerView.ViewHolder(img)
    class HeaderVH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 1) {
            val t = ctx.label("", 22f, Metro.accent, Metro.light).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                setPadding(ctx.dp(4), ctx.dp(18), 0, ctx.dp(10))
                setOnClickListener { onHeader() }
            }
            return HeaderVH(t)
        }
        val img = TileImageView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(gap, gap, gap, gap) }
        }
        val vh = PhotoVH(img)
        img.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && rows[p] >= 0) onOpen(rows[p])
        }
        img.tilt()
        return vh
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderVH -> {
                holder.text.text = sectionList[-rows[position] - 1].label
                holder.text.setTextColor(Metro.accent)
            }
            is PhotoVH -> {
                val m = items[rows[position]]
                holder.img.duration = if (m.isVideo) fmtDuration(m.duration) else null
                holder.img.video = m.isVideo
                ThumbLoader.load(holder.img, m)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is PhotoVH) ThumbLoader.cancel(holder.img)
    }
}

/** RecyclerView + adapter + scroll-ahead prefetching, shared by the collection and album pages. */
class PhotoGrid(ctx: Context, sections: Boolean, onOpen: (Int) -> Unit, onHeader: () -> Unit = {}) {
    val cols: Int
    val adapter: PhotoGridAdapter
    val layout: GridLayoutManager
    val view: RecyclerView

    init {
        val dm = ctx.resources.displayMetrics
        val c = maxOf(4, (dm.widthPixels / dm.density / 96).toInt())
        val a = PhotoGridAdapter(ctx, sections, onOpen, onHeader)
        val lm = GridLayoutManager(ctx, c).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (a.isHeader(position)) c else 1
            }.apply {
                isSpanIndexCacheEnabled = true
                isSpanGroupIndexCacheEnabled = true
            }
        }
        cols = c
        adapter = a
        layout = lm
        view = RecyclerView(ctx).apply {
            layoutManager = lm
            adapter = a
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(c * 4)
            recycledViewPool.setMaxRecycledViews(0, c * 14)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy == 0) return
                    val ahead = c * 5
                    if (dy > 0) {
                        val last = lm.findLastVisibleItemPosition()
                        for (p in last + 1..minOf(last + ahead, a.itemCount - 1)) a.itemAt(p)?.let(ThumbLoader::prefetch)
                    } else {
                        val first = lm.findFirstVisibleItemPosition()
                        for (p in first - 1 downTo maxOf(0, first - ahead)) a.itemAt(p)?.let(ThumbLoader::prefetch)
                    }
                }
            })
        }
    }

    fun submit(list: List<MediaItem>) = adapter.submit(list)

    /** Scroll so the photo last seen in the viewer is on screen. */
    fun reveal(id: Long) {
        val idx = adapter.items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val row = adapter.rowOf(idx)
        if (row < 0) return
        val first = layout.findFirstCompletelyVisibleItemPosition()
        val last = layout.findLastCompletelyVisibleItemPosition()
        if (row < first || row > last) layout.scrollToPositionWithOffset(row, view.height / 3)
    }
}
