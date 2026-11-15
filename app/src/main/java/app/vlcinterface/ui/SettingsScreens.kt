package app.vlcinterface.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
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
    Column(Modifier.fillMaxSize()) {
        BackHeader("Settings", nav) {
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
                ToggleRow("Resume where I left off", "Per file", s.resume) { set(s.copy(resume = it)) }; Divider()
                ToggleRow("Keep audio in background", "Notification controls", s.backgroundAudio) { set(s.copy(backgroundAudio = it)) }
            }
            SoundSettings(s, ::set)
            SectionTitle("Network")
            CardBox {
                ToggleRow("Allow SMB1", "Old, unencrypted — fine on your home LAN, avoid over the internet", s.allowSmb1) { set(s.copy(allowSmb1 = it)) }; Divider()
                ValueRow("Network logs", "One page per server, with Empty log", "Open") { nav.go(Screen.Logs) }
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
                ToggleRow("Watch folders for changes", "Off: no background file watching", s.watchFolders) { set(s.copy(watchFolders = it)) }
            }
            SectionTitle("Look")
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(0xFFFF8A3D, 0xFF4FB3FF, 0xFF7BD88F, 0xFFC792EA).forEach { c ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                        .border(if (s.accent == c) 3.dp else 0.dp, Color.White, CircleShape).clickable { set(s.copy(accent = c)) })
                }
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
            Button({
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
            OutlinedButton({ importer.launch(arrayOf("application/json", "*/*")) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Import setup…", fontSize = 16.sp) }
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
            TextButton({
                val err = store.applyImport(pv, replace, importPass)
                status = err ?: "Imported. Previous setup backed up."
                preview = null
            }) { Text("Import") }
        },
        dismissButton = { TextButton({ preview = null }) { Text("Cancel") } },
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
                Pill(l, selected = st.settings.soundEngine == k) {
                    if (st.settings.soundEngine != k) {
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
                TextButton({ set(s.copy(preampDb = 0f)); App.instance.player.applySoundChain() }) { Text("Reset") }
            }
            androidx.compose.material3.Slider(s.preampDb, { v -> set(s.copy(preampDb = Math.round(v * 2) / 2f)); App.instance.player.applySoundChain() }, valueRange = -12f..12f)
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
