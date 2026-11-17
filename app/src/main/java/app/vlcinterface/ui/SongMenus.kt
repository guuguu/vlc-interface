package app.vlcinterface.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * Radio: "Save this song" — the announced song (saved when it ends), or by hand: Start / Stop saving, or the last
 * X minutes; both ends go to the gap between songs. A name can be typed (else the announced one, else the date).
 */
@Composable
fun SaveSongDialog(onDone: () -> Unit) {
    val s by RadioCapture.status.collectAsState()
    val q by RadioCapture.saveQueue.collectAsState()
    val st by app.vlcinterface.App.instance.store.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var recSec by remember { mutableStateOf(RadioCapture.recordedSec()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); recSec = RadioCapture.recordedSec() } }
    val ctx = LocalContext.current
    fun say(t: String) { Toast.makeText(ctx, t, Toast.LENGTH_LONG).show() }
    val pend = s.pending
    val fmt = if (st.settings.saveFormat == "original") "as the station sends it" else "MP3 (LAME V0)"
    val sub = if (st.settings.saveFormat == "original") "raw" else "mp3"
    val where = (if (st.settings.saveFolder.isEmpty()) "the library root" else Uri.decode(st.settings.saveFolder).substringAfterLast(':').ifEmpty { "your folder" }) + " /$sub"
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Save this song", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.title.ifEmpty { "No song title announced" }, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (!s.running) {
                    Text(s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)." }, color = C.bad, fontSize = 13.sp)
                } else {
                    Text("Saved $fmt to $where. Cut at the gap between songs, with all tags found.", color = C.muted, fontSize = 13.sp)
                    when {
                        pend?.manual == "start" -> Text("Saving by hand since ${fmtTime(System.currentTimeMillis() - pend.at)} — tap Stop saving at the end.", color = accent(), fontSize = 13.sp)
                        pend != null -> Text("Waiting for “${pend.title}” to end — then saved.", color = accent(), fontSize = 13.sp)
                        s.title.isNotBlank() && !s.titleStartKnown -> Text("Recording began during this song: only the part from then on is saved. Songs after it are saved whole.", color = C.muted, fontSize = 12.sp)
                    }
                    // Name (optional): used when the station announces none.
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name (optional)") }, placeholder = { Text("Artist - Title") })
                    Text("By hand", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pend?.manual == "start") Pill("Stop saving", filled = true) { say(RadioCapture.stopSaving()); onDone() }
                        else Pill("Start saving now", filled = pend == null) { if (pend == null) { say(RadioCapture.startSaving(name)); onDone() } }
                    }
                    val mins = listOf(3, 5, 10, 15, 20).filter { it * 60 <= recSec + 5 }
                    Text("Save the last … minutes" + if (mins.isEmpty()) " (${(recSec / 60).toInt()} min recorded so far)" else "", color = C.muted, fontSize = 12.sp)
                    if (mins.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        mins.forEach { m -> Pill("$m min") { say(RadioCapture.saveLast(m, name)); onDone() } }
                    }
                }
                if (q.isNotEmpty()) Text("Saving queue: " + q.joinToString(" · "), color = accent(), fontSize = 12.sp)
            }
        },
        confirmButton = {
            val canAuto = s.running && s.title.isNotBlank() && pend == null
            if (pend != null && pend.manual.isEmpty()) STextButton({ RadioCapture.cancelPending(); onDone() }) { Text("Don't save", color = accent()) }
            else STextButton({ say(RadioCapture.saveCurrent()); onDone() }, enabled = canAuto) {
                Text("Save this song", color = if (canAuto) accent() else C.muted)
            }
        },
        dismissButton = { STextButton(onDone) { Text("Close") } },
    )
}
