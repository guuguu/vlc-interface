package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The songs saved from the radio (newest first): listed in "Saved songs" to play or delete them. */
object SavedSongs {
    data class Entry(val name: String, val path: String, val uri: String, val docUri: String, val station: String,
                     val at: Long, val folderKey: String = "")

    val list = MutableStateFlow<List<Entry>>(emptyList())
    private val file get() = File(App.instance.filesDir, "saved-songs.json")

    fun load() {
        try {
            val a = JSONArray(file.readText())
            list.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                Entry(it.optString("name"), it.optString("path"), it.optString("uri"), it.optString("doc"), it.optString("station"),
                    it.optLong("at"), it.optString("folder"))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        try {
            val a = JSONArray()
            list.value.forEach { e -> a.put(JSONObject().put("name", e.name).put("path", e.path).put("uri", e.uri).put("doc", e.docUri)
                .put("station", e.station).put("at", e.at).put("folder", e.folderKey)) }
            file.writeText(a.toString())
        } catch (_: Exception) {}
    }

    @Synchronized fun add(e: Entry) { if (list.value.isEmpty()) load(); list.value = listOf(e) + list.value.filterNot { it.uri == e.uri }; save() }

    /** Still on the phone? (files deleted elsewhere drop out of the list) */
    fun exists(ctx: Context, e: Entry): Boolean = try {
        if (e.path.startsWith("/")) File(e.path).exists()
        else ctx.contentResolver.openFileDescriptor(Uri.parse(e.uri), "r")?.use { true } ?: false
    } catch (_: Exception) { false }

    @Synchronized fun prune(ctx: Context) {
        if (list.value.isEmpty()) load()
        val keep = list.value.filter { exists(ctx, it) }
        if (keep.size != list.value.size) { list.value = keep; save() }
    }

    /**
     * Songs already in the save folders (raw/ and mp3/ of the saving root, or of the chosen folder) that the list
     * doesn't have yet — saved before the list existed — are added.
     */
    @Synchronized fun discover(ctx: Context) {
        if (list.value.isEmpty()) load()
        val dirs = ArrayList<String>()
        try { RadioCapture.libraryRoot(ctx).second.let { r -> dirs += "${r}raw/"; dirs += "${r}mp3/" } } catch (_: Exception) {}
        App.instance.store.current.settings.saveFolder.takeIf { it.isNotEmpty() }?.let { t ->
            val rel = Uri.decode(t).substringAfterLast(':').trim('/')
            if (rel.isNotEmpty()) { dirs += "$rel/raw/"; dirs += "$rel/mp3/" }
        }
        if (dirs.isEmpty()) return
        val have = list.value.map { it.path }.toHashSet() + list.value.map { it.uri }
        val found = ArrayList<Entry>()
        try {
            val coll = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
            @Suppress("DEPRECATION")
            val proj = arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.provider.MediaStore.MediaColumns.DATA,
                android.provider.MediaStore.MediaColumns.DATE_ADDED, android.provider.MediaStore.MediaColumns.VOLUME_NAME)
            val sel = dirs.joinToString(" OR ") { "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?" }
            ctx.contentResolver.query(coll, proj, sel, dirs.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(coll, c.getLong(0)).toString()
                    val path = c.getString(3) ?: ""
                    if (uri in have || path in have) continue
                    found += Entry(c.getString(1) ?: "", path, uri, "", "", c.getLong(4) * 1000,
                        "local:${c.getString(5) ?: android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY}:${c.getString(2) ?: ""}")
                }
            }
        } catch (_: Exception) {}
        if (found.isNotEmpty()) { list.value = (list.value + found).sortedByDescending { it.at }; save() }
    }

    /** Media-library addresses of [items] that the app may not delete by itself (Android asks first). */
    fun needsConfirm(ctx: Context, items: List<Entry>): List<Uri> = items.filter { e -> e.docUri.isEmpty() && e.uri.startsWith("content://media") }
        .map { Uri.parse(it.uri) }

    /** After Android's confirmation deleted them: out of the list, the playlists and the queue. */
    @Synchronized fun forgetDeleted(ctx: Context, items: List<Entry>) {
        val gone = items.filter { !exists(ctx, it) }
        if (gone.isEmpty()) return
        val uris = gone.map { it.uri }.toHashSet()
        gone.forEach { ServerLog.log("saved-songs", "delete", "radio", "", true, it.name, it.path) }
        forgetUris(uris)
        list.value = list.value.filterNot { it.uri in uris }; save()
    }

    private fun forgetUris(gone: Set<String>) {
        App.instance.store.update { s ->
            s.copy(recent = s.recent.filterNot { it.uri in gone }, playlists = s.playlists.map { p -> p.copy(items = p.items.filterNot { it.uri in gone }) })
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val st = App.instance.player.state.value
            st.queue.withIndex().filter { it.value.uri in gone && it.index != st.index }.map { it.index }.sortedDescending()
                .forEach { App.instance.player.removeFromQueue(it) }
        }
    }

    /**
     * Deletes the files for good (chosen folder: through its folder access; else the app's own media entry) and takes
     * them out of every playlist and the recent list. Returns how many were deleted.
     */
    @Synchronized fun delete(ctx: Context, items: List<Entry>): Int {
        var n = 0
        val gone = HashSet<String>()
        for (e in items) {
            val ok = try {
                when {
                    e.docUri.isNotEmpty() && DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(e.docUri)) -> true
                    e.uri.startsWith("content://") && ctx.contentResolver.delete(Uri.parse(e.uri), null, null) > 0 -> true
                    e.path.startsWith("/") -> File(e.path).delete()
                    else -> false
                }
            } catch (_: Exception) { false } || !exists(ctx, e)
            if (ok) { n++; gone += e.uri; if (e.docUri.isNotEmpty()) gone += e.docUri }
            ServerLog.log("saved-songs", "delete", "radio", "", ok, e.name, e.path)
        }
        if (gone.isNotEmpty()) { forgetUris(gone); list.value = list.value.filterNot { it.uri in gone }; save() }
        return n
    }
}
