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
fun RgScanDialog(nav: Navigator, collect: suspend () -> List<PlayItem>, initialMode: RgMode = RgMode.ALBUMS_BY_TAGS, title: String = "ReplayGain scan", onDone: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<PlayItem>?>(null) }
    var mode by remember { mutableStateOf(initialMode) }
    var target by remember { mutableStateOf(RgTarget.FILES) }
    var rescan by remember { mutableStateOf(false) }
    // One file picked: scan just it, or every song in its folder.
    var single by remember { mutableStateOf<PlayItem?>(null) }
    var folder by remember { mutableStateOf<List<PlayItem>?>(null) }
    var wholeFolder by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val l = collect().filter { it.isMusic }
        items = l
        if (l.size == 1) {
            single = l[0]
            folder = app.vlcinterface.sources.MusicLibrary.folderOf(ctx.applicationContext, l[0]).filter { it.isMusic }.takeIf { it.size > 1 }
        }
    }
    LaunchedEffect(wholeFolder, folder) { val f = folder; val one = single; if (one != null) items = if (wholeFolder && f != null) f else listOf(one) }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text(title, color = C.text) },
        text = {
            val scroll = rememberScrollState()
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val its = items
                Text(if (its == null) "Collecting music files…" else "${its.size} music file${if (its.size == 1) "" else "s"}", color = C.muted, fontSize = 13.sp)
                if (single != null) {
                    Text("Files", color = C.text, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().clickable { wholeFolder = false }, verticalAlignment = Alignment.CenterVertically) {
                        SRadioButton(!wholeFolder, { wholeFolder = false })
                        Text("Just the selected file", color = C.text, fontSize = 14.sp)
                    }
                    val f = folder
                    Row(Modifier.fillMaxWidth().clickable(enabled = f != null) { wholeFolder = true }, verticalAlignment = Alignment.CenterVertically) {
                        SRadioButton(wholeFolder, { if (f != null) wholeFolder = true }, enabled = f != null)
                        Text(if (f != null) "All files in its folder (${f.size})" else "All files in its folder (none other found)",
                            color = if (f != null) C.text else C.muted, fontSize = 14.sp)
                    }
                }
                Text("Scan", color = C.text, fontWeight = FontWeight.SemiBold)
                RgMode.values().forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { mode = m }, verticalAlignment = Alignment.CenterVertically) {
                        SRadioButton(mode == m, { mode = m })
                        Text(m.label, color = C.text, fontSize = 14.sp)
                    }
                }
                Text(when (mode) {
                    RgMode.TRACK -> "Every file gets its own track gain."
                    RgMode.SINGLE_ALBUM -> "Track gain for each file, and one album gain for all selected files together."
                    RgMode.ALBUMS_BY_TAGS -> "Track + album gain; each album gets its own album gain, saved on that album's songs. Albums come from the tags inside the files (album artist | year | album title); a song with no album title in its file goes with its folder."
                    RgMode.ALBUMS_BY_FOLDERS -> "Track + album gain; albums by physical path: every folder the files are really in is one album (subfolders are separate albums), each saving its own album gain on its songs."
                }, color = C.muted, fontSize = 12.sp)
                its?.let { l ->
                    val n = RgScanner.albumCount(l, mode)
                    if (mode != RgMode.TRACK) Text(if (mode == RgMode.SINGLE_ALBUM) "All ${l.size} songs share ONE album gain" +
                        (RgScanner.albumCount(l, RgMode.ALBUMS_BY_TAGS).let { a -> if (a > 1) " — but they come from $a albums: choose \"As albums\" so each album gets its own" else "" })
                        else "$n album${if (n == 1) "" else "s"} found — each saves its own album gain on its songs",
                        color = if (mode == RgMode.SINGLE_ALBUM && RgScanner.albumCount(l, RgMode.ALBUMS_BY_TAGS) > 1) C.bad else accent(), fontSize = 12.sp)
                }
                Text("Save", color = C.text, fontWeight = FontWeight.SemiBold)
                listOf(RgTarget.FILES to "Write into the files", RgTarget.APP to "Keep in the app only").forEach { (t, l) ->
                    Row(Modifier.fillMaxWidth().clickable { target = t }, verticalAlignment = Alignment.CenterVertically) {
                        SRadioButton(target == t, { target = t })
                        Text(l, color = C.text, fontSize = 14.sp)
                    }
                }
                Text(if (target == RgTarget.FILES)
                    "Tags written as REPLAYGAIN_* (MP3: ID3v2 TXXX, FLAC/Ogg: Vorbis comments, M4A: iTunes tags), no backup. " +
                        "The tagged file is checked before it replaces the original. Phone files need Android's confirmation; " +
                        "read-only sources and formats that can't be tagged keep the values in the app."
                else "Nothing in the files changes; only this app uses the values.", color = C.muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().clickable { rescan = !rescan }, verticalAlignment = Alignment.CenterVertically) {
                    SCheckbox(rescan, { rescan = it })
                    Text("Re-scan files that already have tags", color = C.text, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            STextButton({
                val its = items ?: return@STextButton
                RgScanner.start(ctx.applicationContext, its, mode, target, rescan)
                onDone(); nav.go(Screen.RgScan)
            }, enabled = !items.isNullOrEmpty()) { Text(if (RgScanner.state.value.running) "Add to queue" else "Scan", color = accent()) }
        },
        dismissButton = { STextButton(onDone) { Text("Cancel") } },
    )
}

@Composable
fun RgScanScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val s by RgScanner.state.collectAsState()
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("ReplayGain scan", nav, if (s.running) "${(s.progress * 100).toInt()} % · ${s.total} files" else s.phase)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                Box(Modifier.fillMaxWidth(if (s.finished) 1f else s.progress).fillMaxHeight().background(accent()))
            }
            if (s.running) Text("${s.phase}: ${s.current}", color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Live counters: they move while files are read, measured and saved (results below appear per album).
            Text(listOfNotNull(if (s.tagsRead > 0) "Tags read ${s.tagsRead}/${s.total}" else null,
                "Measured ${s.measured}/${s.total}", "Saved ${s.saved}", if (s.skipped > 0) "Skipped ${s.skipped}" else null,
                if (s.problems > 0) "Problems ${s.problems}" else null).joinToString(" · "), color = C.text, fontSize = 13.sp)
            if (s.running) Text("You can leave this page, the scan continues. Results are listed per album once it's measured.", color = C.muted, fontSize = 12.sp)
            val queued by RgScanner.queued.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.running) Pill(if (queued.isEmpty()) "Stop" else "Skip to next") { RgScanner.cancel() } else Pill("Close", filled = true) { nav.back() }
                if (queued.isNotEmpty()) Pill("Stop all") { RgScanner.cancelAll() }
            }
            // Waiting scans, in order; tap one to take it out.
            if (queued.isNotEmpty()) {
                Text("Queue (${queued.size}) — tap to remove", color = accent(), fontSize = 13.sp)
                queued.forEachIndexed { i, name ->
                    Text("${i + 1}. $name", color = C.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { RgScanner.dropQueued(i) }.padding(vertical = 2.dp))
                }
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

/** Details of one song or video file: title, sections, note — for the pop-up and for sharing. */
fun itemDetails(item: PlayItem, i: app.vlcinterface.sources.MediaInfo.Info?): Triple<String, List<Pair<String, List<Pair<String, String>>>>, String> {
    fun g(v: Float?) = v?.let { "%+.2f dB".format(java.util.Locale.US, it) } ?: "—"
    fun pk(v: Float?) = v?.let { "%.6f".format(java.util.Locale.US, it) } ?: "—"
    fun rg(r: app.vlcinterface.sources.Rg?) = if (r == null || !r.any) listOf("Values" to "none")
        else listOf("Track gain" to g(r.trackGain), "Track peak" to pk(r.trackPeak), "Album gain" to g(r.albumGain), "Album peak" to pk(r.albumPeak))
    val file = listOfNotNull("Name" to item.title,
        item.artist.takeIf { it.isNotBlank() }?.let { "Artist" to it }, item.album.takeIf { it.isNotBlank() }?.let { "Album" to it },
        if (item.durationMs > 0) "Length" to fmtTime(item.durationMs) else null,
        "Place" to Uri.decode(item.uri).substringAfter("://").let { if (it.length > 160) "…" + it.takeLast(160) else it },
        if (i != null && i.error.isNotEmpty()) "Tags" to "could not be read: ${i.error}" else null)
    val secs = when {
        i == null -> listOf("File" to file + ("Tags" to "reading the file…"))
        else -> buildList {
            add("File" to file)
            add("Tag records" to i.tags.toList())
            if (item.isMusic) {
                add("ReplayGain in the file" to rg(i.fileRg))
                add("ReplayGain kept in the app" to rg(i.appRg))
            }
        }
    }
    val note = if (i == null || !item.isMusic) "" else when {
        i.appRg?.any == true -> "The player uses the values kept in the app."
        i.fileRg?.any == true -> "The player uses the values in the file."
        else -> "No ReplayGain values: this file plays unchanged. Long-press → ReplayGain scan to measure it."
    }
    return Triple(if (item.isMusic) "Song details · ${item.title}" else "File details · ${item.title}", secs, note)
}

/** Details of one audio or video file (tags and ReplayGain), in the shared details style. */
@Composable
fun TagInfoDialog(item: PlayItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<app.vlcinterface.sources.MediaInfo.Info?>(null) }
    LaunchedEffect(item.uri) { info = app.vlcinterface.sources.MediaInfo.read(ctx.applicationContext, item) }
    val (title, secs, note) = itemDetails(item, info)
    DetailsDialog(title, secs, note = note, youtube = item.takeIf { !it.isMusic && !it.isRadio }, onDone = onDismiss)
}

/**
 * ReplayGain scan of the entire library: choose the places (phone + saved storage sources), the music in all
 * their folders and subfolders is collected, then the usual scan options. Runs in the background with a
 * notification when it ends and a report of any failures.
 */
@Composable
fun LibraryRgDialog(nav: Navigator, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val st by App.instance.store.state.collectAsState()
    val sources = st.sources.filter { it.proto in setOf("smb", "webdav", "http") }
    var phone by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(sources.map { it.id }.toSet()) }
    var go by remember { mutableStateOf(false) }
    if (go) {
        val pick = sources.filter { it.id in chosen }
        RgScanDialog(nav, {
            val all = ArrayList<PlayItem>()
            if (phone) app.vlcinterface.sources.InternalSource.folders(ctx).forEach { f -> all += app.vlcinterface.sources.MusicLibrary.collectLocal(ctx, f, false) }
            pick.forEach { s -> all += app.vlcinterface.sources.MusicLibrary.collectRecursive(s, app.vlcinterface.sources.Urls.forSource(s)) }
            all.filter { it.isMusic }.distinctBy { it.uri }
        }, app.vlcinterface.sources.RgMode.ALBUMS_BY_TAGS, "ReplayGain — entire library", onDone)
        return
    }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("ReplayGain — entire library", color = C.text) },
        text = {
            val scroll = rememberScrollState()
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Scan all music in these places, with every folder and subfolder:", color = C.muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().clickable { phone = !phone }, verticalAlignment = Alignment.CenterVertically) {
                    SCheckbox(phone, { phone = it }); Text("Phone storage (all music folders)", color = C.text, fontSize = 14.sp)
                }
                sources.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (s.id in chosen) chosen - s.id else chosen + s.id }, verticalAlignment = Alignment.CenterVertically) {
                        SCheckbox(s.id in chosen, { chosen = if (it) chosen + s.id else chosen - s.id })
                        Text("${s.name} (${s.proto.uppercase()})", color = C.text, fontSize = 14.sp)
                    }
                }
                Text("Collecting a big NAS takes a while. The scan keeps running in the background; you get a notification when it ends, and a report if anything failed.",
                    color = C.muted, fontSize = 12.sp)
            }
        },
        confirmButton = { STextButton({ go = true }, enabled = phone || chosen.isNotEmpty()) { Text("Continue", color = accent()) } },
        dismissButton = { STextButton(onDone) { Text("Cancel") } },
    )
}

/** Pop-up with the failures of a finished scan (shown once, on any page). */
@Composable
fun RgFailureReport(nav: Navigator) {
    val s by RgScanner.state.collectAsState()
    // Android's permission to change phone files: asked from the app root, so a scan started without
    // its page open (playlist quick scan) never waits for it.
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        RgScanner.phonePermissionResult(r.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(s.needsPhonePermission) {
        if (s.needsPhonePermission.isNotEmpty()) try {
            val pi = MediaStore.createWriteRequest(ctx.contentResolver, s.needsPhonePermission.map { Uri.parse(it) })
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (_: Exception) { RgScanner.phonePermissionResult(false) }
    }
    // Indexing started by hand on a folder whose last indexing was interrupted (2026-11-22 07:36): resume or start over.
    val idxAsk by app.vlcinterface.sources.NetIndex.ask.collectAsState()
    idxAsk?.let { q ->
        val names = q.pins.joinToString(", ") { "\"" + it.trim('/').substringAfterLast('/') + "\"" }
        val whenT = remember(q) { java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(q.at)) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { app.vlcinterface.sources.NetIndex.ask.value = null }, containerColor = C.panel,
            title = { Text("Resume indexing?", color = C.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Indexing of $names was interrupted ($whenT). ${q.songs} songs were already indexed; they're kept and found by searches.",
                        color = C.text, fontSize = 14.sp)
                    Text("Resume: only the folders not read yet are read. Start over: everything is read again (tags already read come from the cache, so it's still quicker).",
                        color = C.muted, fontSize = 13.sp)
                }
            },
            confirmButton = { STextButton({ app.vlcinterface.sources.NetIndex.answer(resume = true) }) { Text("Resume", color = accent()) } },
            dismissButton = { STextButton({ app.vlcinterface.sources.NetIndex.answer(resume = false) }) { Text("Start over") } })
    }
    // Write access to a music folder (22:57): asked as a question first, then Android's folder picker on that folder.
    val folderAsk by app.vlcinterface.sources.MusicFolderAccess.ask.collectAsState()
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val a = app.vlcinterface.sources.MusicFolderAccess.ask.value
        val tree = r.data?.data
        if (r.resultCode == android.app.Activity.RESULT_OK && tree != null) {
            app.vlcinterface.sources.MusicFolderAccess.keep(ctx, tree)
            ShortNote.show("Write access given: " + (android.net.Uri.decode(tree.lastPathSegment ?: "").substringAfter(':').ifEmpty { "folder" }))
        } else a?.let { app.vlcinterface.sources.MusicFolderAccess.decline(it.path) }
        a?.answer?.complete(r.resultCode == android.app.Activity.RESULT_OK)
        app.vlcinterface.sources.MusicFolderAccess.ask.value = null
    }
    folderAsk?.let { a ->
        val name = a.path.substringAfterLast('/')
        androidx.compose.material3.AlertDialog(onDismissRequest = {}, containerColor = C.panel,
            title = { Text("Write ReplayGain into \"$name\"?", color = C.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("To save ReplayGain in the songs' tags, the app needs permission to change the music files in this folder:", color = C.text, fontSize = 14.sp)
                    Text(a.path.removePrefix("/storage/emulated/0").ifEmpty { "/" }, color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Android's folder picker opens on it: tap \"Use this folder\", then \"Allow\". The app gets this folder and its " +
                        "subfolders only — not your mails, photos, messages or anything else. Asked once; it can be removed in " +
                        "Settings → Audio → ReplayGain.", color = C.muted, fontSize = 13.sp)
                }
            },
            confirmButton = { STextButton({
                try { treeLauncher.launch(app.vlcinterface.sources.MusicFolderAccess.pickerIntent(a.path)) }
                catch (_: Exception) { a.answer?.complete(false); app.vlcinterface.sources.MusicFolderAccess.ask.value = null }
            }) { Text("Choose folder", color = accent()) } },
            dismissButton = { STextButton({
                app.vlcinterface.sources.MusicFolderAccess.decline(a.path); a.answer?.complete(false)
                app.vlcinterface.sources.MusicFolderAccess.ask.value = null
            }) { Text("Not now") } })
    }
    if (!s.reportPending || !s.finished) return
    val fails = s.results.filter { !it.ok }
    // Quick scan (playlist): confirmation of what was done, failures listed under it.
    if (s.confirmTitle.isNotEmpty()) {
        AutoInfoDialog(onDismissRequest = { RgScanner.reportSeen() }, containerColor = C.panel,
            title = { Text("ReplayGain done — ${s.confirmTitle}", color = C.text) },
            text = {
                // No scrolling of its own: the dialog already scrolls its text (a second scroll inside it crashed the app).
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✓ Scanned and saved per song: ${s.saved}", color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Already had ReplayGain, left as they were: ${s.skipped}", color = C.text, fontSize = 13.sp)
                    if (fails.isNotEmpty()) Text("Problems: ${fails.size}", color = C.bad, fontSize = 13.sp)
                    fails.forEach { r ->
                        Column {
                            Text(r.name, color = C.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(r.status, color = C.bad, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { STextButton({ RgScanner.reportSeen() }) { Text("OK", color = accent()) } },
        )
        return
    }
    AutoInfoDialog(onDismissRequest = { RgScanner.reportSeen() }, containerColor = C.panel,
        title = { Text("ReplayGain scan: ${fails.size} problem${if (fails.size == 1) "" else "s"}", color = C.text) },
        text = {
            // No scrolling of its own: the dialog already scrolls its text (a second scroll inside it crashed the app).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Saved ${s.saved} · skipped ${s.skipped}", color = C.muted, fontSize = 13.sp)
                fails.forEach { r ->
                    Column {
                        Text(r.name, color = C.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(r.status, color = C.bad, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { STextButton({ RgScanner.reportSeen() }) { Text("OK", color = accent()) } },
        dismissButton = { STextButton({ RgScanner.reportSeen(); if (nav.top !is Screen.RgScan) nav.go(Screen.RgScan) }) { Text("Open scan page") } },
    )
}
