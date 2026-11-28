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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import app.vlcinterface.App
import app.vlcinterface.data.Source
import app.vlcinterface.data.Zone
import app.vlcinterface.sources.LocalFolder

sealed interface Screen {
    /** tab: 0 Internal, 1 LAN, 2 Internet; sub (Internet): 0 Storage, 1 Radio, 2 Streams */
    data class Library(val tab: Int = 0, val sub: Int = 0) : Screen
    /** [video]: opened from the video player's Browse — videos and subtitle files shown, every place reachable. */
    data class Folder(val folder: LocalFolder, val highlight: String = "", val video: Boolean = false) : Screen
    data class Browse(val sourceId: String, val uri: String, val title: String, val highlight: String = "", val video: Boolean = false) : Screen
    data class EditSource(val zone: Zone, val sourceId: String? = null, val draft: Source? = null) : Screen
    data class SmbDetect(val input: String, val draft: Source) : Screen
    /** "What do I need to connect?" for a host name or IP. */
    data class ServerCheck(val host: String, val draft: Source = Source(zone = Zone.LAN)) : Screen
    data object Player : Screen
    data object Settings : Screen
    data object SoundSettings : Screen
    data class SettingsGroup(val id: String) : Screen
    data class Wizard(val page: Int = 0) : Screen
    data class Help(val chapter: String = "") : Screen
    data object MediaFolders : Screen
    data object RgScan : Screen
    data object Backup : Screen
    data object Playlists : Screen
    data class PlaylistDetail(val id: String, val highlight: String = "") : Screen
    data object Queue : Screen
    data object Music : Screen
    data class AlarmEdit(val id: String) : Screen
    data object AlarmSettings : Screen
    data object ClockAspect : Screen
    data object Visualisation : Screen
    data object ClockFull : Screen
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
    /** Back to the video player when it's in the stack (else one step back). */
    fun backToPlayer() {
        if (stack.none { it is Screen.Player }) { back(); return }
        while (stack.size > 1 && stack.last() !is Screen.Player) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun AppRoot(nav: Navigator, onFinish: () -> Unit) {
    val st by App.instance.store.state.collectAsState()
    UiPrefs.popupAlpha = (st.settings.popupOpacity / 100f).coerceIn(0.5f, 1f)     // never below 50 %: always visible
    UiPrefs.menuAlpha = (st.settings.menuOpacity / 100f).coerceIn(0.2f, 1f)
    UiPrefs.menuGapDp = st.settings.menuGap
    UiPrefs.listRowPct = st.settings.listRowPct
    UiPrefs.controlShadow = st.settings.controlShadow
    UiPrefs.menuFont = st.settings.menuFont
    UiPrefs.menuSp = st.settings.menuTextSp
    UiPrefs.menuFace = st.settings.menuFace
    UiPrefs.menuRowDp = st.settings.menuRowDp
    // Battery 15 % or less (not charging): every accent-coloured item turns bright red as a warning.
    val lowColour by app.vlcinterface.player.BatteryGuard.warnColour.collectAsState()
    UiPrefs.lowBattery = lowColour
    // A radio station playing: the radio colour (if one is set), else the files colour.
    val pFull = App.instance.player.state.collectAsState()
    val radioOn by remember { derivedStateOf { pFull.value.item?.isRadio == true } }
    val accent = if (lowColour) LOW_BATTERY_RED
        else Color(if (radioOn && st.settings.radioAccent != 0L) st.settings.radioAccent else st.settings.accent)
    var showOutput by remember { mutableStateOf(false) }
    val openOutput = { showOutput = true }

    MaterialTheme(colorScheme = scheme(accent)) {
        CompositionLocalProvider(LocalAccent provides accent, LocalBtnScale provides st.settings.buttonScale / 100f) {
            val top = nav.top
            val isPlayer = top is Screen.Player
            // Auto-rotate setting: everywhere except the video player (which turns with its own button / the video).
            val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            // The full-screen clock (2026-11-22 19:26) always turns with the phone (upright / sideways, either way round).
            val clockFull = top is Screen.ClockFull
            LaunchedEffect(st.settings.autoRotate, isPlayer, clockFull) {
                if (clockFull) act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                else if (!isPlayer) act?.requestedOrientation = defaultOrientation()
            }
            // Audio or video player on screen: the phone screen stays on (so it doesn't dim and lock) for as long as
            // the player page is shown; any other page lets the screen time out as usual.
            // Not at 10 % battery or less (unless charging).
            val lowBat by app.vlcinterface.player.BatteryGuard.low.collectAsState()
            val keepOn = ((isPlayer || top is Screen.Music) && !lowBat) || top is Screen.Visualisation || top is Screen.ClockFull   // visualisation / full-screen clock: always on
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
                Modifier.fillMaxSize().background(if (isPlayer || top is Screen.Visualisation || top is Screen.ClockFull) Color.Black else C.bg)
                    .then(if (isPlayer || top is Screen.Visualisation || top is Screen.ClockFull) Modifier else Modifier.safeDrawingPadding())
            ) {
                // Every page keeps its own scroll position (and other small state) while it stays in the back stack:
                // coming back shows the same area, not the top of the page.
                val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                val depth = nav.stack.size
                val pageKey = "$depth:$top"
                val kept = remember { HashSet<String>() }
                LaunchedEffect(depth) {
                    kept.filter { (it.substringBefore(':').toIntOrNull() ?: 0) > depth }.forEach { holder.removeState(it); kept.remove(it) }
                }
                kept += pageKey
                // Settings pages: red ✕ to the player in the header; sub-pages also go back to their parent page
                // with a swipe to the right or by pulling down further when already at the top.
                app.vlcinterface.data.CrashLog.screen = top.toString().take(120)
                val settingsPage = SettingsNav.isSettingsPage(top)
                val subPage = settingsPage && top !is Screen.Settings
                val dens = androidx.compose.ui.platform.LocalDensity.current
                val pullBack = remember(top) { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    var acc = 0f
                    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                                              source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                        if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0f) {
                            acc += available.y
                            if (acc > with(dens) { 140.dp.toPx() }) { acc = Float.NEGATIVE_INFINITY; SettingsNav.toParent(nav) }
                        } else if (consumed.y != 0f) acc = 0f
                        return androidx.compose.ui.geometry.Offset.Zero
                    }
                    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                        if (acc != Float.NEGATIVE_INFINITY) acc = 0f; return androidx.compose.ui.unit.Velocity.Zero
                    }
                } }
                CompositionLocalProvider(LocalSettingsClose provides settingsPage) {
                Box(Modifier.fillMaxSize().then(if (settingsPage || top is Screen.Visualisation || top is Screen.Logs || top is Screen.LogPage ||
                    top is Screen.LogView || top is Screen.AlarmEdit) Modifier.pageTouchGuard() else Modifier).then(if (!subPage) Modifier else Modifier
                    .nestedScroll(pullBack)
                    .pointerInput(top) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = { if (dx > 120.dp.toPx()) SettingsNav.toParent(nav) },
                        ) { ch, d -> dx += d; ch.consume() }
                    })) {
                holder.SaveableStateProvider(pageKey) {
                when (top) {
                    is Screen.Library -> LibraryScreen(nav, top, openOutput)
                    is Screen.Folder -> FolderScreen(nav, top.folder, top.highlight, top.video)
                    is Screen.Browse -> BrowseScreen(nav, top)
                    is Screen.EditSource -> EditSourceScreen(nav, top)
                    is Screen.SmbDetect -> SmbDetectScreen(nav, top)
                    is Screen.ServerCheck -> ServerCheckScreen(nav, top)
                    is Screen.Player -> PlayerScreen(nav, openOutput)
                    is Screen.Settings -> SettingsText { SettingsScreen(nav) }
                    is Screen.SoundSettings -> SettingsText { SoundSettingsScreen(nav) }
                    is Screen.SettingsGroup -> SettingsText { SettingsGroupScreen(nav, top.id) }
                    is Screen.Wizard -> SettingsText { WizardScreen(nav, top.page) }
                    is Screen.Help -> SettingsText { HelpScreen(nav, top.chapter) }
                    is Screen.MediaFolders -> SettingsText { MediaFoldersScreen(nav) }
                    is Screen.RgScan -> RgScanScreen(nav)
                    is Screen.Backup -> SettingsText { BackupScreen(nav) }
                    is Screen.Playlists -> PlaylistsScreen(nav, openOutput)
                    is Screen.PlaylistDetail -> PlaylistDetailScreen(nav, top)
                    is Screen.Queue -> QueueScreen(nav)
                    is Screen.Music -> MusicScreen(nav, openOutput)
                    is Screen.AlarmEdit -> SettingsText { AlarmEditScreen(nav, top.id) }
                    is Screen.AlarmSettings -> SettingsText { AlarmSettingsScreen(nav) }
                    is Screen.ClockAspect -> SettingsText { ClockAspectScreen(nav) }
                    is Screen.Visualisation -> SettingsText { VisualisationScreen(nav) }
                    is Screen.ClockFull -> ClockFullScreen(nav)
                    is Screen.Logs -> LogsScreen(nav)
                    is Screen.LogPage -> LogPageScreen(nav, top.key)
                    is Screen.LogView -> LogViewScreen(nav, top.kind)
                    is Screen.PickCue -> PickCueScreen(nav, top)
                    is Screen.PickMedia -> PickMediaScreen(nav, top)
                }
                }
                }
                }
                if (showOutput) OutputSheet(onDismiss = { showOutput = false })
                // Failures of a finished ReplayGain scan pop up on whatever page is open.
                RgFailureReport(nav)
                ExitDialogHost()
                BatteryPromptHost()
                SleepClosedHost()
                FailAskHost()
                RgAskHost()
                RgAutoSummaryHost()
                RgRecentAskHost()
                LowBatteryHost()
                WifiLostHost()
                MobileIdleHost()
                NasUseQuestionHost()
                NasLinkQuestionHost()
                NewVpnServerHost()
                NasScanNoteHost()
                SubnetQuestionHost()
                RadioMobileNoticeHost()
                SaveEndQuestionHost()
                ExitSavingHost()
                VpnQuestionsHost()
                OutputFallbackHost()
                FileDialogsHost(nav)   // windows from file menus (Tags & ReplayGain, ReplayGain scan, Add to playlist)
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
