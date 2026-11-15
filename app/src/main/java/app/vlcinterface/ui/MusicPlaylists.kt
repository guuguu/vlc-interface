package app.vlcinterface.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.PlEntry
import app.vlcinterface.data.Playlist
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Source
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem
import app.vlcinterface.sources.Browse
import app.vlcinterface.sources.Cue
import app.vlcinterface.sources.CueResult
import app.vlcinterface.sources.Entry
import app.vlcinterface.sources.MusicLibrary
import app.vlcinterface.sources.NasWriter
import app.vlcinterface.sources.PlaylistFiles
import app.vlcinterface.sources.SmbProbe
import app.vlcinterface.sources.Urls
import app.vlcinterface.sources.ext
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.vlcinterface.player.NativeAudio
import kotlin.math.log10

// ---------------- Music player ----------------

/** Music now-playing screen (the video player keeps its own interface). Never shows lyrics. */
@Composable
fun MusicScreen(nav: Navigator, openOutput: () -> Unit) {
    val player = App.instance.player
    val p by player.state.collectAsState()
    val st by App.instance.store.state.collectAsState()
    val item = p.item
    if (item == null) { LaunchedEffect(Unit) { nav.back() }; return }
    LaunchedEffect(item.uri) { if (!item.isMusic && !item.isRadio) nav.replace(Screen.Player) }
    var sleepMenu by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val meters = NativeAudio.active
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(end = if (meters) 21.dp else 0.dp)) {
        // No page title: the song name is already shown in the middle of the player.
        BackHeader("", nav) { OutputPill(openOutput) }
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(p.artworkUrl.ifEmpty { item.artwork }, 280, Modifier.padding(top = 8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.title, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(p.metaArtist.ifEmpty { null }, p.metaAlbum.ifEmpty { null }).joinToString(" · "), color = C.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                val badges = listOfNotNull(if (item.trackNo > 0) "Track ${item.trackNo}" else null, p.rgInfo.ifEmpty { null },
                    if (p.sleepUntil > 0) "Sleep ${((p.sleepUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(0)} min" else if (p.sleepEndOfTrack) "Sleep after this song" else null)
                if (badges.isNotEmpty()) Text(badges.joinToString(" · "), color = accent(), fontSize = 12.sp)
            }
            Column(Modifier.fillMaxWidth()) {
                var drag by remember { mutableStateOf<Float?>(null) }
                val len = if (item.endMs > 0) item.endMs - item.startMs else p.length
                val base = if (item.startMs > 0) item.startMs else 0
                val pos = (p.time - base).coerceAtLeast(0)
                Slider(drag ?: if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f, { drag = it },
                    onValueChangeFinished = { drag?.let { player.seekTo(base + (it * len).toLong()) }; drag = null }, enabled = len > 0,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = C.line))
                Row { Text(fmtTime(pos), color = C.muted, fontSize = 13.sp); Grow(); Text("-" + fmtTime((len - pos).coerceAtLeast(0)), color = C.muted, fontSize = 13.sp) }
            }
            // Skip buttons say where they skip in: the playlist or the folder playback started from.
            val within = when (p.origin.kind) { "playlist" -> "playlist"; "folder", "local" -> "folder"; else -> "queue" }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
                    CtlBtn("⏮", 52) { player.prev() }
                    Text("Prev in\n$within", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Box(Modifier.padding(top = 4.dp)) { CtlBtn("−${st.settings.doubleTapSeekSec}", 44) { player.seekBy(-st.settings.doubleTapSeekSec * 1000L) } }
                Box(Modifier.size(76.dp).clip(CircleShape).background(accent()).clickable { player.togglePause() }, contentAlignment = Alignment.Center) {
                    Text(if (p.isPlaying) "❚❚" else "▶", color = Color(0xFF1A0E05), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.padding(top = 4.dp)) { CtlBtn("+${st.settings.doubleTapSeekSec}", 44) { player.seekBy(st.settings.doubleTapSeekSec * 1000L) } }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
                    CtlBtn("⏭", 52) { player.next() }
                    Text("Next in\n$within", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            // Prominent shortcut: where the music is playing from (playlist or Library folder).
            val fromLabel = when (p.origin.kind) {
                "playlist" -> "Playlist: " + (st.playlists.firstOrNull { it.id == p.origin.playlistId }?.name ?: "")
                "folder", "local" -> "Folder: " + p.origin.title
                else -> "Open source"
            }
            Pill("☰  $fromLabel", filled = true, modifier = Modifier.fillMaxWidth()) { openOrigin(nav) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (st.settings.shuffle) "Random" else "In order", selected = st.settings.shuffle) { player.setShuffle(!st.settings.shuffle) }
                Pill("Repeat: " + st.settings.repeat.replaceFirstChar { it.uppercase() }, selected = st.settings.repeat != "off") {
                    val next = when (st.settings.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
                    App.instance.store.update { it.copy(settings = it.settings.copy(repeat = next)) }
                }
                Box {
                    Pill("Sleep", selected = p.sleepUntil > 0 || p.sleepEndOfTrack) { sleepMenu = true }
                    DropdownMenu(sleepMenu, { sleepMenu = false }) {
                        listOf(15, 30, 45, 60, 90).forEach { m -> DropdownMenuItem({ Text("$m minutes") }, { player.setSleep(m); sleepMenu = false }) }
                        DropdownMenuItem({ Text("After this song") }, { player.setSleep(-1); sleepMenu = false })
                        DropdownMenuItem({ Text("Off") }, { player.setSleep(0); sleepMenu = false })
                    }
                }
            }
            val next = p.upNext
            if (next.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) { SectionTitle("Up next", Modifier.weight(1f)); Text("Queue ›", color = accent(), modifier = Modifier.clickable { nav.go(Screen.Queue) }.padding(8.dp)) }
                next.forEachIndexed { i, n ->
                    Row(Modifier.fillMaxWidth().clickable { player.jumpTo(p.index + 1 + i) }.padding(vertical = 8.dp)) {
                        Text("${i + 1}.", color = C.muted, modifier = Modifier.width(28.dp))
                        Text(n.title + if (n.artist.isNotEmpty()) " — ${n.artist}" else "", color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (n.durationMs > 0) Text(fmtTime(n.durationMs), color = C.muted, fontSize = 13.sp)
                    }
                }
            }
            Box(Modifier.height(16.dp))
        }
    }
    // Stereo level meters: right edge, full height of the player.
    if (meters) StereoVu(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).fillMaxHeight().width(15.dp))
    }
}

/**
 * Two vertical meters (left, right): 6 dp wide, 3 dp apart. Fast rise, slow fall;
 * each has a square peak marker that holds ~1 s, then falls slowly.
 */
@Composable
fun StereoVu(modifier: Modifier) {
    var lvL by remember { mutableFloatStateOf(0f) }
    var lvR by remember { mutableFloatStateOf(0f) }
    var pkL by remember { mutableFloatStateOf(0f) }
    var pkR by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val buf = FloatArray(2)
        var last = 0L; var holdL = 0L; var holdR = 0L
        fun meter(p: Float): Float = if (p <= 0.00001f) 0f else ((20f * log10(p) + 48f) / 48f).coerceIn(0f, 1f) // −48…0 dBFS
        while (true) withFrameNanos { t ->
            val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.1f); last = t
            NativeAudio.peaks(buf)
            val nl = meter(buf[0]); val nr = meter(buf[1])
            lvL = if (nl > lvL) nl else maxOf(nl, lvL - dt * 1.2f)
            lvR = if (nr > lvR) nr else maxOf(nr, lvR - dt * 1.2f)
            if (lvL >= pkL) { pkL = lvL; holdL = t } else if (t - holdL > 1_000_000_000L) pkL = maxOf(lvL, pkL - dt * 0.35f)
            if (lvR >= pkR) { pkR = lvR; holdR = t } else if (t - holdR > 1_000_000_000L) pkR = maxOf(lvR, pkR - dt * 0.35f)
        }
    }
    Canvas(modifier) {
        val w = 6.dp.toPx(); val gap = 3.dp.toPx(); val h = size.height
        val zones = listOf(0f to 0.7f to Color(0xFF4CC26A), 0.7f to 0.9f to Color(0xFFE8B23A), 0.9f to 1f to Color(0xFFE5534B))
        listOf(lvL to pkL, lvR to pkR).forEachIndexed { i, (lv, pk) ->
            val x = size.width - (2 - i) * w - (1 - i) * gap
            drawRect(Color(0xFF1A1D23), Offset(x, 0f), Size(w, h))
            zones.forEach { (range, col) ->
                val top = minOf(lv, range.second)
                if (top > range.first) drawRect(col, Offset(x, h * (1f - top)), Size(w, h * (top - range.first)))
            }
            if (pk > 0.01f) drawRect(if (pk >= 0.9f) Color(0xFFE5534B) else Color.White, Offset(x, (h * (1f - pk)).coerceAtMost(h - w)), Size(w, w))
        }
    }
}

@Composable
private fun CtlBtn(t: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(C.card2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(t, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- Queue ----------------

@Composable
fun QueueScreen(nav: Navigator) {
    val player = App.instance.player
    val p by player.state.collectAsState()
    var saveName by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Queue", nav, "${p.queue.size} items") {
            if (p.queue.isNotEmpty()) TextButton({ saveName = "" }) { Text("Save as playlist", color = accent()) }
        }
        val st by App.instance.store.state.collectAsState()
        OrderSwitch(st.settings.shuffle, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { player.setShuffle(it) }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (p.index - 2).coerceAtLeast(0))
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 16.dp), state = state) {
            itemsIndexed(p.queue) { i, q ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (i == p.index) accent().copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { player.jumpTo(i) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = if (i == p.index) accent() else C.muted, modifier = Modifier.width(36.dp), fontSize = 13.sp)
                    Column(Modifier.weight(1f)) {
                        Text(q.title, color = if (i == p.index) accent() else C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (q.artist.isNotEmpty()) Text(q.artist, color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    if (q.durationMs > 0) Text(fmtTime(q.durationMs), color = C.muted, fontSize = 13.sp)
                    if (i != p.index) IconButton({ player.removeFromQueue(i) }) { Text("✕", color = C.muted) }
                }
            }
        }
        BottomBar(nav, current = 1)
    }
    saveName?.let { name ->
        AlertDialog(onDismissRequest = { saveName = null }, title = { Text("Save queue as playlist") },
            text = { OutlinedTextField(name, { saveName = it }, singleLine = true, placeholder = { Text("Playlist name") }) },
            confirmButton = { TextButton({
                val items = p.queue.map { MusicLibrary.toPlEntry(it) }
                App.instance.store.update { s -> s.copy(playlists = s.playlists + Playlist(name = name.ifBlank { "Queue" }, items = items)) }
                saveName = null
            }) { Text("Save") } },
            dismissButton = { TextButton({ saveName = null }) { Text("Cancel") } })
    }
}

// ---------------- Playlists ----------------

@Composable
fun PlaylistsScreen(nav: Navigator, openOutput: () -> Unit) {
    val ctx = LocalContext.current
    val store = App.instance.store
    val st by store.state.collectAsState()
    val p by App.instance.player.state.collectAsState()
    var newName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { ctx.contentResolver.openInputStream(uri)?.use { Cue.decode(it.readBytes()) } ?: "" } catch (_: Exception) { "" }
        val entries = PlaylistFiles.parse(text).map { (t, u) -> PlEntry(u, t) }
        if (entries.isEmpty()) status = "No playable links in that file (relative paths need the file to be on a saved server)"
        else {
            store.update { s -> s.copy(playlists = s.playlists + Playlist(name = Urls.nameOf(uri.toString()).substringBeforeLast('.'), items = entries)) }
            status = "Imported ${entries.size} items"
        }
    }
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Playlists", openOutput) { HomeButton(nav) }
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("New playlist", filled = true) { newName = "" }
                Pill("Import M3U / PLS") { importer.launch(arrayOf("*/*")) }
            }
            if (status.isNotEmpty()) Text(status, color = C.muted, fontSize = 13.sp)
            if (p.queue.isNotEmpty()) {
                SectionTitle("Now playing")
                CardBox(Modifier.clickable { nav.go(Screen.Queue) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Queue", color = C.text, fontWeight = FontWeight.SemiBold)
                            Text("${p.queue.size} items · playing ${p.index + 1}", color = C.muted, fontSize = 13.sp)
                        }
                        Text("›", color = C.muted, fontSize = 20.sp)
                    }
                }
            }
            SectionTitle("Saved playlists")
            if (st.playlists.isEmpty()) Text("No playlists yet. Long-press a song or folder in the Library → Add to playlist, or create one here.", color = C.muted, fontSize = 14.sp)
            st.playlists.forEach { pl ->
                CardBox(Modifier.clickable { nav.go(Screen.PlaylistDetail(pl.id)) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            val total = pl.items.sumOf { it.durationMs }
                            Text("${pl.items.size} items" + if (total > 0) " · ${fmtTime(total)}" else "", color = C.muted, fontSize = 13.sp)
                        }
                        Pill("Play", filled = true) { playPlaylist(nav, pl, 0, random = false) }
                        Pill("Random", modifier = Modifier.padding(start = 6.dp)) { playPlaylist(nav, pl, pl.items.indices.random(), random = true) }
                    }
                }
            }
            Box(Modifier.height(16.dp))
        }
        BottomBar(nav, current = 1)
    }
    newName?.let { name ->
        AlertDialog(onDismissRequest = { newName = null }, title = { Text("New playlist") },
            text = { OutlinedTextField(name, { newName = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { TextButton({ store.update { s -> s.copy(playlists = s.playlists + Playlist(name = name.ifBlank { "Playlist" })) }; newName = null }) { Text("Create") } },
            dismissButton = { TextButton({ newName = null }) { Text("Cancel") } })
    }
}

/** [random]: true = random order, false = in order, null = keep the current mode (tap on a song). */
fun playPlaylist(nav: Navigator, pl: Playlist, index: Int, random: Boolean? = null) {
    if (pl.items.isEmpty()) return
    val items = pl.items.map { MusicLibrary.fromPlEntry(it) }
    App.instance.player.playQueue(items, index, Origin("playlist", playlistId = pl.id, title = pl.name), random = random)
    nav.go(if (items[index.coerceIn(0, items.size - 1)].isMusic) Screen.Music else Screen.Player)
}

/** "In order" / "Random" switch for the queue. */
@Composable
fun OrderSwitch(random: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("In order", selected = !random) { onChange(false) }
        Pill("Random", selected = random) { onChange(true) }
    }
}

/** "Play in order" / "Play random" buttons for a list (playlist or Library folder). */
@Composable
fun PlayListButtons(modifier: Modifier = Modifier, onPlay: (random: Boolean) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("▶  Play in order", filled = true) { onPlay(false) }
        Pill("⤮  Play random") { onPlay(true) }
    }
}

@Composable
fun PlaylistDetailScreen(nav: Navigator, d: Screen.PlaylistDetail) {
    val ctx = LocalContext.current
    val store = App.instance.store
    val st by store.state.collectAsState()
    val pl = st.playlists.firstOrNull { it.id == d.id }
    val playing by App.instance.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    if (pl == null) { LaunchedEffect(Unit) { nav.back() }; return }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sendNas by remember { mutableStateOf(false) }
    var cueChoice by remember { mutableStateOf(false) }
    var cueResult by remember { mutableStateOf<CueResult?>(null) }
    var itemMenu by remember { mutableIntStateOf(-1) }
    fun update(f: (Playlist) -> Playlist) = store.update { s -> s.copy(playlists = s.playlists.map { if (it.id == pl.id) f(it) else it }) }
    val phoneCue = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { cueResult = Cue.fromPhone(ctx, uri) }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader(pl.name, nav, "${pl.items.size} items") {
            Pill("+ Add") { PickBasket.reset(pl.id); nav.go(Screen.PickMedia(pl.id)) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Playlist options", tint = C.text) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Add files or folders…") }, { PickBasket.reset(pl.id); nav.go(Screen.PickMedia(pl.id)); menu = false })
                    DropdownMenuItem({ Text("Add from .cue file…") }, { cueChoice = true; menu = false })
                    DropdownMenuItem({ Text("Send to NAS…") }, { sendNas = true; menu = false })
                    DropdownMenuItem({ Text("Share") }, {
                        val text = PlaylistFiles.toM3u8(pl.items, "")
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/x-mpegurl")
                            .putExtra(Intent.EXTRA_SUBJECT, "${pl.name}.m3u8").putExtra(Intent.EXTRA_TEXT, text), "Share playlist"))
                        menu = false
                    })
                    DropdownMenuItem({ Text("Rename") }, { rename = pl.name; menu = false })
                    DropdownMenuItem({ Text("Delete playlist", color = C.bad) }, { confirmDelete = true; menu = false })
                }
            }
        }
        if (pl.items.isNotEmpty()) PlayListButtons(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { random ->
            playPlaylist(nav, pl, if (random) pl.items.indices.random() else 0, random = random)
        }
        val state = rememberLazyListState()
        LaunchedEffect(d.highlight) {
            val i = pl.items.indexOfFirst { it.uri == d.highlight }
            if (i >= 0) state.scrollToItem((i - 2).coerceAtLeast(0))
        }
        if (pl.items.isEmpty()) Text("Empty. Long-press songs or folders in the Library → Add to playlist, or add a .cue sheet from the menu.",
            color = C.muted, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 16.dp), state = state) {
            itemsIndexed(pl.items) { i, e ->
                val cur = playing.origin.playlistId == pl.id && playing.index == i
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (cur || e.uri == d.highlight) accent().copy(alpha = 0.12f) else Color.Transparent)
                    .combinedClickable(onClick = { playPlaylist(nav, pl, i) }, onLongClick = { itemMenu = i }).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = if (cur) accent() else C.muted, modifier = Modifier.width(36.dp), fontSize = 13.sp)
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = if (cur) accent() else C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(e.artist.ifEmpty { null }, e.album.ifEmpty { null }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(if (e.durationMs > 0) fmtTime(e.durationMs) else "–:––", color = C.muted, fontSize = 13.sp)
                    Box {
                        IconButton({ itemMenu = i }) { Icon(Icons.Filled.MoreVert, "Item options", tint = C.muted) }
                        DropdownMenu(itemMenu == i, { itemMenu = -1 }) {
                            if (i > 0) DropdownMenuItem({ Text("Move up") }, { update { p -> p.copy(items = p.items.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }; itemMenu = -1 })
                            if (i < pl.items.size - 1) DropdownMenuItem({ Text("Move down") }, { update { p -> p.copy(items = p.items.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }; itemMenu = -1 })
                            DropdownMenuItem({ Text("Play next") }, { App.instance.player.playNext(listOf(MusicLibrary.fromPlEntry(e))); itemMenu = -1 })
                            DropdownMenuItem({ Text("Remove from playlist", color = C.bad) }, { update { p -> p.copy(items = p.items.filterIndexed { j, _ -> j != i }) }; itemMenu = -1 })
                        }
                    }
                }
            }
        }
        BottomBar(nav, current = 1)
    }

    rename?.let { n ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename playlist") },
            text = { OutlinedTextField(n, { rename = it }, singleLine = true) },
            confirmButton = { TextButton({ update { it.copy(name = n.ifBlank { it.name }) }; rename = null }) { Text("Save") } },
            dismissButton = { TextButton({ rename = null }) { Text("Cancel") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete \"${pl.name}\"?") },
        text = { Text("Only the playlist is deleted; the music and video files stay where they are.") },
        confirmButton = { TextButton({ store.update { s -> s.copy(playlists = s.playlists.filterNot { it.id == pl.id }) }; confirmDelete = false; nav.back() }) { Text("Delete", color = C.bad) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    if (cueChoice) AlertDialog(onDismissRequest = { cueChoice = false }, title = { Text("Add from .cue file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Where is the .cue file?", color = C.muted)
                Text("On this phone", color = C.text, modifier = Modifier.fillMaxWidth().clickable { cueChoice = false; phoneCue.launch(arrayOf("*/*")) }.padding(10.dp))
                st.sources.filter { it.proto in setOf("smb", "webdav", "http", "sftp", "ftp", "ftps", "nfs") }.forEach { s ->
                    Text(s.name.ifEmpty { s.host }, color = C.text, modifier = Modifier.fillMaxWidth().clickable {
                        cueChoice = false; nav.go(Screen.PickCue(pl.id, s.id, Urls.forSource(s)))
                    }.padding(10.dp))
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton({ cueChoice = false }) { Text("Cancel") } })
    cueResult?.let { r -> CueSummaryDialog(r, pl) { cueResult = null } }
    if (sendNas) SaveToNasDialog("Send playlist to NAS", "${pl.name}.m3u8", "playlist", { folder -> PlaylistFiles.toM3u8(pl.items, folder).toByteArray() }) { sendNas = false }
}

@Composable
fun CueSummaryDialog(r: CueResult, pl: Playlist, onDone: () -> Unit) {
    val store = App.instance.store
    AlertDialog(onDismissRequest = onDone, title = { Text("Add from .cue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.album.isNotEmpty()) Text(r.album, color = C.text, fontWeight = FontWeight.SemiBold)
                Text("${r.items.size} track${if (r.items.size == 1) "" else "s"} with a valid file", color = C.ok)
                if (r.ignored.isNotEmpty()) Text("${r.ignored.size} ignored — file not found: " + r.ignored.joinToString(", ") { "'$it'" }, color = C.bad, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                store.update { s -> s.copy(playlists = s.playlists.map { p ->
                    if (p.id != pl.id) p else {
                        val have = p.items.map { it.key }.toHashSet()
                        p.copy(items = p.items + r.items.map { MusicLibrary.toPlEntry(it) }.filter { have.add(it.key) })
                    }
                }) }
                onDone()
            }, enabled = r.items.isNotEmpty()) { Text("Add ${r.items.size}") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } })
}

/** Browse a saved source to pick a .cue file (the Library itself never shows .cue files). */
@Composable
fun PickCueScreen(nav: Navigator, p: Screen.PickCue) {
    val st by App.instance.store.state.collectAsState()
    val src = st.sources.firstOrNull { it.id == p.sourceId }
    val pl = st.playlists.firstOrNull { it.id == p.playlistId }
    var entries by remember(p.uri) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(p.uri) { mutableStateOf("") }
    var result by remember { mutableStateOf<CueResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(p.uri) {
        if (src == null) { error = "Source not found"; return@LaunchedEffect }
        Browse.listAll(src, p.uri).onSuccess { l -> entries = l.filter { it.isDir || it.name.ext() == "cue" } }.onFailure { error = it.message ?: "Could not open" }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Pick a .cue file", nav, p.uri)
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp)) {
            if (error.isNotEmpty()) Text(error, color = C.bad)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = accent(), strokeWidth = 2.dp); Text("  Checking the files in the .cue…", color = C.muted) }
            entries?.forEach { e ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                    if (e.isDir) nav.go(Screen.PickCue(p.playlistId, p.sourceId, e.uri))
                    else if (src != null) scope.launch {
                        busy = true
                        val bytes = readBytes(src, e.uri)
                        result = if (bytes != null) Cue.fromSource(src, e.uri, bytes) else CueResult(emptyList(), listOf(e.name), "")
                        ServerLog.log(src.host, "cue", src.proto.uppercase(), src.user, result!!.items.isNotEmpty(),
                            "${e.name}: ${result!!.items.size} tracks ok, ${result!!.ignored.size} ignored", result!!.ignored.joinToString(", "))
                        busy = false
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.isDir) "▤" else "≡", color = accent(), modifier = Modifier.padding(end = 14.dp))
                    Text(e.name, color = C.text)
                }
                Divider()
            }
        }
    }
    val r = result
    if (r != null && pl != null) CueSummaryDialog(r, pl) { result = null; nav.back() }
}

private suspend fun readBytes(src: Source, uri: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        when {
            uri.startsWith("smb://") -> SmbFile(android.net.Uri.decode(uri), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain)).openInputStream().use { it.readBytes() }
            uri.startsWith("http") -> {
                val req = okhttp3.Request.Builder().url(uri).apply { if (src.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(src.user, src.password)) }.build()
                app.vlcinterface.sources.Net.http.newCall(req).execute().use { it.body?.bytes() }
            }
            else -> null
        }
    } catch (_: Exception) { null }
}

/** Pick a writable saved source + folder, then write a small file there (asks before overwriting). */
@Composable
fun SaveToNasDialog(title: String, defaultName: String, what: String, content: (String) -> ByteArray, onDone: () -> Unit) {
    val st by App.instance.store.state.collectAsState()
    val writable = st.sources.filter { NasWriter.canWrite(it) }
    var src by remember { mutableStateOf(writable.firstOrNull()) }
    var folder by remember(src) { mutableStateOf(src?.path ?: "") }
    var name by remember { mutableStateOf(defaultName) }
    var status by remember { mutableStateOf("") }
    var askReplace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun doWrite(finalName: String) {
        val s = src ?: return
        scope.launch {
            status = "Saving…"
            val folderUrl = Urls.forSource(s.copy(path = ""), folder)
            NasWriter.write(s, folderUrl, finalName, content(folderUrl), what)
                .onSuccess { status = "Saved $finalName"; onDone() }.onFailure { status = it.message ?: "Could not save" }
        }
    }
    AlertDialog(onDismissRequest = onDone, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (writable.isEmpty()) Text("No saved SMB or WebDAV source to write to. Add your NAS in Library → LAN first.", color = C.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    writable.forEach { s -> Pill(s.name.ifEmpty { s.host }, selected = src?.id == s.id) { src = s } }
                }
                Field("Folder on the server", folder) { folder = it }
                Field("File name", name) { name = it }
                if (status.isNotEmpty()) Text(status, color = C.muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                val s = src ?: return@TextButton
                scope.launch {
                    if (NasWriter.exists(s, Urls.forSource(s.copy(path = ""), folder), name)) askReplace = true else doWrite(name)
                }
            }, enabled = src != null && name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } })
    if (askReplace) AlertDialog(onDismissRequest = { askReplace = false }, title = { Text("$name already exists") },
        text = { Text("Replace it, or keep both?") },
        confirmButton = { TextButton({ askReplace = false; doWrite(name) }) { Text("Replace") } },
        dismissButton = { TextButton({
            askReplace = false
            val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
            doWrite("$base (2)" + if (ext.isNotEmpty()) ".$ext" else "")
        }) { Text("Keep both") } })
}

// ---------------- Network logs ----------------

@Composable
fun LogsScreen(nav: Navigator) {
    val v by ServerLog.version.collectAsState()
    val saved by App.instance.store.state.collectAsState()
    val servers = remember(v, saved.sources) { ServerLog.servers(saved.sources.filter { it.proto != "upnp" }.map { it.host to it.proto }) }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Network logs", nav, "One page per server")
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (servers.isEmpty()) Text("No network activity logged yet.", color = C.muted)
            servers.forEach { s ->
                CardBox(Modifier.clickable { nav.go(Screen.LogPage(s.key)) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge(when (s.last?.ok) { true -> "✓"; false -> "✕"; null -> "–" }, s.last?.ok == true)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(s.host, color = C.text, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(s.protocols.joinToString("/").ifEmpty { null }, "${s.count} entries",
                                s.last?.let { fmt.format(Date(it.at)) }).joinToString(" · "), color = C.muted, fontSize = 12.sp)
                            Text(s.last?.message ?: "Saved server — nothing logged yet", color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogPageScreen(nav: Navigator, key: String) {
    val ctx = LocalContext.current
    val v by ServerLog.version.collectAsState()
    val entries = remember(v, key) { ServerLog.entries(key) }
    var errorsOnly by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var saveNas by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val shown = if (errorsOnly) entries.filter { !it.ok } else entries
    Column(Modifier.fillMaxSize()) {
        BackHeader(key, nav, "${entries.size} entries")
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Errors only", selected = errorsOnly) { errorsOnly = !errorsOnly }
            Pill("Empty log") { confirmEmpty = true }
            Pill("Share") {
                val (name, text) = ServerLog.exportText(key, App.VERSION)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, text), "Share log"))
            }
            Pill("Save to NAS") { saveNas = true }
        }
        val state = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 20.dp), state = state) {
            itemsIndexed(shown) { _, e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Badge(if (e.ok) "✓" else "✕", e.ok)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("${fmt.format(Date(e.at))} · ${e.action} · ${e.protocol}" + (if (e.user.isNotEmpty()) " · ${e.user}" else " · guest") + if (e.ms > 0) " · ${e.ms} ms" else "",
                            color = C.muted, fontSize = 12.sp)
                        Text(e.message, color = C.text, fontSize = 14.sp)
                        if (e.detail.isNotEmpty()) Text(e.detail, color = C.muted, fontSize = 12.sp)
                    }
                }
                Divider()
            }
        }
    }
    if (confirmEmpty) AlertDialog(onDismissRequest = { confirmEmpty = false }, title = { Text("Empty the log for $key?") },
        text = { Text("Only this server's log is cleared.") },
        confirmButton = { TextButton({ ServerLog.clear(key); confirmEmpty = false }) { Text("Empty", color = C.bad) } },
        dismissButton = { TextButton({ confirmEmpty = false }) { Text("Cancel") } })
    if (saveNas) {
        val (name, text) = remember { ServerLog.exportText(key, App.VERSION) }
        SaveToNasDialog("Save log to NAS", name, "log", { text.toByteArray() }) { saveNas = false }
    }
}


// ---------------- Pick files / folders for a playlist ----------------

/** What's ticked while browsing (kept across folder pages). Folders are added with all their subfolders. */
object PickBasket {
    data class Pick(val key: String, val label: String, val isDir: Boolean, val sourceId: String, val uri: String,
                    val local: app.vlcinterface.sources.LocalFolder? = null, val entry: Entry? = null)
    var playlistId = ""
    val picks = androidx.compose.runtime.mutableStateListOf<Pick>()
    fun reset(id: String) { playlistId = id; picks.clear() }
    fun toggle(p: Pick) { val i = picks.indexOfFirst { it.key == p.key }; if (i >= 0) picks.removeAt(i) else picks.add(p) }
    fun has(key: String) = picks.any { it.key == key }
}

@Composable
fun PickMediaScreen(nav: Navigator, p: Screen.PickMedia) {
    val ctx = LocalContext.current
    val st by App.instance.store.state.collectAsState()
    val pl = st.playlists.firstOrNull { it.id == p.playlistId }
    val scope = rememberCoroutineScope()
    var localFolders by remember { mutableStateOf<List<app.vlcinterface.sources.LocalFolder>?>(null) }
    var entries by remember(p.uri) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(p.uri) { mutableStateOf("") }
    var adding by remember { mutableStateOf("") }
    var done by remember { mutableStateOf("") }
    val src = st.sources.firstOrNull { it.id == p.sourceId }
    LaunchedEffect(p.sourceId, p.uri) {
        when {
            p.sourceId == "phone" && p.uri.isEmpty() -> localFolders = app.vlcinterface.sources.InternalSource.folders(ctx)
            p.sourceId == "phone" -> {
                val f = localFolders?.firstOrNull { it.id == p.uri } ?: app.vlcinterface.sources.InternalSource.folders(ctx).firstOrNull { it.id == p.uri }
                entries = if (f != null) MusicLibrary.playOrder(app.vlcinterface.sources.InternalSource.files(ctx, f), f.name) else emptyList()
            }
            src != null -> Browse.list(src, p.uri).onSuccess { l ->
                val tagged = MusicLibrary.withCachedTags(l)
                entries = tagged.filter { it.isDir } + MusicLibrary.playOrder(tagged, Urls.nameOf(p.uri))
            }.onFailure { error = app.vlcinterface.sources.SmbErrors.explain(it, src.user.isEmpty()) }
        }
    }
    fun addAll() {
        val target = pl ?: return
        scope.launch {
            adding = "Collecting files…"
            val items = ArrayList<PlayItem>()
            for (k in PickBasket.picks.toList()) {
                val s = st.sources.firstOrNull { it.id == k.sourceId }
                when {
                    k.local != null -> items += MusicLibrary.collectLocal(ctx, k.local, true)
                    k.isDir && s != null -> items += MusicLibrary.collectRecursive(s, k.uri) { n -> adding = "Collecting files… ${items.size + n}" }
                    k.entry != null -> items += MusicLibrary.toPlayItem(k.entry, s, Urls.parent(k.uri))
                }
            }
            var added = 0
            App.instance.store.update { s ->
                s.copy(playlists = s.playlists.map { pp ->
                    if (pp.id != target.id) pp else {
                        val have = pp.items.map { it.key }.toHashSet()
                        val add = items.map { MusicLibrary.toPlEntry(it) }.filter { have.add(it.key) }
                        added = add.size
                        pp.copy(items = pp.items + add)
                    }
                })
            }
            adding = ""
            done = "Added $added of ${items.size} files to ${target.name}" + if (items.size > added) " (${items.size - added} were already in it)" else ""
            PickBasket.picks.clear()
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader(if (p.sourceId.isEmpty()) "Add to ${pl?.name ?: "playlist"}" else p.title.ifEmpty { "Choose" }, nav,
            "Tick files and folders · folders include their subfolders")
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp)) {
            if (error.isNotEmpty()) Text(error, color = C.bad)
            when {
                p.sourceId.isEmpty() -> {
                    SectionTitle("Where from")
                    PickRow("▤", "On this phone", null, false) { nav.go(Screen.PickMedia(p.playlistId, "phone", "", "On this phone")) }
                    st.sources.filter { it.proto != "m3u" }.forEach { s ->
                        PickRow("▤", s.name.ifEmpty { s.host }, null, false) { nav.go(Screen.PickMedia(p.playlistId, s.id, Urls.forSource(s), s.name)) }
                    }
                }
                p.sourceId == "phone" && p.uri.isEmpty() -> localFolders?.forEach { f ->
                    val key = "phone:${f.id}"
                    PickRow("▤", f.name, PickBasket.has(key), true,
                        onCheck = { PickBasket.toggle(PickBasket.Pick(key, f.name, true, "phone", f.relativePath, local = f)) }) {
                        nav.go(Screen.PickMedia(p.playlistId, "phone", f.id, f.name))
                    }
                } ?: CircularProgressIndicator(color = accent())
                else -> entries?.forEach { e ->
                    val key = "${p.sourceId}:${e.uri}"
                    if (e.isDir) PickRow("▤", e.name, PickBasket.has(key), true,
                        onCheck = { PickBasket.toggle(PickBasket.Pick(key, e.name, true, p.sourceId, e.uri)) }) {
                        nav.go(Screen.PickMedia(p.playlistId, p.sourceId, e.uri, e.name))
                    } else PickRow(if (e.isAudio) "♪" else "▶", (if (e.track > 0) "${e.track}. " else "") + e.display +
                        (if (e.durationMs > 0) "  ·  ${fmtTime(e.durationMs)}" else ""), PickBasket.has(key), true,
                        onCheck = { PickBasket.toggle(PickBasket.Pick(key, e.display, false, p.sourceId, e.uri, entry = e)) }) {
                        PickBasket.toggle(PickBasket.Pick(key, e.display, false, p.sourceId, e.uri, entry = e))
                    }
                } ?: if (error.isEmpty()) CircularProgressIndicator(color = accent()) else Unit
            }
            Box(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().background(C.panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (adding.isNotEmpty()) adding else if (done.isNotEmpty()) done else "${PickBasket.picks.size} selected",
                color = C.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
            if (done.isNotEmpty()) Pill("Back to playlist", filled = true) {
                while (nav.top is Screen.PickMedia) nav.back()
            } else Pill("Add selected", filled = true) { if (PickBasket.picks.isNotEmpty() && adding.isEmpty()) addAll() }
        }
    }
}

@Composable
private fun PickRow(icon: String, label: String, checked: Boolean?, checkable: Boolean, onCheck: () -> Unit = {}, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        if (checkable && checked != null) androidx.compose.material3.Checkbox(checked, { onCheck() },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = accent()))
        Text(icon, color = accent(), modifier = Modifier.padding(start = 4.dp, end = 12.dp))
        Text(label, color = C.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (icon == "▤") Text("›", color = C.muted, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
    Divider()
}
