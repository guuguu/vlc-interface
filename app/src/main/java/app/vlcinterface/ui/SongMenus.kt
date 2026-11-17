package app.vlcinterface.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.player.PlayItem
import app.vlcinterface.sources.RadioCapture
import app.vlcinterface.sources.SongLinks

/** The file's place, readable: phone path (/storage/…), smb://server/share/…, or the web address. */
fun filePathOf(ctx: Context, item: PlayItem): String {
    val u = item.uri
    if (u.startsWith("content://")) try {
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(Uri.parse(u), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    } catch (_: Exception) {}
    if (u.startsWith("file://")) return Uri.parse(u).path ?: Uri.decode(u)
    // Network: never the password, even when the address holds one.
    return Uri.decode(u).replace(Regex("^(\\w+://)[^/@]*@"), "$1")
}

private fun copy(ctx: Context, text: String, what: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(what, text))
    Toast.makeText(ctx, "$what copied", Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, subject: String, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), "Share song")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * A song name from the clipboard: Shazam's share text ("I used Shazam to discover Song by Artist. https://…")
 * becomes "Artist - Song"; anything else: its first line without links.
 */
fun clipSong(ctx: Context): String? {
    val t = try { ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() } catch (_: Exception) { null }
        ?.trim().orEmpty()
    if (t.isEmpty()) return null
    Regex("discover (.+?) by (.+?)(\\.\\s|\\.?\\s*https?://|\\.?$)", RegexOption.IGNORE_CASE).find(t)?.let { m ->
        return "${m.groupValues[2].trim()} - ${m.groupValues[1].trim()}"
    }
    val line = t.lines().map { it.replace(Regex("https?://\\S+"), "").trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    return line.take(120)
}

/** Opens the Shazam app (it listens through the microphone); the Play Store page when it isn't installed. */
fun openShazam(ctx: Context) {
    val pkg = "com.shazam.android"
    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
    try {
        ctx.startActivity((launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(ctx, "Shazam can't be opened", Toast.LENGTH_SHORT).show() }
    }
}

/**
 * YouTube / Spotify links of a song: looked up, then you pick which ones (and the file's path) to copy or share.
 * YouTube: VEVO first, then the artist's "Topic" channel, then the best match by title, artist, length and year.
 */
@Composable
fun SongLinksDialog(artist: String, title: String, lengthMs: Long, album: String, path: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var res by remember { mutableStateOf<SongLinks.Result?>(null) }
    var yt by remember { mutableStateOf(true) }
    var sp by remember { mutableStateOf(true) }
    var fp by remember { mutableStateOf(false) }
    LaunchedEffect(artist, title) { res = SongLinks.find(artist, title, 0, lengthMs, album) }
    val r = res
    fun text(): String = buildString {
        val name = listOf(r?.info?.artist ?: artist, r?.info?.title ?: title).filter { it.isNotBlank() }.joinToString(" – ")
        append(name)
        if (yt) r?.youtube?.let { append("\nYouTube: ${it.url}") }
        if (sp && r != null) append("\nSpotify: ${r.spotify}")
        if (fp && path != null) append("\nFile: $path")
    }
    @Composable
    fun pick(on: Boolean, set: (Boolean) -> Unit, label: String, detail: String, enabled: Boolean = true) {
        Row(Modifier.fillMaxWidth().clickable(enabled) { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
            SCheckbox(on && enabled, { set(it) }, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(label, color = if (enabled) C.text else C.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = C.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Song links", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(listOf(artist, title).filter { it.isNotBlank() }.joinToString(" – "), color = C.text, fontSize = 14.sp)
                if (r == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent())
                    Text("Searching YouTube and Spotify…", color = C.muted, fontSize = 13.sp)
                } else {
                    r.info?.let { i -> Text(listOfNotNull(i.album.ifEmpty { null }, if (i.year > 0) "${i.year}" else null).joinToString(" · "), color = C.muted, fontSize = 12.sp) }
                    pick(yt, { yt = it }, "YouTube", r.youtube?.let { "${it.title}\n${it.channel}" } ?: "No matching video", r.youtube != null)
                    pick(sp, { sp = it }, "Spotify", if (r.spotifyIsSearch) "Song page not found — Spotify search link" else r.spotify)
                    r.notes.filterNot { it.startsWith("Spotify") || it.startsWith("No matching") }.forEach { Text(it, color = C.muted, fontSize = 11.sp) }
                }
                if (path != null) pick(fp, { fp = it }, "File path", path)
            }
        },
        confirmButton = {
            Row {
                STextButton({ copy(ctx, text(), "Links"); onDone() }, enabled = r != null || fp) { Text("Copy") }
                STextButton({ share(ctx, title, text()); onDone() }, enabled = r != null || fp) { Text("Share…", color = accent()) }
            }
        },
        dismissButton = { STextButton(onDone) { Text("Cancel") } },
    )
}

/** Menu of the playing song (long-press its title on the player). */
@Composable
fun SongTitleMenu(item: PlayItem, artist: String, lengthMs: Long, open: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var links by remember { mutableStateOf(false) }
    if (links) SongLinksDialog(artist, item.title, lengthMs, item.album, filePathOf(ctx, item)) { links = false }
    SDropdownMenu(open, onClose) {
        SMenuItem({ Text("ReplayGain scan…") }, { onClose(); FileDialogs.rg = item })
        SMenuItem({ Text("YouTube / Spotify links…") }, { onClose(); links = true })
        SMenuItem({ Text("Copy file path") }, { onClose(); copy(ctx, filePathOf(ctx, item), "Path") })
    }
}

/**
 * Radio: "Save this song". First: how — to the end of the announced song, for 3…30 minutes or a number you type,
 * from now until you stop, or the last X minutes. While saving: stop now (saved), cancel (not saved), or longer
 * (+1 / +5 min, + the next song). Both ends always go to the gap between songs. A name can be typed.
 */
@Composable
fun SaveSongDialog(onDone: () -> Unit) {
    val s by RadioCapture.status.collectAsState()
    val q by RadioCapture.saveQueue.collectAsState()
    val st by app.vlcinterface.App.instance.store.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("song") }
    var minutes by remember { mutableStateOf(5) }
    var lastMin by remember { mutableStateOf(5) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var recSec by remember { mutableStateOf(RadioCapture.recordedSec()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis(); recSec = RadioCapture.recordedSec() } }
    val ctx = LocalContext.current
    fun say(t: String) { Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show() }
    // Back from Shazam: its song (copied or shared to the clipboard) goes into the name.
    var shazamAt by remember { mutableStateOf(0L) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && shazamAt > 0) {
                shazamAt = 0
                clipSong(ctx)?.let { name = it; say("Name pasted: $it") }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val pend = s.pending
    val fmt = if (st.settings.saveFormat == "original") "as the station sends it" else "MP3 (LAME V0)"
    val sub = if (st.settings.saveFormat == "original") "raw" else "mp3"
    val where = (if (st.settings.saveFolder.isEmpty()) "the library root" else Uri.decode(st.settings.saveFolder).substringAfterLast(':').ifEmpty { "your folder" }) + " /$sub"
    val label = @Composable { t: String, c: androidx.compose.ui.graphics.Color -> Text(t, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text(if (pend != null) "Saving" else "Save this song", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((pend?.name?.ifBlank { null } ?: pend?.title?.ifBlank { null } ?: s.title).ifEmpty { "No song title announced" },
                    color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (!s.running && pend == null) {
                    Text(s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)." }, color = C.bad, fontSize = 13.sp)
                } else if (pend != null) {
                    // Saving now: how it ends, how long so far; stop, cancel or longer.
                    val ends = when (pend.manual) {
                        "timed" -> "Ends in ${fmtTime((pend.untilAt - now).coerceAtLeast(0))}"
                        "start" -> "Ends when you stop it"
                        "gap" -> (if (pend.partialStart) "Began before the recording · " else "") + "Ends at the next gap between songs" +
                            (if (pend.songsLeft > 1) " + ${pend.songsLeft - 1} more" else "") +
                            (if (pend.extraAfterSongMs > 0) " + ${pend.extraAfterSongMs / 60_000} min" else "")
                        else -> "Ends with the song" + (if (pend.songsLeft > 1) " + ${pend.songsLeft - 1} more" else "") +
                            (if (pend.extraAfterSongMs > 0) " + ${pend.extraAfterSongMs / 60_000} min" else "")
                    }
                    Text("$ends · recording ${fmtTime(now - pend.at)}", color = accent(), fontSize = 13.sp)
                    if (pend.manual != "start") {
                        label("Longer", C.muted)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("+1 min") { say(RadioCapture.extend(60_000)) }
                            Pill("+5 min") { say(RadioCapture.extend(300_000)) }
                            if (pend.manual.isEmpty() || pend.manual == "gap") Pill("+ next song") { say(RadioCapture.addSong()) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Stop now — save", filled = true) { say(RadioCapture.stopNow()); onDone() }
                        Pill("Cancel — don't save") { RadioCapture.cancelPending(); say("Not saved"); onDone() }
                    }
                } else {
                    Text("Saved to $where. Both ends go to the gap between songs, with all tags found.", color = C.muted, fontSize = 12.sp)
                    // 1. How much
                    label("Save", C.text)
                    val byTitles = s.title.isNotBlank() && s.titleStartKnown
                    @Composable
                    fun choice(key: String, text: String, hint: String) {
                        Row(Modifier.fillMaxWidth().clickable { mode = key }, verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(mode == key, { mode = key })
                            Column(Modifier.weight(1f)) {
                                Text(text, color = C.text, fontSize = 14.sp)
                                if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 11.sp)
                            }
                        }
                    }
                    choice("song", "This whole song", if (byTitles) "From the station's song titles" else "Found by the gaps between songs")
                    choice("minutes", "For a number of minutes from now", "")
                    if (mode == "minutes") {
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(3, 5, 10, 15, 20, 30).forEach { m -> Pill("$m", selected = minutes == m && custom.isEmpty()) { minutes = m; custom = "" } }
                        }
                        androidx.compose.material3.OutlinedTextField(custom, { v -> custom = v.filter { it.isDigit() }.take(3) }, singleLine = true,
                            modifier = Modifier.padding(start = 48.dp).fillMaxWidth(), label = { Text("Custom minutes (1–180)") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    }
                    choice("hand", "From now until I stop it", "")
                    val mins = listOf(3, 5, 10, 15, 20).filter { it * 60 <= recSec + 5 }
                    choice("last", "The last minutes (already played)", if (mins.isEmpty()) "${(recSec / 60).toInt()} min recorded so far" else "")
                    if (mode == "last" && mins.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        mins.forEach { m -> Pill("$m min", selected = lastMin == m) { lastMin = m } }
                    }
                    // 2. Format of this save (also the default in Settings → Saved songs).
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        label("Format", C.text)
                        val orig = st.settings.saveFormat == "original"
                        Pill("Original", selected = orig) { app.vlcinterface.App.instance.store.update { it.copy(settings = it.settings.copy(saveFormat = "original")) } }
                        Pill("MP3 V0", selected = !orig) { app.vlcinterface.App.instance.store.update { it.copy(settings = it.settings.copy(saveFormat = "mp3")) } }
                    }
                    // 3. Name (optional): typed, or from Shazam; else the announced title, else YY_MM_DD_hr_min.
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name (optional)") }, placeholder = { Text("Artist - Title") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Shazam") { shazamAt = System.currentTimeMillis(); openShazam(ctx) }
                        Pill("Paste") { clipSong(ctx)?.let { name = it } ?: say("Nothing to paste") }
                    }
                    Text("No name: the station's song title, else the date and time the song began (YY_MM_DD_hr_min).", color = C.muted, fontSize = 11.sp)
                }
                if (q.isNotEmpty()) Text("Saving queue: " + q.joinToString(" · "), color = accent(), fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (pend != null || !s.running) STextButton(onDone) { Text("Close", color = accent()) }
            else {
                val m = custom.toIntOrNull()?.takeIf { it in 1..180 } ?: minutes
                val ok = when (mode) { "last" -> lastMin * 60 <= recSec + 5; else -> true }
                Row {
                    STextButton(onDone) { Text("Close") }
                    STextButton({
                        val byTitles = s.title.isNotBlank() && s.titleStartKnown
                        say(when (mode) {
                            "minutes" -> RadioCapture.saveFor(m, name)
                            "hand" -> RadioCapture.startSaving(name)
                            "last" -> RadioCapture.saveLast(lastMin, name)
                            else -> if (byTitles) RadioCapture.saveCurrent(name) else RadioCapture.saveWholeSong(name)
                        })
                        onDone()
                    }, enabled = ok) { Text(if (mode == "last") "Save" else "Start saving", color = if (ok) accent() else C.muted, fontWeight = FontWeight.Bold) }
                }
            }
        },
    )
}

/**
 * Thin bar under the player's top bar while a song is being saved: recording, then encoding / saving; it goes
 * from yellow to red as the operation nears its end. Tap it: the save window (stop, cancel, longer).
 */
@Composable
fun SaveSongBar() {
    val cap by RadioCapture.status.collectAsState()
    val q by RadioCapture.saveQueue.collectAsState()
    val enc by RadioCapture.saveProgress.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val pend = cap.pending
    if (pend == null && q.isEmpty()) return
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); now = System.currentTimeMillis() } }
    // Recording: 0 … 0.85 (time-limited: exact; until a song ends: against ~4 min, never full); saving: 0.85 … 1.
    val v = if (pend != null) {
        val el = (now - pend.at).coerceAtLeast(0).toFloat()
        val rec = when {
            pend.manual == "timed" && pend.untilAt > pend.at -> el / (pend.untilAt - pend.at)
            else -> el / maxOf(240_000f, el + 30_000f)
        }
        0.85f * rec.coerceIn(0f, 1f)
    } else 0.85f + 0.15f * enc.coerceIn(0f, 1f)
    val col = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color(0xFFFFD600), androidx.compose.ui.graphics.Color(0xFFFF1744), v)
    var open by remember { mutableStateOf(false) }
    if (open) SaveSongDialog { open = false }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(3.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(C.line).clickable { open = true }) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(v).fillMaxHeight().background(col))
    }
}

/**
 * A song being saved has shown no end for 10 minutes: stop and save what's recorded, go on for a chosen time,
 * or discard everything. Recording goes on while the question waits.
 */
@Composable
fun SaveEndQuestionHost() {
    val ask by RadioCapture.endQuestion.collectAsState()
    if (!ask) return
    val cap by RadioCapture.status.collectAsState()
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf("") }
    fun say(t: String) { Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show() }
    val title = cap.pending?.let { it.name.ifBlank { it.title } }?.ifBlank { null }
    AlertDialog(onDismissRequest = {}, containerColor = C.panel,
        title = { Text("No song end found", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No end of " + (title?.let { "“$it”" } ?: "the song") + " was detected in 10 minutes. Recording goes on.", color = C.text, fontSize = 14.sp)
                Text("Continue for", color = C.muted, fontSize = 13.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5).forEach { m -> Pill("$m min") { say(RadioCapture.answerContinue(m)) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(custom, { v -> custom = v.filter { it.isDigit() }.take(3) }, singleLine = true,
                        modifier = Modifier.weight(1f), label = { Text("Minutes") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    val m = custom.toIntOrNull() ?: 0
                    Pill("Continue", filled = m in 1..180) { if (m in 1..180) say(RadioCapture.answerContinue(m)) }
                }
            }
        },
        confirmButton = { STextButton({ say(RadioCapture.answerStop()) }) { Text("Stop and save", color = accent(), fontWeight = FontWeight.Bold) } },
        dismissButton = { STextButton({ RadioCapture.answerDiscard(); say("Discarded") }) { Text("Discard everything", color = C.bad) } },
    )
}
