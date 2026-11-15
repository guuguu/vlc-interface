package app.vlcinterface.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
private object SongsCache {
    var phone: List<PlayItem>? = null
    var network: List<PlayItem> = emptyList()
}

private fun fmtLong(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * Library → Songs: every song as "artist – album – song   duration" (by artist, album, track — never by song name).
 * Tap to select (highlighted), long-press for the play time of the selection and a new playlist that starts playing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongsTab(nav: Navigator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf((SongsCache.phone ?: emptyList()) + SongsCache.network) }
    var loading by remember { mutableStateOf(SongsCache.phone == null) }
    var netBusy by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var ask by remember { mutableStateOf(false) }
    fun sorted(l: List<PlayItem>) = l.distinctBy { it.uri }.sortedWith(compareBy<PlayItem>(
        { it.artist.ifEmpty { "￿" }.lowercase() }, { it.album.ifEmpty { "￿" }.lowercase() }, { it.trackNo }))
    LaunchedEffect(Unit) {
        if (SongsCache.phone == null) {
            SongsCache.phone = InternalSource.folders(ctx).flatMap { f -> MusicLibrary.collectLocal(ctx, f, false) }.filter { it.isMusic }
        }
        songs = sorted(SongsCache.phone!! + SongsCache.network); loading = false
    }
    val chosen = songs.filter { it.uri in selected }
    val total = chosen.sumOf { it.durationMs.coerceAtLeast(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (selected.isEmpty()) "${songs.size} songs" else "${selected.size} selected · ${fmtLong(total)}",
                color = if (selected.isEmpty()) C.muted else accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (selected.isNotEmpty()) Pill("Clear") { selected = emptySet() } else Pill("All") { selected = songs.map { it.uri }.toSet() }
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
                    songs = sorted((SongsCache.phone ?: emptyList()) + all); netBusy = ""
                }
            }
        }
        Text("Tap songs to select them · long-press for the play time and a new playlist", color = C.muted, fontSize = 11.sp)
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent()) }
        val state = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).scrollbar(state), state = state) {
            items(songs, key = { it.uri }) { s ->
                val on = s.uri in selected
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) accent().copy(alpha = 0.22f) else Color.Transparent)
                    .combinedClickable(onClick = { selected = if (on) selected - s.uri else selected + s.uri },
                        onLongClick = { if (selected.isEmpty()) selected = setOf(s.uri); ask = true })
                    .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    DriftText(listOf(s.artist.ifEmpty { "Unknown artist" }, s.album.ifEmpty { "—" }, s.title).joinToString("  –  "),
                        if (on) accent() else C.text, Modifier.weight(1f))
                    Text(if (s.durationMs > 0) fmtLong(s.durationMs) else "–:––", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (ask) {
        val pick = songs.filter { it.uri in selected }
        val ms = pick.sumOf { it.durationMs.coerceAtLeast(0) }
        val albums = pick.map { it.album.lowercase() to it.folderKey }.distinct().size
        fun create(random: Boolean) {
            val ordered = if (random) pick.shuffled() else pick
            // Name: day_mon_hh_mm_random|Nalbums_minutes, e.g. sun_nov_14_19_3albums_73min
            val now = java.util.Calendar.getInstance()
            val day = String.format(Locale.ENGLISH, "%ta", now).lowercase().take(3)
            val mon = String.format(Locale.ENGLISH, "%tb", now).lowercase().take(3)
            val name = "${day}_${mon}_%02d_%02d_".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE)) +
                (if (random) "random" else "${albums}album${if (albums == 1) "" else "s"}") + "_${(ms + 30_000) / 60_000}min"
            val pl = Playlist(name = name, items = ordered.map { MusicLibrary.toPlEntry(it) })
            App.instance.store.update { st -> st.copy(playlists = st.playlists + pl) }
            ask = false; selected = emptySet()
            playPlaylist(nav, pl, 0, random = false)
        }
        AlertDialog(onDismissRequest = { ask = false }, containerColor = C.panel,
            title = { Text("${pick.size} songs · ${fmtLong(ms)}", color = C.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Total play time ${fmtLong(ms)} from $albums album${if (albums == 1) "" else "s"}.", color = C.text, fontSize = 14.sp)
                    Text("A new playlist is created with these songs and starts playing. In order keeps the order shown; Random mixes them.",
                        color = C.muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row {
                    STextButton({ create(false) }) { Text("In order", color = accent()) }
                    STextButton({ create(true) }) { Text("Random", color = accent()) }
                }
            },
            dismissButton = { STextButton({ ask = false }) { Text("Cancel") } },
        )
    }
}


/**
 * A row too long for the screen glides slowly to its end and back, so all of it can be read — each row on its
 * own random timing, so they don't all move at once. Rows that fit stay still.
 */
@Composable
private fun DriftText(text: String, color: Color, modifier: Modifier) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Box(modifier.clipToBounds()) {
        Text(text, color = color, fontSize = 13.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.horizontalScroll(scroll, enabled = false))
    }
    val max = scroll.maxValue
    LaunchedEffect(text, max) {
        if (max <= 0 || max == Int.MAX_VALUE) return@LaunchedEffect
        val rnd = kotlin.random.Random(text.hashCode() xor System.nanoTime().toInt())
        delay(rnd.nextLong(1_500, 9_000))                       // random first start
        val ms = (max * 22L).coerceIn(1_500L, 12_000L)           // slow: about 45 px per second
        while (true) {
            scroll.animateScrollTo(max, androidx.compose.animation.core.tween(ms.toInt(), easing = androidx.compose.animation.core.LinearEasing))
            delay(1_800)
            scroll.animateScrollTo(0, androidx.compose.animation.core.tween(ms.toInt(), easing = androidx.compose.animation.core.LinearEasing))
            delay(rnd.nextLong(4_000, 14_000))                  // random pause before the next glide
        }
    }
}
