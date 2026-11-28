package app.vlcinterface.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.player.PlayItem
import app.vlcinterface.sources.CoverArt
import kotlinx.coroutines.launch

/**
 * Long-press on the player's picture: looks for every album picture that fits. One found → used at once;
 * several → listed with a preview to choose from; none → [onNone] (the usual search: album, then artist photo).
 * The chosen one is saved as cover.jpg in the album folder. [onDone] gets a note to show under the picture.
 */
@Composable
fun CoverPicker(item: PlayItem, onNone: () -> Unit, onDone: (note: String, changed: Boolean) -> Unit,
                artPath: String = "", onBan: ((clear: Boolean) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<CoverArt.Candidate>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(true) }
    fun use(c: CoverArt.Candidate) {
        saving = true
        scope.launch { val r = CoverArt.useCandidate(ctx.applicationContext, item, c); onDone(r.note, r.bitmap != null) }
    }
    LaunchedEffect(item.uri) {
        if (CoverArt.onMobile()) { onDone("Pictures are downloaded only on Wi-Fi", false); return@LaunchedEffect }
        fun ok(c: CoverArt.Candidate) = CoverArt.notBanned(item.artist, item.album, if (c.kind == "web") c.id else "https://coverartarchive.org/${c.kind}/${c.id}/front-500")
        // The list fills as pictures arrive (2026-11-28 08:31); the search goes on meanwhile ("still looking" under it).
        // At most 45 s: then what was found is shown (or "nothing found"), never an endless spinner (08:51).
        val l = (kotlinx.coroutines.withTimeoutOrNull(45_000) {
            CoverArt.candidates(item.artist, item.album) { c ->
                if (ok(c)) scope.launch(kotlinx.coroutines.Dispatchers.Main) { list = (list ?: emptyList()) + c }
            }
        } ?: (list ?: emptyList()).also {
            app.vlcinterface.data.ServerLog.log("covers", "search", "", "", false, "Picture search for \"${item.album}\" stopped after 45 s (${it.size} found) — the picture sites answered too slowly")
        }).filter { ok(it) }
        searching = false
        when (l.size) {
            0 -> if (onBan != null && (CoverArt.canBan(item.uri, artPath) || CoverArt.hasBans(item.artist, item.album))) list = l else onNone()
            else -> list = l
        }
    }
    val l = list
    AlertDialog(onDismissRequest = { onDone("", false) }, containerColor = C.panel,
        title = { Text(if (l == null) "Album picture" else "Choose the album picture (${l.size})", color = C.text) },
        text = {
            if (l == null || saving) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = accent())
                Text(if (saving) "Saving the picture in the album folder…" else "Looking for \"${item.album}\"" + if (item.artist.isNotBlank()) " by ${item.artist}…" else "…",
                    color = C.muted, fontSize = 13.sp)
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The picture shown is wrong for this album: never used for it again (another is found, or the
                // artist's photo). Or: pictures marked wrong allowed again.
                if (onBan != null) {
                    if (CoverArt.canBan(item.uri, artPath)) STextButton({ onBan(false) }) { Text("Wrong picture — never use it for this album", color = C.bad, fontWeight = FontWeight.SemiBold) }
                    if (CoverArt.hasBans(item.artist, item.album)) STextButton({ onBan(true) }) { Text("Allow pictures marked wrong again") }
                }
                if (searching) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = accent(), strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text("Still looking…", color = C.muted, fontSize = 12.sp)
                }
                val state = rememberLazyListState()
                LazyColumn(Modifier.heightIn(max = 460.dp).scrollbar(state), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(l) { c ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile).clickable { use(c) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Image(c.thumb.asImageBitmap(), null, Modifier.size(84.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(c.title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (c.credit.isNotBlank()) Text(c.credit, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.info.isNotBlank()) Text(c.info, color = accent(), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { if (!saving) STextButton({ onDone("", false) }) { Text("‹ Back") } },
    )
}
