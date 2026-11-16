package app.vlcinterface.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import app.vlcinterface.App
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Battery "Unrestricted" for this app, so Android doesn't stop playback while the app is in the background. */
object Battery {
    /** A question waits to be shown (app start; any playlist / folder / queue started). */
    val pending = kotlinx.coroutines.flow.MutableStateFlow(true)
    fun request() { pending.value = true }

    fun unrestricted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Android's own question "Let the app always run in the background?"; falls back to the app's battery settings. */
    @SuppressLint("BatteryLife")
    fun ask(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}

/**
 * Asked at every app start and whenever a playlist, folder or queue starts playing, as long as the app's
 * battery setting isn't "Unrestricted" (once it is, never again). "Not now" only skips that occasion.
 */
@Composable
fun BatteryPromptHost() {
    val ctx = LocalContext.current
    val want by Battery.pending.collectAsState()
    if (!want) return
    if (Battery.unrestricted(ctx)) { Battery.pending.value = false; return }
    fun done() { Battery.pending.value = false }
    AlertDialog(onDismissRequest = { done() }, containerColor = C.panel,
        title = { Text("Keep playing in the background", color = C.text) },
        text = {
            Text("Android's battery saver can stop the app between two songs while you use other apps. " +
                "Allow it to run in the background (battery: Unrestricted) so playlists go on to the end.", color = C.text)
        },
        confirmButton = { STextButton({ done(); Battery.ask(ctx) }) { Text("Allow", color = accent()) } },
        dismissButton = { STextButton({ done() }) { Text("Not now") } },
    )
}


/** "Player closed by sleep timer" — shown when the sleep timer stopped playback (also on coming back to the app). */
@Composable
fun SleepClosedHost() {
    val at by App.instance.player.sleptAt.collectAsState()
    if (at <= 0L) return
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
    AlertDialog(onDismissRequest = { App.instance.player.sleepSeen() }, containerColor = C.panel,
        title = { Text("Sleep", color = C.text) },
        text = { Text("Player closed by sleep timer at $time.", color = C.text) },
        confirmButton = { STextButton({ App.instance.player.sleepSeen() }) { Text("OK", color = accent()) } },
    )
}


/** 5 songs in a row couldn't be played: try the next songs from the same place, or stop? */
@Composable
fun FailAskHost() {
    val place by App.instance.player.failAsk.collectAsState()
    if (place.isEmpty()) return
    AlertDialog(onDismissRequest = {}, containerColor = C.panel,
        title = { Text("Songs can't be played", color = C.text) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("The last 5 songs couldn't be played (not found or not reachable) from:\n\n$place", color = C.text)
                STextButton({ App.instance.player.failAnswer("keep") }) { Text("Keep trying the next songs", color = accent()) }
                STextButton({ App.instance.player.failAnswer("skip") }) { Text("Skip everything from there", color = accent()) }
                STextButton({ App.instance.player.failAnswer("stop") }) { Text("Stop") }
            }
        },
        confirmButton = {},
    )
}


/**
 * A song without ReplayGain started: scan it now? Or its whole folder (each album gets its album gain), with the
 * choice to leave files that already have ReplayGain as they are. Written into the files. Asked once per folder
 * while the app runs.
 */
@Composable
fun RgAskHost() {
    val item by App.instance.player.rgAsk.collectAsState()
    val it0 = item ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var skipTagged by androidx.compose.runtime.remember(it0.uri) { androidx.compose.runtime.mutableStateOf(true) }
    fun close() { App.instance.player.rgAsk.value = null }
    fun start(items: List<app.vlcinterface.player.PlayItem>, mode: app.vlcinterface.sources.RgMode, title: String) {
        val music = items.filter { it.isMusic }
        if (music.isEmpty()) return
        app.vlcinterface.sources.RgScanner.start(ctx.applicationContext, music, mode, app.vlcinterface.sources.RgTarget.FILES,
            rescanTagged = !skipTagged, confirmTitle = title)
        android.widget.Toast.makeText(ctx, "ReplayGain scan started: ${music.size} file${if (music.size == 1) "" else "s"}", android.widget.Toast.LENGTH_SHORT).show()
    }
    AlertDialog(onDismissRequest = { close() }, containerColor = C.panel,
        title = { Text("No ReplayGain", color = C.text) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("\"${it0.title}\" has no ReplayGain values (it plays as it is for now). Scan it?", color = C.text)
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.clickable { skipTagged = !skipTagged },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SCheckbox(skipTagged, { skipTagged = it })
                    Text("Folder / playlist: skip files that already have ReplayGain", color = C.text, fontSize = 14.sp)
                }
                STextButton({ close(); start(listOf(it0), app.vlcinterface.sources.RgMode.TRACK, it0.title) }) { Text("Scan this song", color = accent()) }
                STextButton({
                    close()
                    // Not tied to this pop-up: it closes at once, and a job tied to it was cancelled with it while the
                    // folder was still being listed (so the folder scan never started).
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val folder = app.vlcinterface.sources.MusicLibrary.folderOf(ctx.applicationContext, it0).ifEmpty { listOf(it0) }
                        start(folder, app.vlcinterface.sources.RgMode.ALBUMS_BY_TAGS, it0.album.ifEmpty { "this folder" })
                    }
                }) { Text("Scan the whole folder", color = accent()) }
                // A playlist (or a list) is loaded: scan everything in it — songs only, radio links and videos ignored.
                val ps = App.instance.player.state.value
                val pl = if (ps.origin.kind == "playlist") App.instance.store.current.playlists.firstOrNull { it.id == ps.origin.playlistId } else null
                val listItems = pl?.items?.map { app.vlcinterface.sources.MusicLibrary.fromPlEntry(it) } ?: ps.queue
                val songs = listItems.filter { it.isMusic && !it.isRadio }.distinctBy { it.uri }
                if (songs.size > 1) STextButton({
                    // Track gain only: a playlist rarely holds whole albums, and an album value from part of an album
                    // would be off. Album values come from folder / album scans.
                    close(); start(songs, app.vlcinterface.sources.RgMode.TRACK, pl?.name ?: "the queue")
                }) { Text("Scan the whole ${if (pl != null) "playlist" else "queue"} (${songs.size} songs)", color = accent()) }
                STextButton({ close() }) { Text("Not now") }
            }
        },
        confirmButton = {},
    )
}


/** Low battery pop-ups: the 15 % warning, and "stopped" at 10 %. */
@Composable
fun LowBatteryHost() {
    val n by app.vlcinterface.player.BatteryGuard.notice.collectAsState()
    if (n == 0) return
    val g = app.vlcinterface.player.BatteryGuard
    AlertDialog(onDismissRequest = { g.seen() }, containerColor = C.panel,
        title = { Text("Battery ${g.level} %", color = C.text) },
        text = { Text(when (n) {
            15 -> "Indexing and scans stopped, only playing goes on. Playback stops at 10 %. Screen-on turns off then too."
            12 -> "Only playing is accepted. Battery is 15% or less. Recharge and comeback."
            else -> app.vlcinterface.player.BatteryGuard.TEXT
        }, color = C.text) },
        confirmButton = { STextButton({ g.seen() }) { Text("OK", color = accent()) } },
    )
}


/**
 * Wi-Fi lost while a station played: exit, or go on over mobile data. Lower-bitrate favourites are offered, and
 * FLAC stations are advised against on mobile data.
 */
@Composable
fun WifiLostHost() {
    val ask by app.vlcinterface.player.NetGuard.ask.collectAsState()
    if (!ask) return
    val g = app.vlcinterface.player.NetGuard
    val st by App.instance.store.state.collectAsState()
    val cur = App.instance.player.state.value.item
    val curMeta = st.stations.firstOrNull { it.url == cur?.uri }?.meta ?: ""
    val light = st.stations.filter { s -> g.bitrateOf(s.meta).let { it in 1..128 } && !s.meta.contains("flac", true) && s.url != cur?.uri }
    AlertDialog(onDismissRequest = {}, containerColor = C.panel,
        title = { Text("Wi-Fi lost", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The radio stopped. Exit, or continue on mobile data?", color = C.text)
                Text("Use lower bitrate stations on mobile data (128 kbps or less). Don't use FLAC stations on mobile data: they use a lot of data.",
                    color = C.muted, fontSize = 13.sp)
                if (curMeta.contains("flac", true)) Text("This station is FLAC.", color = C.bad, fontSize = 13.sp)
                if (light.isNotEmpty()) {
                    Text("Lower bitrate favourites", color = C.text, fontSize = 14.sp)
                    light.take(8).forEach { s ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.tile).clickable {
                            g.mobileOk = true; g.ask.value = false; App.instance.player.playStation(s.name, s.url)
                        }.padding(10.dp)) {
                            Column {
                                Text(s.name, color = C.text, fontSize = 14.sp)
                                if (s.meta.isNotEmpty()) Text(s.meta, color = C.muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { STextButton({ g.goOnMobile() }) { Text("Continue on mobile data", color = accent()) } },
        dismissButton = { STextButton({ g.ask.value = false; exitApp() }) { Text("Exit") } },
    )
}


/** The sound output stayed on libVLC after the app tried twice to bring its own output back. */
@Composable
fun OutputFallbackHost() {
    val player = App.instance.player
    val why by player.fallbackNotice.collectAsState()
    if (why.isEmpty()) return
    AlertDialog(onDismissRequest = { player.fallbackNotice.value = "" }, containerColor = C.panel,
        title = { Text("Sound output", color = C.text) },
        text = { Text("The output is set on libVLC because: $why", color = C.text) },
        confirmButton = { STextButton({ player.fallbackNotice.value = "" }) { Text("OK", color = accent()) } },
    )
}
