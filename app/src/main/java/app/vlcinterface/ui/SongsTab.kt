package app.vlcinterface.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.Playlist
import app.vlcinterface.player.PlayItem
import app.vlcinterface.sources.InternalSource
import app.vlcinterface.sources.MusicLibrary
import app.vlcinterface.sources.Urls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** All songs in memory for the Songs list (phone at once; LAN / Internet storage when added). */
internal object SongsCache {
    /** Songs on the phone (read once). Setting it also refreshes the "same song on the phone" lookup. */
    var phone: List<PlayItem>? = null
        set(v) { field = v; byKey = v?.mapNotNull { p -> app.vlcinterface.sources.NetIndex.songKey(p)?.let { it to p } }?.toMap() ?: emptyMap() }
    var network: List<PlayItem> = emptyList()
    /** The Songs list exactly as last shown. */
    var lastShown: List<PlayItem> = emptyList()
    private var byKey: Map<String, PlayItem> = emptyMap()

    /** The phone's copy of a network song (same artist, album and title), if there is one. */
    fun localTwin(p: PlayItem): PlayItem? =
        if (app.vlcinterface.sources.NetIndex.isLocal(p)) null else app.vlcinterface.sources.NetIndex.songKey(p)?.let { byKey[it] }

    /** The folder key of a phone song (by its address), from the phone's song list. */
    suspend fun phoneFolderOf(ctx: android.content.Context, uri: String): String? =
        loadPhone(ctx).firstOrNull { it.uri == uri }?.folderKey?.takeIf { it.isNotEmpty() }

        suspend fun loadPhone(ctx: android.content.Context): List<PlayItem> {
        if (phone == null) phone = InternalSource.folders(ctx).flatMap { f -> MusicLibrary.collectLocal(ctx, f, false) }.filter { it.isMusic }
        return phone!!
    }

    /**
     * Phone songs + indexed LAN folders (★, only from servers reachable now) + LAN / Internet songs read in the
     * Songs list; a song on both the phone and the network = the phone file.
     */
    suspend fun all(ctx: android.content.Context): List<PlayItem> {
        val hidden = App.instance.store.current.settings.hiddenUris
        return app.vlcinterface.sources.NetIndex.preferLocal(loadPhone(ctx) + app.vlcinterface.sources.NetIndex.songsReachable() + network)
            .filter { it.uri !in hidden && it.folderKey !in hidden && "local:" + it.folderKey.removePrefix("local:") !in hidden &&
                !app.vlcinterface.sources.Ignore.dotName(it.fileName) && !app.vlcinterface.sources.Ignore.dotPath(it.uri.substringAfter("://")) }
    }
}

private fun fmtLong(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * Library → Songs: every song as "artist – album – song   duration" (by artist, album, track — never by song name).
 * Tap to select (highlighted), long-press for the play time of the selection and a new playlist that starts playing.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SongsTab(nav: Navigator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // The list as last shown (same order), so coming back keeps your place; then refreshed.
    var songs by remember { mutableStateOf(SongsCache.lastShown) }
    val indexVer by app.vlcinterface.sources.NetIndex.version.collectAsState()
    var loading by remember { mutableStateOf(SongsCache.phone == null) }
    var netBusy by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var ask by remember { mutableStateOf(false) }
    var addPl by remember { mutableStateOf<List<PlayItem>?>(null) }
    var songMenu by remember { mutableStateOf<String?>(null) }
    fun sorted(l: List<PlayItem>) = l.distinctBy { it.uri }.sortedWith(compareBy<PlayItem>(
        { it.artist.ifEmpty { "￿" }.lowercase() }, { it.album.ifEmpty { "￿" }.lowercase() }, { it.trackNo }))
    // Phone songs + songs of the indexed ★ LAN folders (+ any read with "+ LAN / Internet"); a song on both = phone file.
    LaunchedEffect(indexVer) {
        val fresh = sorted(SongsCache.all(ctx.applicationContext))
        if (fresh != songs) songs = fresh
        SongsCache.lastShown = fresh; loading = false
    }
    // Folders pinned before indexing existed (or never indexed yet): indexed now, in the background.
    LaunchedEffect(Unit) {
        app.vlcinterface.sources.NetIndex.buildMissing()
    }
    val chosen = songs.filter { it.uri in selected }
    val total = chosen.sumOf { it.durationMs.coerceAtLeast(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (selected.isEmpty()) "${songs.size} songs" else "${selected.size} selected · ${fmtLong(total)}",
                color = if (selected.isEmpty()) C.muted else accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Pill(if (netBusy.isNotEmpty()) netBusy else "+ LAN / Internet") {
                if (netBusy.isNotEmpty()) return@Pill
                netBusy = "Reading…"
                scope.launch {
                    val srcs = App.instance.store.current.sources.filter { it.proto in setOf("smb", "webdav", "http") }
                    val all = ArrayList<PlayItem>()
                    srcs.forEachIndexed { i, s ->
                        netBusy = "Reading ${i + 1}/${srcs.size}…"
                        all += MusicLibrary.collectRecursive(s, Urls.forSource(s)).filter { it.isMusic }
                    }
                    SongsCache.network = all
                    songs = sorted(SongsCache.all(ctx.applicationContext)); netBusy = ""
                }
            }
        }
        val state = rememberLazyListState()
        val songsExporter = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            if (uri != null) SongsIo.export(ctx, uri)
        }
        var imported by remember { mutableStateOf<Pair<String, List<SongsIo.Line>>?>(null) }
        val songsImporter = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) SongsIo.read(ctx, uri)?.let { imported = it }
        }
        imported?.let { (name, lines) ->
            // Ask each time: the songs as a playlist, their folders added to the library, or both.
            AlertDialog(onDismissRequest = { imported = null }, containerColor = C.panel,
                title = { Text("Import ${lines.size} songs", color = C.text) },
                text = { Text("From \"$name\". Make a playlist of them, add their folders to the library (so they show in Songs), or both?", color = C.text) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        STextButton({ imported = null; scope.launch { SongsIo.apply(ctx, name, lines, playlist = true, folders = true); songs = sorted(SongsCache.all(ctx.applicationContext)) } }) { Text("Both", color = accent()) }
                        STextButton({ imported = null; scope.launch { SongsIo.apply(ctx, name, lines, playlist = true, folders = false) } }) { Text("Playlist", color = accent()) }
                        STextButton({ imported = null; scope.launch { SongsIo.apply(ctx, name, lines, playlist = false, folders = true); songs = sorted(SongsCache.all(ctx.applicationContext)) } }) { Text("Add folders to library", color = accent()) }
                        STextButton({ imported = null }) { Text("Cancel") }
                    }
                })
        }
        // Play the selection straight away (no playlist made), or add it to the end of the current queue.
        // Wraps onto a second line when the buttons don't fit (big button size): never overlapping.
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Export (the selection, or all songs) / import an M3U list.
            Pill("⬆ Export") { val l = if (selected.isNotEmpty()) chosen else songs
                if (l.isEmpty()) android.widget.Toast.makeText(ctx, "No songs to export", android.widget.Toast.LENGTH_SHORT).show()
                else { SongsIo.pending = l; songsExporter.launch("songs-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".m3u8") } }
            Pill("⬇ Import") { songsImporter.launch(arrayOf("*/*")) }
            if (selected.size < songs.size) Pill("Select all") { selected = songs.map { it.uri }.toSet() }
            if (selected.isNotEmpty()) Pill("Deselect all") { selected = emptySet() }
            if (selected.isNotEmpty()) Pill("▶  Play selected", filled = true) {
                App.instance.player.playQueue(chosen, 0, app.vlcinterface.player.Origin("songs", title = "Songs"))
                nav.go(Screen.Music)
            }
            if (selected.isNotEmpty()) Pill("+ Queue") {
                val n = App.instance.player.addToQueue(chosen)
                android.widget.Toast.makeText(ctx, "$n song${if (n == 1) "" else "s"} added to the queue" +
                    if (chosen.size > n) " (${chosen.size - n} already in it)" else "", android.widget.Toast.LENGTH_SHORT).show()
            }
            // Into an existing playlist (or a new one), without duplicates.
            if (selected.isNotEmpty()) Pill("+ Playlist…") { addPl = chosen }
        }
        // Search: instant results in a pop-up; picking one scrolls the list to it and marks it.
        var query by remember { mutableStateOf("") }
        var focus by remember { mutableStateOf("") }
        val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
        val hits = remember(query, songs) {
            val q = query.trim().lowercase()
            if (q.length < 2) emptyList() else {
                songs.filter { s0 -> songMatches(s0, q) }.take(50)
            }
        }
        var fieldH by remember { mutableStateOf(0) }
        var fieldBottom by remember { mutableStateOf(0f) }
        // The results pop-up ends above the keyboard: its height is limited to the space between the box and the keyboard.
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(dens)
        val winH = with(dens) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() } +
            WindowInsets.navigationBars.getBottom(dens) + WindowInsets.statusBars.getTop(dens)
        val popMax = with(dens) { ((winH - imeBottom - fieldBottom - 12f).coerceAtLeast(120f)).toDp() }.coerceAtMost(340.dp)
        Box {
            androidx.compose.material3.OutlinedTextField(query, { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onGloballyPositioned { fieldH = it.size.height; fieldBottom = it.positionInWindow().y + it.size.height },
                placeholder = { Text("Search artist, album or song", fontSize = 13.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = C.text),
                trailingIcon = { if (query.isNotEmpty()) Text("✕", color = C.muted, modifier = Modifier.clickable { query = "" }.padding(10.dp)) })
            if (hits.isNotEmpty()) androidx.compose.ui.window.Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(0, fieldH + 4),
                properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                val sc = androidx.compose.foundation.rememberScrollState()
                Column(Modifier.fillMaxWidth(0.94f).heightIn(max = popMax).clip(RoundedCornerShape(10.dp)).background(C.popup)
                    .border(1.dp, C.line, RoundedCornerShape(10.dp)).scrollbar(sc).verticalScroll(sc).padding(4.dp)) {
                    Text("${hits.size}${if (hits.size == 50) "+" else ""} found", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
                    hits.forEach { h ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                            val i = songs.indexOfFirst { it.uri == h.uri }
                            focus = h.uri; query = ""; focusMgr.clearFocus()
                            if (i >= 0) scope.launch { state.animateScrollToItem((i - 3).coerceAtLeast(0)) }
                        }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(h.title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${h.artist.ifEmpty { "Unknown artist" }} · ${h.album.ifEmpty { "—" }}", color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        // The found song stays marked for a few seconds.
        LaunchedEffect(focus) { if (focus.isNotEmpty()) { delay(4_000); focus = "" } }
        val indexing by app.vlcinterface.sources.NetIndex.busy.collectAsState()
        if (indexing.isNotEmpty()) Text("★ $indexing", color = accent(), fontSize = 11.sp)
        Text("Tap songs to select them · long-press: the song's menu (with songs selected: play time and a new playlist)", color = C.muted, fontSize = 11.sp)
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent()) }
        LazyColumn(Modifier.weight(1f).scrollbar(state), state = state) {
            items(songs, key = { it.uri }) { s ->
                val on = s.uri in selected
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) accent().copy(alpha = 0.22f) else Color.Transparent)
                    .then(if (s.uri == focus) Modifier.border(2.dp, accent(), RoundedCornerShape(6.dp)) else Modifier)
                    .combinedClickable(onClick = { selected = if (on) selected - s.uri else selected + s.uri },
                        onLongClick = { if (selected.isNotEmpty()) ask = true else songMenu = s.uri })
                    .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    DriftText(listOf(s.artist.ifEmpty { "Unknown artist" }, s.album.ifEmpty { "—" }, s.title).joinToString("  –  "),
                        if (on) accent() else C.text, Modifier.weight(1f))
                    Text(if (s.durationMs > 0) fmtLong(s.durationMs) else "–:––", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                    Box { if (songMenu == s.uri) FileMenu(nav, s, true, { songMenu = null }, FileMenuExtras(
                        onSelect = { selected = selected + s.uri },
                        onRemove = { HiddenFiles.hide(s.uri); songs = songs.filterNot { it.uri == s.uri } })) }
                }
            }
        }
    }
    addPl?.let { its -> AddToPlaylistDialog({ its }) { addPl = null } }
    if (ask) QuickPlaylistDialog(nav, { songs.filter { it.uri in selected } }) { made -> ask = false; if (made) selected = emptySet() }
}


/**
 * A row too long for the screen glides slowly to its end and back, so all of it can be read — each row on its
 * own random timing, so they don't all move at once. Rows that fit stay still.
 */
@Composable
internal fun DriftText(text: String, color: Color, modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 13.sp) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Box(modifier.clipToBounds()) {
        Text(text, color = color, fontSize = fontSize, maxLines = 1, softWrap = false,
            modifier = Modifier.horizontalScroll(scroll, enabled = false))
    }
    val max = scroll.maxValue
    val speed = App.instance.store.state.collectAsState().value.settings.songsScrollSpeed
    LaunchedEffect(text, max, speed) {
        if (speed <= 0) { scroll.scrollTo(0); return@LaunchedEffect }
        if (max <= 0 || max == Int.MAX_VALUE) return@LaunchedEffect
        val rnd = kotlin.random.Random(text.hashCode() xor System.nanoTime().toInt())
        delay(rnd.nextLong(300, 4_000))                         // rows don't all start together
        // Normal (100 %): about 65 px per second; the Settings slider scales it. Goes back and forth without
        // stopping, easing in and out at both ends (smooth turn, only a short rest there).
        val ms = (max * 15L * 100 / speed).coerceIn(400L, 40_000L).toInt()
        val ease = androidx.compose.animation.core.CubicBezierEasing(0.45f, 0f, 0.55f, 1f)
        while (true) {
            scroll.animateScrollTo(max, androidx.compose.animation.core.tween(ms, easing = ease))
            delay(700)
            scroll.animateScrollTo(0, androidx.compose.animation.core.tween(ms, easing = ease))
            delay(700)
        }
    }
}


/**
 * One line that, when too long, glides continuously in one direction (right to left, starting over seamlessly).
 * Speed follows Settings → Songs list horizontal autoscroll (0 = still).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarqueeText(text: String, color: Color, modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 13.sp) {
    val speed = App.instance.store.state.collectAsState().value.settings.songsScrollSpeed
    val startDelay = remember(text) { kotlin.random.Random.nextInt(300, 3_000) }
    Box(modifier.clipToBounds()) {
        Text(text, color = color, fontSize = fontSize, maxLines = 1, softWrap = false,
            modifier = if (speed <= 0) Modifier else Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = startDelay,
                repeatDelayMillis = 0, spacing = androidx.compose.foundation.MarqueeSpacing(48.dp), velocity = (40f * speed / 100f).dp))
    }
}


/**
 * Total play time of the chosen songs, then In order / Random: a playlist named day_mon_hh_mm_(random|Nalbums)_Nmin
 * is made from them and starts playing. Used by the Songs list and by folder lists (phone and LAN).
 */
@Composable
fun QuickPlaylistDialog(nav: Navigator, collect: suspend () -> List<PlayItem>, onDone: (made: Boolean) -> Unit) {
    var pick by remember { mutableStateOf<List<PlayItem>?>(null) }
    LaunchedEffect(Unit) { pick = collect().filter { !it.isRadio }.distinctBy { "${it.uri}#${it.startMs}" } }
    val p = pick
    val ms = p?.sumOf { it.durationMs.coerceAtLeast(0) } ?: 0L
    val albums = p?.map { it.album.lowercase() to it.folderKey }?.distinct()?.size ?: 0
    fun create(random: Boolean) {
        val l = p ?: return
        if (l.isEmpty()) { onDone(false); return }
        val ordered = if (random) l.shuffled() else l
        // Name: day_mon_hh_mm_random|Nalbums_minutes, e.g. sun_nov_14_19_3albums_73min
        val now = java.util.Calendar.getInstance()
        val day = String.format(Locale.ENGLISH, "%ta", now).lowercase().take(3)
        val mon = String.format(Locale.ENGLISH, "%tb", now).lowercase().take(3)
        val name = "${day}_${mon}_%02d_%02d_".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE)) +
            (if (random) "random" else "${albums}album${if (albums == 1) "" else "s"}") + "_${(ms + 30_000) / 60_000}min"
        // No duplicate lists: the same songs already saved as a playlist → that one is played, no new list.
        val wanted = l.map { it.uri }.toSet()
        val same = App.instance.store.current.playlists.firstOrNull { it.items.size == wanted.size && it.items.map { e -> e.uri }.toSet() == wanted }
        if (same != null) {
            onDone(true)
            playPlaylist(nav, same, if (random) same.items.indices.random() else 0, random = random)
            return
        }
        val pl = Playlist(name = name, items = ordered.map { MusicLibrary.toPlEntry(it) })
        App.instance.store.update { st -> st.copy(playlists = st.playlists + pl) }
        onDone(true)
        playPlaylist(nav, pl, 0, random = false)
    }
    AlertDialog(onDismissRequest = { onDone(false) }, containerColor = C.panel,
        title = { Text(if (p == null) "Collecting songs…" else "${p.size} songs · ${fmtLong(ms)}", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p == null) CircularProgressIndicator(color = accent())
                else {
                    Text("Total play time ${fmtLong(ms)} from $albums album${if (albums == 1) "" else "s"}.", color = C.text, fontSize = 14.sp)
                    Text("A new playlist is created with these songs and starts playing. In order keeps the order shown; Random mixes them.",
                        color = C.muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (p != null) Row {
                STextButton({ create(false) }) { Text("In order", color = accent()) }
                STextButton({ create(true) }) { Text("Random", color = accent()) }
            }
        },
        dismissButton = { STextButton({ onDone(false) }) { Text("Cancel") } },
    )
}


/** Lowercase, accents and punctuation dropped: "AC/DC" → "acdc", "You´ve" → "youve", "Béla" → "bela". */
internal fun searchNorm(t: String): String =
    java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")                     // accents
        .replace(Regex("[´`’‘ʼ'/.\\-]"), "")                // joined marks: ac/dc, you've, a-ha
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/**
 * Search used by the Songs list, Play next and Jump to: every typed word (2 or more, any order) must appear,
 * whole or partly, in the artist, album, title or file name — case, accents and punctuation ignored.
 * E.g. "acd ros", "rosie acdc", "blood got".
 */
fun songMatches(p: PlayItem, query: String): Boolean {
    val words = searchNorm(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    val hay = searchNorm("${p.artist} ${p.album} ${p.title} ${p.fileName}")
    val joined = hay.replace(" ", "")
    return words.all { w -> hay.contains(w) || joined.contains(w) }
}


/**
 * Songs list export / import (M3U8: "#EXTINF:seconds,Artist - Title" + the song's address).
 * Import offers: a playlist of the songs, adding their folders to the library (LAN folders pinned ★ and indexed,
 * hidden phone folders shown again), or both.
 */
internal object SongsIo {
    data class Line(val uri: String, val title: String, val secs: Int)
    var pending: List<PlayItem> = emptyList()

    fun export(ctx: android.content.Context, uri: android.net.Uri) {
        val l = pending
        val text = buildString {
            append("#EXTM3U\n")
            l.forEach { p ->
                val label = listOf(p.artist, p.title).filter { it.isNotBlank() }.joinToString(" - ")
                append("#EXTINF:${(p.durationMs / 1000).coerceAtLeast(-1)},$label\n")
                if (p.album.isNotBlank()) append("#EXTALB:${p.album}\n")
                append(p.uri).append('\n')
            }
        }
        val ok = try { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (_: Exception) { false }
        android.widget.Toast.makeText(ctx, if (ok) "${l.size} songs exported" else "Could not write the file", android.widget.Toast.LENGTH_LONG).show()
    }

    fun read(ctx: android.content.Context, uri: android.net.Uri): Pair<String, List<Line>>? {
        val text = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } catch (_: Exception) { null }
        val name = (try { ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } } catch (_: Exception) { null })
            ?.substringBeforeLast('.') ?: "Imported"
        val out = ArrayList<Line>()
        var title = ""; var secs = -1
        (text ?: "").lineSequence().map { it.trim().removePrefix("\uFEFF") }.forEach { line ->
            when {
                line.startsWith("#EXTINF", true) -> { title = line.substringAfter(',').trim(); secs = line.substringAfter(':').substringBefore(',').trim().toIntOrNull() ?: -1 }
                line.isEmpty() || line.startsWith("#") -> {}
                else -> { out += Line(line, title, secs); title = ""; secs = -1 }
            }
        }
        if (out.isEmpty()) { android.widget.Toast.makeText(ctx, "No songs found in that file", android.widget.Toast.LENGTH_LONG).show(); return null }
        return name to out
    }

    /** Finds each listed song among the known ones (address, file path, or "Artist - Title"). */
    private suspend fun match(ctx: android.content.Context, lines: List<Line>): List<Pair<Line, PlayItem?>> {
        val known = SongsCache.loadPhone(ctx.applicationContext) + app.vlcinterface.sources.NetIndex.songsReachable() + SongsCache.network
        val byUri = known.associateBy { it.uri }
        val byName = known.groupBy { it.fileName.lowercase() }
        val byLabel = known.associateBy { (it.artist + " - " + it.title).lowercase() }
        return lines.map { l ->
            val fn = android.net.Uri.decode(l.uri).substringAfterLast('/').substringAfterLast('\\').lowercase()
            l to (byUri[l.uri] ?: byUri[android.net.Uri.decode(l.uri)] ?: byName[fn]?.firstOrNull() ?: byLabel[l.title.lowercase()])
        }
    }

    suspend fun apply(ctx: android.content.Context, name: String, lines: List<Line>, playlist: Boolean, folders: Boolean) {
        val st = App.instance.store
        val matched = match(ctx, lines)
        val notes = ArrayList<String>()
        if (playlist) {
            val items = matched.mapNotNull { (l, p) ->
                p ?: if (l.uri.contains("://")) PlayItem(l.uri, l.title.ifEmpty { android.net.Uri.decode(l.uri).substringAfterLast('/') }, durationMs = if (l.secs > 0) l.secs * 1000L else 0)
                else null
            }.distinctBy { it.uri }
            if (items.isNotEmpty()) {
                st.update { x -> x.copy(playlists = x.playlists + app.vlcinterface.data.Playlist(name = name, items = items.map { MusicLibrary.toPlEntry(it) })) }
                notes += "Playlist \"$name\": ${items.size} songs" + if (items.size < lines.size) " (${lines.size - items.size} not found)" else ""
            } else notes += "No playable songs found for a playlist"
        }
        if (folders) {
            var pinned = 0; var shown = 0; var unknown = 0
            val hidden = st.current.settings.hiddenUris
            val toShow = HashSet<String>()
            matched.forEach { (l, p) ->
                val u = p?.uri ?: l.uri
                when {
                    u.startsWith("smb://", true) -> {
                        val host = u.substringAfter("://").substringBefore('/').substringAfter('@')
                        val src = st.current.sources.firstOrNull { it.host.equals(host, true) }
                        if (src == null) { unknown++; return@forEach }
                        val folder = "/" + android.net.Uri.decode(u.substringAfter("://").substringAfter('/', "")).substringBeforeLast('/').trimEnd('/')
                        if (folder !in st.current.sources.first { it.id == src.id }.pins) {
                            st.update { x -> x.copy(sources = x.sources.map { if (it.id == src.id) it.copy(pins = it.pins + folder) else it }) }
                            app.vlcinterface.sources.NetIndex.build(st.current.sources.first { it.id == src.id }, folder); pinned++
                        }
                    }
                    p != null && p.folderKey.isNotEmpty() -> {
                        val k = "local:" + p.folderKey.removePrefix("local:")
                        if (k in hidden || p.folderKey in hidden || p.uri in hidden) { toShow += k; toShow += p.folderKey; toShow += p.uri }
                    }
                    p == null -> unknown++
                }
            }
            if (toShow.isNotEmpty()) {
                st.update { x -> x.copy(settings = x.settings.copy(hiddenUris = x.settings.hiddenUris - toShow)) }
                shown = toShow.count { it.startsWith("local:") }
            }
            notes += "Library: $pinned LAN folders added (indexing), $shown phone folders shown again" +
                if (unknown > 0) ", $unknown songs from places not set up in the app" else ""
        }
        android.widget.Toast.makeText(ctx, notes.joinToString("\n"), android.widget.Toast.LENGTH_LONG).show()
    }
}
