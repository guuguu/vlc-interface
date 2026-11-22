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
import androidx.compose.foundation.layout.heightIn
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
    val removeLabel: String = "Remove from lists (never re-added)",
    val onRemove: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/**
 * The same long-press menu for a song or video on every page (folders, Songs list, playlists, queue, Up next):
 * Play now, Play next, Add to queue, Add to playlist…, Open song folder, Details…, Share details…, ReplayGain scan…,
 * plus what the page allows (Select, Move up / down, Remove from this list, Delete file).
 */
@Composable
fun FileMenu(nav: Navigator, item: PlayItem, expanded: Boolean, onDismiss: () -> Unit, x: FileMenuExtras = FileMenuExtras()) {
    val ctx = LocalContext.current
    val player = App.instance.player
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
        SMenuItem({ Text("Add to playlist…") }, { close { FileDialogs.addTo = listOf(item) } })
        x.onSelect?.let { f -> SMenuItem({ Text("Select…") }, { close(f) }) }
        x.onMoveUp?.let { f -> SMenuItem({ Text("Move up") }, { close(f) }) }
        x.onMoveDown?.let { f -> SMenuItem({ Text("Move down") }, { close(f) }) }
        if (!item.isRadio) SMenuItem({ Text("Open song folder") }, { close { openItemFolder(nav, item) } })
        if (!item.isRadio) SMenuItem({ Text("Details…") }, { close { FileDialogs.info = item } })
        if (!item.isRadio) SMenuItem({ Text("Share details…") }, { close { FileDialogs.share = item } })
        // Videos (old TV series …): the same name searched on YouTube (2026-11-21 21:04).
        if (!item.isMusic && !item.isRadio) SMenuItem({ Text("Search on YouTube") }, { close { YouTube.open(ctx, item) } })
        if (item.isMusic) SMenuItem({ Text("ReplayGain scan…") }, { close { FileDialogs.rg = item } })
        x.onRemove?.let { f -> SMenuItem({ Text(x.removeLabel, color = C.bad) }, { close(f) }) }
        x.onDelete?.let { f -> SMenuItem({ Text("Delete file…", color = C.bad) }, { close(f) }) }
    }
}

/**
 * Windows opened from a file's menu. They live outside the menu: the menu (and often the row that showed it) goes
 * away when an entry is tapped, and a window kept inside it would never appear.
 */
object FileDialogs {
    var addTo by mutableStateOf<List<PlayItem>?>(null)
    var rg by mutableStateOf<PlayItem?>(null)
    var info by mutableStateOf<PlayItem?>(null)
    /** File whose details are being shared (read, then Android's share sheet). */
    var share by mutableStateOf<PlayItem?>(null)
    /** Folder details pop-up: title and how to collect the folder's items. */
    var folder by mutableStateOf<Pair<FolderInfo, suspend () -> List<PlayItem>>?>(null)
    /** Folder whose details are being shared. */
    var shareFolder by mutableStateOf<Pair<FolderInfo, suspend () -> List<PlayItem>>?>(null)
}

/** What a folder's details start from: its name, where it is, which source. */
class FolderInfo(val name: String, val place: String, val source: String)

/** Folder details: name, place, source, then what's inside (counted through its subfolders). */
fun folderDetails(f: FolderInfo, items: List<PlayItem>?): Pair<String, List<Pair<String, List<Pair<String, String>>>>> {
    val head = listOfNotNull("Name" to f.name, f.place.takeIf { it.isNotBlank() }?.let { "Place" to it }, f.source.takeIf { it.isNotBlank() }?.let { "Source" to it })
    val inside = if (items == null) listOf("Files" to "counting…") else {
        val songs = items.count { it.isMusic }; val videos = items.count { !it.isMusic && !it.isRadio }
        val total = items.sumOf { it.durationMs.coerceAtLeast(0) }
        val folders = items.map { it.folderKey.ifEmpty { it.uri.substringBeforeLast('/') } }.distinct().size
        listOfNotNull("Songs" to "$songs", "Videos" to "$videos", "Folders with files" to "$folders",
            if (total > 0) "Total play time" to fmtTime(total) else null)
    }
    return "Folder details · ${f.name}" to listOf("Folder" to head, "Inside (with subfolders)" to inside)
}

/** Shows the windows asked for from file menus (placed once, at the app's root). */
@Composable
fun FileDialogsHost(nav: Navigator) {
    FileDialogs.addTo?.let { l -> AddToPlaylistDialog({ l }) { FileDialogs.addTo = null } }
    FileDialogs.rg?.let { it0 -> RgScanDialog(nav, { listOf(it0) }) { FileDialogs.rg = null } }
    FileDialogs.info?.let { it0 -> TagInfoDialog(it0) { FileDialogs.info = null } }
    val ctx = LocalContext.current
    FileDialogs.share?.let { it0 ->
        androidx.compose.runtime.LaunchedEffect(it0) {
            val info = try { app.vlcinterface.sources.MediaInfo.read(ctx.applicationContext, it0) } catch (_: Exception) { null }
            val (t, secs, note) = itemDetails(it0, info)
            FileDialogs.share = null
            shareDetails(ctx, t, detailsText(t, secs, note))
        }
    }
    FileDialogs.folder?.let { (f, collect) ->
        var items by remember(f) { mutableStateOf<List<PlayItem>?>(null) }
        androidx.compose.runtime.LaunchedEffect(f) { items = try { collect() } catch (_: Exception) { emptyList() } }
        val (t, secs) = folderDetails(f, items)
        DetailsDialog(t, secs) { FileDialogs.folder = null }
    }
    FileDialogs.shareFolder?.let { (f, collect) ->
        androidx.compose.runtime.LaunchedEffect(f) {
            val items = try { collect() } catch (_: Exception) { emptyList() }
            val (t, secs) = folderDetails(f, items)
            FileDialogs.shareFolder = null
            shareDetails(ctx, t, detailsText(t, secs))
        }
    }
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
    val dq = rememberDebounced(query)
    var pick by remember { mutableStateOf<PlayItem?>(null) }
    var note by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<String?>(null) }          // full path of the file just deleted
    var pickPath by remember { mutableStateOf("") }
    // Songs, or whole folders (phone folders + LAN folders known from the index / bookmarks).
    var folders by remember { mutableStateOf(false) }
    var folderList by remember { mutableStateOf<List<FolderHit>?>(null) }
    var pickFolder by remember { mutableStateOf<FolderHit?>(null) }
    var phoneDelete by remember { mutableStateOf<Pair<FolderHit, List<String>>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        library = SongsCache.phoneOnly(ctx.applicationContext)           // phone songs at once; the NAS ones when checked (19:37)
        library = SongsCache.all(ctx.applicationContext)
    }
    androidx.compose.runtime.LaunchedEffect(folders) { if (folders && folderList == null) folderList = allFolders(ctx.applicationContext) }
    val folderHits = remember(dq, folderList) {
        if (dq.trim().length < 2) emptyList()
        else (folderList ?: emptyList()).filter { f -> textMatch(normText(f.path), dq) }.take(60)
    }
    androidx.compose.runtime.LaunchedEffect(pick) { pickPath = pick?.let { fullPathOf(ctx, it) } ?: "" }
    val hits = remember(dq, library) {
        if (dq.trim().length < 2) emptyList() else (library ?: emptyList()).filter { songMatches(it, dq) }.take(60)
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
            .dialogBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.popup).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (folders) "Delete a folder" else "Delete a file", color = C.bad, fontSize = 22.sp)
                Text(if (folders) "The folder and everything in it is removed for good — you see the full content first."
                    else "The file is removed from the phone or the NAS for good.", color = C.muted, fontSize = 12.sp)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Songs", selected = !folders) { folders = false }
                    Pill("Folders", selected = folders) { folders = true }
                }
                val st = androidx.compose.foundation.lazy.rememberLazyListState()
                var at by remember(dq, folders) { mutableStateOf(-1) }
                val n = if (folders) folderHits.size else hits.size
                fun step(k: Int) { if (n == 0) return; at = (k + n) % n; scope.launch { st.animateScrollToItem(at) } }
                SmallSearchBar(query, { query = it }, if (folders) "Folder name or path" else "Artist, album or song", n, at.coerceAtLeast(0), { step(at - 1) }, { step(at + 1) })
                Text(when {
                    folders && folderList == null -> "Reading the folders…"
                    !folders && library == null -> "Reading the Library…"
                    query.trim().length < 2 -> "Type at least 2 letters"
                    folders -> "${folderHits.size}${if (folderHits.size == 60) "+" else ""} found"
                    else -> "${hits.size}${if (hits.size == 60) "+" else ""} found"
                }, color = C.muted, fontSize = 12.sp)
                if (note.isNotEmpty()) Text(note, color = C.bad, fontSize = 13.sp)
                androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.weight(1f, fill = false).scrollbar(st), state = st,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (folders) items(folderHits.size) { k ->
                        val f = folderHits[k]
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (k == at) accent().copy(alpha = 0.25f) else C.menu)
                            .clickable { note = ""; pickFolder = f }.padding(horizontal = 8.dp, vertical = UiPrefs.menuGapDp.dp)) {
                            Text("▤ " + f.path.trimEnd('/').substringAfterLast('/'), color = C.text, fontSize = 14.sp, maxLines = 1)
                            Text(f.path + " · " + (if (f.src != null) "LAN" else "phone"), color = C.muted, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                    else items(hits.size) { k ->
                        val h = hits[k]
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (k == at) accent().copy(alpha = 0.25f) else C.menu)
                            .clickable { note = ""; pick = h }.padding(horizontal = 8.dp, vertical = UiPrefs.menuGapDp.dp)) {
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
            f.uri.startsWith("smb://") -> AlertDialog(onDismissRequest = { pick = null }, containerColor = C.panel,
                title = { Text("Delete from the NAS?", color = C.bad) },
                text = { Text("This file will be deleted for good:\n\n$pickPath", color = C.text) },
                confirmButton = { STextButton({
                    val target = f; pick = null
                    scope.launch {
                        // Read-only share: nothing is sent; the file can still be hidden from the lists.
                        if (app.vlcinterface.sources.WriteGuard.isReadOnly(target)) {
                            note = "Not deleted: the share is read-only. Use Hide in the file's menu to take it out of the lists (\"Check write access\" on the server tests the share again)."
                            return@launch
                        }
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                jcifs.smb.SmbFile(app.vlcinterface.sources.VpnLink.route(Uri.decode(target.uri)), app.vlcinterface.sources.SmbProbe.fileContext(target.smbVersion, target.user, target.password, target.domain))
                                    .use { it.delete() }; true
                            } catch (e: Exception) {
                                if (app.vlcinterface.sources.WriteGuard.isDenied(e)) app.vlcinterface.sources.WriteGuard.markReadOnly(target.sourceId, target.uri, e.message ?: "refused")
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
    // Folder: full content listed first (hidden files included), then deleted for good.
    pickFolder?.let { f ->
        FolderContentConfirm("Delete this folder for good?", f.path, true, "Delete everything",
            { if (f.src != null) lanFolderFiles(f.src, f.uri) else phoneFolderFiles(ctx, f.phone!!).map { it.second } },
            onCancel = { pickFolder = null },
            onConfirm = { _ ->
                pickFolder = null
                if (f.src != null) scope.launch {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            jcifs.smb.SmbFile(app.vlcinterface.sources.VpnLink.route(Uri.decode(f.uri).trimEnd('/') + "/"), app.vlcinterface.sources.SmbProbe.fileContext(f.src.smbVersion, f.src.user, f.src.password, f.src.domain))
                                .use { it.delete() }; null
                        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                    }
                    val L = app.vlcinterface.data.ServerLog
                    if (ok == null) {
                        (library ?: emptyList()).filter { it.uri.startsWith(f.uri.trimEnd('/') + "/") }.forEach { forget(it.uri) }
                        library = library?.filterNot { it.uri.startsWith(f.uri.trimEnd('/') + "/") }
                        folderList = folderList?.filterNot { it.uri.startsWith(f.uri.trimEnd('/')) }
                        L.log(L.DELETED, "delete folder", "SMB", f.src.user, true, "Deleted folder ${f.path}", f.uri)
                        done = f.path
                    } else {
                        note = "Not deleted: $ok"
                        L.log(L.DELETED, "delete folder", "SMB", f.src.user, false, "Not deleted folder ${f.path}: $ok", f.uri)
                    }
                } else scope.launch {
                    val uris = phoneFolderFiles(ctx, f.phone!!).map { it.first }
                    if (uris.isEmpty()) note = "Nothing in that folder that Android lets the app delete" else phoneDelete = f to uris
                }
            })
    }
    phoneDelete?.let { (f, uris) ->
        DeleteDialog(uris, f.path, 0, uris.size) { deleted ->
            phoneDelete = null
            if (deleted) {
                uris.forEach { forget(it) }
                folderList = folderList?.filterNot { it.path == f.path }
                val L = app.vlcinterface.data.ServerLog
                L.log(L.DELETED, "delete folder", "phone", "", true, "Deleted folder ${f.path} (${uris.size} files)", f.path)
                done = f.path
            }
        }
    }
    // Confirmation of what was deleted, with its full path.
    done?.let { path ->
        AutoInfoDialog(onDismissRequest = { done = null }, containerColor = C.panel,
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

/**
 * Before a whole folder is taken out of the lists or deleted for good: its entire content is listed (full paths),
 * then the action is confirmed (or not).
 */
@Composable
fun FolderContentConfirm(title: String, folderPath: String, danger: Boolean, confirmLabel: String,
                         collect: suspend () -> List<String>, onConfirm: (count: Int) -> Unit, onCancel: () -> Unit) {
    var list by remember { mutableStateOf<List<String>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { list = try { collect().sorted() } catch (_: Exception) { emptyList() } }
    val l = list
    AlertDialog(onDismissRequest = onCancel, containerColor = C.panel,
        title = { Text(title, color = if (danger) C.bad else C.text) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(folderPath, color = C.text, fontSize = 13.sp)
                if (l == null) {
                    androidx.compose.material3.CircularProgressIndicator(color = accent())
                    Text("Listing everything inside (subfolders included)…", color = C.muted, fontSize = 12.sp)
                } else {
                    val hidden = l.count { p -> p.split('/').any { it.startsWith(".") } }
                    Text("${l.size} file${if (l.size == 1) "" else "s"} inside" + (if (hidden > 0) " ($hidden hidden)" else "") + ":", color = C.muted, fontSize = 12.sp)
                    val st = androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.heightIn(max = 360.dp).scrollbar(st), state = st) {
                        // Hidden files (".name", or inside a ".folder") are listed too, marked "hidden".
                        items(l.size) { k ->
                            val hid = l[k].split('/').any { it.startsWith(".") }
                            Text(l[k] + if (hid) "   · hidden" else "", color = if (hid) C.muted else C.text, fontSize = 12.sp,
                                modifier = androidx.compose.ui.Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { if (l != null) STextButton({ onConfirm(l.size) }) { Text(confirmLabel, color = if (danger) C.bad else accent()) } },
        dismissButton = { STextButton(onCancel) { Text("Cancel") } })
}

/** Full paths of every file inside a LAN folder, subfolders included (all files, not only songs). */
suspend fun lanFolderFiles(src: app.vlcinterface.data.Source, uri: String, depth: Int = 0): List<String> {
    if (depth > 15) return emptyList()
    val l = app.vlcinterface.sources.Browse.listAll(src, uri, "list").getOrElse { return emptyList() }
    return l.filter { !it.isDir }.map { Uri.decode(it.uri) } + l.filter { it.isDir }.flatMap { lanFolderFiles(src, it.uri, depth + 1) }
}

/** Phone folder: its media files (content URIs) and their full paths, subfolders included. */
suspend fun phoneFolderFiles(ctx: android.content.Context, f: app.vlcinterface.sources.LocalFolder): List<Pair<String, String>> {
    val root = if (f.volume == android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY) "/storage/emulated/0/" else "/storage/${f.volume.uppercase()}/"
    return app.vlcinterface.sources.InternalSource.files(ctx, f, true, includeHidden = true).map { it.uri to (root + it.meta + it.name) }
}


/** A folder that can be deleted from Settings: on the phone ([phone]) or on a LAN server ([src] + [uri]). */
class FolderHit(val path: String, val uri: String, val phone: app.vlcinterface.sources.LocalFolder? = null, val src: app.vlcinterface.data.Source? = null)

/** Phone folders + LAN folders known from the index and the bookmarks (with their parent folders). */
private suspend fun allFolders(ctx: android.content.Context): List<FolderHit> {
    val out = ArrayList<FolderHit>()
    app.vlcinterface.sources.InternalSource.folders(ctx).forEach { f ->
        val root = if (f.volume == android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY) "/storage/emulated/0/" else "/storage/${f.volume.uppercase()}/"
        out += FolderHit(root + f.relativePath, "local:${f.id}", phone = f)
    }
    val sources = App.instance.store.current.sources
    val lan = HashMap<String, FolderHit>()
    fun addChain(src: app.vlcinterface.data.Source, folderUri: String, stopAt: String) {
        var u = folderUri.trimEnd('/')
        while (u.length > stopAt.trimEnd('/').length && u.contains("://")) {
            lan.getOrPut(u) { FolderHit(Uri.decode(u), u, src = src) }
            u = u.substringBeforeLast('/')
        }
    }
    app.vlcinterface.sources.NetIndex.songs().forEach { s ->
        val src = sources.firstOrNull { it.id == s.sourceId } ?: return@forEach
        if (s.folderKey.contains("://")) addChain(src, s.folderKey, app.vlcinterface.sources.Urls.forSource(src.copy(path = "")))
    }
    sources.forEach { src -> src.pins.forEach { pin ->
        val u = app.vlcinterface.sources.Urls.forSource(src.copy(path = ""), pin)
        lan.getOrPut(u.trimEnd('/')) { FolderHit(Uri.decode(u.trimEnd('/')), u.trimEnd('/'), src = src) }
    } }
    return out + lan.values.filter { it.src?.proto == "smb" }.sortedBy { it.path.lowercase() }
}
