@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.clickable
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
fun Cover(url: String, sizeDp: Int, modifier: Modifier = Modifier, item: PlayItem? = null,
          download: Boolean = false, onNote: (String) -> Unit = {}, onFound: () -> Unit = {}, retry: Int = 0, refresh: Int = 0,
          noCover: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    // True once the search for a picture has finished (so [noCover] isn't shown while still looking).
    var looked by remember(url, item?.uri, retry, refresh) { mutableStateOf(false) }
    val online = download && App.instance.store.current.settings.coverDownload
    val bmp by produceState<Bitmap?>(null, url, item?.uri, online, retry, refresh) {
        value = null          // a new song: the old picture goes at once, not when the new one has loaded
        looked = false
        value = withContext(Dispatchers.IO) {
            // A picture chosen by hand (refresh) wins over the one inside the file.
            val own = if (refresh > 0 || (item != null && app.vlcinterface.sources.CoverArt.bannedFile(item, url))) null else try {
                when {
                    url.startsWith("content://") -> ctx.contentResolver.loadThumbnail(Uri.parse(url), Size(512, 512), null)
                    url.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(url).path)
                    url.startsWith("/") -> BitmapFactory.decodeFile(url)
                    // Station logos (web address): kept in the temporary radio pictures folder.
                    url.startsWith("http") -> app.vlcinterface.sources.CoverArt.webPicture(ctx, url)?.let { BitmapFactory.decodeFile(it.path) }
                    else -> null
                }
            } catch (_: Exception) { null }
            // No picture inside the file: the album folder's picture (cover.jpg, folder.jpg, artist.jpg…);
            // in the player also from the internet (album, then artist / band), saved into that folder.
            own ?: item?.let { it0 ->
                if (download) {
                    val r = app.vlcinterface.sources.CoverArt.forPlayer(ctx, it0, it0.artist, it0.album, online || retry > 0, force = retry > 0)
                    withContext(Dispatchers.Main) { if (r.note.isNotEmpty()) onNote(r.note); if (r.bitmap != null) onFound() }
                    r.bitmap
                } else app.vlcinterface.sources.CoverArt.folderOnly(ctx, it0)
            }
        }
        looked = true
    }
    val b = bmp
    // The visualisation in place of a missing cover: the cover's full square area, square corners.
    val visHere = b == null && noCover != null && looked
    Box(modifier.size(sizeDp.dp).clip(if (visHere) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(8.dp))
        .background(if (visHere) androidx.compose.ui.graphics.Color.Black else C.card2), contentAlignment = Alignment.Center) {
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (visHere) noCover!!()
        else Text("♪", color = accent(), fontSize = (sizeDp / 2.6).sp)
    }
}

private sealed interface Row0 {
    data class Dir(val e: Entry) : Row0
    data class Header(val g: AlbumGroup) : Row0
    data class Song(val e: Entry, val multiDisc: Boolean) : Row0
    data class Video(val e: Entry) : Row0
    data class Sub(val e: Entry) : Row0
}

/**
 * Library listing: folders, then music grouped by album (disc → track order, never alphabetical, with durations),
 * then videos. Long-press opens the file / folder menu.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    /** Shown kind instead of the Libraries' Audio / Video switch ("video" from the video player). */
    kindOverride: String? = null,
    /** Subtitle files listed too; a tap gives them to the video playing. */
    onSubtitle: ((Entry) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Audio / Video switch of Libraries: folders always shown, files only of the chosen kind.
    val kind = kindOverride ?: App.instance.store.state.collectAsState().value.settings.libraryKind
    val hidden = App.instance.store.state.collectAsState().value.settings.hiddenUris
    val list = remember(entries, kind, hidden) { (entries ?: emptyList()).filter { (it.isDir || (if (kind == "video") it.isVideo else it.isAudio) ||
        (onSubtitle != null && (app.vlcinterface.sources.SubtitleFiles.isSub(it.name) || app.vlcinterface.sources.SubtitleFiles.isSeasonArchive(it.name)))) && it.uri !in hidden } }
    val groups = remember(list) { MusicLibrary.group(list, folderName) }
    val order = remember(list) { MusicLibrary.playOrder(list, folderName) }
    val rows = remember(list) {
        buildList<Row0> {
            list.filter { it.isDir }.forEach { add(Row0.Dir(it)) }
            groups.forEach { g -> add(Row0.Header(g)); g.entries.forEach { add(Row0.Song(it, g.multiDisc)) } }
            list.filter { it.isVideo }.forEach { add(Row0.Video(it)) }
            if (onSubtitle != null) {
                // This season's archives first, then the subtitle files.
                val season = App.instance.player.state.value.item?.let { app.vlcinterface.player.Episodes.parse(Uri.decode(it.uri.substringAfterLast('/')))?.season }
                list.filter { !it.isDir && app.vlcinterface.sources.SubtitleFiles.isSeasonArchive(it.name) }
                    .sortedBy { if (app.vlcinterface.sources.SubtitleFiles.seasonOf(it.name) == season) 0 else 1 }.forEach { add(Row0.Sub(it)) }
                list.filter { !it.isDir && app.vlcinterface.sources.SubtitleFiles.isSub(it.name) }.forEach { add(Row0.Sub(it)) }
            }
        }
    }
    // Each folder remembers where it was scrolled to (kept while the app runs): coming back from a sub-folder, the
    // player or another page shows the same place again, also when the folder's list has to be read again first.
    val memKey = "folder:$folderKey:$kind"
    val state = rememberLazyListState(ScrollMemory.pos[memKey] ?: 0, ScrollMemory.pos["$memKey#o"] ?: 0)
    var placed by remember(memKey) { mutableStateOf((ScrollMemory.pos[memKey] ?: 0) == 0) }
    LaunchedEffect(memKey, rows.size) {
        val want = ScrollMemory.pos[memKey] ?: 0
        if (!placed && rows.size > want) { state.scrollToItem(want, ScrollMemory.pos["$memKey#o"] ?: 0); placed = true }
    }
    LaunchedEffect(memKey) {
        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { (i, o) ->
            if (placed && state.layoutInfo.totalItemsCount > 0) { ScrollMemory.pos[memKey] = i; ScrollMemory.pos["$memKey#o"] = o }
        }
    }
    // Jump to the marked song once; coming back to this page later keeps the place you scrolled to.
    var highlightDone by androidx.compose.runtime.saveable.rememberSaveable(highlightUri) { mutableStateOf(false) }
    LaunchedEffect(highlightUri, rows.size) {
        if (highlightUri.isNotEmpty() && !highlightDone) {
            val i = rows.indexOfFirst { (it as? Row0.Song)?.e?.uri == highlightUri || (it as? Row0.Video)?.e?.uri == highlightUri }
            if (i >= 0) { state.scrollToItem((i - 2).coerceAtLeast(0)); highlightDone = true }
        }
    }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var addTarget by remember { mutableStateOf<(suspend () -> List<PlayItem>)?>(null) }
    var quickTarget by remember { mutableStateOf<(suspend () -> List<PlayItem>)?>(null) }
    var blackDir by remember { mutableStateOf<Entry?>(null) }
    var deleteTarget by remember { mutableStateOf<Entry?>(null) }
    var rgTarget by remember { mutableStateOf<(suspend () -> List<PlayItem>)?>(null) }
    var infoItem by remember { mutableStateOf<PlayItem?>(null) }
    var rgAlbum by remember { mutableStateOf<Pair<String, List<PlayItem>>?>(null) }
    // Multi-select (songs and folders): long-press → Select…, then taps tick/untick.
    var selected by remember(entries) { mutableStateOf(setOf<String>()) }
    fun toggle(e: Entry) { selected = if (e.uri in selected) selected - e.uri else selected + e.uri }
    val playing by App.instance.player.state.collectAsState()

    fun play(e: Entry, random: Boolean? = null, whole: Boolean = false) {
        val items = order.map { MusicLibrary.toPlayItem(it, source, folderKey) }
        val i = items.indexOfFirst { it.uri == e.uri }.coerceAtLeast(0)
        val player = App.instance.player
        player.playQueue(items, i, origin, random = random, wholeList = whole)
        if (!items[i].isMusic) nav.go(Screen.Player) else nav.go(Screen.Music)
        // Playing from a folder plays the whole folder, subfolders included (user rule), in the order shown:
        // this folder's files as listed (albums in track order, then videos) start at once; then each subfolder
        // in the order listed, each with its files as shown and then its own subfolders.
        scope.launch {
            val have = items.map { it.uri }.toHashSet()
            val extra = when {
                source != null -> list.filter { it.isDir }.flatMap { d -> MusicLibrary.collectRecursive(source, d.uri) }
                folderKey.startsWith("local:") -> {
                    val vol = folderKey.removePrefix("local:").substringBefore(':')
                    val rel = folderKey.removePrefix("local:").substringAfter(':')
                    MusicLibrary.collectLocal(ctx, app.vlcinterface.sources.LocalFolder("$vol:$rel", folderName, rel, 0, vol), true)
                }
                else -> emptyList()
            }.filter { it.uri !in have }.let { ofKind(it) }
            // Only if that folder is still what's playing.
            if (extra.isNotEmpty() && player.state.value.origin == origin)
                player.addToQueue(if (App.instance.store.current.settings.shuffle && extra.all { it.isMusic }) extra.shuffled() else extra)
        }
    }

    fun itemsOfDir(e: Entry): suspend () -> List<PlayItem> = {
        ofKind(when {
            source != null -> MusicLibrary.collectRecursive(source, e.uri)
            // A phone subfolder ("localdir:<volume>:<relative path>"): everything under it.
            e.uri.startsWith("localdir:") -> {
                val id = e.uri.removePrefix("localdir:"); val vol = id.substringBefore(':'); val rel = id.substringAfter(':')
                MusicLibrary.collectLocal(ctx, app.vlcinterface.sources.LocalFolder(id, e.name, rel, 0, vol), true)
            }
            else -> emptyList()
        })
    }

    /** Long-press while selecting (like the Songs list): play time of the selection + new playlist. */
    fun quickFromSelection() {
        val chosen = list.filter { it.uri in selected }
        quickTarget = { chosen.flatMap { e -> if (e.isDir) itemsOfDir(e)() else listOf(MusicLibrary.toPlayItem(e, source, folderKey)) } }
    }

    when {
        error.isNotEmpty() -> Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(error, color = C.bad, fontSize = 14.sp)
            if (source != null) Pill("Open server log", modifier = Modifier.padding(top = 12.dp)) { nav.go(Screen.LogPage(app.vlcinterface.data.ServerLog.keyOf(source.host))) }
        }
        entries == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent()) }
        rows.isEmpty() -> Text(if (kind == "video") "No videos here — switch to Audio at the top of Libraries for music." else "No music here — switch to Video at the top of Libraries for videos.", color = C.muted, modifier = Modifier.padding(20.dp))
        else -> LazyColumn(Modifier.fillMaxSize().scrollbar(state).padding(horizontal = 16.dp), state = state) {
            // Long folders: "Jump to…" pinned on top (each letter: the first matching name).
            if (rows.size > 20) stickyHeader {
                JumpToBox(state, rows.map { r -> when (r) { is Row0.Dir -> r.e.name; is Row0.Song -> r.e.name; is Row0.Video -> r.e.name; is Row0.Sub -> r.e.name; is Row0.Header -> "" } },
                    offset = 1 + (if (selected.isNotEmpty()) 1 else 0) + (if (order.size > 1 && selected.isEmpty()) 1 else 0))
            }
            // Play the whole folder (its audio + video files, album order) in order or at random.
            if (selected.isNotEmpty()) item {
                val chosen = list.filter { it.uri in selected }
                fun collect(): suspend () -> List<PlayItem> = {
                    chosen.flatMap { e -> if (e.isDir) itemsOfDir(e)() else listOf(MusicLibrary.toPlayItem(e, source, folderKey)) }
                }
                // Same choices as the Songs list (wraps onto more lines when needed, never overlapping);
                // long-press a selected item = play time + new playlist (In order / Random).
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.card2)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${selected.size} selected", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
                    val all = list.filter { it.isDir || it.isAudio || it.isVideo }.map { it.uri }.toSet()
                    if (selected.size < all.size) Pill("Select all") { selected = all }
                    Pill("Deselect all") { selected = emptySet() }
                    Pill("▶  Play selected", filled = true) {
                        val c = collect()
                        scope.launch {
                            val items = c()
                            if (items.isNotEmpty()) {
                                App.instance.player.playQueue(items, 0, origin)
                                nav.go(if (items[0].isMusic) Screen.Music else Screen.Player)
                            }
                            selected = emptySet()
                        }
                    }
                    Pill("+ Queue") {
                        val c = collect()
                        scope.launch {
                            val its = c(); val n = App.instance.player.addToQueue(its)
                            android.widget.Toast.makeText(ctx, "$n added to the queue" + if (its.size > n) " (${its.size - n} already in it)" else "",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    Pill("+ Playlist…") { addTarget = collect() }
                    Pill("⏱ New playlist…") { quickTarget = collect() }
                    Pill("RG scan") { rgTarget = collect() }
                }
            }
            if (order.size > 1 && selected.isEmpty()) item {
                PlayListButtons(Modifier.padding(top = 8.dp, bottom = 4.dp)) { random ->
                    play(if (random) order.random() else order.first(), random, whole = true)
                }
            }
            items(rows) { r ->
                when (r) {
                    is Row0.Dir -> Row(Modifier.fillMaxWidth().heightIn(min = lrDp(56)).clip(RoundedCornerShape(8.dp))
                        .background(if (r.e.uri in selected) accent().copy(alpha = 0.18f) else Color.Transparent)
                        .combinedClickable(onClick = { if (selected.isNotEmpty()) toggle(r.e) else onOpenDir(r.e) }, onLongClick = { if (selected.isNotEmpty()) quickFromSelection() else menuFor = r.e }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(lrDp(40).coerceIn(26.dp, 44.dp)).clip(RoundedCornerShape(10.dp)).background(C.card2), contentAlignment = Alignment.Center) {
                            Text(if (r.e.uri in selected) "✓" else "▤", color = accent(), fontWeight = FontWeight.Bold)
                        }
                        MarqueeText(r.e.name, C.text, Modifier.padding(start = 14.dp).weight(1f), fontSize = 15.sp, list = "folders")
                        Box { if (menuFor == r.e) EntryMenu(r.e, true, source, onDismiss = { menuFor = null },
                            onPlayNext = { scope.launch { App.instance.player.playNext(itemsOfDir(r.e)()) } },
                            onQueue = { scope.launch { App.instance.player.addToQueue(itemsOfDir(r.e)()) } },
                            onAddPlaylist = { addTarget = itemsOfDir(r.e) }, onDelete = null,
                            onQuickPlaylist = { quickTarget = itemsOfDir(r.e) },
                            onRemove = { blackDir = r.e },
                            onSelect = { toggle(r.e) },
                            onRgScan = if (App.instance.store.current.settings.libraryKind == "audio") ({ rgTarget = itemsOfDir(r.e) }) else null,
                            onPlay = { scope.launch { itemsOfDir(r.e)().takeIf { it.isNotEmpty() }?.let { l ->
                                App.instance.player.playQueue(l, 0, app.vlcinterface.player.Origin("folder", source?.id ?: "", r.e.uri, r.e.name))
                                nav.go(if (l.first().isMusic) Screen.Music else Screen.Player) } } },
                            folderInfo = FolderInfo(r.e.name, Uri.decode(r.e.uri).substringAfter("://"), source?.name ?: "Phone"), collect = itemsOfDir(r.e)) }
                    }
                    // Long-press an album title: ReplayGain scan of that whole album.
                    is Row0.Header -> Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = {
                            val songs = r.g.entries.map { MusicLibrary.toPlayItem(it, source, folderKey) }
                            rgAlbum = r.g.album to songs
                        }).padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(r.g.artwork, 56, item = r.g.entries.firstOrNull()?.let { MusicLibrary.toPlayItem(it, source, folderKey) })
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
                        val sel = e.uri in selected
                        Row(Modifier.fillMaxWidth().heightIn(min = lrDp(52)).clip(RoundedCornerShape(8.dp))
                            .background(if (sel) accent().copy(alpha = 0.18f) else if (hl) accent().copy(alpha = 0.12f) else Color.Transparent)
                            .combinedClickable(onClick = { if (selected.isNotEmpty()) toggle(e) else play(e) }, onLongClick = { if (selected.isNotEmpty()) quickFromSelection() else menuFor = e }).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (sel) Text("✓", color = accent(), fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp)) else
                            Text(if (e.track > 0) (if (r.multiDisc && e.disc > 0) "${e.disc}-%02d".format(e.track) else e.track.toString()) else "·",
                                color = if (isCur) accent() else C.muted, fontSize = 13.sp, modifier = Modifier.width(36.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.display, color = if (isCur) accent() else C.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (e.artist.isNotEmpty() && e.artist != e.albumArtist) Text(e.artist, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (e.durationMs > 0) fmtTime(e.durationMs) else "–:––", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                            // The common file menu (same on every page) + Select, Remove from this list, Delete file.
                            Box { if (menuFor == e) FileMenu(nav, MusicLibrary.toPlayItem(e, source, folderKey), true, { menuFor = null }, FileMenuExtras(
                                onSelect = { toggle(e) },
                                onRemove = { HiddenFiles.hide(e.uri) })) }   // deleting files: only from Settings
                        }
                    }
                    // Subtitle file (video player's Browse): a tap puts it on the video playing.
                    is Row0.Sub -> Row(Modifier.fillMaxWidth().heightIn(min = lrDp(52)).clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { onSubtitle?.invoke(r.e) }, onLongClick = { onSubtitle?.invoke(r.e) }), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(start = 2.dp, end = 10.dp).clip(RoundedCornerShape(4.dp)).background(C.card2).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            Text(if (app.vlcinterface.sources.SubtitleFiles.isZip(r.e.name)) "ZIP" else "CC", color = accent(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            MarqueeText(r.e.name, C.text, Modifier.fillMaxWidth(), fontSize = 15.sp, list = "videos")
                            Text(if (app.vlcinterface.sources.SubtitleFiles.isZip(r.e.name)) "Season ${app.vlcinterface.sources.SubtitleFiles.seasonOf(r.e.name)} subtitles archive · tap to pick one"
                                else "Subtitles · tap to use with the video playing", color = C.muted, fontSize = 12.sp)
                        }
                    }
                    is Row0.Video -> {
                        val e = r.e
                        Row(Modifier.fillMaxWidth().heightIn(min = lrDp(60)).clip(RoundedCornerShape(8.dp))
                            .background(if (e.uri == highlightUri || playing.item?.uri == e.uri) accent().copy(alpha = 0.12f) else Color.Transparent)
                            .combinedClickable(onClick = { play(e) }, onLongClick = { menuFor = e }), verticalAlignment = Alignment.CenterVertically) {
                            Text("▶", color = accent(), modifier = Modifier.padding(start = 6.dp, end = 14.dp))
                            Column(Modifier.weight(1f)) {
                                MarqueeText(e.name, C.text, Modifier.fillMaxWidth(), fontSize = 15.sp, list = if (e.isVideo) "videos" else "folders")
                                val meta = listOfNotNull(if (e.durationMs > 0) fmtTime(e.durationMs) else null, if (e.size > 0) "%.0f MB".format(e.size / 1e6) else null).joinToString(" · ")
                                if (meta.isNotEmpty()) Text(meta, color = C.muted, fontSize = 12.sp)
                            }
                            // The common file menu (same on every page) + Select, Remove from this list, Delete file.
                            Box { if (menuFor == e) FileMenu(nav, MusicLibrary.toPlayItem(e, source, folderKey), true, { menuFor = null }, FileMenuExtras(
                                onSelect = { toggle(e) },
                                onRemove = { HiddenFiles.hide(e.uri) })) }   // deleting files: only from Settings
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }

    addTarget?.let { t -> AddToPlaylistDialog(t) { addTarget = null; selected = emptySet() } }
    quickTarget?.let { t -> QuickPlaylistDialog(nav, t) { made -> quickTarget = null; if (made) selected = emptySet() } }
    // Folder out of the lists: everything inside is listed first, then confirmed.
    blackDir?.let { d ->
        FolderContentConfirm("Remove folder from lists?", Uri.decode(d.uri) + "\nNever re-added (indexing, rescans, adds). Nothing is deleted.", false,
            "Remove from lists", { if (source != null) lanFolderFiles(source, d.uri) else itemsOfDir(d)().map { Uri.decode(it.uri) } },
            onConfirm = { HiddenFiles.hide(d.uri); blackDir = null }, onCancel = { blackDir = null })
    }
    rgTarget?.let { t -> RgScanDialog(nav, t) { rgTarget = null; selected = emptySet() } }
    infoItem?.let { it0 -> TagInfoDialog(it0) { infoItem = null } }
    rgAlbum?.let { (name, songs) -> RgScanDialog(nav, { songs }, RgModeSingle, "ReplayGain: $name") { rgAlbum = null } }
    deleteTarget?.let { e ->
        DeleteDialog(listOf(e.uri), e.name, if (e.size > 0) e.size else 0, 1, onDone = { deleted -> deleteTarget = null; if (deleted) onRefresh() })
    }
}

@Composable
fun EntryMenu(
    e: Entry, isDir: Boolean, source: Source?, onDismiss: () -> Unit,
    onPlayNext: () -> Unit, onQueue: () -> Unit, onAddPlaylist: () -> Unit, onDelete: (() -> Unit)?,
    onSelect: (() -> Unit)? = null, onRgScan: (() -> Unit)? = null, onInfo: (() -> Unit)? = null,
    onQuickPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    folderInfo: FolderInfo? = null, collect: (suspend () -> List<PlayItem>)? = null,
) {
    SDropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        // Same choices as every media item (2026-11-21 11:56): play, queues, playlist, ReplayGain, details, share.
        if (onPlay != null) SMenuItem(text = { Text(if (isDir) "▶  Play all now" else "▶  Play now") }, onClick = { onPlay(); onDismiss() })
        if (onSelect != null) SMenuItem(text = { Text("Select…") }, onClick = { onSelect(); onDismiss() })
        SMenuItem(text = { Text(if (isDir) "Play all next" else "Play next") }, onClick = { onPlayNext(); onDismiss() })
        SMenuItem(text = { Text(if (isDir) "Add all to queue" else "Add to queue") }, onClick = { onQueue(); onDismiss() })
        SMenuItem(text = { Text(if (isDir) "Add all to playlist…" else "Add to playlist…") }, onClick = { onAddPlaylist(); onDismiss() })
        if (onQuickPlaylist != null) SMenuItem(text = { Text("New playlist from this folder & play…") }, onClick = { onQuickPlaylist(); onDismiss() })
        if (onInfo != null) SMenuItem(text = { Text("Details…") }, onClick = { onInfo(); onDismiss() })
        if (folderInfo != null && collect != null) {
            SMenuItem(text = { Text("Details…") }, onClick = { FileDialogs.folder = folderInfo to collect; onDismiss() })
            SMenuItem(text = { Text("Share details…") }, onClick = { FileDialogs.shareFolder = folderInfo to collect; onDismiss() })
        }
        if (onRgScan != null) SMenuItem(text = { Text(if (isDir) "ReplayGain scan (with subfolders)…" else "ReplayGain scan…") }, onClick = { onRgScan(); onDismiss() })
        if (onRemove != null) SMenuItem(text = { Text(if (isDir) "Remove folder from lists (never re-added)" else "Remove from lists (never re-added)", color = C.bad) },
            onClick = { onRemove(); onDismiss() })
        if (onDelete != null) SMenuItem(text = { Text("Delete…", color = C.bad) }, onClick = { onDelete(); onDismiss() })
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
                        STextButton({ addTo(null) }, enabled = its.isNotEmpty()) { Text("Create") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { STextButton(onDone) { Text("Cancel") } },
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
        text = { Text(if (count == 1) "This file will be deleted for good:\n\n$name" + (if (totalBytes > 0) "\n${"%.1f".format(totalBytes / 1e6)} MB" else "")
            else "$name\n$count files · ${"%.1f".format(totalBytes / 1e6)} MB") },
        confirmButton = {
            STextButton({
                try {
                    val pi = MediaStore.createDeleteRequest(ctx.contentResolver, uris.map { Uri.parse(it) })
                    launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } catch (e: Exception) { onDone(false) }
            }) { Text("Delete", color = C.bad) }
        },
        dismissButton = { STextButton({ onDone(false) }) { Text("Cancel") } },
    )
}

/** Opens where the playing item came from (folder, playlist, radio). Used by the playing bar and the notification. */
/** [keepStack]: opened over the current page (the video player), so Back comes back to it. */
fun openOrigin(nav: Navigator, keepStack: Boolean = false, video: Boolean = false) {
    val st = App.instance.player.state.value
    val item = st.item ?: return
    val o = st.origin
    // From the video player (08:02 / 08:03): the video's own folder, with videos and subtitle files and every place reachable.
    if (video) { openItemFolder(nav, item, keepStack, video = true); return }
    when {
        item.isRadio || o.kind == "radio" -> { if (!keepStack) nav.home(); nav.replace(Screen.Library(4, 0)) }
        o.kind == "playlist" -> { if (!keepStack) nav.home(); nav.go(Screen.PlaylistDetail(o.playlistId, item.uri)) }
        o.kind == "local" -> {
            val f = LocalFolder(o.localFolderId, o.title, o.uri, 0, o.sourceId)
            if (!keepStack) nav.home(); nav.go(Screen.Folder(f, item.uri))
        }
        o.kind == "folder" -> { if (!keepStack) nav.home(); nav.go(Screen.Browse(o.sourceId, o.uri, o.title, item.uri)) }
        // Played from the queue / Songs list / a single file: the song's own folder in Libraries.
        else -> openItemFolder(nav, item, keepStack)
    }
}


/**
 * Opens the folder the item's file is in, in Libraries (Audio side for songs), with the item marked.
 * Unknown folder (single link, radio…) → where it was played from.
 */
fun openItemFolder(nav: Navigator, item: PlayItem, keepStack: Boolean = false, video: Boolean = false) {
    // 2026-11-28 01:18: a queue item's folderKey is where it was played from (a playlist, a search …) or empty — the
    // file's own folder is worked out first (network: its address; phone: its folder in the phone's song list).
    kotlinx.coroutines.MainScope().launch {
        val real = try { MusicLibrary.realFolderKey(App.instance, item) } catch (_: Exception) { item.folderKey }
        val src = MusicLibrary.sourceOf(item)
        openItemFolderAt(nav, item.copy(folderKey = real.ifEmpty { item.folderKey }, sourceId = src?.id ?: item.sourceId), keepStack, video)
    }
}

private fun openItemFolderAt(nav: Navigator, item: PlayItem, keepStack: Boolean, video: Boolean) {
    val fk = item.folderKey
    val store = App.instance.store
    val kind = if (item.isMusic) "audio" else "video"
    if (store.current.settings.libraryKind != kind) store.update { it.copy(settings = it.settings.copy(libraryKind = kind)) }
    when {
        fk.startsWith("local:") -> {
            val id = fk.removePrefix("local:")
            val vol = id.substringBefore(':'); val rel = id.substringAfter(':')
            val name = rel.trimEnd('/').substringAfterLast('/').ifEmpty { rel } +
                if (vol != android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY) " (SD card)" else ""
            if (!keepStack) nav.home(); nav.go(Screen.Folder(LocalFolder(id, name, rel, 0, vol), item.uri, video))
        }
        fk.contains("://") && item.sourceId.isNotEmpty() && store.current.sources.any { it.id == item.sourceId } -> {
            if (!keepStack) nav.home(); nav.go(Screen.Browse(item.sourceId, fk, Uri.decode(fk.trimEnd('/').substringAfterLast('/')), item.uri, video))
        }
        video -> { if (!keepStack) nav.home(); nav.go(Screen.Folder(app.vlcinterface.sources.LocalFolder(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY + ":", "Phone", "", 0, android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), "", true)) }
        else -> if (keepStack) ShortNote.show("This file has no folder to browse — open one from the Library") else nav.go(if (item.isMusic) Screen.Music else Screen.Player)
    }
}

/** Keeps only the kind chosen with the Libraries Audio / Video switch (folder actions: play all, queue, add to playlist). */
fun ofKind(items: List<PlayItem>): List<PlayItem> =
    if (App.instance.store.current.settings.libraryKind == "video") items.filter { !it.isMusic } else items.filter { it.isMusic }

/** Scan mode for one album picked by its title. */
val RgModeSingle = app.vlcinterface.sources.RgMode.SINGLE_ALBUM
