package app.vlcinterface.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Settings
import app.vlcinterface.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(nav: Navigator) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val s = st.settings
    fun set(n: Settings) = store.update { it.copy(settings = n) }
    val scroll = rememberScrollState()
    var deleteFile by remember { mutableStateOf(false) }
    if (deleteFile) DeleteFileDialog { deleteFile = false }
    Column(Modifier.fillMaxSize()) {
        val ctxV = LocalContext.current
        // Exact version of this build, e.g. "vlc interface 0.3.0-build29 (29)".
        val version = remember {
            try { ctxV.packageManager.getPackageInfo(ctxV.packageName, 0).let { "vlc interface ${it.versionName} (${it.longVersionCode})" } }
            catch (_: Exception) { "vlc interface ${app.vlcinterface.App.VERSION}" }
        }
        BackHeader("Settings", nav, version) {
            Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, C.line, RoundedCornerShape(20.dp))
                .clickable { nav.go(Screen.Backup) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text("Export / import", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Gestures")
            CardBox {
                ToggleRow("Volume swipe", "Right half, vertical", s.volumeSwipe) { set(s.copy(volumeSwipe = it)) }; Divider()
                ToggleRow("Brightness swipe", "Left half, vertical", s.brightnessSwipe) { set(s.copy(brightnessSwipe = it)) }; Divider()
                ValueRow("Double-tap seek", "Left / right edge", "${s.doubleTapSeekSec} s") {
                    val opts = listOf(5, 10, 15, 30); set(s.copy(doubleTapSeekSec = opts[(opts.indexOf(s.doubleTapSeekSec) + 1) % opts.size]))
                }; Divider()
                ToggleRow("Hold for 2× speed", "Release to return", s.holdForSpeed) { set(s.copy(holdForSpeed = it)) }
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
            SoundSettings(s, ::set)
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Radio buffer: ${s.radioBufferMin} min", color = C.text, fontSize = 15.sp)
                    Text("The last ${s.radioBufferMin} min are kept for saving (longer while a song is being saved); a lost connection back within ${s.radioBufferMin} min keeps the recording",
                        color = C.muted, fontSize = 12.sp)
                    var v by remember { mutableStateOf(s.radioBufferMin.toFloat()) }
                    androidx.compose.material3.Slider(v, { v = it }, valueRange = 1f..20f, steps = 18,
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
            SectionTitle("Network")
            CardBox {
                ToggleRow("Allow SMB1", "Old, unencrypted — fine on your home LAN, avoid over the internet", s.allowSmb1) { set(s.copy(allowSmb1 = it)) }
                Divider()
                // Song links: Spotify's own search with a free developer app (no Spotify login). Without it the
                // links are found through MusicBrainz and a web search.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Spotify links", color = C.text, fontSize = 15.sp)
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
                        title = { Text("Spotify keys", color = C.text) },
                        text = {
                            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    color = C.muted, fontSize = 12.sp)
                            }
                        },
                        confirmButton = { STextButton({ help = false }) { Text("OK", color = accent()) } },
                        dismissButton = { STextButton({
                            help = false
                            try { ctxV.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://developer.spotify.com/dashboard"))) } catch (_: Exception) {}
                        }) { Text("Open the site") } })
                }
            }
            // VPN: home NAS through ZeroTier / Tailscale … (their own app keeps the login: nothing to enter here).
            SectionTitle("VPN")
            CardBox {
                val vl = app.vlcinterface.sources.VpnLink
                val up by vl.vpnUp.collectAsState()
                val busy by vl.searching.collectAsState()
                ValueRow("Status", if (up) "A VPN is on" else "No VPN on", if (up) "On" else "Off") {}
                Divider()
                ToggleRow("Home NAS through the VPN", "Away from home with the VPN on, your LAN servers are reached through it — at home, directly", s.vpnNas) { set(s.copy(vpnNas = it)) }
                Divider()
                ToggleRow("Ask to start the VPN", "Away from home with the VPN off, when a home server can't be reached", s.vpnAskStart) { set(s.copy(vpnAskStart = it)) }
                Divider()
                ValueRow("VPN app", "Opened by “Start the VPN” (its saved login is used)",
                    when (s.vpnApp) { "zerotier" -> "ZeroTier"; "tailscale" -> "Tailscale"; "settings" -> "Android settings"; else -> "Auto" }) {
                    val o = listOf("auto", "zerotier", "tailscale", "settings"); set(s.copy(vpnApp = o[(o.indexOf(s.vpnApp) + 1).mod(o.size)]))
                }
                Divider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Home servers on the VPN", color = C.text, fontSize = 15.sp)
                    if (busy.isNotEmpty()) Text(busy, color = accent(), fontSize = 12.sp)
                    else if (up && s.vpnNas) Pill("Search the VPN now") { vl.discover() }
                    val kept = st.sources.filter { it.vpnHost.isNotBlank() }
                    if (kept.isEmpty()) Text("None yet — found by themselves when the VPN starts", color = C.muted, fontSize = 12.sp)
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
            // Logs, by kind. Each page has Errors only / Empty log / Share / Save to NAS.
            SectionTitle("Logs")
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
            SectionTitle("Subtitles")
            CardBox {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Preferred languages (e.g. en, es)", s.subLangs.joinToString(", ")) { v ->
                        set(s.copy(subLangs = v.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }))
                    }
                }
                ToggleRow("Auto-download missing subtitles", "When a movie has none in your languages", s.autoDownloadSubs) { set(s.copy(autoDownloadSubs = it)) }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OpenSubtitles.com account (free). Create an API key under your profile → API consumers.", color = C.muted, fontSize = 12.sp)
                    Field("API key", s.osApiKey, password = true) { set(s.copy(osApiKey = it.trim())) }
                    Field("Username", s.osUser) { set(s.copy(osUser = it.trim())) }
                    Field("Password", s.osPassword, password = true) { set(s.copy(osPassword = it)) }
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
            SectionTitle("Look")
            CardBox {
                ToggleRow("Auto-rotate", "On: the app turns with the phone · Off: stays upright (videos still turn with the rotate button)", s.autoRotate) { set(s.copy(autoRotate = it)) }
                Divider()
                // All long lists: the bar on the right; drag it (or touch the right edge) to jump anywhere fast.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Scroll bar width", color = C.text, fontSize = 15.sp)
                    Text("All lists: drag the bar on the right edge to scroll fast", color = C.muted, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (4..9).forEach { px -> Pill("$px px", selected = s.scrollbarPx == px) { set(s.copy(scrollbarPx = px)) } }
                    }
                }
            }
            // Deleting a file for good: only here (not in the lists' menus), with a search like the Songs list / player.
            CardBox {
                Row(Modifier.fillMaxWidth().clickable { deleteFile = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
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
            CardBox {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Button size", color = C.text, fontSize = 15.sp)
                            Text("Every symbol button and its tap area, everywhere in the app", color = C.muted, fontSize = 13.sp)
                        }
                        Text("${s.buttonScale}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(buttonScale = 75)) }) { Text("Reset") }
                    }
                    androidx.compose.material3.Slider(s.buttonScale.toFloat(), { v -> set(s.copy(buttonScale = (Math.round(v / 5f) * 5).coerceIn(50, 125))) }, valueRange = 50f..125f)
                    Text("75 % = 25 % smaller than standard (default) · 100 % = standard Android size", color = C.muted, fontSize = 12.sp)
                }
            }
            CardBox {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Songs list horizontal autoscroll", color = C.text, fontSize = 15.sp)
                            Text("Speed of long rows gliding to their end and back (Library → Songs)", color = C.muted, fontSize = 13.sp)
                        }
                        Text(if (s.songsScrollSpeed == 0) "Off" else "${s.songsScrollSpeed}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(songsScrollSpeed = 100)) }) { Text("Reset") }
                    }
                    androidx.compose.material3.Slider(s.songsScrollSpeed.toFloat(), { v -> set(s.copy(songsScrollSpeed = (Math.round(v / 10f) * 10).coerceIn(0, 300))) }, valueRange = 0f..300f)
                    Text("0 = rows stay still · 100 % = normal (slow) · 300 % = three times faster", color = C.muted, fontSize = 12.sp)
                }
            }
            CardBox {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Up next songs on the player", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Pill("−") { set(s.copy(upNextCount = (s.upNextCount - 1).coerceAtLeast(0))) }
                    Text("${s.upNextCount}", color = accent(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(40.dp))
                    Pill("+") { set(s.copy(upNextCount = (s.upNextCount + 1).coerceAtMost(20))) }
                }
            }
            CardBox {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pop-up opacity", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${s.popupOpacity}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(popupOpacity = 90)) }) { Text("Reset") }
                    }
                    OpacityPreview(s.popupOpacity / 100f, "Pop-up window", 15)
                    androidx.compose.material3.Slider(s.popupOpacity.toFloat(), { v -> set(s.copy(popupOpacity = (Math.round(v / 5f) * 5).coerceIn(50, 100))) }, valueRange = 50f..100f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Menu opacity (menus and search results, own setting)", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${s.menuOpacity}%", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(menuOpacity = 90)) }) { Text("Reset") }
                    }
                    OpacityPreview(s.menuOpacity / 100f, "Menu entry", UiPrefs.spOf(s.menuFont))
                    androidx.compose.material3.Slider(s.menuOpacity.toFloat(), { v -> set(s.copy(menuOpacity = (Math.round(v / 5f) * 5).coerceIn(30, 100))) }, valueRange = 30f..100f)
                    // Menu row spacing: how close menu (and search result) rows are; preview of three rows.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Menu row spacing", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${s.menuGap} dp", color = accent(), fontWeight = FontWeight.SemiBold)
                        STextButton({ set(s.copy(menuGap = 6)) }) { Text("Reset") }
                    }
                    Column(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF14171C).copy(alpha = s.menuOpacity / 100f))) {
                        listOf("Play now", "Add to queue", "Go to folder").forEach { t ->
                            Text(t, color = C.text, fontSize = UiPrefs.spOf(s.menuFont).sp, lineHeight = (UiPrefs.spOf(s.menuFont) + 4).sp,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = s.menuGap.dp))
                        }
                    }
                    androidx.compose.material3.Slider(s.menuGap.toFloat(), { v -> set(s.copy(menuGap = Math.round(v).coerceIn(0, 16))) }, valueRange = 0f..16f, steps = 15)
                    Text("Menu text", color = C.text, fontSize = 15.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("small" to "Small", "medium" to "Medium", "large" to "Large", "xlarge" to "Extra large").forEach { (k, l) ->
                            Pill(l, selected = s.menuFont == k, filled = s.menuFont == k) { set(s.copy(menuFont = k)) }
                        }
                    }
                }
            }
            // Separate colours for local / LAN files and for radio: choose which one the circles below set.
            var forRadio by remember { mutableStateOf(false) }
            val cur = if (forRadio) (s.radioAccent.takeIf { it != 0L } ?: s.accent) else s.accent
            fun pick(c: Long) = set(if (forRadio) s.copy(radioAccent = c) else s.copy(accent = c))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Colour for", color = C.text, fontSize = 15.sp)
                Pill("Files", selected = !forRadio, filled = !forRadio) { forRadio = false }
                Pill("Radio", selected = forRadio, filled = forRadio) { forRadio = true }
            }
            // Custom colours: up to 5 slots; tap uses one, long-press edits it. "+" adds while a slot is free.
            var editSlot by remember { mutableStateOf(-1) }      // -1 closed, 0..2 edit that slot, 3 new
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        existing -> { list[slot] = c; set(s.copy(customAccents = list, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio) c else s.radioAccent)) }
                        list.size < 5 -> { list += c; set(s.copy(customAccents = list, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio) c else s.radioAccent)) }
                        else -> replacing = c
                    }
                }
            }
            // All 5 slots used: tap the one to overwrite, or drop one first.
            replacing?.let { c ->
                AlertDialog(onDismissRequest = { replacing = null }, containerColor = C.panel,
                    title = { Text("Overwrite which?", color = C.text) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c)))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            s.customAccents.forEachIndexed { i, old ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(old)).border(2.dp, Color.White, CircleShape).clickable {
                                        replacing = null
                                        set(s.copy(customAccents = s.customAccents.toMutableList().also { it[i] = c }, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio) c else s.radioAccent))
                                    })
                                    Text("Drop", color = C.bad, fontSize = 12.sp, modifier = Modifier.clickable {
                                        set(s.copy(customAccents = s.customAccents.filterIndexed { k, _ -> k != i } + c, accent = if (forRadio) s.accent else c, radioAccent = if (forRadio) c else s.radioAccent)); replacing = null
                                    })
                                }
                            }
                        }
                        }
                    },
                    confirmButton = { STextButton({ replacing = null }) { Text("Cancel") } },
                )
            }
            Text("vlc interface ${App.VERSION} · playback by libVLC (LGPL)", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@Composable
fun BackupScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val store = App.instance.store
    val st by store.state.collectAsState()
    var withPasswords by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Store.ImportPreview?>(null) }
    var importPass by remember { mutableStateOf("") }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson(withPasswords, passphrase).toByteArray()) }
            "Exported."
        } catch (e: Exception) { "Export failed: ${e.message}" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: "" } catch (e: Exception) { "" }
        val p = store.previewImport(text)
        if (p.ok) preview = p else status = p.error
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Export & import", nav)
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SButton({
                val name = "vlc-interface-setup_" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
                exporter.launch(name)
            }, Modifier.fillMaxWidth().height(64.dp), enabled = !withPasswords || passphrase.length >= 4,
                colors = ButtonDefaults.buttonColors(containerColor = accent())) { Text("Export entire setup", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            Text("One file you can save to Downloads, your NAS or a cloud drive.", color = C.muted, fontSize = 13.sp)
            SectionTitle("What's in the file")
            CardBox {
                listOf(
                    "Settings, gestures, theme" to "all",
                    "LAN and Internet sources" to "${st.sources.size} sources",
                    "Radio favourites" to "${st.stations.size} stations",
                    "Subtitle style & sync offsets" to "${st.subOffsets.size + st.audioOffsets.size} offsets",
                    "Resume positions & scanned folders" to "${st.recent.size} files",
                ).forEach { (l, n) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge("✓", true); Text(l, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp)); Text(n, color = C.muted, fontSize = 12.sp)
                    }
                    Divider()
                }
                ToggleRow("Include saved passwords", "Encrypted with a passphrase you choose", withPasswords) { withPasswords = it }
                if (withPasswords) Column(Modifier.padding(12.dp)) { Field("Passphrase (min. 4 characters)", passphrase, password = true) { passphrase = it } }
            }
            SOutlinedButton({ importer.launch(arrayOf("application/json", "*/*")) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Import setup…", fontSize = 16.sp) }
            SectionTitle("On import")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Merge · add new, keep mine", selected = !replace) { replace = false }
                Pill("Replace all", selected = replace) { replace = true }
            }
            Text("You'll see a summary before anything changes. Your current setup is backed up automatically first.", color = C.muted, fontSize = 12.sp)
            if (status.isNotEmpty()) Text(status, color = C.text, fontSize = 14.sp)
            Box(Modifier.height(16.dp))
        }
    }

    val pv = preview
    if (pv != null) AlertDialog(
        onDismissRequest = { preview = null },
        title = { Text("Import setup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pv.sources} sources, ${pv.stations} radio stations. Mode: ${if (replace) "replace everything" else "merge (keep mine, add new)"}.")
                if (pv.needsPassphrase) Field("Passphrase for passwords", importPass, password = true) { importPass = it }
            }
        },
        confirmButton = {
            STextButton({
                val err = store.applyImport(pv, replace, importPass)
                status = err ?: "Imported. Previous setup backed up."
                preview = null
            }) { Text("Import") }
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
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Per output device", color = C.text, fontSize = 15.sp)
        val na = app.vlcinterface.player.NativeAudio
        Text("Sound engine", color = C.text, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text("Added on top of the general preamp (total max +12 dB). Applied automatically when the output changes.", color = C.muted, fontSize = 12.sp)
        // Auto loudness: Loudness follows the phone's volume; each device below has its own start %.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto loudness", color = C.text, fontSize = 15.sp)
                Text("Full Loudness up to each device's start %, flattening step by step above it, none at full volume", color = C.muted, fontSize = 12.sp)
            }
            androidx.compose.material3.Switch(st.settings.autoLoud, { on ->
                store.update { it.copy(settings = it.settings.copy(autoLoud = on)) }; App.instance.player.applySoundChain()
            })
        }
        names.forEach { n -> DeviceSoundControls(n) }
    }
}

@Composable
fun DeviceSoundControls(name: String) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val pre = st.devicePreamp[name] ?: 0f
    val bal = st.deviceBalance[name] ?: 0
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Preamp", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            androidx.compose.material3.Slider(pre, { v ->
                store.update { it.copy(devicePreamp = it.devicePreamp + (name to Math.round(v * 2) / 2f)) }; App.instance.player.applySoundChain()
            }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
            Text("%+.1f dB".format(pre), color = accent(), fontSize = 12.sp, modifier = Modifier.width(64.dp))
        }
        if (st.settings.autoLoud) {
            val from = st.deviceLoudFrom[name] ?: 50
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loudness\nflat from", color = C.muted, fontSize = 12.sp, lineHeight = 13.sp, modifier = Modifier.width(64.dp))
                androidx.compose.material3.Slider(from.toFloat(), { v ->
                    store.update { it.copy(deviceLoudFrom = it.deviceLoudFrom + (name to (Math.round(v / 5f) * 5).coerceIn(0, 95))) }
                    App.instance.player.applySoundChain()
                }, valueRange = 0f..95f, modifier = Modifier.weight(1f))
                Text("$from % vol", color = accent(), fontSize = 12.sp, modifier = Modifier.width(64.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Balance", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            Text("L", color = C.muted, fontSize = 12.sp)
            androidx.compose.material3.Slider(bal.toFloat(), { v ->
                val snapped = if (kotlin.math.abs(v) < 6f) 0 else v.toInt()
                store.update { it.copy(deviceBalance = it.deviceBalance + (name to snapped)) }; App.instance.player.applySoundChain()
            }, valueRange = -100f..100f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            Text("R", color = C.muted, fontSize = 12.sp)
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
    SectionTitle("Sound")
    CardBox {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start volume", color = C.text, fontSize = 15.sp)
                    Text("Set every time the app starts, before any sound", color = C.muted, fontSize = 13.sp)
                }
                Text("${s.startVolume}%", color = accent(), fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.Slider(s.startVolume.toFloat(), { set(s.copy(startVolume = it.toInt())) }, valueRange = 0f..100f)
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Preamp (everything)", color = C.text, fontSize = 15.sp)
                    Text(if (s.preampDb > 0f) "Above 0 dB loud tracks may distort" else "Music, video and radio", color = C.muted, fontSize = 13.sp)
                }
                Text("%+.1f dB".format(s.preampDb), color = accent(), fontWeight = FontWeight.SemiBold)
                STextButton({ set(s.copy(preampDb = 0f)); App.instance.player.applySoundChain() }) { Text("Reset") }
            }
            androidx.compose.material3.Slider(s.preampDb, { v -> set(s.copy(preampDb = Math.round(v * 2) / 2f)); App.instance.player.applySoundChain() }, valueRange = -12f..12f)
            Divider()
            // All three player sliders on / off: off takes them off the players and stops their processing.
            ToggleRow("Loudness, Treble and Preamp sliders", "On the audio and radio players", s.toneSliders) { on ->
                set(s.copy(toneSliders = on)); App.instance.player.applySoundChain()
            }
        }
        Divider()
        DeviceSoundList()
        Divider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ReplayGain (tagged music only)", color = C.text, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("off" to "Off", "track" to "Track", "album" to "Album", "auto" to "Auto").forEach { (k, l) ->
                    Pill(l, selected = s.rgMode == k) { set(s.copy(rgMode = k)) }
                }
            }
            Text("Auto: album gain when playing an album in order, track gain for shuffle and playlists. Videos are never adjusted.", color = C.muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ReplayGain pre-amp", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("%+.1f dB".format(s.rgPreampDb), color = accent(), fontSize = 13.sp)
            }
            androidx.compose.material3.Slider(s.rgPreampDb, { v -> set(s.copy(rgPreampDb = Math.round(v * 2) / 2f)) }, valueRange = -6f..6f)
        }
        ToggleRow("Prevent clipping", "Uses the peak values in the tags", s.rgClipProtect) { set(s.copy(rgClipProtect = it)) }
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
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        title = { Text("Custom colour", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(argb)))
                Text("Hue", color = C.muted, fontSize = 13.sp)
                androidx.compose.material3.Slider(h, { h = it }, valueRange = 0f..360f)
                Text("Saturation", color = C.muted, fontSize = 13.sp)
                androidx.compose.material3.Slider(sat, { sat = it }, valueRange = 0f..1f)
                Text("Brightness", color = C.muted, fontSize = 13.sp)
                androidx.compose.material3.Slider(v, { v = it }, valueRange = 0.2f..1f)
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
                Text(it, color = C.text, fontSize = fontSp.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            } else {
                Text(label, color = C.text, fontSize = 20.sp)
                Text("Text inside a window", color = C.muted, fontSize = 14.sp)
                Text("Cancel     OK", color = a, fontSize = 15.sp, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}
