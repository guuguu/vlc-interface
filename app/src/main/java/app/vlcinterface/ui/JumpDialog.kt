package app.vlcinterface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.player.PlayItem

/**
 * Player → Jump to: search the queue and every song in the Library (phone, plus LAN / Internet songs already read
 * in Library → Songs). Picking one plays it: from its place when already in the queue, otherwise it's put right
 * after the current song (and into the playing playlist) — nothing is removed, no duplicates.
 */
@Composable
fun JumpDialog(playNext: Boolean = false, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val player = App.instance.player
    var library by remember { mutableStateOf<List<PlayItem>?>(null) }
    var query by remember { mutableStateOf("") }
    // Phone songs + indexed ★ LAN folders (servers reachable now); reloaded when the index changes.
    val indexVer by app.vlcinterface.sources.NetIndex.version.collectAsState()
    val indexing by app.vlcinterface.sources.NetIndex.busy.collectAsState()
    LaunchedEffect(Unit) { app.vlcinterface.sources.NetIndex.buildMissing() }
    LaunchedEffect(indexVer) { library = SongsCache.all(ctx.applicationContext) }
    val queue = player.state.value.queue
    val inQueue = remember(queue) { queue.map { "${it.uri}#${it.startMs}" }.toHashSet() }
    val hits = remember(query, library, queue) {
        val q = query.trim().lowercase()
        if (q.length < 2) emptyList() else {
            val words = q.split(Regex("\\s+"))
            fun ok(p: PlayItem) = "${p.artist} ${p.album} ${p.title}".lowercase().let { t -> words.all { t.contains(it) } }
            // Play next searches the whole Songs list; Jump to also looks in the queue first.
            ((if (playNext) emptyList() else queue.filter(::ok)) + (library ?: emptyList()).filter(::ok)).distinctBy { "${it.uri}#${it.startMs}" }.take(60)
        }
    }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text(if (playNext) "Play next" else "Jump to song", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Artist, album or song", fontSize = 13.sp) }, textStyle = TextStyle(fontSize = 14.sp, color = C.text))
                Text(when {
                    library == null -> "Reading the Library…"
                    query.trim().length < 2 -> if (playNext) "Type at least 2 letters · the song you pick plays after the current one"
                        else "Type at least 2 letters · queue and Library are searched"
                    else -> "${hits.size}${if (hits.size == 60) "+" else ""} found"
                }, color = C.muted, fontSize = 12.sp)
                if (indexing.isNotEmpty()) Text("★ $indexing — LAN songs appear when done", color = accent(), fontSize = 12.sp)
                val st = rememberLazyListState()
                LazyColumn(Modifier.heightIn(max = 380.dp).scrollbar(st), state = st, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(hits) { h ->
                        val queued = "${h.uri}#${h.startMs}" in inQueue
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(C.card2)
                            .clickable { if (playNext) player.queueNext(h) else player.jumpToSong(h); onDone() }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(h.title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${h.artist.ifEmpty { "Unknown artist" }} · ${h.album.ifEmpty { "—" }}" + if (queued) "  · in the queue" else "",
                                color = if (queued) accent() else C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { STextButton(onDone) { Text("‹ Back") } },
    )
}
