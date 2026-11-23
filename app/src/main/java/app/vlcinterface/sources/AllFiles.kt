package app.vlcinterface.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "All files access" (2026-11-23 01:14, VLC shows more music): with Android's "All files access" given to the app, the
 * storage itself is read, like VLC does — so the media files Android keeps out of its own media list (folders with a
 * ".nomedia" file, e.g. some messaging apps' audio, or files it didn't index) are found too and join the phone's songs and
 * videos. Without it, the app sees what Android lists (as before). Hidden ".folders" and unreadable files stay out.
 */
object AllFiles {
    fun granted(): Boolean = try { Environment.isExternalStorageManager() } catch (_: Exception) { false }

    /** Android's page where "All files access" is given or taken back for this app. */
    fun open(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private var cache: List<PlayItem>? = null
    fun forget() { cache = null }

    /** The media files on the phone's storage that Android's media list doesn't have (only with All files access). */
    suspend fun extras(ctx: Context): List<PlayItem> = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext emptyList()
        cache?.let { return@withContext it }
        val known = HashSet<String>()
        try {
            ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns.DATA),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
                null, null)?.use { c -> while (c.moveToNext()) c.getString(0)?.let { known += it } }
        } catch (_: Exception) {}
        val roots = LinkedHashSet<File>()
        roots += Environment.getExternalStorageDirectory()
        try { ctx.getSystemService(StorageManager::class.java).storageVolumes.mapNotNullTo(roots) { it.directory } } catch (_: Exception) {}
        val out = ArrayList<PlayItem>()
        var seen = 0
        fun walk(d: File, depth: Int) {
            if (depth > 14 || seen > 60_000) return
            val list = try { d.listFiles() } catch (_: Exception) { null } ?: return
            for (f in list) {
                seen++
                val name = f.name
                if (name.startsWith(".")) continue                                   // hidden, as everywhere in the app
                if (f.isDirectory) {
                    // The apps' private data (and the streaming apps' encrypted downloads) aren't media folders.
                    if (depth == 1 && f.parentFile?.name == "Android" && (name == "data" || name == "obb")) continue
                    walk(f, depth + 1)
                } else if (isPlayable(name) && f.absolutePath !in known) {
                    val uri = Uri.fromFile(f).toString()
                    if (Ignore.unreadable(uri, name) || Ignore.blacklisted(uri)) continue
                    out += PlayItem(uri = uri, title = name.substringBeforeLast('.'), folderKey = "file:" + (f.parent ?: ""), fileName = name)
                }
            }
        }
        for (r in roots) walk(r, 0)
        out.also { cache = it }
    }
}
