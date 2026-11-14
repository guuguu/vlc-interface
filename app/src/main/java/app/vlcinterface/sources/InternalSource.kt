package app.vlcinterface.sources

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class LocalFolder(val id: String, val name: String, val relativePath: String, val count: Int, val volume: String)

/** Videos and music on the phone and SD card, through Android's media index. */
object InternalSource {
    private val collections = listOf(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
    )

    suspend fun folders(ctx: Context): List<LocalFolder> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, LocalFolder>()
        for (coll in collections) {
            val proj = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME)
            try {
                ctx.contentResolver.query(coll, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val rel = c.getString(0) ?: continue
                        val vol = c.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        val id = "$vol:$rel"
                        val prev = map[id]
                        val name = rel.trimEnd('/').substringAfterLast('/').ifEmpty { rel } +
                            if (vol != MediaStore.VOLUME_EXTERNAL_PRIMARY) " (SD card)" else ""
                        map[id] = LocalFolder(id, name, rel, (prev?.count ?: 0) + 1, vol)
                    }
                }
            } catch (_: SecurityException) {
            }
        }
        map.values.sortedBy { it.name.lowercase() }
    }

    suspend fun files(ctx: Context, folder: LocalFolder): List<Entry> = withContext(Dispatchers.IO) {
        val out = ArrayList<Entry>()
        for (coll in collections) {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            try {
                ctx.contentResolver.query(
                    coll, proj,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.VOLUME_NAME}=?",
                    arrayOf(folder.relativePath, folder.volume),
                    MediaStore.MediaColumns.DISPLAY_NAME
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(coll, c.getLong(0))
                        out += Entry(c.getString(1) ?: "?", uri.toString(), false, c.getLong(2))
                    }
                }
            } catch (_: SecurityException) {
            }
        }
        out.sortedBy { it.name.lowercase() }
    }

    /** Asks Android to re-index one folder (manual rescan). */
    suspend fun rescan(ctx: Context, relativePath: String) {
        val dir = File(Environment.getExternalStorageDirectory(), relativePath)
        val paths = (dir.listFiles()?.map { it.absolutePath } ?: emptyList()) + dir.absolutePath
        suspendCancellableCoroutine<Unit> { cont ->
            var left = paths.size
            if (left == 0) { cont.resume(Unit); return@suspendCancellableCoroutine }
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ ->
                left--
                if (left <= 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }
}
