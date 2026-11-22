package app.vlcinterface.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.player.PlayItem

/**
 * Library → Videos (the Video switch, 2026-11-22 08:13): every video on the phone (the Library's places) and in the
 * indexed network folders whose server answers now, with a search. Tap plays it; long press opens the usual file menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideosTab(nav: Navigator) {
    val ctx = LocalContext.current
    val indexVer by app.vlcinterface.sources.NetIndex.version.collectAsState()
    val indexing by app.vlcinterface.sources.NetIndex.busy.collectAsState()
    var all by remember { mutableStateOf<List<PlayItem>?>(null) }
    LaunchedEffect(indexVer) { all = SongsCache.allVideos(ctx.applicationContext) }
    var q by remember { mutableStateOf("") }
    val shown = remember(all, q) { (all ?: emptyList()).let { l -> if (q.trim().length < 2) l else l.filter { songMatches(it, q) } } }
    val playing by App.instance.player.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SmallSearchBar(q, { q = it }, "Find a video: title, series or file name", count = if (q.trim().length < 2) 0 else shown.size, onClose = { q = "" })
        Text(when {
            all == null -> "Reading the videos…"
            else -> "${shown.size} videos" + if (q.trim().length >= 2) " found" else " · phone and indexed network folders"
        }, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        if (indexing.isNotEmpty()) Text("★ $indexing — network videos appear when done", color = accent(), fontSize = 12.sp)
        val st = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().scrollbar(st), state = st) {
            items(shown, key = { it.uri }) { v ->
                var menu by remember(v.uri) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().heightIn(min = lrDp(56)).clip(RoundedCornerShape(8.dp))
                    .background(if (playing.item?.uri == v.uri) accent().copy(alpha = 0.12f) else Color.Transparent)
                    .combinedClickable(onClick = {
                        // Plays this video.
                        App.instance.player.playQueue(listOf(v), 0, app.vlcinterface.player.Origin("single"))
                        nav.go(Screen.Player)
                    }, onLongClick = { menu = true }), verticalAlignment = Alignment.CenterVertically) {
                    Text("▶", color = accent(), modifier = Modifier.padding(start = 6.dp, end = 14.dp))
                    Column(Modifier.weight(1f)) {
                        MarqueeText(v.title.ifEmpty { v.fileName }, C.text, Modifier.fillMaxWidth(), fontSize = 15.sp, list = "videos")
                        val folder = android.net.Uri.decode(v.folderKey.trimEnd('/').substringAfterLast('/')).substringAfterLast(':')
                        val where = if (app.vlcinterface.sources.NetIndex.isLocal(v)) "Phone" else
                            App.instance.store.current.sources.firstOrNull { it.id == v.sourceId }?.name ?: "Network"
                        Text(listOfNotNull(folder.ifEmpty { null }, where, if (v.durationMs > 0) fmtTime(v.durationMs) else null).joinToString(" · "),
                            color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Box { if (menu) FileMenu(nav, v, true, { menu = false }) }
                }
            }
        }
    }
}
