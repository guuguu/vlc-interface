package app.vlcinterface.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
                    is Screen.PickCue -> PickCueScreen(nav, top)
                    is Screen.PickMedia -> PickMediaScreen(nav, top)
                }
                if (showOutput) OutputSheet(onDismiss = { showOutput = false })
                // Failures of a finished ReplayGain scan pop up on whatever page is open.
                RgFailureReport(nav)
                ExitDialogHost()
                BatteryPromptHost()
            }
            BackHandler(enabled = true) {
                if (showOutput) showOutput = false else if (!nav.back()) onFinish()
            }
        }
    }
}
