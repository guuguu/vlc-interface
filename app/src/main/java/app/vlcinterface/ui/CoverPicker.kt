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
fun CoverPicker(item: PlayItem, onNone: () -> Unit, onDone: (note: String, changed: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<CoverArt.Candidate>?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun use(c: CoverArt.Candidate) {
        saving = true
        scope.launch { val r = CoverArt.useCandidate(ctx.applicationContext, item, c); onDone(r.note, r.bitmap != null) }
    }
    LaunchedEffect(item.uri) {
        val l = CoverArt.candidates(item.artist, item.album)
        when (l.size) {
            0 -> onNone()
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
            } else {
                val state = rememberLazyListState()
                LazyColumn(Modifier.heightIn(max = 460.dp).scrollbar(state), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(l) { c ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card2).clickable { use(c) }.padding(6.dp),
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
