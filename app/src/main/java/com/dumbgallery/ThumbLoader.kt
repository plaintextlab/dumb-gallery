package com.dumbgallery

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Thumbnail pipeline tuned for flinging through big libraries:
 *  - MediaStore's own thumbnail cache via ContentResolver.loadThumbnail (no full decodes)
 *  - in-memory LRU of ready bitmaps
 *  - priority queue: on-screen requests first, newest first; prefetch runs only when idle
 *  - requests for views that scrolled away are dropped before they start
 *  - duplicate requests for the same thumbnail share one decode
 */
object ThumbLoader {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var resolver: ContentResolver
    private lateinit var app: Context
    private var small = 256
    private var large = 512

    private val seq = AtomicLong()
    private val inflight = ConcurrentHashMap<Long, Task>()
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 3).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount / 1024
    }
    private val queue = PriorityBlockingQueue<Runnable>()
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val pool = ThreadPoolExecutor(
        threads, threads, 15, TimeUnit.SECONDS, queue,
        ThreadFactory { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
                r.run()
            }, "thumb")
        },
    ).apply { allowCoreThreadTimeOut(true) }

    fun init(ctx: Context) {
        resolver = ctx.contentResolver
        app = ctx.applicationContext
        val dm = ctx.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels)
        small = (w / 4).coerceIn(128, 384)
        large = (w / 2).coerceIn(256, 720)
    }

    private fun key(id: Long, big: Boolean) = (id shl 1) or (if (big) 1L else 0L)

    fun cached(item: MediaItem): Bitmap? = cache.get(key(item.id, true)) ?: cache.get(key(item.id, false))

    fun load(view: ImageView, item: MediaItem, big: Boolean = false) {
        val k = key(item.id, big)
        val old = view.tag as? Task
        if (old != null) {
            if (old.key == k) return
            release(old, view)
        }
        view.animate().cancel()
        view.alpha = 1f
        val hit = cache.get(k)
        if (hit != null) { view.put(hit); return }
        view.put(if (big) cache.get(key(item.id, false)) else null)

        val t = inflight[k]
        if (t == null || t.cancelled) {
            val n = Task(k, item, big, 0)
            n.view = view
            view.tag = n
            inflight[k] = n
            pool.execute(n)
        } else {
            t.view = view
            view.tag = t
            if (t.priority != 0 && queue.remove(t)) {
                t.priority = 0
                t.order = seq.incrementAndGet()
                pool.execute(t)
            }
        }
    }

    /** Warm the cache for items about to scroll into view. */
    fun prefetch(item: MediaItem) {
        val k = key(item.id, false)
        if (queue.size > 150 || inflight.containsKey(k) || cache.get(k) != null) return
        val t = Task(k, item, false, 1)
        inflight[k] = t
        pool.execute(t)
    }

    fun cancel(view: ImageView) {
        (view.tag as? Task)?.let { release(it, view) }
    }

    private fun release(t: Task, view: ImageView) {
        view.tag = null
        if (t.view !== view) return
        t.view = null
        if (!t.started && t.priority == 0) {
            t.cancelled = true
            queue.remove(t)
            inflight.remove(t.key, t)
        }
    }

    @Suppress("DEPRECATION")
    fun trim(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimToSize(cache.maxSize() / 2)
        }
    }

    private fun ImageView.put(b: Bitmap?) {
        if (this is TileImageView) setBitmapFast(b) else setImageBitmap(b)
    }

    /** For picked items whose provider can't serve thumbnails: decode a small version ourselves. */
    private fun decodeDirect(item: MediaItem, s: Int): Bitmap? = try {
        if (item.isVideo) {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(app, item.uri)
                r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, s, s)
            }
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { dec, info, _ ->
                val sample = minOf(info.size.width, info.size.height) / s
                if (sample > 1) dec.setTargetSampleSize(sample)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    } catch (e: Exception) {
        null
    }

    private class Task(val key: Long, val item: MediaItem, val big: Boolean, @Volatile var priority: Int) :
        Runnable, Comparable<Task> {
        @Volatile var view: ImageView? = null
        @Volatile var cancelled = false
        @Volatile var started = false
        @Volatile var order = seq.incrementAndGet()

        override fun compareTo(other: Task): Int =
            if (priority != other.priority) priority.compareTo(other.priority)
            else other.order.compareTo(order) // newest request first

        override fun run() {
            if (cancelled) return
            started = true
            val s = if (big) large else small
            val bmp = try {
                resolver.loadThumbnail(item.uri, Size(s, s), null)
            } catch (e: Exception) {
                null
            } ?: if (item.bucketId == CloudStore.ID) decodeDirect(item, s) else null
            if (bmp != null) cache.put(key, bmp)
            inflight.remove(key, this)
            main.post { deliver(bmp) }
        }

        private fun deliver(bmp: Bitmap?) {
            val v = view ?: return
            if (v.tag !== this) return
            v.tag = null
            view = null
            if (bmp != null) {
                v.put(bmp)
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(140).start()
            }
        }
    }
}
