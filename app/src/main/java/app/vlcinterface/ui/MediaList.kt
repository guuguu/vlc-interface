package app.vlcinterface.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.Playlist
import app.vlcinterface.data.Source
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem
import app.vlcinterface.sources.AlbumGroup
import app.vlcinterface.sources.Entry
import app.vlcinterface.sources.InternalSource
import app.vlcinterface.sources.LocalFolder
import app.vlcinterface.sources.MusicLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Album cover / thumbnail from a content:// uri (phone) or a file:// / path (libVLC cache). */
@Composable
fun Cover(url: String, sizeDp: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            try {
                when {
                    url.startsWith("content://") -> ctx.contentResolver.loadThumbnail(Uri.parse(url), Size(256, 256), null)
                    url.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(url).path)
                    url.startsWith("/") -> BitmapFactory.decodeFile(url)
                    else -> null
                }
            } catch (_: Exception) { null }
        }
    }
    Box(modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(C.card2), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("♪", color = accent(), fontSize = (sizeDp / 2.6).sp)
    }
}

private sealed interface Row0 {
    data class Dir(val e: Entry) : Row0
    data class Header(val g: AlbumGroup) : Row0
    data class Song(val e: Entry, val multiDisc: Boolean) : Row0
    data class Video(val e: Entry) : Row0
}

/**
 * Library listing: folders, then music grouped by album (disc → track order, never alphabetical, with durations),
 * then videos. Long-press opens the file / folder menu.
 */
@Composable
fun MediaList(
    nav: Navigator,
    entries: List<Entry>?,
    error: String,
    folderName: String,
    source: Source?,
    folderKey: String,
    origin: Origin,
    highlightUri: String = "",
    onOpenDir: (Entry) -> Unit,
    onRefresh: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = entries ?: emptyList()
    val groups = remember(entries) { MusicLibrary.group(list, folderName) }
    val order = remember(entries) { MusicLibrary.playOrder(list, folderName) }
    val rows = remember(entries) {
        buildList<Row0> {
            list.filter { it.isDir }.forEach { add(Row0.Dir(it)) }
            groups.forEach { g -> add(Row0.Header(g)); g.entries.forEach { add(Row0.Song(it, g.multiDisc)) } }
            list.filter { it.isVideo }.forEach { add(Row0.Video(it)) }
        }
    }
    val state = rememberLazyListState()
    LaunchedEffect(highlightUri, rows.size) {
        if (highlightUri.isNotEmpty()) {
            val i = rows.indexOfFirst { (it as? Row0.Song)?.e?.uri == highlightUri || (it as? Row0.Video)?.e?.uri == highlightUri }
            if (i >= 0) state.scrollToItem((i - 2).coerceAtLeast(0))
        }
    }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var addTarget by remember { mutableStateOf<(suspend () -> List<PlayItem>)?>(null) }
    var deleteTarget by remember { mutableStateOf<Entry?>(null) }
    val playing by App.instance.player.state.collectAsState()

    fun play(e: Entry) {
        val items = order.map { MusicLibrary.toPlayItem(it, source, folderKey) }
        val i = items.indexOfFirst { it.uri == e.uri }.coerceAtLeast(0)
        App.instance.player.playQueue(items, i, origin)
        if (!items[i].isMusic) nav.go(Screen.Player) else nav.go(Screen.Music)
    }

    fun itemsOfDir(e: Entry): suspend () -> List<PlayItem> = {
        if (source != null) MusicLibrary.collectRecursive(source, e.uri) else emptyList()
    }

    when {
        error.isNotEmpty() -> Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(error, color = C.bad, fontSize = 14.sp)
            if (source != null) Pill("Open server log", modifier = Modifier.padding(top = 12.dp)) { nav.go(Screen.LogPage(app.vlcinterface.data.ServerLog.keyOf(source.host))) }
        }
        entries == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent()) }
        rows.isEmpty() -> Text("No audio or video here.", color = C.muted, modifier = Modifier.padding(20.dp))
        else -> LazyColumn(Modifier.fillMaxSize().scrollbar(state).padding(horizontal = 16.dp), state = state) {
            items(rows) { r ->
                when (r) {
                    is Row0.Dir -> Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(onClick = { onOpenDir(r.e) }, onLongClick = { menuFor = r.e }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.card2), contentAlignment = Alignment.Center) { Text("▤", color = accent()) }
                        Text(r.e.name, color = C.text, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp).weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Box { if (menuFor == r.e) EntryMenu(r.e, true, source, onDismiss = { menuFor = null },
                            onPlayNext = { scope.launch { App.instance.player.playNext(itemsOfDir(r.e)()) } },
                            onQueue = { scope.launch { App.instance.player.addToQueue(itemsOfDir(r.e)()) } },
                            onAddPlaylist = { addTarget = itemsOfDir(r.e) }, onDelete = null) }
                    }
                    is Row0.Header -> Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(r.g.artwork, 56)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(r.g.album, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(r.g.artist.ifEmpty { null }, if (r.g.year > 0) r.g.year.toString() else null,
                                "${r.g.entries.size} tracks", if (r.g.totalMs > 0) fmtTime(r.g.totalMs) else null).joinToString(" · ")
                            Text(sub, color = C.muted, fontSize = 12.sp)
                        }
                    }
                    is Row0.Song -> {
                        val e = r.e
                        val isCur = playing.item?.uri == e.uri
                        val hl = isCur || e.uri == highlightUri
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (hl) accent().copy(alpha = 0.12f) else Color.Transparent)
                            .combinedClickable(onClick = { play(e) }, onLongClick = { menuFor = e }).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (e.track > 0) (if (r.multiDisc && e.disc > 0) "${e.disc}-%02d".format(e.track) else e.track.toString()) else "·",
                                color = if (isCur) accent() else C.muted, fontSize = 13.sp, modifier = Modifier.width(36.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.display, color = if (isCur) accent() else C.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (e.artist.isNotEmpty() && e.artist != e.albumArtist) Text(e.artist, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (e.durationMs > 0) fmtTime(e.durationMs) else "–:––", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                            Box { if (menuFor == e) EntryMenu(e, false, source, onDismiss = { menuFor = null },
                                onPlayNext = { App.instance.player.playNext(listOf(MusicLibrary.toPlayItem(e, source, folderKey))) },
                                onQueue = { App.instance.player.addToQueue(listOf(MusicLibrary.toPlayItem(e, source, folderKey))) },
                                onAddPlaylist = { addTarget = { listOf(MusicLibrary.toPlayItem(e, source, folderKey)) } },
                                onDelete = if (e.uri.startsWith("content://")) ({ deleteTarget = e }) else null) }
                        }
                    }
                    is Row0.Video -> {
                        val e = r.e
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (e.uri == highlightUri || playing.item?.uri == e.uri) accent().copy(alpha = 0.12f) else Color.Transparent)
                            .combinedClickable(onClick = { play(e) }, onLongClick = { menuFor = e }), verticalAlignment = Alignment.CenterVertically) {
                            Text("▶", color = accent(), modifier = Modifier.padding(start = 6.dp, end = 14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = C.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val meta = listOfNotNull(if (e.durationMs > 0) fmtTime(e.durationMs) else null, if (e.size > 0) "%.0f MB".format(e.size / 1e6) else null).joinToString(" · ")
                                if (meta.isNotEmpty()) Text(meta, color = C.muted, fontSize = 12.sp)
                            }
                            Box { if (menuFor == e) EntryMenu(e, false, source, onDismiss = { menuFor = null },
                                onPlayNext = { App.instance.player.playNext(listOf(MusicLibrary.toPlayItem(e, source, folderKey))) },
                                onQueue = { App.instance.player.addToQueue(listOf(MusicLibrary.toPlayItem(e, source, folderKey))) },
                                onAddPlaylist = { addTarget = { listOf(MusicLibrary.toPlayItem(e, source, folderKey)) } },
                                onDelete = if (e.uri.startsWith("content://")) ({ deleteTarget = e }) else null) }
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }

    addTarget?.let { t -> AddToPlaylistDialog(t) { addTarget = null } }
    deleteTarget?.let { e ->
        DeleteDialog(listOf(e.uri), e.name, if (e.size > 0) e.size else 0, 1, onDone = { deleted -> deleteTarget = null; if (deleted) onRefresh() })
    }
}

@Composable
fun EntryMenu(
    e: Entry, isDir: Boolean, source: Source?, onDismiss: () -> Unit,
    onPlayNext: () -> Unit, onQueue: () -> Unit, onAddPlaylist: () -> Unit, onDelete: (() -> Unit)?,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(if (isDir) "Play all next" else "Play next") }, onClick = { onPlayNext(); onDismiss() })
        DropdownMenuItem(text = { Text(if (isDir) "Add all to queue" else "Add to queue") }, onClick = { onQueue(); onDismiss() })
        DropdownMenuItem(text = { Text(if (isDir) "Add all to playlist…" else "Add to playlist…") }, onClick = { onAddPlaylist(); onDismiss() })
        if (onDelete != null) DropdownMenuItem(text = { Text("Delete…", color = C.bad) }, onClick = { onDelete(); onDismiss() })
    }
}

/** Collects the items (recursively for folders), then lets you pick or create a playlist. No duplicates are added. */
@Composable
fun AddToPlaylistDialog(collect: suspend () -> List<PlayItem>, onDone: () -> Unit) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    var items by remember { mutableStateOf<List<PlayItem>?>(null) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { items = collect() }
    fun addTo(p: Playlist?) {
        val its = items ?: return
        store.update { s ->
            val target = p ?: Playlist(name = newName.trim().ifEmpty { "New playlist" })
            val have = target.items.map { it.key }.toHashSet()
            val add = its.map { MusicLibrary.toPlEntry(it) }.filter { have.add(it.key) }
            val updated = target.copy(items = target.items + add)
            s.copy(playlists = if (p == null) s.playlists + updated else s.playlists.map { if (it.id == p.id) updated else it })
        }
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val its = items
                if (its == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = accent(), strokeWidth = 2.dp); Text("  Collecting audio and video files…")
                } else {
                    val folders = its.map { it.folderKey }.distinct().size
                    Text("${its.size} file${if (its.size == 1) "" else "s"}" + if (folders > 1) " from $folders folders" else "", color = C.muted)
                    st.playlists.forEach { p ->
                        Text("${p.name}  (${p.items.size})", color = C.text, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { addTo(p) }).padding(10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("New playlist name") })
                        TextButton({ addTo(null) }, enabled = its.isNotEmpty()) { Text("Create") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

/** Delete phone files: the app's confirmation first, then Android's own confirmation. */
@Composable
fun DeleteDialog(uris: List<String>, name: String, totalBytes: Long, count: Int, onDone: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ok = r.resultCode == android.app.Activity.RESULT_OK
        if (ok) {
            val set = uris.toSet()
            App.instance.store.update { s ->
                s.copy(recent = s.recent.filterNot { it.uri in set }, playlists = s.playlists.map { p -> p.copy(items = p.items.filterNot { it.uri in set }) })
            }
            val st = App.instance.player.state.value
            st.queue.withIndex().filter { it.value.uri in set && it.index != st.index }.map { it.index }.sortedDescending().forEach { App.instance.player.removeFromQueue(it) }
        }
        onDone(ok)
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Delete from the phone?") },
        text = { Text(if (count == 1) "$name\n${"%.1f".format(totalBytes / 1e6)} MB" else "$name\n$count files · ${"%.1f".format(totalBytes / 1e6)} MB") },
        confirmButton = {
            TextButton({
                try {
                    val pi = MediaStore.createDeleteRequest(ctx.contentResolver, uris.map { Uri.parse(it) })
                    launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } catch (e: Exception) { onDone(false) }
            }) { Text("Delete", color = C.bad) }
        },
        dismissButton = { TextButton({ onDone(false) }) { Text("Cancel") } },
    )
}

/** Opens where the playing item came from (folder, playlist, radio). Used by the playing bar and the notification. */
fun openOrigin(nav: Navigator) {
    val st = App.instance.player.state.value
    val item = st.item ?: return
    val o = st.origin
    when {
        item.isRadio || o.kind == "radio" -> { nav.home(); nav.replace(Screen.Library(2, 1)) }
        o.kind == "playlist" -> { nav.home(); nav.go(Screen.PlaylistDetail(o.playlistId, item.uri)) }
        o.kind == "local" -> {
            val f = LocalFolder(o.localFolderId, o.title, o.uri, 0, o.sourceId)
            nav.home(); nav.go(Screen.Folder(f, item.uri))
        }
        o.kind == "folder" -> { nav.home(); nav.go(Screen.Browse(o.sourceId, o.uri, o.title, item.uri)) }
        else -> nav.go(if (item.isMusic) Screen.Music else Screen.Player)
    }
}
