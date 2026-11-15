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
