package com.dumbgallery

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/**
 * The "Google Photos" album: items the user picked through the system photo picker
 * (which shows Google Photos cloud items when Google Photos is the cloud media app).
 * We keep a persistent read grant per item and a small TSV list in app storage.
 * Call only from MediaRepo's worker thread.
 */
object CloudStore {
    const val ID = Long.MIN_VALUE + 1
    const val NAME = "Google Photos"
    private const val FILE = "cloud_album.tsv"

    /** Synthetic ids live far below anything else (MediaStore ids are positive, grid headers ~ -30000). */
    private const val FIRST_ID = -10_000_000L

    fun read(ctx: Context): List<MediaItem> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull(::parse).sortedByDescending { it.date }
    }

    fun add(ctx: Context, uris: List<Uri>): List<MediaItem> {
        val current = read(ctx)
        val known = current.mapTo(HashSet()) { it.uri.toString() }
        var next = (current.minOfOrNull { it.id } ?: FIRST_ID + 1) - 1
        val added = uris.filter { known.add(it.toString()) }.map { describe(ctx, it, next--) }
        val all = (current + added).sortedByDescending { it.date }
        write(ctx, all)
        return all
    }

    fun remove(ctx: Context, item: MediaItem): List<MediaItem> {
        val all = read(ctx).filter { it.uri != item.uri }
        write(ctx, all)
        try {
            ctx.contentResolver.releasePersistableUriPermission(item.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // grant was never persisted or is already gone
        }
        return all
    }

    /** Keep read access across reboots. Main thread, straight from the picker result. */
    fun persist(ctx: Context, uris: List<Uri>) {
        for (u in uris) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // provider doesn't offer persistable grants; access lasts until reboot
            }
        }
    }

    @SuppressLint("NewApi")
    fun hasSystemPicker(): Boolean =
        Build.VERSION.SDK_INT >= 33 ||
            (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)

    @SuppressLint("NewApi", "InlinedApi")
    fun pickIntent(): Intent =
        if (hasSystemPicker()) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    fun urisFrom(data: Intent?): List<Uri> {
        data ?: return emptyList()
        val clip = data.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(data.data)
    }

    private fun describe(ctx: Context, uri: Uri, id: Long): MediaItem {
        var name = ""
        var size = 0L
        var mime: String? = null
        var date = 0L
        var w = 0
        var h = 0
        var dur = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
                    fun num(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                    name = str(OpenableColumns.DISPLAY_NAME) ?: ""
                    size = num(OpenableColumns.SIZE)
                    mime = str(MediaStore.MediaColumns.MIME_TYPE)
                    date = num(MediaStore.MediaColumns.DATE_TAKEN)
                    w = num(MediaStore.MediaColumns.WIDTH).toInt()
                    h = num(MediaStore.MediaColumns.HEIGHT).toInt()
                    dur = num(MediaStore.MediaColumns.DURATION)
                }
            }
        } catch (e: Exception) {
            // metadata is best effort
        }
        val type = mime ?: ctx.contentResolver.getType(uri) ?: "image/*"
        return MediaItem(
            id = id,
            uri = uri,
            isVideo = type.startsWith("video/"),
            bucketId = ID,
            bucket = NAME,
            date = if (date > 0) date else System.currentTimeMillis(),
            width = w,
            height = h,
            duration = dur,
            name = name,
            size = size,
            mime = type,
        )
    }

    private fun write(ctx: Context, items: List<MediaItem>) {
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(items.joinToString("") { m ->
            listOf(
                m.id, m.uri, if (m.isVideo) 1 else 0, m.date, m.width, m.height,
                m.duration, m.size, m.mime, m.name.replace('\t', ' ').replace('\n', ' '),
            ).joinToString("\t", postfix = "\n")
        })
        tmp.renameTo(File(ctx.filesDir, FILE))
    }

    private fun parse(line: String): MediaItem? {
        val p = line.split('\t')
        if (p.size < 10) return null
        return try {
            MediaItem(
                id = p[0].toLong(),
                uri = Uri.parse(p[1]),
                isVideo = p[2] == "1",
                bucketId = ID,
                bucket = NAME,
                date = p[3].toLong(),
                width = p[4].toInt(),
                height = p[5].toInt(),
                duration = p[6].toLong(),
                size = p[7].toLong(),
                mime = p[8],
                name = p[9],
            )
        } catch (e: Exception) {
            null
        }
    }
}
