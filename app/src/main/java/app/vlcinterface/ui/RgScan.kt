package app.vlcinterface.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.Playlist
import app.vlcinterface.data.Source
import app.vlcinterface.player.PlayItem
import app.vlcinterface.sources.RgMode
import app.vlcinterface.sources.RgScanner
import app.vlcinterface.sources.RgTarget

/** ReplayGain scan options (foobar2000-style modes), then the progress page. */
@Composable
fun RgScanDialog(nav: Navigator, collect: suspend () -> List<PlayItem>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<PlayItem>?>(null) }
    var mode by remember { mutableStateOf(RgMode.ALBUMS_BY_TAGS) }
    var target by remember { mutableStateOf(RgTarget.FILES) }
    var rescan by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { items = collect().filter { it.isMusic } }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("ReplayGain scan", color = C.text) },
        text = {
            val scroll = rememberScrollState()
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val its = items
                Text(if (its == null) "Collecting music files…" else "${its.size} music files", color = C.muted, fontSize = 13.sp)
                Text("Scan", color = C.text, fontWeight = FontWeight.SemiBold)
                RgMode.values().forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { mode = m }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == m, { mode = m })
                        Text(m.label, color = C.text, fontSize = 14.sp)
                    }
                }
                Text(when (mode) {
                    RgMode.TRACK -> "Every file gets its own track gain."
                    RgMode.SINGLE_ALBUM -> "Track gain for each file, and one album gain for all selected files together."
                    RgMode.ALBUMS_BY_TAGS -> "Track + album gain; albums grouped by album artist | year | album. Files with no album tag get track gain only."
                    RgMode.ALBUMS_BY_FOLDERS -> "Track + album gain; every folder is one album."
                }, color = C.muted, fontSize = 12.sp)
                Text("Save", color = C.text, fontWeight = FontWeight.SemiBold)
                listOf(RgTarget.FILES to "Write into the files", RgTarget.APP to "Keep in the app only").forEach { (t, l) ->
                    Row(Modifier.fillMaxWidth().clickable { target = t }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(target == t, { target = t })
                        Text(l, color = C.text, fontSize = 14.sp)
                    }
                }
                Text(if (target == RgTarget.FILES)
                    "Tags written as REPLAYGAIN_* (MP3: ID3v2 TXXX, FLAC/Ogg: Vorbis comments, M4A: iTunes tags), no backup. " +
                        "The tagged file is checked before it replaces the original. Phone files need Android's confirmation; " +
                        "read-only sources and formats that can't be tagged keep the values in the app."
                else "Nothing in the files changes; only this app uses the values.", color = C.muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().clickable { rescan = !rescan }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rescan, { rescan = it })
                    Text("Re-scan files that already have tags", color = C.text, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton({
                val its = items ?: return@TextButton
                RgScanner.start(ctx.applicationContext, its, mode, target, rescan)
                onDone(); nav.go(Screen.RgScan)
            }, enabled = !items.isNullOrEmpty() && !RgScanner.state.value.running) { Text("Scan", color = accent()) }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

@Composable
fun RgScanScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val s by RgScanner.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        RgScanner.phonePermissionResult(r.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(s.needsPhonePermission) {
        if (s.needsPhonePermission.isNotEmpty()) try {
            val pi = MediaStore.createWriteRequest(ctx.contentResolver, s.needsPhonePermission.map { Uri.parse(it) })
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (_: Exception) { RgScanner.phonePermissionResult(false) }
    }
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("ReplayGain scan", nav, if (s.running) "${s.done} / ${s.total}" else s.phase)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                Box(Modifier.fillMaxWidth(if (s.total > 0) s.done.toFloat() / s.total else 0f).fillMaxHeight().background(accent()))
            }
            if (s.running) Text("${s.phase}: ${s.current}", color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ok = s.results.count { it.ok }; val bad = s.results.count { !it.ok }
            Text("$ok done · $bad with a problem" + if (s.running) " · you can leave this page, the scan continues" else "", color = C.muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.running) Pill("Stop") { RgScanner.cancel() } else Pill("Close", filled = true) { nav.back() }
            }
        }
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 16.dp, vertical = 8.dp), state = state) {
            items(s.results.reversed()) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Badge(if (r.ok) "✓" else "!", r.ok)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(r.name, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.status, color = if (r.ok) C.muted else C.bad, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
