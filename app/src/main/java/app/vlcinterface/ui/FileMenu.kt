package app.vlcinterface.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.vlcinterface.App
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem

/**
 * What the page adds to the common file menu: only the choices that make sense where the file is shown.
 * [onRemove]: takes the file out of this list only — the file itself is never touched.
 */
class FileMenuExtras(
    val onSelect: (() -> Unit)? = null,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    val removeLabel: String = "Remove from this list",
    val onRemove: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/**
 * The same long-press menu for a song or video on every page (folders, Songs list, playlists, queue, Up next):
 * Play now, Play next, Add to queue, Add to playlist…, Open song folder, Tags & ReplayGain…, ReplayGain scan…,
 * plus what the page allows (Select, Move up / down, Remove from this list, Delete file).
 */
@Composable
fun FileMenu(nav: Navigator, item: PlayItem, expanded: Boolean, onDismiss: () -> Unit, x: FileMenuExtras = FileMenuExtras()) {
    val ctx = LocalContext.current
    val player = App.instance.player
    var addTo by remember { mutableStateOf(false) }
    var rg by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    fun close(then: () -> Unit) { onDismiss(); then() }
    SDropdownMenu(expanded, onDismiss) {
        SMenuItem({ Text("▶  Play now") }, { close {
            player.playQueue(listOf(item), 0, Origin("single", title = item.title))
            nav.go(if (item.isMusic) Screen.Music else Screen.Player)
        } })
        SMenuItem({ Text("Play next") }, { close { player.queueNext(item) } })
        SMenuItem({ Text("Add to queue") }, { close {
            val n = player.addToQueue(listOf(item))
            android.widget.Toast.makeText(ctx, if (n == 0) "Already in the queue" else "Added to the queue", android.widget.Toast.LENGTH_SHORT).show()
        } })
        SMenuItem({ Text("Add to playlist…") }, { close { addTo = true } })
        x.onSelect?.let { f -> SMenuItem({ Text("Select…") }, { close(f) }) }
        x.onMoveUp?.let { f -> SMenuItem({ Text("Move up") }, { close(f) }) }
        x.onMoveDown?.let { f -> SMenuItem({ Text("Move down") }, { close(f) }) }
        if (!item.isRadio) SMenuItem({ Text("Open song folder") }, { close { openItemFolder(nav, item) } })
        if (!item.isRadio) SMenuItem({ Text("Tags & ReplayGain…") }, { close { info = true } })
        if (item.isMusic) SMenuItem({ Text("ReplayGain scan…") }, { close { rg = true } })
        x.onRemove?.let { f -> SMenuItem({ Text(x.removeLabel, color = C.bad) }, { close(f) }) }
        x.onDelete?.let { f -> SMenuItem({ Text("Delete file…", color = C.bad) }, { close(f) }) }
    }
    if (addTo) AddToPlaylistDialog({ listOf(item) }) { addTo = false }
    if (rg) RgScanDialog(nav, { listOf(item) }) { rg = false }
    if (info) TagInfoDialog(item) { info = false }
}

/** Files taken out of the Library lists (folders, Songs list) with "Remove from this list" — never deleted. */
object HiddenFiles {
    fun isHidden(uri: String) = uri in App.instance.store.current.settings.hiddenUris
    fun hide(uri: String) = App.instance.store.update { it.copy(settings = it.settings.copy(hiddenUris = it.settings.hiddenUris + uri)) }
    fun showAll() = App.instance.store.update { it.copy(settings = it.settings.copy(hiddenUris = emptySet())) }
}


/**
 * Settings → Delete a file: the same search as the Songs list / player (phone songs + indexed LAN folders);
 * the picked file is deleted for good after a confirmation — phone files through Android's own confirmation,
 * LAN (SMB) files directly. It also leaves every playlist, the queue and the lists.
 */
@Composable
fun DeleteFileDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var library by remember { mutableStateOf<List<PlayItem>?>(null) }
    var query by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<PlayItem?>(null) }
    var note by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<String?>(null) }          // full path of the file just deleted
    var pickPath by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) { library = SongsCache.all(ctx.applicationContext) }
    androidx.compose.runtime.LaunchedEffect(pick) { pickPath = pick?.let { fullPathOf(ctx, it) } ?: "" }
    val hits = remember(query, library) {
        val q = query.trim()
        if (q.length < 2) emptyList() else (library ?: emptyList()).filter { songMatches(it, q) }.take(60)
    }
    fun forget(uri: String) {
        App.instance.store.update { s ->
            s.copy(recent = s.recent.filterNot { it.uri == uri }, playlists = s.playlists.map { p -> p.copy(items = p.items.filterNot { it.uri == uri }) })
        }
        val st = App.instance.player.state.value
        st.queue.withIndex().filter { it.value.uri == uri && it.index != st.index }.map { it.index }.sortedDescending()
            .forEach { App.instance.player.removeFromQueue(it) }
        HiddenFiles.hide(uri)                       // gone from the Songs list at once
        SongsCache.phone = null                     // phone list read again next time
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.panel).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Delete a file", color = C.bad, fontSize = 22.sp)
                Text("The file is removed from the phone or the NAS for good.", color = C.muted, fontSize = 12.sp)
                androidx.compose.material3.OutlinedTextField(query, { query = it }, singleLine = true, modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    placeholder = { Text("Artist, album or song", fontSize = 13.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = C.text))
                Text(when {
                    library == null -> "Reading the Library…"
                    query.trim().length < 2 -> "Type at least 2 letters"
                    else -> "${hits.size}${if (hits.size == 60) "+" else ""} found"
                }, color = C.muted, fontSize = 12.sp)
                if (note.isNotEmpty()) Text(note, color = C.bad, fontSize = 13.sp)
                val st = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.weight(1f, fill = false).scrollbar(st), state = st,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(hits.size) { k ->
                        val h = hits[k]
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(C.card2)
                            .clickable { note = ""; pick = h }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(h.title, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${h.artist.ifEmpty { "Unknown artist" }} · ${h.album.ifEmpty { "—" }} · ${if (h.uri.startsWith("smb://")) "LAN" else "phone"}",
                                color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    STextButton(onDone) { Text("‹ Back") }
                }
            }
        }
    }
    pick?.let { f ->
        when {
            f.uri.startsWith("content://") ->
                DeleteDialog(listOf(f.uri), pickPath.ifEmpty { f.fileName.ifEmpty { f.title } }, 0, 1) { deleted ->
                    val path = pickPath
                    pick = null
                    if (deleted) {
                        forget(f.uri); note = ""; library = library?.filterNot { it.uri == f.uri }; done = path
                        app.vlcinterface.data.ServerLog.log(app.vlcinterface.data.ServerLog.DELETED, "delete", "phone", "", true, "Deleted $path", f.uri)
                    }
                }
            f.uri.startsWith("smb://") -> androidx.compose.material3.AlertDialog(onDismissRequest = { pick = null }, containerColor = C.panel,
                title = { Text("Delete from the NAS?", color = C.bad) },
                text = { Text("This file will be deleted for good:\n\n$pickPath", color = C.text) },
                confirmButton = { STextButton({
                    val target = f; pick = null
                    scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                jcifs.smb.SmbFile(Uri.decode(target.uri), app.vlcinterface.sources.SmbProbe.fileContext(target.smbVersion, target.user, target.password, target.domain))
                                    .use { it.delete() }; true
                            } catch (e: Exception) {
                                note = "Not deleted: ${e.message ?: e.javaClass.simpleName}"
                                app.vlcinterface.data.ServerLog.log(app.vlcinterface.data.ServerLog.DELETED, "delete", "SMB", target.user, false,
                                    "Not deleted ${Uri.decode(target.uri)}: ${e.message ?: e.javaClass.simpleName}", target.uri)
                                false
                            }
                        }
                        if (ok) {
                            forget(target.uri); library = library?.filterNot { it.uri == target.uri }; done = Uri.decode(target.uri)
                            app.vlcinterface.data.ServerLog.log(app.vlcinterface.data.ServerLog.DELETED, "delete", "SMB", target.user, true,
                                "Deleted ${Uri.decode(target.uri)}", target.uri)
                        }
                    }
                }) { Text("Delete", color = C.bad) } },
                dismissButton = { STextButton({ pick = null }) { Text("Cancel") } })
            else -> { note = "This kind of location can't be deleted from here"; pick = null }
        }
    }
    // Confirmation of what was deleted, with its full path.
    done?.let { path ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { done = null }, containerColor = C.panel,
            title = { Text("File deleted", color = C.text) },
            text = { Text("Deleted for good:\n\n$path", color = C.text) },
            confirmButton = { STextButton({ done = null }) { Text("OK", color = accent()) } })
    }
}

/** The file's full path as shown to the user: "/storage/emulated/0/Music/…/song.flac" or "smb://nas/share/…/song.flac". */
private suspend fun fullPathOf(ctx: android.content.Context, item: PlayItem): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val u = item.uri
    if (!u.startsWith("content://")) return@withContext Uri.decode(u)
    try {
        ctx.contentResolver.query(Uri.parse(u), arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val rel = c.getString(0).orEmpty(); val name = c.getString(1).orEmpty(); val vol = c.getString(2).orEmpty()
            val root = if (vol.isEmpty() || vol == android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY) "/storage/emulated/0/" else "/storage/${vol.uppercase()}/"
            root + rel + name
        }
    } catch (_: Exception) { null } ?: (item.fileName.ifEmpty { item.title })
}
