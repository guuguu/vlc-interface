package app.vlcinterface.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.player.BackgroundWork
import app.vlcinterface.sources.NetIndex
import app.vlcinterface.sources.RadioCapture
import app.vlcinterface.sources.RgScanner

private val Red = Color(0xFFFF3B30)

/**
 * Players' red button: pauses the background work (ReplayGain scans, songs being encoded, indexing) — queues are
 * kept — and shows what was stopped and what waits, with Resume. Red while work runs or is paused; grey when idle.
 */
@Composable
fun BackgroundWorkButton() {
    val paused by BackgroundWork.paused.collectAsState()
    val rg by RgScanner.state.collectAsState()
    val rgQ by RgScanner.queued.collectAsState()
    val saveQ by RadioCapture.saveQueue.collectAsState()
    val idx by NetIndex.busy.collectAsState()
    val active = paused || rg.running || rgQ.isNotEmpty() || saveQ.isNotEmpty() || idx.isNotEmpty()
    var show by remember { mutableStateOf(false) }
    // What was running at the moment of the press (the summary says what was stopped).
    var stopped by remember { mutableStateOf(emptyList<String>()) }
    SymButton({
        if (!paused && active) {
            stopped = buildList {
                if (rg.running) add("ReplayGain scan: ${rg.done}/${rg.total} files done${if (rg.current.isNotEmpty()) " (at ${rg.current})" else ""} — continues from the files left")
                saveQ.firstOrNull()?.let { add("Encoding / saving: $it — continues where it stopped") }
                if (idx.isNotEmpty()) add("$idx — redone on resume")
            }
            BackgroundWork.pause()
        }
        show = true
    }) {
        val c = if (active) Red else Color.Gray
        Canvas(Modifier.size(22.dp)) {
            if (paused) {
                // Paused: two bars.
                val w = size.width * 0.28f
                drawRoundRect(c, Offset(size.width * 0.14f, size.height * 0.12f), Size(w, size.height * 0.76f), CornerRadius(3f))
                drawRoundRect(c, Offset(size.width * 0.58f, size.height * 0.12f), Size(w, size.height * 0.76f), CornerRadius(3f))
            } else drawRoundRect(c, Offset(size.width * 0.12f, size.height * 0.12f), Size(size.width * 0.76f, size.height * 0.76f), CornerRadius(5f))
        }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, containerColor = C.panel,
        title = { Text(if (paused) "Background work paused" else "No background work", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (paused) {
                    Text("Stopped", color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (stopped.isEmpty()) Text("Nothing was running", color = C.muted, fontSize = 13.sp)
                    stopped.forEach { Text("• $it", color = C.text, fontSize = 13.sp) }
                    Text("Waiting (kept)", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    val waiting = rgQ.map { "ReplayGain scan: $it" } + saveQ.drop(1).map { "Save song: $it" }
                    if (waiting.isEmpty()) Text("Nothing else queued", color = C.muted, fontSize = 13.sp)
                    waiting.forEachIndexed { i, w -> Text("${i + 1}. $w", color = C.text, fontSize = 13.sp) }
                    Text("Playback and radio recording go on.", color = C.muted, fontSize = 12.sp)
                } else Text("Nothing runs or waits in the background.", color = C.muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            if (paused) STextButton({ BackgroundWork.resume(); show = false }) { Text("Resume", color = accent()) }
        },
        dismissButton = { STextButton({ show = false }) { Text("Close") } },
    )
}
