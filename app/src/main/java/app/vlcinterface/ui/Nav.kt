package app.vlcinterface.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.vlcinterface.App
import app.vlcinterface.data.Source
import app.vlcinterface.data.Zone
import app.vlcinterface.sources.LocalFolder

sealed interface Screen {
    /** tab: 0 Internal, 1 LAN, 2 Internet; sub (Internet): 0 Storage, 1 Radio, 2 Streams */
    data class Library(val tab: Int = 0, val sub: Int = 0) : Screen
    data class Folder(val folder: LocalFolder, val highlight: String = "") : Screen
    data class Browse(val sourceId: String, val uri: String, val title: String, val highlight: String = "") : Screen
    data class EditSource(val zone: Zone, val sourceId: String? = null, val draft: Source? = null) : Screen
    data class SmbDetect(val input: String, val draft: Source) : Screen
    /** "What do I need to connect?" for a host name or IP. */
    data class ServerCheck(val host: String, val draft: Source = Source(zone = Zone.LAN)) : Screen
    data object Player : Screen
    data object Settings : Screen
    data object SoundSettings : Screen
    data object RgScan : Screen
    data object Backup : Screen
    data object Playlists : Screen
    data class PlaylistDetail(val id: String, val highlight: String = "") : Screen
    data object Queue : Screen
    data object Music : Screen
    data object Logs : Screen
    data class LogPage(val key: String) : Screen
    /** A log made of entries from all logs: "errors", "indexing", "lan" (servers). */
    data class LogView(val kind: String) : Screen
    /** Browse a saved source to pick a .cue file for a playlist. */
    data class PickCue(val playlistId: String, val sourceId: String, val uri: String) : Screen
    /** Choose files and folders (folders are added recursively) for a playlist. sourceId "" = start, "phone" = phone folders. */
    data class PickMedia(val playlistId: String, val sourceId: String = "", val uri: String = "", val title: String = "") : Screen
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Library())
    val top: Screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

@Composable
fun AppRoot(nav: Navigator, onFinish: () -> Unit) {
    val st by App.instance.store.state.collectAsState()
    val accent = Color(st.settings.accent)
    var showOutput by remember { mutableStateOf(false) }
    val openOutput = { showOutput = true }

    MaterialTheme(colorScheme = scheme(accent)) {
        CompositionLocalProvider(LocalAccent provides accent, LocalBtnScale provides st.settings.buttonScale / 100f) {
            val top = nav.top
            val isPlayer = top is Screen.Player
            // Auto-rotate setting: everywhere except the video player (which turns with its own button / the video).
            val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            LaunchedEffect(st.settings.autoRotate, isPlayer) { if (!isPlayer) act?.requestedOrientation = defaultOrientation() }
            // Audio or video player on screen: the phone screen stays on (so it doesn't dim and lock) for as long as
            // the player page is shown; any other page lets the screen time out as usual.
            val keepOn = isPlayer || top is Screen.Music
            // Also off as soon as the app goes to the background (and back on when it returns to the player).
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(keepOn, owner) {
                val w = act?.window
                fun apply(on: Boolean) {
                    ScreenOn.active.value = on
                    if (on) w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                    when (ev) {
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> apply(false)
                        androidx.lifecycle.Lifecycle.Event.ON_START -> apply(keepOn)
                        else -> {}
                    }
                }
                owner.lifecycle.addObserver(obs)
                apply(keepOn && owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                onDispose { owner.lifecycle.removeObserver(obs); apply(false) }
            }
            Box(
                Modifier.fillMaxSize().background(if (isPlayer) Color.Black else C.bg)
                    .then(if (isPlayer) Modifier else Modifier.safeDrawingPadding())
            ) {
                when (top) {
                    is Screen.Library -> LibraryScreen(nav, top, openOutput)
                    is Screen.Folder -> FolderScreen(nav, top.folder, top.highlight)
                    is Screen.Browse -> BrowseScreen(nav, top)
                    is Screen.EditSource -> EditSourceScreen(nav, top)
                    is Screen.SmbDetect -> SmbDetectScreen(nav, top)
                    is Screen.ServerCheck -> ServerCheckScreen(nav, top)
                    is Screen.Player -> PlayerScreen(nav, openOutput)
                    is Screen.Settings -> SettingsScreen(nav)
                    is Screen.SoundSettings -> SoundSettingsScreen(nav)
                    is Screen.RgScan -> RgScanScreen(nav)
                    is Screen.Backup -> BackupScreen(nav)
                    is Screen.Playlists -> PlaylistsScreen(nav, openOutput)
                    is Screen.PlaylistDetail -> PlaylistDetailScreen(nav, top)
                    is Screen.Queue -> QueueScreen(nav)
                    is Screen.Music -> MusicScreen(nav, openOutput)
                    is Screen.Logs -> LogsScreen(nav)
                    is Screen.LogPage -> LogPageScreen(nav, top.key)
                    is Screen.LogView -> LogViewScreen(nav, top.kind)
                    is Screen.PickCue -> PickCueScreen(nav, top)
                    is Screen.PickMedia -> PickMediaScreen(nav, top)
                }
                if (showOutput) OutputSheet(onDismiss = { showOutput = false })
                // Failures of a finished ReplayGain scan pop up on whatever page is open.
                RgFailureReport(nav)
                ExitDialogHost()
                BatteryPromptHost()
                SleepClosedHost()
                FailAskHost()
                RgAskHost()
            }
            BackHandler(enabled = true) {
                if (showOutput) showOutput = false else if (!nav.back()) onFinish()
            }
        }
    }
}


/** The app's orientation outside the video player: follows the phone, or stays upright when auto-rotate is off. */
fun defaultOrientation(): Int = if (App.instance.store.current.settings.autoRotate)
    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT


/** Is the "screen stays on" mode active right now (a player page shown, app in the foreground)? */
object ScreenOn {
    val active = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** Small sign on the players: the screen is being kept on (tap: what it means). */
@Composable
fun ScreenOnSign(tint: Color) {
    val on by ScreenOn.active.collectAsState()
    if (!on) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text("🔆", fontSize = 18.sp, color = tint, modifier = Modifier.clickable {
        android.widget.Toast.makeText(ctx, "Screen stays on while the player is shown (off in the background)", android.widget.Toast.LENGTH_SHORT).show()
    }.padding(horizontal = 6.dp))
}
