package app.vlcinterface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.sources.InternalSource

/** Find media folders (Settings), full page. */
@Composable
fun MediaFoldersScreen(nav: Navigator) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Find media folders", nav)
        Column(Modifier.weight(1f).anchorViewport(scroll, "folders").scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
            Text("Searches internal storage, the SD card, USB storage and Downloads for songs and videos. Only ticked folders are added; folders already in the Library stay as they are.",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            MediaFoldersCard()
            Box(Modifier.height(24.dp))
        }
    }
}

/** Media places found on the phone: added ones marked ✓, the others with tick boxes; "Add ticked" adds only those. */
@Composable
fun MediaFoldersCard() {
    val ctx = LocalContext.current
    val store = App.instance.store
    val st by store.state.collectAsState()
    var roots by remember { mutableStateOf<List<InternalSource.MediaRoot>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(reload) {
        val r = try { InternalSource.roots(ctx) } catch (_: Exception) { emptyList() }
        roots = r
        // Pre-ticked: usual folder names on the SD card / USB, and Downloads; anything else waits for you.
        ticked = r.filter { !InternalSource.approved(it.key) }.filter { m ->
            val top = m.key.substringAfter(':').trimEnd('/').lowercase()
            top in InternalSource.USUAL || top == android.os.Environment.DIRECTORY_DOWNLOADS.lowercase()
        }.map { it.key }.toSet()
    }
    @Suppress("UNUSED_VARIABLE") val dep = st.settings.phoneFolders
    val list = roots
    if (list == null) { Text("Searching…", color = C.muted, fontSize = 14.sp); return }
    val added = list.filter { InternalSource.approved(it.key) }
    val offered = list.filter { !InternalSource.approved(it.key) }
    fun count(m: InternalSource.MediaRoot) = listOfNotNull(if (m.songs > 0) "${m.songs} songs" else null, if (m.videos > 0) "${m.videos} videos" else null)
        .joinToString(" · ").ifEmpty { "no media yet" }
    SectionTitle("Added")
    CardBox {
        if (added.isEmpty()) Text("None yet", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
        added.forEachIndexed { i, m ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = (9 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓", color = WIZ_GREEN, fontSize = SYM, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(m.label, color = C.text, fontSize = 14.sp)
                    Text(count(m), color = C.muted, fontSize = 12.sp)
                }
            }
        }
    }
    if (offered.isNotEmpty()) {
        SectionTitle("Add these?")
        CardBox {
            offered.forEachIndexed { i, m ->
                if (i > 0) Divider()
                val on = m.key in ticked
                Row(Modifier.fillMaxWidth().clickable { ticked = if (on) ticked - m.key else ticked + m.key }.padding(horizontal = 14.dp, vertical = (9 * sg()).dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "☑" else "☐", color = if (on) WIZ_GREEN else C.text, fontSize = SYM)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(m.label, color = C.text, fontSize = 14.sp)
                        Text(count(m), color = C.muted, fontSize = 12.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (ticked.isEmpty()) C.line else accent())
                .clickable(enabled = ticked.isNotEmpty()) {
                    val add = ticked
                    store.update { it.copy(settings = it.settings.copy(phoneFoldersSet = true, phoneFolders = (it.settings.phoneFolders + add).distinct())) }
                    note = "${add.size} folder${if (add.size == 1) "" else "s"} added to the Library."
                    reload++
                }.padding(vertical = (12 * sg()).dp), contentAlignment = Alignment.Center) {
                Text("Add ticked", color = if (ticked.isEmpty()) C.muted else Color(0xFF1A0E05), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else Text("Every media folder found is already in the Library.", color = C.muted, fontSize = 13.sp)
    if (note.isNotEmpty()) Text(note, color = WIZ_GREEN, fontSize = 13.sp)
    Text("Search again", color = accent(), fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { note = ""; roots = null; reload++ }.padding(8.dp))
}
