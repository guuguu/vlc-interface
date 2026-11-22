package app.vlcinterface.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Write access to the phone's music folders (2026-11-21 22:57), asked for as a person understands it: Android's folder
 * picker opens on the music folder and the app gets that folder (and its subfolders) only — nothing else on the phone.
 * The folders granted are kept by Android itself (persisted folder permissions); ReplayGain tags are then written into
 * the files there without any further question.
 */
object MusicFolderAccess {
    private const val EXT = "com.android.externalstorage.documents"

    /** A folder the app may write in: its folder URI from Android and the path it stands for. */
    data class Grant(val tree: Uri, val path: String)

    /** The folder the app asks for now (shown as a question at the app's root), and how it was answered. */
    data class Ask(val path: String, val answer: kotlinx.coroutines.CompletableDeferred<Boolean>?)
    val ask = MutableStateFlow<Ask?>(null)

    private fun pathOf(docId: String): String? {
        val vol = docId.substringBefore(':', ""); val rel = docId.substringAfter(':', "")
        if (vol.isEmpty()) return null
        val root = if (vol.equals("primary", true)) "/storage/emulated/0" else "/storage/$vol"
        return if (rel.isEmpty()) root else "$root/${rel.trim('/')}"
    }

    private fun docIdOf(path: String): String? {
        val p = path.trimEnd('/')
        return when {
            p.startsWith("/storage/emulated/0") -> "primary:" + p.removePrefix("/storage/emulated/0").trim('/')
            p.startsWith("/sdcard") -> "primary:" + p.removePrefix("/sdcard").trim('/')
            p.startsWith("/storage/") -> { val r = p.removePrefix("/storage/"); r.substringBefore('/') + ":" + r.substringAfter('/', "") }
            else -> null
        }
    }

    fun grants(ctx: Context): List<Grant> = try {
        ctx.contentResolver.persistedUriPermissions.filter { it.isWritePermission && DocumentsContract.isTreeUri(it.uri) && it.uri.authority == EXT }
            .mapNotNull { pm -> pathOf(DocumentsContract.getTreeDocumentId(pm.uri))?.let { Grant(pm.uri, it) } }
    } catch (_: Exception) { emptyList() }

    /** The phone file's real path, or null. */
    fun pathOf(ctx: Context, item: PlayItem): String? =
        try { PhysicalPath.of(ctx, item).takeIf { it.startsWith("/") } } catch (_: Exception) { null }

    /** The writable document for this file through a granted folder, or null when no granted folder holds it. */
    fun docUri(ctx: Context, item: PlayItem): Uri? {
        val p = pathOf(ctx, item) ?: return null
        val g = grants(ctx).filter { p == it.path || p.startsWith(it.path.trimEnd('/') + "/") }.maxByOrNull { it.path.length } ?: return null
        val id = docIdOf(p) ?: return null
        return try { DocumentsContract.buildDocumentUriUsingTree(g.tree, id) } catch (_: Exception) { null }
    }

    fun covered(ctx: Context, item: PlayItem) = docUri(ctx, item) != null

    /** The music folder to ask for: the top folder of the storage the file is in (e.g. /storage/emulated/0/Music). */
    fun folderToAsk(ctx: Context, item: PlayItem): String? {
        val p = pathOf(ctx, item) ?: return null
        val root = when { p.startsWith("/storage/emulated/0/") -> "/storage/emulated/0"; p.startsWith("/storage/") -> "/storage/" + p.removePrefix("/storage/").substringBefore('/'); else -> return null }
        val top = p.removePrefix("$root/").substringBefore('/')
        return if (top.isEmpty() || !p.removePrefix("$root/").contains('/')) p.substringBeforeLast('/') else "$root/$top"
    }

    /** Android's folder picker, opened on [path]. */
    fun pickerIntent(path: String?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        path?.let { docIdOf(it) }?.let { id -> try { putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(EXT, id)) } catch (_: Exception) {} }
    }

    /** The folder picked: kept (read + write) for good. */
    fun keep(ctx: Context, tree: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        try { DocumentsContract.getTreeDocumentId(tree).let { id -> pathOf(id)?.let { unrefuse(it) } } } catch (_: Exception) {}
    }

    fun remove(ctx: Context, g: Grant) {
        try { ctx.contentResolver.releasePersistableUriPermission(g.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
    }

    /**
     * "No" for a folder (2026-11-22 07:41): a valid answer, kept for good — never asked again, by scans or while playing.
     * Access can still be given by hand (Settings → Audio → ReplayGain → Music folders with write access), which
     * clears it. Backing out of Android's folder picker only skips it until the app restarts.
     */
    private val declined = HashSet<String>()
    private fun prefs() = app.vlcinterface.App.instance.getSharedPreferences("music_folder_access", Context.MODE_PRIVATE)
    private val refused: MutableSet<String> by lazy { HashSet(try { prefs().getStringSet("no", emptySet()) ?: emptySet() } catch (_: Exception) { emptySet() }) }
    fun declinedFor(path: String) = synchronized(declined) { path in declined || path in refused }
    fun decline(path: String) = synchronized(declined) { declined += path }
    fun refuse(path: String) = synchronized(declined) { refused += path; try { prefs().edit().putStringSet("no", HashSet(refused)).apply() } catch (_: Exception) {} }
    private fun unrefuse(path: String) = synchronized(declined) {
        if (refused.removeAll { it == path || it.startsWith(path.trimEnd('/') + "/") || path.startsWith(it.trimEnd('/') + "/") })
            try { prefs().edit().putStringSet("no", HashSet(refused)).apply() } catch (_: Exception) {}
    }
    fun clearDeclined() = synchronized(declined) { declined.clear() }
}
