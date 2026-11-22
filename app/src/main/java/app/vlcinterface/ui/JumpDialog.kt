@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.vlcinterface.ui

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun JumpDialog(playNext: Boolean = false, nav: Navigator? = null, videos: Boolean = false, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val player = App.instance.player
    var library by remember { mutableStateOf<List<PlayItem>?>(null) }
    var query by remember { mutableStateOf("") }
    val dq = rememberDebounced(query)
    // Phone songs + indexed ★ LAN folders (servers reachable now); reloaded when the index changes.
    val indexVer by app.vlcinterface.sources.NetIndex.version.collectAsState()
    val indexing by app.vlcinterface.sources.NetIndex.busy.collectAsState()
    LaunchedEffect(Unit) { app.vlcinterface.sources.NetIndex.buildMissing() }
    // [videos]: the video player's Find — the phone's videos and the indexed network videos.
    // The phone's songs first, at once; the NAS ones (only from a NAS that can be used now) join when checked (19:37).
    LaunchedEffect(indexVer) {
        if (!videos && library == null && app.vlcinterface.sources.NetIndex.anyNasDoubt()) library = SongsCache.phoneOnly(ctx.applicationContext)
        library = if (videos) SongsCache.allVideos(ctx.applicationContext) else SongsCache.all(ctx.applicationContext)
    }
    val queue = player.state.value.queue
    val inQueue = remember(queue) { queue.map { "${it.uri}#${it.startMs}" }.toHashSet() }
    val hits = remember(dq, library, queue) {
        if (dq.trim().length < 2) emptyList() else {
            fun ok(p: PlayItem) = songMatches(p, dq)
            // Play next searches the whole Songs list; Jump to also looks in the queue first.
            ((if (playNext) emptyList() else queue.filter { videos == !it.isMusic }.filter(::ok)) + (library ?: emptyList()).filter(::ok)).distinctBy { "${it.uri}#${it.startMs}" }.take(60)
        }
    }
    // Own window instead of a centred dialog: it sits at the top and always ends above the keyboard (the results
    // list shrinks to the space left), so it never goes under the keyboard.
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDone)
            .dialogBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.menu)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (videos) "Find a video" else if (playNext) "Play next" else "Jump to song", color = C.text, fontSize = 22.sp)
                val st = rememberLazyListState()
                val jscope = androidx.compose.runtime.rememberCoroutineScope()
                var at by remember(dq) { mutableStateOf(-1) }
                fun step(k: Int) { if (hits.isEmpty()) return; at = (k + hits.size) % hits.size; jscope.launch { st.animateScrollToItem(at) } }
                SmallSearchBar(query, { query = it }, if (videos) "Title, series or file name" else "Artist, album or song", hits.size, at.coerceAtLeast(0), { step(at - 1) }, { step(at + 1) })
                Text(when {
                    library == null -> "Reading the Library…"
                    query.trim().length < 2 -> if (playNext) "Type at least 2 letters · the song you pick plays after the current one"
                        else "Type at least 2 letters · queue and Library are searched"
                    else -> "${hits.size}${if (hits.size == 60) "+" else ""} found"
                }, color = C.muted, fontSize = 12.sp)
                if (indexing.isNotEmpty()) Text("★ $indexing — LAN ${if (videos) "videos" else "songs"} appear when done", color = accent(), fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f, fill = false).scrollbar(st), state = st, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(hits) { idx, h ->
                        val queued = "${h.uri}#${h.startMs}" in inQueue
                        // Long-press: Play, Add to queue, Go to folder, ReplayGain scan.
                        var resMenu by remember(h.uri) { mutableStateOf(false) }
                        androidx.compose.foundation.layout.Box { SearchResultMenu(h, resMenu, nav, { resMenu = false }, after = onDone) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (idx == at) accent().copy(alpha = 0.25f) else C.menu)
                            .combinedClickable(onClick = { if (playNext) player.queueNext(h) else player.jumpToSong(h); onDone() }, onLongClick = { resMenu = true })
                            .padding(horizontal = 8.dp, vertical = UiPrefs.menuGapDp.dp)) {
                            // Results follow the menu settings (text size, row spacing, opacity).
                            Text(h.title, color = C.text, fontSize = UiPrefs.menuFontSp.sp, lineHeight = (UiPrefs.menuFontSp + 3).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (videos) android.net.Uri.decode(h.folderKey.trimEnd('/').substringAfterLast('/')).substringAfterLast(':').ifEmpty { "—" } +
                                    (if (app.vlcinterface.sources.NetIndex.isLocal(h)) " · phone" else " · network")
                                else "${h.artist.ifEmpty { "Unknown artist" }} · ${h.album.ifEmpty { "—" }}") + if (queued) "  · in the queue" else "",
                                color = if (queued) accent() else C.muted, fontSize = (UiPrefs.menuFontSp - 2).sp, lineHeight = (UiPrefs.menuFontSp + 1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    STextButton(onDone) { Text("‹ Back") }
                }
            }
        }
    }
}
