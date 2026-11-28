package app.vlcinterface.ui

import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Settings
import app.vlcinterface.data.Store
import app.vlcinterface.data.ExportCat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings groups: id → name (the main page's links; their order is chosen in Look). */
val SETTINGS_GROUPS: List<Pair<String, String>> = listOf("audio" to "Audio", "playback" to "Playback & gestures", "video" to "Video",
    "radio" to "Radio", "look" to "Look", "library" to "Library & files", "network" to "Network & NAS", "vpn" to "VPN",
    "alarm" to "Alarm clock", "vis" to "Visualisation", "backup" to "Export / import", "logs" to "Logs", "folders" to "Find media folders", "assoc" to "File associations")

private val GROUP_HINTS = mapOf("audio" to "Start volume, preamp, ReplayGain, sound engine", "playback" to "Gestures, resume, background playback",
    "video" to "Decoding, deinterlacing, subtitles", "radio" to "Saving songs, radio buffer, free space", "look" to "Colours, sizes, menus, order of these links",
    "library" to "Scanning, album pictures, deleting files", "network" to "SMB1, Spotify links", "vpn" to "Home NAS through ZeroTier / Tailscale",
    "alarm" to "Alarms, ringing, wake check, clock aspect", "vis" to "Patterns, colours, frequencies", "backup" to "Your whole setup in one file",
    "logs" to "Errors, servers, indexing, crashes, caches", "folders" to "Search the phone, SD card and Downloads for music and videos",
    "assoc" to "Which media files and radio links open with this app")

/** The main Settings page's links, in the chosen order (new groups added at the end). */
fun orderedGroups(order: List<String>): List<Pair<String, String>> {
    val byId = SETTINGS_GROUPS.toMap()
    return (order.filter { it in byId } + SETTINGS_GROUPS.map { it.first }.filter { it !in order }).map { it to byId.getValue(it) }
}

/** A link to a settings page: name, what's in it, and a coloured triangle. */
@Composable
fun SettingsLink(name: String, hint: String, onClick: () -> Unit) {
    val ex = LocalExplain.current
    Row(Modifier.anchor(name).fillMaxWidth().heightIn(min = 58.dp).combinedClickable(onClick = onClick, onLongClick = { ex(name, hint) })
        .padding(horizontal = 16.dp, vertical = (8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
        }
        Text("▶", color = accent(), fontSize = SYM)
    }
}

fun openSettingsGroup(nav: Navigator, id: String) = when (id) {
    "alarm" -> nav.go(Screen.AlarmSettings)
    "backup" -> nav.go(Screen.Backup)
    "folders" -> nav.go(Screen.MediaFolders)
    else -> nav.go(Screen.SettingsGroup(id))
}

@Composable
fun SettingsScreen(nav: Navigator) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val s = st.settings
    fun set(n: Settings) = store.update { it.copy(settings = n) }
    val scroll = rememberScrollState()
    val ctxV = LocalContext.current
    val explainNow = LocalExplain.current
    Column(Modifier.fillMaxSize()) {
        // Exact version of this build, e.g. "vlc interface 0.3.0-build29 (29)".
        val version = remember {
            try { ctxV.packageManager.getPackageInfo(ctxV.packageName, 0).let { "vlc interface ${it.versionName} (${it.longVersionCode})" } }
            catch (_: Exception) { "vlc interface ${app.vlcinterface.App.VERSION}" }
        }
        BackHeader("Settings", nav, version)
        // Search every settings page (2026-11-22 07:19): each result opens its page at that setting.
        val q = SettingsSearch.query
        SmallSearchBar(q, { SettingsSearch.query = it }, "Search settings", count = if (q.isBlank()) 0 else SettingsSearch.results(q).size,
            modifier = Modifier.padding(horizontal = 20.dp), onClose = { SettingsSearch.query = "" })
        // The last 6 searches (07:26): a tap searches it again, ✕ removes it from the history.
        val past = SettingsSearch.history
        if (past.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            past.forEach { w ->
                Row(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(if (w == q.trim()) accent().copy(alpha = 0.25f) else C.card2),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(w, color = C.text, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.clickable { SettingsSearch.query = w }.padding(start = 10.dp, end = 4.dp))
                    Text("✕", color = C.muted, fontSize = 11.sp, modifier = Modifier.clickable { SettingsSearch.forget(w) }.padding(start = 2.dp, end = 9.dp))
                }
            }
        }
        Column(Modifier.weight(1f).anchorViewport(scroll, "main").scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            if (q.isNotBlank()) { SettingsSearchResults(nav, q); return@Column }
            CardBox {
                orderedGroups(s.settingsOrder).forEachIndexed { i, (id, name) ->
                    if (i > 0) Divider()
                    SettingsLink(name, if (id == "alarm" && !app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.systemOn) "Off — the alarm system is switched off"
                        else GROUP_HINTS[id] ?: "") { openSettingsGroup(nav, id) }
                }
            }
            // The whole alarm system on / off (big button).
            AlarmSystemButton()
            CardBox {
                SettingsLink("Launch the setup wizard", "Permissions and the important settings, page by page") { nav.go(Screen.Wizard()) }
            }
            // Text size of the settings pages, the wizard, the help guide and the helper pop-ups (only a slider).
            val helpersOnM = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.helpers
            var sizeHelp by remember { mutableStateOf(false) }
            CardBox {
                Column(Modifier.explain("Text size").padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Text size", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Text size").weight(1f))
                        Text("${s.settingsTextPct} %", color = accent(), fontWeight = FontWeight.SemiBold)
                    }
                    ThinSlider(s.settingsTextPct.toFloat(), { v -> set(s.copy(settingsTextPct = (Math.round(v / 5f) * 5).coerceIn(70, 160))) }, valueRange = 70f..160f,
                        onHoldStill = { if (helpersOnM && AutoHelp.ok("look", "sizeHelp")) sizeHelp = true })
                }
            }
            // Page titles size (18:05): one size for every page title in the app.
            CardBox {
                Column(Modifier.explain("Page titles size").padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Page titles size", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Page titles size").weight(1f))
                        Text("${s.pageTitlePct} %", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(pageTitlePct = 100)) }) { Text("Reset") }
                    }
                    ThinSlider(s.pageTitlePct.toFloat(), { v -> set(s.copy(pageTitlePct = (Math.round(v / 5f) * 5).coerceIn(60, 180))) }, valueRange = 60f..180f)
                }
            }
            var gapHelp by remember { mutableStateOf(false) }
            CardBox {
                Column(Modifier.explain("Vertical spacing").padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vertical spacing", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Vertical spacing").weight(1f))
                        Text("${s.settingsGapPct} %", color = accent(), fontWeight = FontWeight.SemiBold)
                    }
                    ThinSlider(s.settingsGapPct.toFloat(), { v -> set(s.copy(settingsGapPct = (Math.round(v / 5f) * 5).coerceIn(40, 160))) }, valueRange = 40f..160f,
                        onHoldStill = { if (helpersOnM && AutoHelp.ok("look", "gapHelp")) gapHelp = true })
                }
            }
            if (gapHelp) HelperDialog("Vertical spacing", SettingHelp.text("Vertical spacing", ""), extra = {
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card)) {
                    listOf("Sample setting" to "The line under it", "Another setting" to "Its explanation").forEachIndexed { i, (t, h) ->
                        if (i > 0) Divider()
                        Column(Modifier.fillMaxWidth().heightIn(min = (48 * s.settingsGapPct / 100f).dp).padding(horizontal = 14.dp, vertical = (5 * s.settingsGapPct / 100f).dp)) {
                            Text(t, color = C.text, fontSize = 15.sp); Text(h, color = C.muted, fontSize = 13.sp)
                        }
                    }
                }
            }) { gapHelp = false }
            if (sizeHelp) HelperDialog("Text size", SettingHelp.text("Text size", ""), extra = {
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card).padding(12.dp)) {
                    Text("Sample setting", color = C.text, modifier = Modifier.anchor("Sample setting"), fontSize = 15.sp)
                    Text("The line under a setting looks like this, at ${s.settingsTextPct} %.", color = C.muted, fontSize = 13.sp)
                }
            }) { sizeHelp = false }
            Text("Long-press any setting for an explanation", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Text("vlc interface ${App.VERSION} · playback by libVLC (LGPL)", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = (16 * sg()).dp))
        }
    }
}

/** One settings page (opened from the main Settings page, or by a player's settings symbol). */
@Composable
fun SettingsGroupScreen(nav: Navigator, id: String) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader(SETTINGS_GROUPS.toMap()[id] ?: "Settings", nav)
        Column(Modifier.weight(1f).anchorViewport(scroll, id).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            SettingsGroupBody(nav, id)
            Box(Modifier.height(24.dp))
        }
    }
}

/** The settings of one group (also shown on the setup wizard's pages). */
@Composable
fun ColumnScope.SettingsGroupBody(nav: Navigator, id: String) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val s = st.settings
    fun set(n: Settings) = store.update { it.copy(settings = n) }
    val ctxV = LocalContext.current
    val explainNow = LocalExplain.current
    var deleteFile by remember { mutableStateOf(false) }
    if (deleteFile) DeleteFileDialog { deleteFile = false }
            when (id) {
                "playback" -> {
            SectionTitle("Gestures")
            CardBox {
                ToggleRow("Volume swipe", "Right half, vertical", s.volumeSwipe) { set(s.copy(volumeSwipe = it)) }; Divider()
                ToggleRow("Brightness swipe", "Left half, vertical", s.brightnessSwipe) { set(s.copy(brightnessSwipe = it)) }; Divider()
                ValueRow("Double-tap seek", "Left / right edge", "${s.doubleTapSeekSec} s") {
                    val opts = listOf(5, 10, 15, 30); set(s.copy(doubleTapSeekSec = opts[(opts.indexOf(s.doubleTapSeekSec) + 1) % opts.size]))
                }
                // (Long press on the video now shows its details; the speed is chosen with the speed button.)
            }
            SectionTitle("Playback")
            CardBox {
                ToggleRow("Resume where I left off", "Videos only, per file — songs always start from the beginning", s.resume) { set(s.copy(resume = it)) }; Divider()
                ToggleRow("Keep audio in background", "Notification controls", s.backgroundAudio) { set(s.copy(backgroundAudio = it)) }; Divider()
                // Re-checked each time the page is shown (the answer is given in Android's own window).
                val bctx = androidx.compose.ui.platform.LocalContext.current
                val free = Battery.unrestricted(bctx)
                ValueRow("Background playback", if (free) "Battery: Unrestricted — playlists go on while you use other apps"
                    else "Battery saver may stop playback between songs in the background", if (free) "Unrestricted ✓" else "Allow") { Battery.ask(bctx) }
            }
                }
                "audio" -> {
            SoundSettings(s, ::set)
                }
                "video" -> {
            SectionTitle("Horizontal autoscroll")
            ScrollSpeedCard("Video lists autoscroll", "Long video names in the phone and network folder lists", "videos")
            // On-screen buttons size (2026-11-21 17:13): the video player's buttons (side columns sideways, under the
            // picture upright) — bigger or smaller together, always against the edges and touching each other.
            SectionTitle("On-screen buttons")
            var vidHelp by remember { mutableStateOf("") }
            val helpersV = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.helpers
            CardBox {
                Column(Modifier.explain("On screen buttons size").padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("On screen buttons size", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("On screen buttons size").weight(1f))
                        Text("${s.videoBtnPct} %", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(videoBtnPct = 100)) }) { Text("Reset") }
                    }
                    ThinSlider(s.videoBtnPct.toFloat(), { v -> set(s.copy(videoBtnPct = (Math.round(v / 5f) * 5).coerceIn(60, 160))) }, valueRange = 60f..160f)
                    Text("The video player's buttons; never larger than the screen allows (they stay apart from each other and inside the edges).",
                        color = C.muted, fontSize = 12.sp)
                }
                Divider()
                // Clock on the video picture (2026-11-22 13:23): time only, top-left, always while a video plays.
                ToggleRow("Clock on the video", "The time in the top-left corner of the picture", s.videoClock) { v ->
                    set(s.copy(videoClock = v)); if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" }
                if (s.videoClock) Column(Modifier.padding(horizontal = 16.dp, vertical = (6 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clock size", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Clock size").weight(1f).explain("Clock on the video"))
                        Text("${s.videoClockPct} %", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(videoClockPct = 100)) }) { Text("Reset") }
                    }
                    ThinSlider(s.videoClockPct.toFloat(), { v -> set(s.copy(videoClockPct = (Math.round(v / 10f) * 10).coerceIn(50, 250))) }, valueRange = 50f..250f,
                        onHoldStill = { if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clock opacity", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Clock opacity").weight(1f).explain("Clock on the video"))
                        Text("${s.videoClockAlpha} %", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(videoClockAlpha = 80)) }) { Text("Reset") }
                    }
                    ThinSlider(s.videoClockAlpha.toFloat(), { v -> set(s.copy(videoClockAlpha = (Math.round(v / 5f) * 5).coerceIn(20, 100))) }, valueRange = 20f..100f,
                        onHoldStill = { if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" })
                    // 13:25: any corner, the digits' colour (full colour editor), a grey shadow.
                    Text("Clock corner", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Clock corner").padding(top = 6.dp).explain("Clock on the video"))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("tl" to "↖ Top left", "tr" to "↗ Top right", "bl" to "↙ Bottom left", "br" to "↘ Bottom right").forEach { (k, l) ->
                            Pill(l, selected = s.videoClockCorner == k, filled = s.videoClockCorner == k) { set(s.copy(videoClockCorner = k)); if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" }
                        }
                    }
                    var clockColour by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Digits colour", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Digits colour").weight(1f).explain("Clock on the video"))
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(s.videoClockColor)).border(1.dp, C.muted, CircleShape))
                        Text("+ Create", color = WIZ_GREEN, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, WIZ_GREEN, RoundedCornerShape(10.dp))
                                .clickable { clockColour = true }.padding(horizontal = 10.dp, vertical = (4 * sg()).dp))
                        STextButton({ set(s.copy(videoClockColor = 0xFFFFFFFFL)) }) { Text("White") }
                    }
                    if (clockColour) ColorPickDialog(s.videoClockColor, onDismiss = { clockColour = false }) { c ->
                        set(s.copy(videoClockColor = c)); clockColour = false; if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" }
                }
                if (s.videoClock) ToggleRow("Clock shadow", "A grey shadow under the digits", s.videoClockShadow) { v ->
                    set(s.copy(videoClockShadow = v)); if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" }
                if (s.videoClock && s.videoClockShadow) Column(Modifier.padding(horizontal = 16.dp, vertical = (6 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Shadow intensity", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Shadow intensity").weight(1f).explain("Clock on the video"))
                        Text("${s.videoClockShadowPct} %", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(videoClockShadowPct = 70)) }) { Text("Reset") }
                    }
                    ThinSlider(s.videoClockShadowPct.toFloat(), { v -> set(s.copy(videoClockShadowPct = (Math.round(v / 5f) * 5).coerceIn(10, 100))) }, valueRange = 10f..100f,
                        onHoldStill = { if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Shadow width", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Shadow width").weight(1f).explain("Clock on the video"))
                        Text("${s.videoClockShadowW} dp", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(videoClockShadowW = 3)) }) { Text("Reset") }
                    }
                    ThinSlider(s.videoClockShadowW.toFloat(), { v -> set(s.copy(videoClockShadowW = Math.round(v).coerceIn(1, 12))) }, valueRange = 1f..12f, steps = 10,
                        onHoldStill = { if (helpersV && AutoHelp.ok("video", "Clock on the video")) vidHelp = "Clock on the video" })
                }
            }
            if (vidHelp.isNotEmpty()) HelperDialog(vidHelp, SettingHelp.text(vidHelp, "")) { vidHelp = "" }
            SectionTitle("Picture")
            run {
                var vHelp by remember { mutableStateOf("") }
                if (vHelp.isNotEmpty()) HelperDialog(vHelp, SettingHelp.text(vHelp, "")) { vHelp = "" }
                // What the playing video uses right now (Automatic chooses it per video).
                App.instance.player.state.collectAsState().value
                val now = App.instance.player.videoNow()
                val enc = now?.let { n -> listOf(n.codec.uppercase(), if (n.w > 0) "${n.w}×${n.h}" else "").filter { it.isNotEmpty() }.joinToString(" ") }.orEmpty()
                @Composable fun NowLine(text: String) = Text(text, color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                // A video is playing: the choice is for that video file only (kept with it); otherwise it's the default for all.
                val vItem = App.instance.player.state.value.item?.takeIf { !it.isMusic && !it.isRadio }
                val fileDec = vItem?.let { app.vlcinterface.player.VideoAuto.manualDecoder(it.uri) }.orEmpty()
                val fileDi = vItem?.let { app.vlcinterface.player.VideoAuto.manualDeinterlace(it.uri) }.orEmpty()
                val decSel = fileDec.ifEmpty { s.videoDecoder }
                val diSel = fileDi.ifEmpty { s.videoDeinterlace }
                var refresh by remember { mutableStateOf(0) }; refresh
                fun named(v: String) = when (v) { "hw" -> "Hardware"; "sw" -> "Software"; "on" -> "On"; "off" -> "Off"; else -> "Automatic" }
                fun pick(k: String, v: String, helpKey: String) {
                    if (vItem != null) {
                        app.vlcinterface.player.VideoAuto.setStr(vItem.uri, k, v)
                        // Hardware / Software chosen by hand on more than 5 videos: it becomes the default for new videos.
                        if (k == "dec" && (v == "hw" || v == "sw") && s.videoDecoder != v) {
                            val n = app.vlcinterface.player.VideoAuto.files().count { app.vlcinterface.player.VideoAuto.manualDecoder(it) == v }
                            if (n > 5) { set(s.copy(videoDecoder = v)); ShortNote.show("${named(v)} chosen for $n videos: now the default for new videos") }
                        }
                    } else set(if (k == "dec") s.copy(videoDecoder = v) else s.copy(videoDeinterlace = v))
                    refresh++
                    App.instance.player.reloadVideo()       // the playing video (if any) reopens at the same spot with it
                    if (AutoHelp.ok("video", helpKey)) vHelp = helpKey
                }
                val forWhat = if (vItem != null) "For this video (kept with the file)" else "Default for all videos"
                CardBox {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                        Text("Video decoding", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Video decoding").explain("Video decoding"))
                        Text("$forWhat · Automatic: chosen from the video's encoding", color = C.muted, fontSize = 13.sp)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                            listOf("auto" to "Automatic", "hw" to "Hardware", "sw" to "Software").forEach { (m, l) ->
                                Pill(l, selected = decSel == m, filled = decSel == m) { if (decSel != m || (vItem != null && fileDec.isEmpty())) pick("dec", m, "Video decoding") }
                            }
                        }
                        NowLine(if (now == null) "Now: no video playing" else "Now: " + (if (now.software) "Software" else "Hardware") +
                            (if (decSel == "auto") " (chosen automatically)" else "") + (if (enc.isNotEmpty()) " · $enc" else ""))
                        if (vItem != null) Text("Default for other videos: " + named(s.videoDecoder), color = C.muted, fontSize = 12.sp)
                    }
                    Divider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                        Text("Deinterlacing", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Deinterlacing").explain("Deinterlacing"))
                        Text("$forWhat · Automatic: on for TV recordings and DVDs", color = C.muted, fontSize = 13.sp)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                            listOf("auto" to "Automatic", "on" to "On", "off" to "Off").forEach { (m, l) ->
                                Pill(l, selected = diSel == m, filled = diSel == m) { if (diSel != m || (vItem != null && fileDi.isEmpty())) pick("di", m, "Deinterlacing") }
                            }
                        }
                        NowLine(if (now == null) "Now: no video playing" else "Now: " + (if (now.deinterlace) "On" else "Off") +
                            (if (diSel == "auto") " (chosen automatically)" else "") + (if (enc.isNotEmpty()) " · $enc" else ""))
                        if (vItem != null) Text("Default for other videos: " + named(s.videoDeinterlace), color = C.muted, fontSize = 12.sp)
                    }
                }
            }
            // Subtitle format (2026-11-21 16:21): look of the subtitles, with a preview shaped like the video.
            SectionTitle("Subtitle format")
            CardBox { SubtitleFormatSettings() }
            SectionTitle("Subtitles")
            CardBox {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                    Text("Preferred languages", color = C.muted, fontSize = 13.sp, modifier = Modifier.explain("Preferred languages"))
                    Field("Preferred languages (e.g. en, es)", s.subLangs.joinToString(", ")) { v ->
                        set(s.copy(subLangs = v.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }))
                    }
                }
                ToggleRow("Auto-download missing subtitles", "When a movie has none in your languages", s.autoDownloadSubs) { set(s.copy(autoDownloadSubs = it)) }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                    // Helper: how to get the account, the API key and what username / password are for.
                    var osHelp by remember { mutableStateOf(false) }
                    if (osHelp) HelperDialog("OpenSubtitles account", SettingHelp.text("OpenSubtitles account", "")) { osHelp = false }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("OpenSubtitles.com account (free). Create an API key under your profile → API consumers.", color = C.muted, fontSize = 13.sp,
                            modifier = Modifier.weight(1f).explain("OpenSubtitles account"))
                        Text("How?", color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { osHelp = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    Field("API key", s.osApiKey, password = true) { set(s.copy(osApiKey = it.trim())) }
                    Field("Username", s.osUser) { set(s.copy(osUser = it.trim())) }
                    Field("Password", s.osPassword, password = true) { set(s.copy(osPassword = it)) }
                }
            }
            SectionTitle("Rotation")
            CardBox {
                ToggleRow("Auto-rotate", "On: the app turns with the phone · Off: stays upright (videos still turn with the rotate button)", s.autoRotate) { set(s.copy(autoRotate = it)) }
            }
                }
                "radio" -> {
            SectionTitle("Horizontal autoscroll")
            ScrollSpeedCard("Favourite stations autoscroll", "Long station names in the favourites (radio player and Library → Radio)", "radioFav")
            ScrollSpeedCard("Find stations autoscroll", "Long station names in the search results and station lists", "radioFind")
            // Radio songs saved from the player (station menu → Save this song).
            SectionTitle("Saved songs")
            CardBox {
                val sctx = LocalContext.current
                val pickSave = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) try {
                        sctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        set(s.copy(saveFolder = uri.toString()))
                    } catch (_: Exception) {}
                }
                // Saving root: raw/ and mp3/ are made inside it. Default: the root of the phone music library.
                var root by remember { mutableStateOf("") }
                LaunchedEffect(Unit) { root = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.vlcinterface.sources.RadioCapture.libraryRoot(sctx).second } }
                val chosen = s.saveFolder.isNotEmpty()
                val rootShown = (if (chosen) android.net.Uri.decode(s.saveFolder).substringAfterLast(':').ifEmpty { "Chosen folder" } else root.ifEmpty { "…" }).trimEnd('/') + "/"
                ValueRow("Saving root: $rootShown",
                    "Saved radio songs go into two folders here: ${rootShown}raw (as the station sends them) and ${rootShown}mp3 (re-encoded, LAME V0). " +
                        if (chosen) "Chosen by you." else "Now the root of your phone music library, found from where its songs are.",
                    "Change") { pickSave.launch(null) }
                if (chosen) { Divider(); ValueRow("Use the library root again", "Back to ${root.ifEmpty { "the library root" }.trimEnd('/')}/", "Reset") { set(s.copy(saveFolder = "")) } }
                Divider()
                ValueRow("Format", if (s.saveFormat == "original") "As the station sends it (MP3 as is, AAC as .m4a)" else "Re-encoded, best VBR quality",
                    if (s.saveFormat == "original") "Original" else "MP3 V0") { set(s.copy(saveFormat = if (s.saveFormat == "original") "mp3" else "original")) }
                Divider()
                ValueRow("Keep radio ready to save", "Records the playing station (a second connection: double data). " + when (s.radioCapture) {
                    "always" -> "Any network, mobile data included: never interrupted, dropped or deleted when the network changes"
                    "off" -> "Off: songs can't be saved"
                    else -> "Wi-Fi only: when Wi-Fi goes, kept if it's back within the radio buffer (${s.radioBufferMin} min), else deleted"
                },
                    when (s.radioCapture) { "always" -> "Always"; "off" -> "Off"; else -> "Wi-Fi only" }) {
                    set(s.copy(radioCapture = when (s.radioCapture) { "wifi" -> "always"; "always" -> "off"; else -> "wifi" }))
                }
                Divider()
                // Radio buffer: the last N minutes kept for saving, and how long a lost connection may take to come back.
                Column(Modifier.explain("Radio buffer").padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Text("Radio buffer: ${s.radioBufferMin} min", color = C.text, fontSize = 15.sp)
                    Text("The last ${s.radioBufferMin} min are kept for saving (longer while a song is being saved); a lost connection back within ${s.radioBufferMin} min keeps the recording",
                        color = C.muted, fontSize = 13.sp)
                    var v by remember { mutableStateOf(s.radioBufferMin.toFloat()) }
                    ThinSlider(v, { v = it }, valueRange = 1f..20f, steps = 18,
                        onValueChangeFinished = { set(s.copy(radioBufferMin = Math.round(v).coerceIn(1, 20))) })
                }
                Divider()
                // Free space: under X GB free, recordings may use Y % of it (every decision is said and logged).
                ValueRow("Free space limit", (if (s.spaceUnderGb == 0) "Off: recordings use any free space"
                    else "When under ${s.spaceUnderGb} GB free, recordings use at most ${s.spacePercent} % of it") + " · never any recording under 2 GB free",
                    if (s.spaceUnderGb == 0) "Off" else "< ${s.spaceUnderGb} GB") {
                    val opts = listOf(100, 200, 50, 25, 10, 0); set(s.copy(spaceUnderGb = opts[(opts.indexOf(s.spaceUnderGb) + 1).mod(opts.size)]))
                }
                if (s.spaceUnderGb > 0) {
                    Divider()
                    ValueRow("Share of free space", "Recordings (kept minutes and saves) may use", "${s.spacePercent} %") {
                        val opts = listOf(30, 40, 50, 60, 70, 80, 10, 20); set(s.copy(spacePercent = opts[(opts.indexOf(s.spacePercent) + 1).mod(opts.size)]))
                    }
                }
                Divider()
                var savedList by remember { mutableStateOf(false) }
                if (savedList) SavedSongsDialog { savedList = false }
                ValueRow("Saved songs", "Play or delete the songs saved from the radio", "Open") { savedList = true }
                Divider()
                ValueRow("Saved songs log", "Each song saved, or why not", "Open") { nav.go(Screen.LogPage("saved-songs")) }
                Divider()
                ValueRow("MP3 encoder", "LAME ${app.vlcinterface.sources.Mp3Encoder.version} — LGPL, bundled unchanged as libmp3lame.so (lame.sourceforge.io)", "") {}
            }
                }
                "look" -> {
            SectionTitle("Look")
            CardBox {
                ToggleRow("Auto-rotate", "On: the app turns with the phone · Off: stays upright (videos still turn with the rotate button)", s.autoRotate) { set(s.copy(autoRotate = it)) }
                Divider()
                // All long lists: the bar on the right; drag it (or touch the right edge) to jump anywhere fast.
                Column(Modifier.explain("Scroll bar width").padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Text("Scroll bar width", color = C.text, modifier = Modifier.anchor("Scroll bar width"), fontSize = 15.sp)
                    Text("All lists: drag the bar on the right edge to scroll fast", color = C.muted, fontSize = 13.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                        (4..9).forEach { px -> Pill("$px px", selected = s.scrollbarPx == px) { set(s.copy(scrollbarPx = px)) } }
                    }
                }
            }
            CardBox {
                Column(Modifier.explain("Button size").padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Button size", color = C.text, modifier = Modifier.anchor("Button size"), fontSize = 15.sp)
                            Text("Every symbol button and its tap area, everywhere in the app", color = C.muted, fontSize = 13.sp)
                        }
                        Text("${s.buttonScale}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(buttonScale = 75)) }) { Text("Reset") }
                    }
                    ThinSlider(s.buttonScale.toFloat(), { v -> set(s.copy(buttonScale = (Math.round(v / 5f) * 5).coerceIn(50, 125))) }, valueRange = 50f..125f)
                    Text("75 % = 25 % smaller than standard (default) · 100 % = standard Android size", color = C.muted, fontSize = 13.sp)
                }
            }
            ScrollSpeedCard("Up next autoscroll", "Long names in the players' Up next lists and the queue", "upnext")
            ScrollSpeedCard("Player titles autoscroll", "Long song, station and video titles on the players (one line)", "player")
            CardBox {
                Row(Modifier.explain("Up next songs on the player").padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Up next songs on the player", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Up next songs on the player").weight(1f))
                    Pill("−") { set(s.copy(upNextCount = (s.upNextCount - 1).coerceAtLeast(0))) }
                    Text("${s.upNextCount}", color = accent(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(40.dp))
                    Pill("+") { set(s.copy(upNextCount = (s.upNextCount + 1).coerceAtMost(20))) }
                }
            }
            CardBox {
                Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pop-up opacity", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Pop-up opacity").weight(1f).explain("Pop-up opacity"))
                        Text("${s.popupOpacity}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(popupOpacity = 90)) }) { Text("Reset") }
                    }
                    OpacityPreview(s.popupOpacity / 100f, "Pop-up window", 15)
                    ThinSlider(s.popupOpacity.toFloat(), { v -> set(s.copy(popupOpacity = (Math.round(v / 5f) * 5).coerceIn(50, 100))) }, valueRange = 50f..100f)
                    // Floating play / pause on the players (shown when their buttons are scrolled away).
                    var floatHelp by remember { mutableStateOf(false) }
                    if (floatHelp) HelperDialog("Floating play / pause button", SettingHelp.text("Floating play / pause button", "")) { floatHelp = false }
                    Text("Floating play / pause button", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Floating play / pause button").padding(top = 8.dp).explain("Floating play / pause button"))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                        listOf("always" to "Always", "scroll" to "When the buttons scroll away", "off" to "Off").forEach { (m, l) ->
                            Pill(l, selected = s.floatBtnMode == m) {
                                set(s.copy(floatBtnMode = m))
                                if (AutoHelp.ok("look", "floatHelp")) floatHelp = true
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Floating button size", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Floating button size").weight(1f).explain("Floating button size"))
                        Text("${s.floatBtnSize} dp", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(floatBtnSize = 64)) }) { Text("Reset") }
                    }
                    ThinSlider(s.floatBtnSize.toFloat(), { v -> set(s.copy(floatBtnSize = (Math.round(v / 4f) * 4).coerceIn(40, 112))) }, valueRange = 40f..112f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Floating button transparency", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Floating button transparency").weight(1f).explain("Floating button transparency"))
                        Text("${100 - s.floatBtnAlpha}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(floatBtnAlpha = 85)) }) { Text("Reset") }
                    }
                    ThinSlider((100 - s.floatBtnAlpha).toFloat(), { v -> set(s.copy(floatBtnAlpha = 100 - (Math.round(v / 5f) * 5).coerceIn(0, 70))) }, valueRange = 0f..70f)
                    // Helpers (Settings → Alarm clock → Helpers): explanation, with a preview where useful, after a change.
                    val helpersOn = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.helpers
                    var lookHelp by remember { mutableStateOf("") }
                    // Alarm rows under the clock: height (50 % = half the old rows). Hidden while the alarm system is off.
                    val alarmSysOn = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.systemOn
                    if (alarmSysOn) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Alarm rows under the clock", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Alarm rows under the clock").weight(1f).explain("Alarm rows under the clock"))
                        Text("${s.alarmRowPct}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(alarmRowPct = 50)) }) { Text("Reset") }
                    }
                    if (alarmSysOn) ThinSlider(s.alarmRowPct.toFloat(), { v -> set(s.copy(alarmRowPct = (Math.round(v / 5f) * 5).coerceIn(50, 100))) }, valueRange = 50f..100f,
                        onHoldStill = { if (helpersOn && AutoHelp.ok("look", "Alarm rows under the clock")) lookHelp = "Alarm rows under the clock" })
                    // Order of the player rows, songs and radio apart.
                    // Songs / Radio / Both ("Both": one order for the rows the two players share).
                    var rowsMode by rememberKept("look.rowsMode", "song")
                    Text("Order of the player rows", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Order of the player rows").padding(top = 10.dp).explain("Order of the player rows"))
                    Row(horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                        listOf("song" to "Songs", "radio" to "Radio", "video" to "Video", "both" to "All").forEach { (m, l) -> Pill(l, selected = rowsMode == m, filled = rowsMode == m) { rowsMode = m } }
                    }
                    if (rowsMode == "video") Text("Video player upright: the rows under the picture", color = C.muted, fontSize = 13.sp)
                    if (rowsMode == "both") Text("Songs, Radio and Video: the video player's Title, Play buttons and Up next follow the same order", color = C.muted, fontSize = 13.sp)
                    val rowRadio = rowsMode == "radio"
                    val rowAll = if (rowsMode == "video") videoRowOrder(s) else playerRowOrderAll(s, rowRadio)
                    fun saveRows(l: List<String>) {
                        set(when (rowsMode) {
                            "video" -> s.copy(rowOrderVideo = l)
                            "radio" -> s.copy(rowOrderRadio = l)
                            // All (07:46 / 07:47): songs, radio and the video player — the video rows that match (title, play
                            // buttons, Up next) take the same order among themselves; its other rows keep their places.
                            "both" -> s.copy(rowOrderSong = l, rowOrderRadio = l.filter { it in PLAYER_RADIO_ROWS },
                                rowOrderVideo = if (l.isEmpty()) emptyList() else videoRowsLike(l, videoRowOrder(s)))
                            else -> s.copy(rowOrderSong = l)
                        })
                        if (helpersOn && AutoHelp.ok("look", "Order of the player rows")) lookHelp = "Order of the player rows"
                    }
                    // Drag (≡ or long press) to reorder (11:28). A list only for ordering has no ✕ (11:32).
                    DragOrderList(rowAll, { (if (rowsMode == "video") VIDEO_ROW_NAMES[it] else PLAYER_ROW_NAMES[it]) ?: it }, onOrder = { l -> saveRows(l) })
                    STextButton({ saveRows(emptyList()) }) { Text("Reset order", color = accent()) }
                    // Gap between the player's rows.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Player rows spacing", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Player rows spacing").weight(1f).explain("Player rows spacing"))
                        Text("${s.playerGap} dp", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(playerGap = 4)) }) { Text("Reset") }
                    }
                    ThinSlider(s.playerGap.toFloat(), { v -> set(s.copy(playerGap = Math.round(v).coerceIn(0, 16))) }, valueRange = 0f..16f, steps = 15,
                        onHoldStill = { if (helpersOn && AutoHelp.ok("look", "Player rows spacing")) lookHelp = "Player rows spacing" })
                    // One spacing for every list and queue (2026-11-22 09:12).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("List row spacing", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("List row spacing").weight(1f).explain("List row spacing"))
                        Text("${s.listRowPct} %", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(listRowPct = 70)) }) { Text("Reset") }
                    }
                    ThinSlider(s.listRowPct.toFloat(), { v -> set(s.copy(listRowPct = (Math.round(v / 5f) * 5).coerceIn(5, 160))) }, valueRange = 5f..160f,
                        onHoldStill = { if (helpersOn && AutoHelp.ok("look", "List row spacing")) lookHelp = "List row spacing" })
                    Text("Every list and queue: queue, Up next (songs and videos), playlists, folders, Songs, Videos, radio stations", color = C.muted, fontSize = 12.sp)
                    // Upright player: the clock / album picture hide while scrolling (on) or not (off: clock pinned), and for how long.
                    // Clock / Picture / Both ("Both": one switch and one time for the two).
                    var hideMode by rememberKept("look.hideMode", "clock")
                    Text("Hiding while scrolling", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Hiding while scrolling").padding(top = 10.dp).explain("Hide the clock while scrolling"))
                    Row(horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                        listOf("clock" to "Clock", "cover" to "Picture", "both" to "All").forEach { (m, l) -> Pill(l, selected = hideMode == m, filled = hideMode == m) { hideMode = m } }
                    }
                    val hideOn = when (hideMode) { "clock" -> s.clockAutoHide; "cover" -> s.coverAutoHide; else -> s.clockAutoHide && s.coverAutoHide }
                    val hideSecV = when (hideMode) { "cover" -> s.coverHideSec; else -> s.clockHideSec }
                    val hideLabel = when (hideMode) { "clock" -> "Hide the clock while scrolling"; "cover" -> "Hide the album picture while scrolling"; else -> "Hide the clock and picture while scrolling" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(hideLabel, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f).explain(if (hideMode == "cover") "Hide the album picture while scrolling" else "Hide the clock while scrolling"))
                        AppSwitch(hideOn, { v ->
                            set(when (hideMode) { "clock" -> s.copy(clockAutoHide = v); "cover" -> s.copy(coverAutoHide = v); else -> s.copy(clockAutoHide = v, coverAutoHide = v) })
                            if (helpersOn && AutoHelp.ok("look", "hide-" + hideMode)) lookHelp = if (hideMode == "cover") "Hide the album picture while scrolling" else "Hide the clock while scrolling" })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val tl = when (hideMode) { "clock" -> "Clock hidden for"; "cover" -> "Cover hidden for"; else -> "Clock and picture hidden for" }
                        Text(tl, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f).explain(if (hideMode == "cover") "Cover hidden for" else "Clock hidden for"))
                        Text("$hideSecV s", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(when (hideMode) { "clock" -> s.copy(clockHideSec = 10); "cover" -> s.copy(coverHideSec = 10); else -> s.copy(clockHideSec = 10, coverHideSec = 10) }) }) { Text("Reset") }
                    }
                    ThinSlider(hideSecV.toFloat(), { v -> val n = Math.round(v).coerceIn(3, 60)
                        set(when (hideMode) { "clock" -> s.copy(clockHideSec = n); "cover" -> s.copy(coverHideSec = n); else -> s.copy(clockHideSec = n, coverHideSec = n) }) },
                        valueRange = 3f..60f, onHoldStill = { if (helpersOn && AutoHelp.ok("look", "hide-" + hideMode)) lookHelp = if (hideMode == "cover") "Cover hidden for" else "Clock hidden for" })
                    // Short, self-closing pop-ups: how long they stay.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Short pop-ups stay for", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Short pop-ups stay for").weight(1f).explain("Short pop-ups stay for"))
                        Text("${s.shortPopupSec} s", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(shortPopupSec = 3)) }) { Text("Reset") }
                    }
                    ThinSlider(s.shortPopupSec.toFloat(), { v -> set(s.copy(shortPopupSec = Math.round(v).coerceIn(1, 10))) }, valueRange = 1f..10f, steps = 8,
                        onHoldStill = { if (helpersOn && AutoHelp.ok("look", "Short pop-ups stay for")) lookHelp = "Short pop-ups stay for" })
                    // Switch colours: only the current colour and a colour creation button for each.
                    var swEdit by remember { mutableStateOf("") }
                    val accArgb = accent().toArgb().toLong() and 0xFFFFFFFFL
                    // On / Off / Both ("Both": the same colour for the two positions).
                    var swMode by rememberKept("look.swMode", "on")
                    Text("Switch colour", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Switch colour").padding(top = 10.dp).explain("Switch colour"))
                    Row(horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("on" to "On", "off" to "Off", "both" to "All").forEach { (m, l) -> Pill(l, selected = swMode == m, filled = swMode == m) { swMode = m } }
                        Box(Modifier.weight(1f))
                        val cur = if (swMode == "off") s.switchOff else (if (s.switchOn != 0L) s.switchOn else accArgb)
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(cur)))
                        Text("+ Create", color = WIZ_GREEN, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, WIZ_GREEN, RoundedCornerShape(10.dp))
                                .clickable { swEdit = swMode }.padding(horizontal = 10.dp, vertical = (4 * sg()).dp))
                    }
                    // Floating play / pause: its own colour.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Floating button colour", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Floating button colour").weight(1f).explain("Floating button colour"))
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(if (s.floatBtnColor != 0L) s.floatBtnColor else accArgb)))
                        Text("+ Create", color = WIZ_GREEN, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, WIZ_GREEN, RoundedCornerShape(10.dp))
                                .clickable { swEdit = "float" }.padding(horizontal = 10.dp, vertical = (4 * sg()).dp))
                    }
                    if (swEdit.isNotEmpty()) ColorPickDialog(when (swEdit) { "on" -> if (s.switchOn != 0L) s.switchOn else accArgb
                        "float" -> if (s.floatBtnColor != 0L) s.floatBtnColor else accArgb; else -> s.switchOff },
                        onDismiss = { swEdit = "" }) { c ->
                        set(when (swEdit) { "on" -> s.copy(switchOn = c); "float" -> s.copy(floatBtnColor = c); "both" -> s.copy(switchOn = c, switchOff = c); else -> s.copy(switchOff = c) })
                        val l = when (swEdit) { "float" -> "Floating button colour"; else -> "Switch colour" }
                        swEdit = ""; if (helpersOn && AutoHelp.ok("look", l)) lookHelp = l
                    }
                    if (lookHelp.isNotEmpty()) HelperDialog(lookHelp, SettingHelp.text(lookHelp, ""), extra = {
                        when {
                            lookHelp.startsWith("Alarm rows") -> Column(Modifier.padding(top = 12.dp)) {
                                Text("Preview · ${s.alarmRowPct} %", color = C.muted, fontSize = 13.sp)
                                val k = s.alarmRowPct / 100f
                                listOf(Triple("07:30", "Work · Mon–Fri", true), Triple("09:00", "Sat", false)).forEach { (t, w, on) ->
                                    Row(Modifier.padding(top = 4.dp).fillMaxWidth().height((56 * k).dp).clip(RoundedCornerShape(8.dp)).background(Color(0x14FFFFFF))
                                        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(t, color = if (on) C.text else C.muted, fontSize = (12 + 8 * (k - 0.5f) * 2).sp, fontWeight = FontWeight.SemiBold)
                                        Text(w, color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1)
                                        WideSwitch(on, height0 = (14 + 12 * (k - 0.5f) * 2).dp) {}
                                    }
                                }
                            }
                            lookHelp.startsWith("Player rows spacing") -> Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF16181C)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(s.playerGap.dp)) {
                                Text("Song title", color = C.text, modifier = Modifier.anchor("Song title"), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(accent()).padding(horizontal = 10.dp, vertical = (2 * sg()).dp)) {
                                    Text("NAS active", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                Text("Track 7 · RG track −10.79 dB", color = accent(), fontSize = 11.sp)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(0.5f).height(3.dp).background(accent())); Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                                    Box(Modifier.weight(0.5f).height(3.dp).background(C.line)) }
                                Row(Modifier.fillMaxWidth()) { Text("1:50", color = C.muted, fontSize = 10.sp); Box(Modifier.weight(1f)); Text("-1:29", color = C.muted, fontSize = 10.sp) }
                                Text("⏮   ▶   ⏭", color = C.text, fontSize = 16.sp)
                            }
                            lookHelp.startsWith("Order of the player rows") -> Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy((12 * sg()).dp)) {
                                listOf(false to "Songs", true to "Radio").forEach { (r, t) ->
                                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16181C)).padding(6.dp),
                                        verticalArrangement = Arrangement.spacedBy((3 * sg()).dp)) {
                                        Text(t, color = C.muted, fontSize = 11.sp)
                                        playerRowOrder(s, r).forEach { id ->
                                            val h = when (id) { "picture" -> 34; "upnext" -> 22; "title" -> 16; else -> 10 }
                                            Box(Modifier.fillMaxWidth().height(h.dp).clip(RoundedCornerShape(4.dp))
                                                .background(if (id == "picture") accent().copy(alpha = 0.5f) else Color(0xFF2A2E35)), contentAlignment = Alignment.CenterStart) {
                                                Text((PLAYER_ROW_NAMES[id] ?: id).substringBefore(" ("), color = C.text, fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
                                            }
                                        }
                                    }
                                }
                                // Video player upright: picture on top, then its rows in their order.
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16181C)).padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy((3 * sg()).dp)) {
                                    Text("Video", color = C.muted, fontSize = 11.sp)
                                    Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(4.dp)).background(accent().copy(alpha = 0.5f)))
                                    videoRowOrder(s).forEach { id ->
                                        Box(Modifier.fillMaxWidth().height((if (id == "upnext") 22 else if (id == "playing") 14 else 10).dp).clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF2A2E35)), contentAlignment = Alignment.CenterStart) {
                                            Text((VIDEO_ROW_NAMES[id] ?: id).substringBefore(" ("), color = C.text, fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
                                        }
                                    }
                                }
                            }
                            lookHelp.startsWith("Floating button colour") -> Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy((18 * sg()).dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                FloatingButtonSample(s.floatBtnColor, playing = true); FloatingButtonSample(s.floatBtnColor, playing = false)
                            }
                            lookHelp.startsWith("Switch colour") -> Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy((18 * sg()).dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AppSwitch(true, {}); AppSwitch(false, {})
                                WideSwitch(true) {}; WideSwitch(false) {}
                            }
                            else -> Unit
                        }
                    }) { lookHelp = "" }
                    // Players: faint shadow on buttons and slider knobs (knobs ringed in grey).
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { set(s.copy(controlShadow = !s.controlShadow)) },
                        onLongClick = { explainNow("Shadow on player buttons", "") })) {
                        Column(Modifier.weight(1f)) {
                            Text("Shadow on player buttons", color = C.text, modifier = Modifier.anchor("Shadow on player buttons"), fontSize = 15.sp)
                            Text("Audio and radio players: faint grey shadow on buttons and slider knobs, knobs ringed in grey", color = C.muted, fontSize = 13.sp)
                        }
                        AppSwitch(s.controlShadow, { set(s.copy(controlShadow = it)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Menu opacity (menus and search results, own setting)", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f).explain("Menu opacity"))
                        Text("${s.menuOpacity}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(menuOpacity = 90)) }) { Text("Reset") }
                    }
                    OpacityPreview(s.menuOpacity / 100f, "Menu entry", UiPrefs.spOf(s.menuFont))
                    ThinSlider(s.menuOpacity.toFloat(), { v -> set(s.copy(menuOpacity = (Math.round(v / 5f) * 5).coerceIn(30, 100))) }, valueRange = 30f..100f)
                    // Menu row spacing: how close menu (and search result) rows are; preview of three rows.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Menu row spacing", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Menu row spacing").weight(1f).explain("Menu row spacing"))
                        Text("${s.menuGap} dp", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(menuGap = 6)) }) { Text("Reset") }
                    }
                    Column(Modifier.padding(vertical = (4 * sg()).dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF14171C).copy(alpha = s.menuOpacity / 100f))) {
                        listOf("Play now", "Add to queue", "Go to folder").forEach { t ->
                            Text(t, color = C.text, fontSize = UiPrefs.spOf(s.menuFont).sp, lineHeight = (UiPrefs.spOf(s.menuFont) + 4).sp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = s.menuGap.dp))
                        }
                    }
                    ThinSlider(s.menuGap.toFloat(), { v -> set(s.copy(menuGap = Math.round(v).coerceIn(0, 16))) }, valueRange = 0f..16f, steps = 15)
                    Text("Menu text", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Menu text").explain("Menu text"))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                        listOf("small" to "Small", "medium" to "Medium", "large" to "Large", "xlarge" to "Extra large").forEach { (k, l) ->
                            Pill(l, selected = s.menuFont == k, filled = s.menuFont == k) { set(s.copy(menuFont = k)) }
                        }
                    }
                }
            }
            // Separate colours for local / LAN files and for radio: choose which one the circles below set.
            // Files / Radio / Both ("Both": the same colour for files and radio).
            var accMode by rememberKept("look.accMode", "files")
            val forRadio = accMode == "radio"
            val cur = if (forRadio) (s.radioAccent.takeIf { it != 0L } ?: s.accent) else s.accent
            fun pick(c: Long) = set(when (accMode) { "radio" -> s.copy(radioAccent = c); "both" -> s.copy(accent = c, radioAccent = c); else -> s.copy(accent = c) })
            Row(horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Colour for", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Colour for").explain("Colour for"))
                listOf("files" to "Files", "radio" to "Radio", "both" to "All").forEach { (m, l) -> Pill(l, selected = accMode == m, filled = accMode == m) { accMode = m } }
            }
            // Custom colours: up to 5 slots; tap uses one, long-press edits it. "+" adds while a slot is free.
            var editSlot by remember { mutableStateOf(-1) }      // -1 closed, 0..2 edit that slot, 3 new
            Row(Modifier.padding(vertical = (6 * sg()).dp), horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0xFFFF8A3D).forEach { c ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(if (cur == c) 3.dp else 0.dp, Color.White, CircleShape).clickable { pick(c) })
                }
                s.customAccents.forEachIndexed { i, c ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(if (cur == c) 3.dp else 0.dp, Color.White, CircleShape)
                        .combinedClickable(onClick = { pick(c) }, onLongClick = { editSlot = i }))
                }
                Box(Modifier.size(34.dp).clip(CircleShape).border(2.dp, C.muted, CircleShape).clickable { editSlot = 3 }, contentAlignment = Alignment.Center) {
                        Text("+", color = C.text, fontSize = 22.sp)
                    }
            }
            var replacing by remember { mutableStateOf<Long?>(null) }   // new colour waiting for a slot (all 5 used)
            if (editSlot >= 0) {
                val slot = editSlot
                val existing = slot < s.customAccents.size
                ColorPickDialog(s.customAccents.getOrNull(slot) ?: cur, onDismiss = { editSlot = -1 },
                    onRemove = if (existing) ({
                        editSlot = -1
                        val gone = s.customAccents[slot]
                        set(s.copy(customAccents = s.customAccents.filterIndexed { i, _ -> i != slot },
                            accent = if (s.accent == gone) 0xFFFF8A3D else s.accent, radioAccent = if (s.radioAccent == gone) 0L else s.radioAccent))
                    }) else null) { c ->
                    editSlot = -1
                    val list = s.customAccents.toMutableList()
                    when {
                        existing -> { list[slot] = c; set(s.copy(customAccents = list, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio || accMode == "both") c else s.radioAccent)) }
                        list.size < 5 -> { list += c; set(s.copy(customAccents = list, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio || accMode == "both") c else s.radioAccent)) }
                        else -> replacing = c
                    }
                }
            }
            // All 5 slots used: tap the one to overwrite, or drop one first.
            replacing?.let { c ->
                AlertDialog(onDismissRequest = { replacing = null }, containerColor = C.panel,
                    title = { Text("Overwrite which?", color = C.text) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy((12 * sg()).dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c)))
                        Row(horizontalArrangement = Arrangement.spacedBy((10 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                            s.customAccents.forEachIndexed { i, old ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((4 * sg()).dp)) {
                                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(old)).border(2.dp, Color.White, CircleShape).clickable {
                                        replacing = null
                                        set(s.copy(customAccents = s.customAccents.toMutableList().also { it[i] = c }, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio || accMode == "both") c else s.radioAccent))
                                    })
                                    Text("Drop", color = C.bad, fontSize = 12.sp, modifier = Modifier.clickable {
                                        set(s.copy(customAccents = s.customAccents.filterIndexed { k, _ -> k != i } + c, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio || accMode == "both") c else s.radioAccent)); replacing = null
                                    })
                                }
                            }
                        }
                        }
                    },
                    confirmButton = { STextButton({ replacing = null }) { Text("Cancel") } },
                )
            }
            SectionTitle("Order of the Settings links")
            CardBox {
                val order = orderedGroups(s.settingsOrder).map { it.first }
                // Drag (≡ or long press) to reorder (11:28). A list only for ordering has no ✕ (11:32).
                DragOrderList(order, { SETTINGS_GROUPS.toMap()[it] ?: it },
                    onOrder = { l -> set(s.copy(settingsOrder = l)) }, modifier = Modifier.explain("Order of the Settings links"))
                Divider()
                ValueRow("Reset order", "Back to the original order", "Reset") { set(s.copy(settingsOrder = emptyList(), settingsHidden = emptyList())) }
            }
                }
                "library" -> {
            SectionTitle("Horizontal autoscroll")
            ScrollSpeedCard("Songs list autoscroll", "Long rows in Library → Songs, playlists and saved radio songs", "songs")
            ScrollSpeedCard("Folder lists autoscroll", "Long folder and song names in the phone and network folder lists", "folders")
            // All files access (2026-11-23 01:14): read the storage itself, like VLC, for what Android's media list leaves out.
            SectionTitle("Phone storage")
            CardBox {
                var afTick by remember { mutableIntStateOf(0) }
                val afOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(afOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) afTick++ }
                    afOwner.lifecycle.addObserver(obs); onDispose { afOwner.lifecycle.removeObserver(obs) }
                }
                val granted = remember(afTick) { app.vlcinterface.sources.AllFiles.granted() }
                var was by remember { mutableStateOf(granted) }
                LaunchedEffect(granted) { if (granted != was) { was = granted; app.vlcinterface.sources.AllFiles.forget(); SongsCache.phone = null; SongsCache.phoneVideos = null } }
                // Helpers on: the helper first, Android's page when it's closed.
                val helpersAF = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.helpers
                var afHelp by remember { mutableStateOf(false) }
                ToggleRow("All files access (like VLC)", if (granted) "On: the whole storage is read — also folders Android leaves out of its media list"
                    else "Off: the app sees what Android lists as media", granted) { _ -> if (helpersAF && AutoHelp.ok("library", "allfiles")) afHelp = true else app.vlcinterface.sources.AllFiles.open(ctxV) }
                if (afHelp) HelperDialog("All files access (like VLC)", SettingHelp.text("All files access (like VLC)", "")) {
                    afHelp = false; app.vlcinterface.sources.AllFiles.open(ctxV) }
            }
            // Indexing again (2026-11-22 08:19): how pinned network folders are kept up to date.
            SectionTitle("Indexing")
            CardBox {
                Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                    Text("Index again", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Index again").explain("Index again"))
                    Text("Pinned network folders are indexed once; this says when they're read again", color = C.muted, fontSize = 13.sp)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                        listOf("never" to "Never", "days" to "Every ${s.indexDays} days", "new" to "When a new file appears").forEach { (m, l) ->
                            Pill(l, selected = s.indexMode == m, filled = s.indexMode == m) { set(s.copy(indexMode = m)) }
                        }
                    }
                    if (s.indexMode == "days") Column {
                        // Same fix: the field across the full width so its digit pad opens whole.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Days", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            STextButton({ set(s.copy(indexDays = 4)) }) { Text("Reset") }
                        }
                        DigitField(s.indexDays.toString(), "days", 3, Modifier.fillMaxWidth()) { v -> v.toIntOrNull()?.takeIf { it in 1..365 }?.let { set(s.copy(indexDays = it)) } }
                    }
                    Text(when (s.indexMode) {
                        "never" -> "Only the first time, and when you tap 🔍 Index or ↻ Rescan."
                        "new" -> "A folder is read again when a file not in the index is found there (opening the folder, or playing from it)."
                        else -> "Each pinned home-network folder is read again ${s.indexDays} days after its last indexing (tags already read come from the cache)."
                    } + " Indexing runs in the background at low priority, several folders at once, and waits for ReplayGain scans.", color = C.muted, fontSize = 12.sp)
                }
            }
            SectionTitle("Library scanning")
            CardBox {
                ToggleRow("Automatic scanning", "Off: only scans when you tap Rescan", s.autoScan) { set(s.copy(autoScan = it)) }; Divider()
                ToggleRow("Watch folders for changes", "Off: no background file watching", s.watchFolders) { set(s.copy(watchFolders = it)) }; Divider()
                ToggleRow("Album pictures from the internet", "For albums with no picture (MusicBrainz / Cover Art Archive); artist or band photo when the album has none (Wikimedia). Resized for the phone and saved in the album folder (cover.jpg / artist.jpg) when that folder holds one album; songs in folders mixing albums use the pictures folder below", s.coverDownload) { set(s.copy(coverDownload = it)) }
                Divider()
                ValueRow("Pictures folder", "For songs not in a one-album folder: each picture saved as <unique id>.jpg, tied to the song it was downloaded for (and its album / artist) in " +
                    app.vlcinterface.sources.CoverArt.store.path, "") {}
                Divider()
                val ctx = LocalContext.current
                var granted by remember { mutableStateOf(app.vlcinterface.sources.CoverArt.phoneAccessGranted(ctx)) }
                val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) try {
                        ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        granted = true
                    } catch (_: Exception) {}
                }
                ValueRow("Phone music folders", if (granted) "Album pictures can be saved in phone album folders" else "Allow once (e.g. pick Music) so pictures can be saved next to the songs",
                    if (granted) "Allowed ✓" else "Allow") { pick.launch(null) }
            }
            // Deleting a file for good: only here (not in the lists' menus), with a search like the Songs list / player.
            CardBox {
                Row(Modifier.fillMaxWidth().clickable { deleteFile = true }.padding(horizontal = 16.dp, vertical = (14 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Delete a file or folder…", color = C.bad, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Search for a song or a folder and delete it from the phone or the NAS for good (folders: full content shown first)", color = C.bad.copy(alpha = 0.8f), fontSize = 13.sp)
                    }
                }
            }
            // Files taken out of lists with "Remove from this list" (never deleted): put them back.
            if (s.hiddenUris.isNotEmpty()) CardBox {
                ValueRow("Removed from lists", "${s.hiddenUris.size} file${if (s.hiddenUris.size == 1) "" else "s"} taken out of the lists and never re-added — files and folders (not deleted)",
                    "Show again") { HiddenFiles.showAll() }
            }
                }
                "network" -> {
            SectionTitle("Network")
            CardBox {
                ToggleRow("Allow SMB1", "Old, unencrypted — fine on your home LAN, avoid over the internet", s.allowSmb1) { set(s.copy(allowSmb1 = it)) }
                Divider()
                // Song links: Spotify's own search with a free developer app (no Spotify login). Without it the
                // links are found through MusicBrainz and a web search.
                Column(Modifier.explain("Spotify links").padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                    Text("Spotify links", color = C.text, modifier = Modifier.anchor("Spotify links"), fontSize = 15.sp)
                    val on = s.spotifyId.isNotBlank() && s.spotifySecret.isNotBlank()
                    Text(if (on) "On: song links include Spotify" else "Off: no Spotify anywhere until both keys are set", color = if (on) accent() else C.muted, fontSize = 12.sp)
                    var help by remember { mutableStateOf(false) }
                    var helped by remember { mutableStateOf(false) }
                    Text("How to get them?", color = accent(), fontSize = 13.sp, modifier = Modifier.clickable { help = true })
                    var id by remember { mutableStateOf(s.spotifyId) }
                    var sec by remember { mutableStateOf(s.spotifySecret) }
                    // The first tap on a box: the help.
                    val firstTap = Modifier.onFocusChanged { if (it.isFocused && !helped && !on) { helped = true; help = true } }
                    androidx.compose.material3.OutlinedTextField(id, { id = it.trim(); set(s.copy(spotifyId = id)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().then(firstTap), label = { Text("Client ID") })
                    androidx.compose.material3.OutlinedTextField(sec, { sec = it.trim(); set(s.copy(spotifySecret = sec)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().then(firstTap), label = { Text("Client Secret") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    if (help) AlertDialog(onDismissRequest = { help = false }, containerColor = C.panel,
                        title = { Text("Spotify keys", color = C.text, modifier = Modifier.anchor("Spotify keys")) },
                        text = {
                            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                                Text("Free, a few minutes, any Spotify account (free accounts too). Best done on a computer.", color = C.text, fontSize = 14.sp)
                                listOf(
                                    "Open developer.spotify.com and log in with your Spotify account.",
                                    "Accept the developer terms if asked.",
                                    "Dashboard → Create app.",
                                    "App name and description: anything (e.g. \"vlc interface\").",
                                    "Redirect URI: https://localhost (it's not used), then Add.",
                                    "Which API: tick Web API. Accept the terms → Save.",
                                    "Open the app → Settings: copy the Client ID.",
                                    "Click View client secret: copy the Client Secret.",
                                    "Paste both here. Spotify links work at once.",
                                ).forEachIndexed { i, t -> Text("${i + 1}. $t", color = C.text, fontSize = 13.sp) }
                                Text("The keys stay on this phone and are only used to search Spotify's catalogue for song links — no access to your account.",
                                    color = C.muted, fontSize = 13.sp)
                            }
                        },
                        confirmButton = { STextButton({ help = false }) { Text("OK", color = accent()) } },
                        dismissButton = { STextButton({
                            help = false
                            try { ctxV.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://developer.spotify.com/dashboard"))) } catch (_: Exception) {}
                        }) { Text("Open the site") } })
                }
            }
                }
                "vpn" -> {
            // VPN: home NAS through ZeroTier / Tailscale … (their own app keeps the login: nothing to enter here).
            SectionTitle("VPN")
            CardBox {
                val vl = app.vlcinterface.sources.VpnLink
                val up by vl.vpnUp.collectAsState()
                val busy by vl.searching.collectAsState()
                ValueRow("Status", if (up) "A VPN is on" else "No VPN on", if (up) "On" else "Off") {}
                if (up) vl.phoneVpnInfo().takeIf { it.isNotEmpty() }?.let { Text(it, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
                Divider()
                ToggleRow("Home NAS through the VPN", "Away from home with the VPN on, your LAN servers are reached through it — at home, directly", s.vpnNas) { set(s.copy(vpnNas = it)) }
                Divider()
                ToggleRow("Ask to start the VPN", "Away from home with the VPN off, when the phone isn't on the subnet of any address stored for a home server", s.vpnAskStart) { set(s.copy(vpnAskStart = it)) }
                if (s.vpnAskStart) {
                    Column(Modifier.explain("Ask to start the VPN").padding(horizontal = 16.dp, vertical = (6 * sg()).dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("“Retry in” delay (time to start the VPN)", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("“Retry in” delay (time to start the VPN)").weight(1f))
                            Text("${s.vpnRetrySec} s", color = accent(), fontWeight = FontWeight.SemiBold)
                            STextButton({ set(s.copy(vpnRetrySec = 40)) }) { Text("Reset") }
                        }
                        ThinSlider(s.vpnRetrySec.toFloat(), { v -> set(s.copy(vpnRetrySec = (Math.round(v / 5f) * 5).coerceIn(10, 120))) }, valueRange = 10f..120f)
                    }
                }
                Divider()
                ValueRow("VPN app", "Opened by “Start the VPN” (its saved login is used)",
                    when (s.vpnApp) { "zerotier" -> "ZeroTier"; "tailscale" -> "Tailscale"; "settings" -> "Android settings"; else -> "Auto" }) {
                    val o = listOf("auto", "zerotier", "tailscale", "settings"); set(s.copy(vpnApp = o[(o.indexOf(s.vpnApp) + 1).mod(o.size)]))
                }
                Divider()
                Column(Modifier.explain("Home servers on the VPN").padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                    Text("Home servers on the VPN", color = C.text, modifier = Modifier.anchor("Home servers on the VPN"), fontSize = 15.sp)
                    if (busy.isNotEmpty()) Text(busy, color = accent(), fontSize = 12.sp)
                    else {
                        // Always here (special cases: a new NAS, an address changed on the VPN …).
                        val sctx = androidx.compose.ui.platform.LocalContext.current
                        Pill("Search for NAS now") {
                            if (up) vl.rescan() else android.widget.Toast.makeText(sctx, "Start the VPN first, then search", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        if (!up) Text("VPN off — start it to search", color = C.muted, fontSize = 13.sp)
                    }
                    val kept = st.sources.filter { it.vpnHost.isNotBlank() }
                    if (kept.isEmpty()) Text("None yet — found by themselves when the VPN starts", color = C.muted, fontSize = 13.sp)
                    var editing by remember { mutableStateOf<app.vlcinterface.data.Source?>(null) }
                    kept.forEach { src ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${src.name.ifBlank { src.host }}: LAN ${src.host} · VPN ${src.vpnHost}", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            STextButton({ editing = src }) { Text("Modify") }
                            STextButton({ vl.forget(src) }) { Text("Forget") }
                        }
                    }
                    editing?.let { src ->
                        var ip by remember(src.id) { mutableStateOf(src.vpnHost) }
                        AlertDialog(onDismissRequest = { editing = null }, containerColor = C.panel,
                            title = { Text("${src.name.ifBlank { src.host }}: VPN address", color = C.text) },
                            text = { androidx.compose.material3.OutlinedTextField(ip, { ip = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("VPN address") }) },
                            confirmButton = { STextButton({ vl.setVpnHost(src, ip); editing = null }, enabled = ip.isNotBlank()) { Text("Save", color = accent()) } },
                            dismissButton = { STextButton({ editing = null }) { Text("Cancel") } })
                    }
                }
                Divider()
                ValueRow("VPN log", "VPN on / off, servers found, LAN ↔ VPN switches", "Open") { nav.go(Screen.LogPage("vpn")) }
            }
                }
                "assoc" -> {
            AssociationSettings()
                }
                "vis" -> {
            SectionTitle("Visualisation")
            VisualisationSettings()
                }
                "logs" -> {
            // Logs, by kind. Each page has Errors only / Empty log / Share / Save to NAS.
            SectionTitle("Logs")
            // One zip with every log, the app's system log, why it ended the last times and the phone / app details
            // (no passwords), saved where you choose — to send for a problem report.
            run {
                val ctxL = androidx.compose.ui.platform.LocalContext.current
                val scopeL = androidx.compose.runtime.rememberCoroutineScope()
                CardBox {
                    // Straight into the phone's Download folder (Download/vlc_interface), no file picker.
                    ValueRow("Collect error logs", "One zip with only the errors (every log's failures, system errors, crashes) and phone details, no passwords — saved straight to Download/vlc_interface", "Save") {
                        scopeL.launch {
                            val where = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.vlcinterface.data.LogPack.saveToDownloads(ctxL) }
                            ShortNote.show(if (where != null) "Saved: $where" else "Could not save the logs")
                        }
                    }
                }
                // Sound check (2026-11-21 22:28): measures what happens to the playing song on this phone.
                run {
                    var scStep by remember { mutableStateOf("") }
                    var scReport by remember { mutableStateOf<app.vlcinterface.player.SoundCheck.Report?>(null) }
                    CardBox {
                        Column(Modifier.explain("Sound check")) {
                            ValueRow("Sound check", if (scStep.isNotEmpty()) scStep else
                                "While a song, video or radio station plays: 10 s of what libVLC gives the app's sound engine and what the engine sends to Android are " +
                                "compared with the same passage decoded by Android's own decoder — decoding, processing, rate conversion, output, Bluetooth",
                                if (scStep.isNotEmpty()) "…" else "Start") {
                                if (scStep.isEmpty()) scopeL.launch {
                                    scStep = "Starting…"
                                    scReport = try { app.vlcinterface.player.SoundCheck.run(ctxL) { scStep = it } }
                                        catch (e: Exception) { app.vlcinterface.player.SoundCheck.Report("Sound check", listOf("Sound check" to listOf("Result" to "failed: ${e.message ?: e.javaClass.simpleName}")), "") }
                                    scStep = ""
                                }
                            }
                        }
                    }
                    scReport?.let { r -> DetailsDialog(r.title, r.sections, note = r.note) { scReport = null } }
                }
                // Video format analysis: turns the mode on; the rest happens on the video player.
                val anal by app.vlcinterface.ui.FormatCheck.mode.collectAsState()
                CardBox {
                    ValueRow("Video format analysis", "Keeps every video control on screen and adds \"Analyse ${app.vlcinterface.ui.FormatCheck.VIDEOS} videos\" on the player. " +
                        "Open the folder, start the first video, press it: each of ${app.vlcinterface.ui.FormatCheck.VIDEOS} videos is shown in every format, upright and " +
                        "sideways, photographed and measured — one zip in Download/vlc_interface to upload", if (anal) "On" else "Start") {
                        app.vlcinterface.ui.FormatCheck.mode.value = !anal
                        ShortNote.show(if (!anal) "Analysis mode on: open the video folder, start the first video, then press \"Analyse ${app.vlcinterface.ui.FormatCheck.VIDEOS} videos\"" else "Analysis mode off")
                    }
                }
            }
            CardBox {
                ValueRow("All errors", "Every failure from every log, newest first", "Open") { nav.go(Screen.LogView("errors")) }; Divider()
                ValueRow("Deleted files", "Every file deleted from Settings, with its full path", "Open") { nav.go(Screen.LogPage(ServerLog.DELETED)) }; Divider()
                ValueRow("Network", "One page per LAN / Internet server", "Open") { nav.go(Screen.Logs) }; Divider()
                ValueRow("Indexing", "LAN folders indexed for search (songs found, or why not)", "Open") { nav.go(Screen.LogView("indexing")) }; Divider()
                ValueRow("ReplayGain", "Every scan, and each file that had a problem", "Open") { nav.go(Screen.LogPage(ServerLog.REPLAYGAIN)) }; Divider()
                ValueRow("Crashes", "App crashes, and why the app ended last time (from Android)", "Open") { nav.go(Screen.LogPage(app.vlcinterface.data.CrashLog.KEY)) }; Divider()
                ValueRow("Sound engine", "The app's sound output: switches and problems", "Open") { nav.go(Screen.LogPage("sound-engine")) }; Divider()
                ValueRow("Radio pictures", "Each song a station announced: picture found, or what was searched", "Open") { nav.go(Screen.LogPage("radio-pictures")) }; Divider()
                ValueRow("Player", "Songs skipped (not found / not reachable)", "Open") { nav.go(Screen.LogPage("player")) }
                Divider()
                // By hand: everything in the app's cache folder (radio buffer, songs being saved, radio pictures,
                // temporary files). Settings, playlists, logs and ReplayGain values are not touched.
                var askClear by remember { mutableStateOf(false) }
                var cacheSize by remember { mutableStateOf(-1L) }
                val cctx = LocalContext.current
                LaunchedEffect(askClear) { cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cctx.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } } }
                ValueRow("Clear all caches", "Radio buffer, songs being saved, radio pictures, temporary files" +
                    if (cacheSize >= 0) " · ${"%.1f".format(java.util.Locale.US, cacheSize / 1048576.0)} MB" else "", "Clear") { askClear = true }
                if (askClear) {
                    val busy = app.vlcinterface.sources.RadioCapture.hasWork()
                    AlertDialog(onDismissRequest = { askClear = false }, containerColor = C.panel,
                        title = { Text("Clear all caches?", color = C.text) },
                        text = { Text((if (busy) "Songs being saved from the radio are stopped and lost. " else "") +
                            "The radio buffer, radio pictures and temporary files are deleted. Settings, playlists, logs and saved songs stay.",
                            color = if (busy) C.bad else C.muted, fontSize = 13.sp) },
                        confirmButton = { STextButton({
                            askClear = false
                            val freed = app.vlcinterface.sources.RadioCapture.clearAllCaches(cctx)
                            android.widget.Toast.makeText(cctx, "Caches cleared: ${"%.1f".format(java.util.Locale.US, freed / 1048576.0)} MB freed", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("Clear", color = C.bad, fontWeight = FontWeight.Bold) } },
                        dismissButton = { STextButton({ askClear = false }) { Text("Cancel") } })
                }
            }
                }
            }
}

/** One tick box of the setup file's categories (greyed while "Everything" is ticked). */
@Composable
private fun CatRow(label: String, count: String, checked: Boolean, enabled: Boolean, bold: Boolean = false, onChange: (Boolean) -> Unit) {
    val a = accent()
    val ex = LocalExplain.current
    val col = if (enabled) C.text else C.muted.copy(alpha = 0.55f)
    Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = { onChange(!checked) }, onLongClick = { ex(label, "") })
        .padding(start = if (bold) 12.dp else 26.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (checked) "☑" else "☐", color = if (!enabled) SYM_OFF else if (checked) a else C.text, fontSize = SYM)
        Text(label, color = col, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (count.isNotEmpty()) Text(count, color = C.muted, fontSize = 13.sp)
    }
}

@Composable
fun BackupScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val store = App.instance.store
    val st by store.state.collectAsState()
    val alarmsN = app.vlcinterface.alarm.Alarms.state.collectAsState().value.alarms.size
    // Export categories: kept as they were left.
    var catsK by rememberKept("backup.cats", ExportCat.ALL)
    val cats: Set<String> = catsK.split(',').filter { it.isNotEmpty() }.toSet()
    fun setCats(v: Set<String>) { catsK = v.joinToString(",") }
    var passphrase by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Store.ImportPreview?>(null) }
    // Parts read when the app starts were imported (per-video choices, ReplayGain values, saved songs…): restart offered.
    var askRestart by remember { mutableStateOf(false) }
    if (askRestart) androidx.compose.material3.AlertDialog(onDismissRequest = { askRestart = false }, containerColor = C.panel,
        title = { Text("Restart to finish the import?", color = C.text) },
        text = { Text("Some imported parts (per-video choices, ReplayGain values, saved radio songs, page choices…) are read when the app starts. Restart now to use them; otherwise they apply the next time the app opens.", color = C.text, fontSize = 14.sp) },
        confirmButton = { STextButton({ askRestart = false; exitApp(restart = true) }) { Text("Restart now", color = accent()) } },
        dismissButton = { STextButton({ askRestart = false }) { Text("Later") } })
    var importCats by remember { mutableStateOf(emptySet<String>()) }
    var importPass by remember { mutableStateOf("") }
    val all = ExportCat.ALL in cats
    val pwChosen = all || ExportCat.PASSWORDS in cats

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson(cats, passphrase).toByteArray()) }
            "Exported." + if (pwChosen && passphrase.isEmpty()) " Passwords left out (no passphrase)." else ""
        } catch (e: Exception) { "Export failed: ${e.message}" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: "" } catch (e: Exception) { "" }
        val p = store.previewImport(text)
        if (p.ok) { preview = p; importCats = p.cats; importPass = "" } else status = p.error
    }
    val counts = mapOf(
        ExportCat.SETTINGS to "all",
        ExportCat.NAS to "${st.sources.count { it.zone == app.vlcinterface.data.Zone.LAN }} servers",
        ExportCat.INTERNET to "${st.sources.count { it.zone == app.vlcinterface.data.Zone.INTERNET }} sources",
        ExportCat.PASSWORDS to "with a passphrase",
        ExportCat.RADIO to "${st.stations.size} stations",
        ExportCat.PLAYLISTS to "${st.playlists.size} playlists",
        ExportCat.ALARMS to "$alarmsN alarms",
        ExportCat.VIS to "patterns",
        ExportCat.SUBS to "${st.subOffsets.size + st.audioOffsets.size} offsets",
        ExportCat.RESUME to "${st.recent.size} files",
    )
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Export & import", nav)
        Column(Modifier.weight(1f).anchorViewport(scroll, "backup").scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((12 * sg()).dp)) {
            SButton({
                val name = "vlc-interface-setup_" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
                exporter.launch(name)
            }, Modifier.fillMaxWidth().height(64.dp), enabled = all || cats.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = accent())) { Text(if (all) "Export entire setup" else "Export chosen", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            Text("One file you can save to Downloads, your NAS or a cloud drive.", color = C.muted, fontSize = 13.sp)
            SectionTitle("What's in the file")
            CardBox {
                CatRow("Everything", "", all, true, bold = true) { on -> setCats(if (on) setOf(ExportCat.ALL) else ExportCat.ALL_IDS.toSet()) }
                Divider()
                ExportCat.LIST.forEach { (id, label) ->
                    CatRow(label, counts[id] ?: "", all || id in cats, !all) { on -> setCats(if (on) cats + id else cats - id) }
                }
                if (pwChosen) Column(Modifier.padding(12.dp)) {
                    Field("Passphrase for the passwords (min. 4 characters)", passphrase, password = true) { passphrase = it }
                    Text(if (passphrase.isEmpty()) "Without a passphrase the passwords are left out." else if (passphrase.length < 4) "At least 4 characters."
                        else "Passwords saved encrypted with this passphrase.", color = C.muted, fontSize = 13.sp)
                }
            }
            SOutlinedButton({ importer.launch(arrayOf("application/json", "*/*")) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Import setup…", fontSize = 16.sp) }
            SectionTitle("On import")
            Row(horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                Pill("Merge · add new, keep mine", selected = !replace) { replace = false }
                Pill("Replace chosen parts", selected = replace) { replace = true }
            }
            Text("You'll choose which parts of the file to apply; the rest of your setup stays as it is. Your current setup is backed up automatically first.",
                color = C.muted, fontSize = 13.sp)
            if (status.isNotEmpty()) Text(status, color = C.text, fontSize = 14.sp)
            Box(Modifier.height(16.dp))
        }
    }

    val pv = preview
    if (pv != null) AlertDialog(
        onDismissRequest = { preview = null },
        title = { Text("Import setup?") },
        text = {
            val sc = rememberScrollState()
            Column(Modifier.verticalScroll(sc), verticalArrangement = Arrangement.spacedBy((2 * sg()).dp)) {
                Text("Mode: ${if (replace) "replace the chosen parts" else "merge (keep mine, add new)"}. In the file:", fontSize = 13.sp)
                ExportCat.LIST.filter { it.first in pv.cats }.forEach { (id, label) ->
                    CatRow(label, "", id in importCats, true) { on -> importCats = if (on) importCats + id else importCats - id }
                }
                if (pv.needsPassphrase && ExportCat.PASSWORDS in importCats) Field("Passphrase for passwords", importPass, password = true) { importPass = it }
            }
        },
        confirmButton = {
            STextButton({
                val err = store.applyImport(pv, replace, importPass, importCats)
                status = err ?: "Imported. Previous setup backed up."
                if (err == null) preview = null
                if (err == null && store.lastImportNeedsRestart) askRestart = true
            }, enabled = importCats.isNotEmpty()) { Text("Import") }
        },
        dismissButton = { STextButton({ preview = null }) { Text("Cancel") } },
    )
}


/** Per-output preamp and balance, for every device seen so far. */
@Composable
fun DeviceSoundList() {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val devices by App.instance.output.devices.collectAsState()
    val names = (devices.filter { it.connected }.map { it.name } + st.devicePreamp.keys + st.deviceBalance.keys).distinct()
    val na = app.vlcinterface.player.NativeAudio
    SectionTitle("Sound engine")
    CardBox { Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
            listOf("app" to "App output", "libvlc" to "libVLC output").forEach { (k, l) ->
                val inUse = if (k == "app") na.active else !na.active
                Pill(l, selected = st.settings.soundEngine == k && inUse) {
                    // Also acts when the setting already says so but the other output is running (e.g. after a fallback).
                    if (st.settings.soundEngine != k || !inUse) {
                        store.update { it.copy(settings = it.settings.copy(soundEngine = k)) }
                        App.instance.player.applySoundEngine()
                    }
                }
            }
        }
        Text(if (na.active) "In use: app output — balance, preamp and stereo meters active"
            else "In use: libVLC output (" + na.reason.ifEmpty { "not attached" } + ") — balance may not apply on every phone, no meters",
            color = if (na.active) C.muted else C.bad, fontSize = 12.sp)
        if (na.active) {
            var diag by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { while (true) { diag = na.stats()?.toString() ?: ""; kotlinx.coroutines.delay(1000) } }
            if (diag.isNotEmpty()) Text(diag, color = C.muted, fontSize = 11.sp)
        }
    } }
    SectionTitle("Per output device")
    CardBox { Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
        Text("Added on top of the general preamp (total max +12 dB). Applied automatically when the output changes.", color = C.muted, fontSize = 13.sp)
        // Auto loudness: Loudness follows the phone's volume; each device below has its own start %.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto loudness", color = C.text, modifier = Modifier.anchor("Auto loudness"), fontSize = 15.sp)
                Text("Full Loudness up to each device's start %, flattening step by step above it, none at full volume", color = C.muted, fontSize = 13.sp)
            }
            AppSwitch(st.settings.autoLoud, { on ->
                store.update { it.copy(settings = it.settings.copy(autoLoud = on)) }; App.instance.player.applySoundChain()
            })
        }
        names.forEach { n -> Divider(); DeviceSoundControls(n) }
    } }
}

@Composable
fun DeviceSoundControls(name: String) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val pre = st.devicePreamp[name] ?: 0f
    val bal = st.deviceBalance[name] ?: 0
    Column(Modifier.fillMaxWidth().padding(vertical = (4 * sg()).dp)) {
        Text(name, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Preamp", color = C.muted, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            ThinSlider(pre, { v ->
                store.update { it.copy(devicePreamp = it.devicePreamp + (name to Math.round(v * 2) / 2f)) }; App.instance.player.applySoundChain()
            }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
            Text("%+.1f dB".format(pre), color = accent(), fontSize = 12.sp, modifier = Modifier.width(64.dp))
        }
        if (st.settings.autoLoud) {
            val from = st.deviceLoudFrom[name] ?: 50
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loudness\nflat from", color = C.muted, fontSize = 13.sp, lineHeight = 13.sp, modifier = Modifier.width(64.dp))
                ThinSlider(from.toFloat(), { v ->
                    store.update { it.copy(deviceLoudFrom = it.deviceLoudFrom + (name to (Math.round(v / 5f) * 5).coerceIn(0, 95))) }
                    App.instance.player.applySoundChain()
                }, valueRange = 0f..95f, modifier = Modifier.weight(1f))
                Text("$from % vol", color = accent(), fontSize = 12.sp, modifier = Modifier.width(64.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Balance", color = C.muted, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Text("L", color = C.muted, fontSize = 13.sp)
            ThinSlider(bal.toFloat(), { v ->
                val snapped = if (kotlin.math.abs(v) < 6f) 0 else v.toInt()
                store.update { it.copy(deviceBalance = it.deviceBalance + (name to snapped)) }; App.instance.player.applySoundChain()
            }, valueRange = -100f..100f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            Text("R", color = C.muted, fontSize = 13.sp)
            Text(when { bal == 0 -> "Centre"; bal < 0 -> "L ${-bal}"; else -> "R $bal" }, color = accent(), fontSize = 12.sp,
                modifier = Modifier.width(64.dp).padding(start = 6.dp).clickable {
                    store.update { it.copy(deviceBalance = it.deviceBalance + (name to 0)) }; App.instance.player.applySoundChain()
                })
        }
    }
}

/** Sound section: used in Settings and on its own page (gear button in the music player). */
@Composable
fun SoundSettings(s: Settings, set: (Settings) -> Unit) {
    // Helpers (2026-11-23 01:16): with Helpers on, a change or a still hold on the slider shows the setting's helper.
    val helpersS = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.helpers
    var sndHelp by remember { mutableStateOf("") }
    if (sndHelp.isNotEmpty()) HelperDialog(sndHelp, SettingHelp.text(sndHelp, "")) { sndHelp = "" }
    SectionTitle("Sound")
    CardBox {
        Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((4 * sg()).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start volume", color = C.text, modifier = Modifier.anchor("Start volume").explain("Start volume"), fontSize = 15.sp)
                    Text("Set when the app starts and after the quiet time below, before any sound", color = C.muted, fontSize = 13.sp)
                }
                Text("${s.startVolume}%", color = accent(), fontWeight = FontWeight.SemiBold)
            }
            ThinSlider(s.startVolume.toFloat(), { set(s.copy(startVolume = it.toInt())) }, valueRange = 0f..100f,
                onHoldStill = { if (AutoHelp.ok("audio", "Start volume")) sndHelp = "Start volume" })
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Preamp (everything)", color = C.text, modifier = Modifier.anchor("Preamp (everything)"), fontSize = 15.sp)
                    Text(if (s.preampDb > 0f) "Above 0 dB loud tracks may distort" else "Music, video and radio", color = C.muted, fontSize = 13.sp)
                }
                Text("%+.1f dB".format(s.preampDb), color = accent(), fontWeight = FontWeight.SemiBold)
                STextButton({ set(s.copy(preampDb = 0f)); App.instance.player.applySoundChain() }) { Text("Reset") }
            }
            ThinSlider(s.preampDb, { v -> set(s.copy(preampDb = Math.round(v * 2) / 2f)); App.instance.player.applySoundChain() }, valueRange = -12f..12f)
            Divider()
            ToggleRow("Start volume on a new output", "When the sound moves to another device, it starts at the Start volume", s.startVolOnOutput) { v ->
                set(s.copy(startVolOnOutput = v)); if (AutoHelp.ok("audio", "Start volume on a new output")) sndHelp = "Start volume on a new output" }
            Divider()
            // 2026-11-22 18:37: minutes with nothing playing → the next sound starts at the Start volume (0 = never).
            Text("Reset volume after", color = C.text, modifier = Modifier.anchor("Reset volume after").explain("Reset volume after"), fontSize = 15.sp)
            var idleHelpShown by remember { mutableStateOf(false) }      // once per visit, not on every digit typed
            // The field across the full width (20:38: beside other things the digit pad opened squeezed into one column).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Minutes with nothing playing", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                STextButton({ set(s.copy(idleResetMin = 30)) }) { Text("Reset") }
            }
            DigitField(s.idleResetMin.toString(), "minutes", 3, Modifier.fillMaxWidth()) { v -> v.toIntOrNull()?.takeIf { it in 0..999 }?.let {
                val first = it != s.idleResetMin
                set(s.copy(idleResetMin = it)); if (first && sndHelp.isEmpty() && !idleHelpShown && AutoHelp.ok("audio", "Reset volume after")) { idleHelpShown = true; sndHelp = "Reset volume after" } } }
            Text(if (s.idleResetMin == 0) "Never: the volume stays as you left it (the start volume is set only when the app starts)."
                else "After ${s.idleResetMin} min with nothing playing, the next song, station or video starts at ${s.startVolume} % (Preamp as set: " + "%+.1f".format(s.preampDb) + " dB).",
                color = C.muted, fontSize = 13.sp)
        }
        Divider()
        // All three player sliders on / off: off takes them off the players and stops their processing.
        // (Outside the padded block: same left margin as every other row.)
        ToggleRow("Loudness, Treble and Preamp sliders", "On the audio and radio players", s.toneSliders) { on ->
            set(s.copy(toneSliders = on)); App.instance.player.applySoundChain()
        }
    }
    TrebleBandBox(s, set)
    // Own categories (they were headings inside the Sound box): sound engine, per output device, ReplayGain.
    DeviceSoundList()
    SectionTitle("ReplayGain")
    CardBox {
        Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            Text("ReplayGain (tagged music only)", color = C.text, modifier = Modifier.anchor("ReplayGain (tagged music only)"), fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                listOf("off" to "Off", "track" to "Track", "album" to "Album", "auto" to "Auto").forEach { (k, l) ->
                    Pill(l, selected = s.rgMode == k) { set(s.copy(rgMode = k)) }
                }
            }
            Text("Auto: album gain when playing an album in order, track gain for shuffle and playlists. Videos are never adjusted.", color = C.muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ReplayGain pre-amp", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("%+.1f dB".format(s.rgPreampDb), color = accent(), fontSize = 13.sp)
            }
            ThinSlider(s.rgPreampDb, { v -> set(s.copy(rgPreampDb = Math.round(v * 2) / 2f)) }, valueRange = -6f..6f)
        }
        Divider()
        ToggleRow("Prevent clipping", "Uses the peak values in the tags", s.rgClipProtect) { set(s.copy(rgClipProtect = it)) }
        Divider()
        // 2026-11-21 12:01: a song without ReplayGain → its whole folder scanned silently, 5 s summary at the end.
        ToggleRow("Automatic ReplayGain for folders", "Phone and home-network songs: a song without it → its whole folder is scanned silently (track gain per song, written into the files' tags); through the VPN you're asked instead",
            s.rgAutoFolder) { set(s.copy(rgAutoFolder = it)) }
        Divider()
        // Music folders the app may write ReplayGain into (22:57): Android's folder permission, given per folder.
        run {
            val ctxA = androidx.compose.ui.platform.LocalContext.current
            var grants by remember { mutableStateOf(app.vlcinterface.sources.MusicFolderAccess.grants(ctxA)) }
            val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
                r.data?.data?.let { t -> if (r.resultCode == android.app.Activity.RESULT_OK) app.vlcinterface.sources.MusicFolderAccess.keep(ctxA, t) }
                grants = app.vlcinterface.sources.MusicFolderAccess.grants(ctxA)
            }
            Column(Modifier.explain("Music folders with write access").padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((4 * sg()).dp)) {
                Text("Music folders with write access", color = C.text, modifier = Modifier.anchor("Music folders with write access"), fontSize = 15.sp)
                Text("ReplayGain tags are written into phone songs in these folders (and their subfolders) without any question. " +
                    "Only these folders — nothing else on the phone.", color = C.muted, fontSize = 13.sp)
                if (grants.isEmpty()) Text("None yet — you're asked the first time a phone folder gets ReplayGain.", color = C.muted, fontSize = 13.sp)
                grants.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("▤  " + g.path.removePrefix("/storage/emulated/0").ifEmpty { "/" }, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        STextButton({ app.vlcinterface.sources.MusicFolderAccess.remove(ctxA, g); grants = app.vlcinterface.sources.MusicFolderAccess.grants(ctxA) }) { Text("Remove", color = C.bad) }
                    }
                }
                STextButton({ try { pick.launch(app.vlcinterface.sources.MusicFolderAccess.pickerIntent("/storage/emulated/0/Music")) } catch (_: Exception) {} }) {
                    Text("+ Add a music folder…", color = accent())
                }
            }
        }
    }
}

/** Response in dB of the treble band at [hz] for a gain of [db] (the same filter the sound engine uses, 48 kHz). */
private fun trebleDb(f: Double, peak: Boolean, f0: Double, w: Double, db: Double): Double {
    val fs = 48000.0
    val a = Math.pow(10.0, db / 40.0); val w0 = 2 * Math.PI * minOf(f0, fs * 0.45) / fs; val cw = Math.cos(w0); val sw = Math.sin(w0)
    val b: DoubleArray; val d: DoubleArray
    if (peak) {
        val al = sw / (2 * w)
        b = doubleArrayOf(1 + al * a, -2 * cw, 1 - al * a); d = doubleArrayOf(1 + al / a, -2 * cw, 1 - al / a)
    } else {
        val al = sw / 2 * Math.sqrt((a + 1 / a) * (1 / w - 1) + 2); val sa = 2 * Math.sqrt(a) * al
        b = doubleArrayOf(a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa))
        d = doubleArrayOf((a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa)
    }
    val om = 2 * Math.PI * f / fs
    fun mag(c: DoubleArray): Double {
        val re = c[0] + c[1] * Math.cos(om) + c[2] * Math.cos(2 * om); val im = -(c[1] * Math.sin(om) + c[2] * Math.sin(2 * om))
        return Math.sqrt(re * re + im * im)
    }
    return 20 * Math.log10(mag(b) / mag(d))
}

/**
 * Treble band (2026-11-21 21:50): the one filter the Treble slider drives — Shelf (lifts everything above the
 * frequency) or Peak (one bell band), its frequency, width (shelf slope / peak Q) and how much the slider's ends mean.
 * The curve is drawn from the values set (solid: Treble +5, dashed: −5); dragging on it sets the frequency.
 * Opened from the players with a double tap on the Treble slider.
 */
@Composable
private fun TrebleBandBox(s: Settings, set: (Settings) -> Unit) {
    fun put(n: Settings) { set(n); App.instance.player.applySoundChain() }
    val peak = s.trebleType == "peak"
    val width = if (peak) s.trebleQ else s.trebleSlope
    // The volume now (refreshed every second) and how much of the correction is left at it (flattening, 22:03).
    var volNow by remember { mutableStateOf(App.instance.player.volumeNowPct()) }
    LaunchedEffect(Unit) { while (true) { volNow = App.instance.player.volumeNowPct(); kotlinx.coroutines.delay(1000) } }
    val flatK = if (s.trebleFlatPct <= 0) 1f else (1f - volNow / s.trebleFlatPct).coerceIn(0f, 1f)
    SectionTitle("Treble band")
    CardBox {
        Column(Modifier.explain("Treble band").padding(horizontal = 16.dp, vertical = (10 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            // The response curve, from the values set.
            val a = accent(); val grid = C.line; val muted = C.muted
            val fMin = 50.0; val fMax = 20000.0
            fun xOf(f: Double, w: Float) = (Math.log(f / fMin) / Math.log(fMax / fMin) * w).toFloat()
            fun fOf(x: Float, w: Float) = fMin * Math.pow(fMax / fMin, (x / w).toDouble().coerceIn(0.0, 1.0))
            val range = maxOf(6f, s.trebleMaxDb + 2f)
            val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D0F13))
                .pointerInput(s.trebleType) {
                    fun setX(x: Float) {
                        val hz = (Math.round(fOf(x, size.width.toFloat()) / 100.0) * 100).toInt().coerceIn(1000, 16000)
                        if (hz != App.instance.store.current.settings.trebleHz) put(App.instance.store.current.settings.copy(trebleHz = hz))
                    }
                    detectDragGestures(onDragStart = { o -> setX(o.x) }) { ch, _ -> ch.consume(); setX(ch.position.x) }
                }) {
                val w = size.width; val h = size.height; val pad = 14f
                // The curves keep above the frequency labels at the bottom.
                val bottom = h - 26.dp.toPx(); val mid = (pad + bottom) / 2; val half = (bottom - pad) / 2
                fun yOf(db: Double) = (mid - (db / range * half)).toFloat()
                drawLine(grid, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(w, mid), 1f)
                listOf(100.0, 1000.0, 4000.0, 12000.0).forEach { f ->
                    val x = xOf(f, w)
                    drawLine(grid.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h), 1f)
                    // Labels measured, so they sit fully inside the box (23:31: they were cut off at the bottom).
                    val lay = textMeasurer.measure(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", androidx.compose.ui.text.TextStyle(color = muted, fontSize = 10.sp))
                    drawText(lay, topLeft = androidx.compose.ui.geometry.Offset(x + 4f, h - lay.size.height - 4f))
                }
                for ((sign, dashed) in listOf(1.0 to false, -1.0 to true)) {
                    val path = androidx.compose.ui.graphics.Path()
                    for (i in 0..120) {
                        val f = fMin * Math.pow(fMax / fMin, i / 120.0)
                        val y = yOf(trebleDb(f, peak, s.trebleHz.toDouble(), width.toDouble(), sign * s.trebleMaxDb))
                        if (i == 0) path.moveTo(0f, y) else path.lineTo(xOf(f, w), y)
                    }
                    drawPath(path, if (dashed) a.copy(alpha = 0.5f) else a, style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = if (dashed) 2f else 5f, pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) else null))
                }
                // Flattening on: the +5 curve as it is at the volume now (thin, full colour).
                if (s.trebleFlatPct > 0) {
                    val path = androidx.compose.ui.graphics.Path()
                    for (i in 0..120) {
                        val f = fMin * Math.pow(fMax / fMin, i / 120.0)
                        val y = yOf(trebleDb(f, peak, s.trebleHz.toDouble(), width.toDouble(), s.trebleMaxDb.toDouble() * flatK))
                        if (i == 0) path.moveTo(0f, y) else path.lineTo(xOf(f, w), y)
                    }
                    drawPath(path, Color.White.copy(alpha = 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                }
                // The dot: the frequency, on the +5 curve.
                val fx = xOf(s.trebleHz.toDouble(), w)
                drawCircle(a, 12f, androidx.compose.ui.geometry.Offset(fx, yOf(trebleDb(s.trebleHz.toDouble(), peak, s.trebleHz.toDouble(), width.toDouble(), s.trebleMaxDb.toDouble()))))
            }
            Text("Solid: Treble +5 · dashed: −5" + (if (s.trebleFlatPct > 0) " · white: +5 at the volume now" else "") + " · drag on the curve to set the frequency",
                color = C.muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Shelf", selected = !peak) { put(s.copy(trebleType = "shelf")) }
                Pill("Peak", selected = peak) { put(s.copy(trebleType = "peak")) }
                Box(Modifier.weight(1f))
                STextButton({ put(s.copy(trebleType = "shelf", trebleHz = 4000, trebleSlope = 0.6f, trebleQ = 1.0f, trebleMaxDb = 10f, trebleFlatPct = 0)) }) { Text("Reset") }
            }
            Text(if (peak) "Peak: one band around the frequency is raised or lowered." else "Shelf: everything above the frequency is raised or lowered.",
                color = C.muted, fontSize = 12.sp)
            // Frequency: typed with the app's digit pad, or slid (log scale 1–16 kHz).
            // Frequency: label, then the field across the full width so its digit pad has room (23:31: the pad was cut off in a
            // narrow box). What is typed is kept as typed and applied as soon as it is a frequency of 1000–16000 Hz.
            Text("Frequency", color = C.text, modifier = Modifier.anchor("Frequency"), fontSize = 15.sp)
            var hzText by remember(s.trebleHz) { mutableStateOf(s.trebleHz.toString()) }
            DigitField(hzText, "Hz (1000–16000)", 5, Modifier.fillMaxWidth()) { v ->
                hzText = v
                v.toIntOrNull()?.let { if (it in 1000..16000 && it != s.trebleHz) put(s.copy(trebleHz = it)) }
            }
            ThinSlider((Math.log(s.trebleHz / 1000.0) / Math.log(16.0)).toFloat(), { v ->
                val hz = (Math.round(1000.0 * Math.pow(16.0, v.toDouble()) / 100.0) * 100).toInt().coerceIn(1000, 16000); put(s.copy(trebleHz = hz))
            }, valueRange = 0f..1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (peak) "Width (Q)" else "Width (slope)", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("%.1f".format(java.util.Locale.US, width), color = accent(), fontWeight = FontWeight.SemiBold)
            }
            if (peak) ThinSlider(s.trebleQ, { v -> put(s.copy(trebleQ = Math.round(v * 10) / 10f)) }, valueRange = 0.3f..4f)
            else ThinSlider(s.trebleSlope, { v -> put(s.copy(trebleSlope = Math.round(v * 20) / 20f)) }, valueRange = 0.3f..1f)
            Text(if (peak) "Smaller = wider band, larger = narrower band." else "Smaller = gentler rise, 1.0 = steepest.", color = C.muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("At slider end (±5)", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("At slider end (±5)").weight(1f))
                Text("±%.0f dB".format(java.util.Locale.US, s.trebleMaxDb), color = accent(), fontWeight = FontWeight.SemiBold)
            }
            ThinSlider(s.trebleMaxDb, { v -> put(s.copy(trebleMaxDb = Math.round(v).toFloat())) }, valueRange = 4f..15f)
            // Fourth value (22:03): the correction flattens in proportion to the volume, to none at this volume.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Flatten at volume", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Flatten at volume").weight(1f))
                Text(if (s.trebleFlatPct <= 0) "Off" else "${s.trebleFlatPct} %", color = accent(), fontWeight = FontWeight.SemiBold)
            }
            ThinSlider(s.trebleFlatPct.toFloat(), { v -> put(s.copy(trebleFlatPct = (Math.round(v / 10f) * 10).coerceIn(0, 200))) }, valueRange = 0f..200f)
            Text(if (s.trebleFlatPct <= 0) "Off: the whole correction at every volume. Set a volume to flatten it gradually: all of it at volume 0, none at that volume."
                else "All of the correction at volume 0, none at ${s.trebleFlatPct} %. Now at ${volNow.toInt()} %: ${(flatK * 100).toInt()} % of the correction." +
                    (if (s.trebleFlatPct > 100) " Above 100 % only the app's own volume reaches (outputs Android isn't routing to)." else ""),
                color = C.muted, fontSize = 12.sp)
            Text("With the app's own sound output (libVLC's output keeps the plain treble).", color = C.muted, fontSize = 12.sp)
        }
    }
}

/** Sound settings on their own page, opened from the music player's gear button. */
@Composable
fun SoundSettingsScreen(nav: Navigator) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Sound settings", nav)
        Column(Modifier.weight(1f).anchorViewport(scroll, "sound").scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            SoundSettings(st.settings) { n -> store.update { it.copy(settings = n) } }
            Box(Modifier.height(16.dp))
        }
    }
}


/** Colour picker: hue, saturation and brightness sliders with a preview. */
@Composable
fun ColorPickDialog(start: Long, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null, onPick: (Long) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start.toInt(), it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v)).toLong() and 0xFFFFFFFFL
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.panel,
        title = { Text("Custom colour", color = C.text, modifier = Modifier.anchor("Custom colour")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy((4 * sg()).dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(argb)))
                Text("Hue", color = C.muted, fontSize = 13.sp)
                ThinSlider(h, { h = it }, valueRange = 0f..360f)
                Text("Saturation", color = C.muted, fontSize = 13.sp)
                ThinSlider(sat, { sat = it }, valueRange = 0f..1f)
                Text("Brightness", color = C.muted, fontSize = 13.sp)
                ThinSlider(v, { v = it }, valueRange = 0.2f..1f)
            }
        },
        confirmButton = { STextButton({ onPick(argb) }) { Text("Save", color = Color(argb)) } },
        dismissButton = {
            Row {
                onRemove?.let { STextButton(it) { Text("Remove", color = C.bad) } }
                STextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}


/**
 * Live sample, big enough to judge: a pop-up window or a menu at the chosen opacity over a busy background
 * (stripes and text, like a page behind it), so both what shows through and how readable it stays can be checked.
 */
@Composable
fun OpacityPreview(alpha: Float, label: String, fontSp: Int) {
    val a = accent()
    val menu = label.startsWith("Menu")
    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp))
        .drawBehind {
            val w = 22.dp.toPx()
            var x = -size.height
            var k = 0
            while (x < size.width) {
                drawLine(when (k % 3) { 0 -> a; 1 -> Color.White; else -> Color(0xFF2A6FDB) },
                    androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset(x + size.height, 0f), strokeWidth = w)
                x += w; k++
            }
        }) {
        // Page text behind the window.
        Column(Modifier.padding(10.dp)) {
            repeat(6) { Text("Artist – Album – Song title ${it + 1}", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
        Column(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).clip(RoundedCornerShape(if (menu) 8.dp else 20.dp))
            .background(Color(0xFF14171C).copy(alpha = alpha)).padding(if (menu) 6.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (menu) 2.dp else 6.dp)) {
            if (menu) listOf("▶  Play", "Play next", "Add to playlist…").forEach {
                Text(it, color = C.text, fontSize = fontSp.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = (8 * sg()).dp))
            } else {
                Text(label, color = C.text, fontSize = 20.sp)
                Text("Text inside a window", color = C.muted, fontSize = 14.sp)
                Text("Cancel     OK", color = a, fontSize = 15.sp, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

/**
 * Speed of one list's horizontal autoscroll (requested 2026-11-21 11:34): names too long for their row glide
 * continuously one way; 0 = still, 100 % = normal, up to 300 %. Text helper on a long press or a still hold.
 */
@Composable
fun ScrollSpeedCard(title: String, what: String, key: String) {
    val st by App.instance.store.state.collectAsState()
    val s = st.settings
    val v = app.vlcinterface.data.scrollSpeedOf(s, key)
    val ex = LocalExplain.current
    fun put(x: Int) = App.instance.store.update { a ->
        if (key == "upnext") a.copy(settings = a.settings.copy(songsScrollSpeed = x))
        else a.copy(settings = a.settings.copy(listScroll = a.settings.listScroll + (key to x)))
    }
    CardBox {
        Column(Modifier.explain(title).padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = C.text, fontSize = 15.sp)
                    Text(what, color = C.muted, fontSize = 13.sp)
                }
                Text(if (v == 0) "Off" else "$v%", color = accent(), fontWeight = FontWeight.SemiBold)
                STextButton({ put(100) }) { Text("Reset") }
            }
            ThinSlider(v.toFloat(), { x -> put((Math.round(x / 10f) * 10).coerceIn(0, 300)) }, valueRange = 0f..300f,
                onHoldStill = { ex(title, "") })
            Text("0 = names stay still · 100 % = normal · 300 % = three times faster", color = C.muted, fontSize = 13.sp)
        }
    }
}



/** A section to scroll to when a settings page opens (set by the button that opens it). */



/** Settings search: the words typed (kept while the app runs, so Back from a result shows the same results). */
object SettingsSearch {
    var query by mutableStateOf("")
    /** Settings that couldn't be found on their page this session (shown only at times): no longer offered. */
    private val missing = androidx.compose.runtime.mutableStateListOf<String>()
    fun notFound(key: String) { if (key !in missing) missing += key; ShortNote.show("\"$key\" isn't shown on this page right now") }

    class Hit(val e: SettingsEntry, val inHelp: Boolean)

    private fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun results(q: String): List<Hit> {
        val words = norm(q).split(' ', ',', '/').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val alarmOn = app.vlcinterface.alarm.Alarms.current.global.systemOn
        return SETTINGS_INDEX.asSequence()
            .filter { it.title.isEmpty() || it.title !in missing }
            // Alarm system switched off: its page shows only the clock's own settings.
            .filter { alarmOn || it.page != "alarm" || it.section.isEmpty() || it.section == "Display" || it.section.startsWith("Location") }
            .mapNotNull { e ->
                val name = norm(if (e.title.isEmpty()) e.pageName else e.title)
                val where = norm(e.pageName + " " + e.section + " " + e.hint)
                val help = norm(if (e.title.isEmpty()) "" else (SettingHelp.own(e.title) ?: HELP[e.title] ?: ""))
                // Every word must be found: in the name, where it is, or its explanation.
                if (!words.all { w -> w in name || w in where || w in help }) return@mapNotNull null
                val score = when {
                    name.startsWith(words[0]) -> 0
                    words.all { it in name } -> 1
                    words.all { it in name || it in where } -> 2
                    else -> 3
                } * 10 + (if (e.title.isEmpty()) 0 else if (e.title == e.section) 1 else 2)
                Triple(e, score, words.all { it in name || it in where }.not())
            }
            .sortedBy { it.second }
            .map { Hit(it.first, it.third) }
            .distinctBy { it.e.page + "|" + it.e.title }
            .take(60).toList()
    }

    /** The last 6 searches that led to a result being opened, newest first (kept across app restarts). */
    private val prefs get() = App.instance.getSharedPreferences("settings_search", android.content.Context.MODE_PRIVATE)
    val history = androidx.compose.runtime.mutableStateListOf<String>().apply {
        try { addAll(App.instance.getSharedPreferences("settings_search", android.content.Context.MODE_PRIVATE)
            .getString("history", "").orEmpty().split('\n').filter { it.isNotBlank() }.take(6)) } catch (_: Throwable) {}
    }
    private fun saveHistory() = try { prefs.edit().putString("history", history.joinToString("\n")).apply() } catch (_: Throwable) {}
    fun keep(q: String) {
        val w = q.trim().replace(Regex("\\s+"), " ")
        if (w.isEmpty()) return
        history.removeAll { it.equals(w, ignoreCase = true) }
        history.add(0, w)
        while (history.size > 6) history.removeAt(history.size - 1)
        saveHistory()
    }
    fun forget(w: String) { history.remove(w); saveHistory() }

    /** Opens the result's page at its setting (or its section). */
    fun open(nav: Navigator, e: SettingsEntry) {
        keep(query)
        SettingsAnchor.go(e.page, if (e.own) e.title else null, e.section)
        when (e.page) {
            "main" -> query = ""
            "clock" -> nav.go(Screen.ClockAspect)
            else -> openSettingsGroup(nav, e.page)
        }
    }
}

/** The results: the setting's name (words found in the accent colour), its page › section, its grey line. */
@Composable
private fun SettingsSearchResults(nav: Navigator, q: String) {
    val hits = SettingsSearch.results(q)
    if (hits.isEmpty()) {
        Text("No setting matches \"$q\"", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    Text("${hits.size} result${if (hits.size == 1) "" else "s"} · tap one to open it", color = C.muted, fontSize = 12.sp)
    val words = q.lowercase().split(' ', ',', '/').filter { it.isNotBlank() }
    @Composable fun lit(text: String, base: Color) = Text(androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val low = text.lowercase()
        words.forEach { w -> var i = low.indexOf(w); while (i >= 0) {
            addStyle(androidx.compose.ui.text.SpanStyle(color = accent(), fontWeight = FontWeight.SemiBold), i, i + w.length); i = low.indexOf(w, i + w.length) } }
    }, color = base, fontSize = if (base == C.text) 15.sp else 12.sp)
    CardBox {
        hits.forEachIndexed { i, h ->
            if (i > 0) Divider()
            val e = h.e
            Column(Modifier.fillMaxWidth().clickable { SettingsSearch.open(nav, e) }.padding(horizontal = 16.dp, vertical = (8 * sg()).dp)) {
                lit(if (e.title.isEmpty()) e.pageName + " (page)" else e.title, C.text)
                val where = if (e.title.isEmpty()) "Settings" else listOf(if (e.page == "main") "Settings" else e.pageName, e.section).filter { it.isNotEmpty() && it != e.title }.distinct().joinToString(" › ")
                if (where.isNotEmpty()) lit(where, C.muted)
                if (e.hint.isNotEmpty()) lit(e.hint, C.muted)
                if (h.inHelp) Text("Found in its explanation", color = C.muted, fontSize = 12.sp)
            }
        }
    }
}
