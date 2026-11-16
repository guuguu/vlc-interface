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
import androidx.compose.material3.AlertDialog
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
    private var byKey: Map<String, PlayItem> = emptyMap()

    /** The phone's copy of a network song (same artist, album and title), if there is one. */
    fun localTwin(p: PlayItem): PlayItem? =
        if (app.vlcinterface.sources.NetIndex.isLocal(p)) null else app.vlcinterface.sources.NetIndex.songKey(p)?.let { byKey[it] }

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
    var songs by remember { mutableStateOf((SongsCache.phone ?: emptyList()) + SongsCache.network) }
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
    LaunchedEffect(indexVer) { songs = sorted(SongsCache.all(ctx.applicationContext)); loading = false }
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
        // Play the selection straight away (no playlist made), or add it to the end of the current queue.
        // Wraps onto a second line when the buttons don't fit (big button size): never overlapping.
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Column(Modifier.fillMaxWidth(0.94f).heightIn(max = popMax).clip(RoundedCornerShape(10.dp)).background(C.panel)
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
private fun searchNorm(t: String): String =
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
