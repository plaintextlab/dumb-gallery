package com.dumbgallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import java.util.concurrent.Executors

class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val bucketId: Long,
    val bucket: String,
    /** Epoch millis: date taken, falling back to last modified. */
    val date: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val name: String,
    val size: Long,
    val mime: String,
)

class Album(val id: Long, val name: String, val items: List<MediaItem>)

/**
 * One MediaStore query (images + videos together) on a background thread, kept in memory.
 * Albums are derived from the same pass. A ContentObserver keeps it fresh.
 */
object MediaRepo {
    const val ALL = Long.MIN_VALUE

    var items: List<MediaItem> = emptyList()
        private set
    var albums: List<Album> = emptyList()
        private set
    var loaded = false
        private set
    private var cloud: List<MediaItem> = emptyList()

    private val listeners = ArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var again = false
    private var observing = false
    private var app: Context? = null
    private val debounced = Runnable { app?.let { refresh(it) } }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    fun albumItems(id: Long): List<MediaItem> =
        if (id == ALL) items else albums.firstOrNull { it.id == id }?.items ?: emptyList()

    fun hasAccess(ctx: Context): Boolean {
        fun g(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> g(Manifest.permission.READ_MEDIA_IMAGES) ||
                g(Manifest.permission.READ_MEDIA_VIDEO) ||
                g(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> g(Manifest.permission.READ_MEDIA_IMAGES) ||
                g(Manifest.permission.READ_MEDIA_VIDEO)
            else -> g(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** Main thread only. Coalesces overlapping requests. */
    fun refresh(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        if (!hasAccess(c)) return
        observe(c)
        if (running) { again = true; return }
        running = true
        worker.execute {
            val list = try { query(c) } catch (e: Exception) { emptyList() }
            val cl = try { CloudStore.read(c) } catch (e: Exception) { emptyList() }
            val grouped = group(list, cl)
            main.post {
                items = list
                cloud = cl
                albums = grouped
                loaded = true
                running = false
                listeners.toList().forEach { it() }
                if (again) { again = false; refresh(c) }
            }
        }
    }

    /** Add photo-picker results to the Google Photos album. Main thread. */
    fun addCloud(ctx: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val c = ctx.applicationContext
        CloudStore.persist(c, uris)
        updateCloud { CloudStore.add(c, uris) }
    }

    fun removeCloud(ctx: Context, item: MediaItem) {
        val c = ctx.applicationContext
        updateCloud { CloudStore.remove(c, item) }
    }

    private fun updateCloud(op: () -> List<MediaItem>) {
        worker.execute {
            val cl = try { op() } catch (e: Exception) { return@execute }
            main.post {
                // Worker is serial, so this post always lands after any earlier refresh's post.
                cloud = cl
                albums = group(items, cl)
                listeners.toList().forEach { it() }
            }
        }
    }

    private fun observe(c: Context) {
        if (observing) return
        observing = true
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(debounced)
                main.postDelayed(debounced, 300)
            }
        }
        c.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        c.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
    }

    private fun query(c: Context): List<MediaItem> {
        val proj = arrayOf(
            BaseColumns._ID,                 // 0
            FileColumns.MEDIA_TYPE,          // 1
            MediaColumns.BUCKET_ID,          // 2
            MediaColumns.BUCKET_DISPLAY_NAME,// 3
            MediaColumns.DATE_TAKEN,         // 4
            MediaColumns.DATE_MODIFIED,      // 5
            MediaColumns.WIDTH,              // 6
            MediaColumns.HEIGHT,             // 7
            MediaColumns.DURATION,           // 8
            MediaColumns.DISPLAY_NAME,       // 9
            MediaColumns.SIZE,               // 10
            MediaColumns.MIME_TYPE,          // 11
        )
        val sel = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE},${FileColumns.MEDIA_TYPE_VIDEO})"
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<MediaItem>()
        c.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            proj, sel, null, "${MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cur ->
            out.ensureCapacity(cur.count)
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val video = cur.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
                val taken = cur.getLong(4)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(if (video) videos else images, id),
                    isVideo = video,
                    bucketId = cur.getLong(2),
                    bucket = cur.getString(3) ?: "unknown",
                    date = if (taken > 0) taken else cur.getLong(5) * 1000,
                    width = cur.getInt(6),
                    height = cur.getInt(7),
                    duration = cur.getLong(8),
                    name = cur.getString(9) ?: "",
                    size = cur.getLong(10),
                    mime = cur.getString(11) ?: if (video) "video/*" else "image/*",
                )
            }
        }
        out.sortWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.id })
        return out
    }

    private fun group(list: List<MediaItem>, cloud: List<MediaItem>): List<Album> {
        val map = LinkedHashMap<Long, ArrayList<MediaItem>>()
        for (m in list) map.getOrPut(m.bucketId) { ArrayList() }.add(m)
        // Most recent first, camera roll pinned to the top, Google Photos right after it.
        val out = map.map { (id, l) -> Album(id, l[0].bucket, l) }
            .sortedBy { if (it.name.equals("camera", ignoreCase = true)) 0 else 1 }
            .toMutableList()
        val at = if (out.firstOrNull()?.name.equals("camera", ignoreCase = true)) 1 else 0
        out.add(at, Album(CloudStore.ID, CloudStore.NAME, cloud))
        return out
    }
}
