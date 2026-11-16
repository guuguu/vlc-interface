package app.vlcinterface.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import app.vlcinterface.App

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
                Text("\"${it0.title}\" has no ReplayGain values (it plays 5 dB lower for now). Scan it?", color = C.text)
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.clickable { skipTagged = !skipTagged },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SCheckbox(skipTagged, { skipTagged = it })
                    Text("Folder: skip files that already have ReplayGain", color = C.text, fontSize = 14.sp)
                }
                STextButton({ close(); start(listOf(it0), app.vlcinterface.sources.RgMode.TRACK, it0.title) }) { Text("Scan this song", color = accent()) }
                STextButton({
                    close()
                    scope.launch {
                        val folder = app.vlcinterface.sources.MusicLibrary.folderOf(ctx.applicationContext, it0).ifEmpty { listOf(it0) }
                        start(folder, app.vlcinterface.sources.RgMode.ALBUMS_BY_TAGS, it0.album.ifEmpty { "this folder" })
                    }
                }) { Text("Scan the whole folder", color = accent()) }
                STextButton({ close() }) { Text("Not now") }
            }
        },
        confirmButton = {},
    )
}
