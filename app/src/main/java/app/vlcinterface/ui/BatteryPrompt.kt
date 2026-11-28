@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.vlcinterface.ui

import app.vlcinterface.data.CrashLog

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.combinedClickable
import app.vlcinterface.App
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
            val hw = android.os.Build.MANUFACTURER.lowercase() in listOf("huawei", "honor")
            Text("Android's battery saver can stop the app between two songs while you use other apps. " +
                "Allow it to run in the background (battery: Unrestricted) so playlists go on to the end." +
                (if (hw) "\n\nHuawei / Honor: then also App launch → vlc interface → switch off \"Manage automatically\" and allow Auto-launch, Secondary launch and Run in background (\"App launch\" opens it)." else ""),
                color = C.text)
        },
        confirmButton = { Row {
            if (android.os.Build.MANUFACTURER.lowercase() in listOf("huawei", "honor")) STextButton({ BrandBattery.open(ctx) }) { Text("App launch", color = accent()) }
            STextButton({ done(); Battery.ask(ctx) }) { Text("Allow", color = accent()) } } },
        dismissButton = { STextButton({ done() }) { Text("Not now") } },
    )
}


/** "Player closed by sleep timer" — shown when the sleep timer stopped playback (also on coming back to the app). */
@Composable
fun SleepClosedHost() {
    val at by App.instance.player.sleptAt.collectAsState()
    if (at <= 0L) return
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
    AutoInfoDialog(onDismissRequest = { App.instance.player.sleepSeen() }, containerColor = C.panel,
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
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + CrashLog.guard).launch {
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


/**
 * End of an automatic (silent) folder ReplayGain scan: a short pop-up for 5 seconds — the folder, how many songs were
 * scanned, and each song with its new track gain (requested 2026-11-21 12:01). Tap outside to close it sooner.
 */
@Composable
fun RgAutoSummaryHost() {
    val sum by app.vlcinterface.sources.RgScanner.autoDone.collectAsState()
    val s = sum ?: return
    androidx.compose.runtime.LaunchedEffect(s) { kotlinx.coroutines.delay(5_000); app.vlcinterface.sources.RgScanner.autoDone.value = null }
    val ok = s.songs.count { it.second != null }
    androidx.compose.ui.window.Dialog(onDismissRequest = { app.vlcinterface.sources.RgScanner.autoDone.value = null }) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(C.panel)
            .border(1.dp, accent().copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("✓  ReplayGain added", color = accent(), fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(if (ok == s.songs.size) "All ${s.songs.size} song${if (s.songs.size == 1) "" else "s"} of \"${s.folder}\" were scanned."
                 else "$ok of ${s.songs.size} songs of \"${s.folder}\" were scanned.", color = C.text, fontSize = 14.sp,
                modifier = androidx.compose.ui.Modifier.padding(top = 4.dp, bottom = 8.dp))
            val shown = s.songs.take(10)
            shown.forEachIndexed { i, (t, g) ->
                if (i > 0) Divider()
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(t, color = C.text, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = androidx.compose.ui.Modifier.weight(1f).padding(end = 10.dp))
                    Text(g?.let { (if (it >= 0) "+" else "−") + "%.2f dB".format(java.util.Locale.US, kotlin.math.abs(it)) } ?: "not measured",
                        color = if (g == null) C.bad else accent(), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
            if (s.songs.size > shown.size) Text("… and ${s.songs.size - shown.size} more", color = C.muted, fontSize = 12.sp,
                modifier = androidx.compose.ui.Modifier.padding(top = 4.dp))
            Text("Track gain · kept in the app", color = C.muted, fontSize = 11.sp, modifier = androidx.compose.ui.Modifier.padding(top = 8.dp))
        }
    }
}


/**
 * A network song scanned in the last 10 days but with no ReplayGain in its file (it wasn't written): a short pop-up
 * (5 s, at most once an hour) names one such song and the date it was scanned; it's scanned again and the problem is
 * in the error log (2026-11-21 12:06–12:09). Songs whose values are in the file are simply left out, without a pop-up.
 */
@Composable
fun RgRecentAskHost() {
    val ask by app.vlcinterface.sources.RgScanner.recentAsk.collectAsState()
    val a = ask ?: return
    fun close() { app.vlcinterface.sources.RgScanner.recentAsk.value = null }
    androidx.compose.runtime.LaunchedEffect(a) { kotlinx.coroutines.delay(5_000); if (app.vlcinterface.sources.RgScanner.recentAsk.value === a) close() }
    val fmt = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
    val (t, at) = a.songs.first()
    androidx.compose.ui.window.Dialog(onDismissRequest = { close() }) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(C.panel)
            .border(1.dp, C.bad.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("⚠  ReplayGain wasn't written", color = C.bad, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("\"$t\"", color = C.text, fontSize = 14.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = androidx.compose.ui.Modifier.padding(top = 6.dp))
            Text("This item was previously scanned for ReplayGain on:", color = C.muted, fontSize = 13.sp, modifier = androidx.compose.ui.Modifier.padding(top = 6.dp))
            Text(fmt.format(java.util.Date(at)), color = accent(), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text((if (a.count > 1) "It and ${a.count - 1} more have" else "It has") + " no ReplayGain in the file: scanned again, noted in the error log.",
                color = C.muted, fontSize = 12.sp, modifier = androidx.compose.ui.Modifier.padding(top = 6.dp))
        }
    }
}


/** Low battery pop-ups: the 15 % warning, and "stopped" at 10 %. */
@Composable
fun LowBatteryHost() {
    val n by app.vlcinterface.player.BatteryGuard.notice.collectAsState()
    if (n == 0) return
    val g = app.vlcinterface.player.BatteryGuard
    AutoInfoDialog(onDismissRequest = { g.seen() }, containerColor = C.panel,
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
 * Wi-Fi lost, mobile data in use: what needed internet was stopped — listed here. Continue on mobile data,
 * stay stopped, or exit. For radio: lower-bitrate favourites, and FLAC advised against on mobile data.
 */
@Composable
fun WifiLostHost() {
    val ask by app.vlcinterface.player.NetGuard.ask.collectAsState()
    if (!ask) return
    val g = app.vlcinterface.player.NetGuard
    val stopped by g.stopped.collectAsState()
    val st by App.instance.store.state.collectAsState()
    val cur = App.instance.player.state.value.item
    val radio = cur?.isRadio == true
    val curMeta = st.stations.firstOrNull { it.url == cur?.uri }?.meta ?: ""
    val light = if (!radio) emptyList() else st.stations.filter { s -> g.bitrateOf(s.meta).let { it in 1..128 } && !s.meta.contains("flac", true) && s.url != cur?.uri }
    AlertDialog(onDismissRequest = {}, containerColor = C.panel,
        title = { Text("Wi-Fi lost — mobile data in use", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stopped, because it needs internet:", color = C.text)
                stopped.forEach { Text("• $it", color = C.text, fontSize = 13.sp) }
                Text("Phone files and the rest go on. Nothing uses mobile data unless you continue.", color = C.muted, fontSize = 13.sp)
                if (radio) {
                    Text("Use lower bitrate stations on mobile data (128 kbps or less). Don't use FLAC stations on mobile data: they use a lot of data.",
                        color = C.muted, fontSize = 13.sp)
                    if (curMeta.contains("flac", true)) Text("This station is FLAC.", color = C.bad, fontSize = 13.sp)
                }
                if (light.isNotEmpty()) {
                    Text("Lower bitrate favourites", color = C.text, fontSize = 14.sp)
                    light.take(8).forEach { s ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.tile).clickable {
                            g.goOnMobile(); App.instance.player.playStation(s.name, s.url)
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
        confirmButton = {
            Row {
                STextButton({ g.stayStopped() }) { Text("Stay stopped") }
                STextButton({ g.goOnMobile() }) { Text("Continue on mobile data", color = accent()) }
            }
        },
        dismissButton = { STextButton({ g.ask.value = false; exitApp() }) { Text("Exit") } },
    )
}

/**
 * The player unused for an hour on mobile data: mobile data has to be allowed again — Accept / Deny, with what
 * each one means.
 */
@Composable
fun MobileIdleHost() {
    val g = app.vlcinterface.player.NetGuard
    val ask by g.idleAsk.collectAsState()
    if (!ask) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = {}, containerColor = C.panel,
        title = { Text("Use mobile data again?", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The player wasn't used for an hour and there's no Wi-Fi.", color = C.text)
                Text("Accept: everything goes on over mobile data for this session, with no more questions — network songs, " +
                    "the NAS through the VPN (opened if it's off), radio and saving songs from it, ReplayGain scans and indexing of " +
                    "network folders, pictures and song links. The data used is counted by your mobile plan.", color = C.text, fontSize = 13.sp)
                Text("Deny: only songs on the phone play. Network songs and radio wait (press “Use NAS” on the player when you " +
                    "want them), network background work is paused, no pictures or song links are fetched. Everything goes " +
                    "on by itself when Wi-Fi is back.", color = C.muted, fontSize = 13.sp)
            }
        },
        confirmButton = { STextButton({ g.idleAnswer(ctx, true) }) { Text("Accept", color = accent(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } },
        dismissButton = { STextButton({ g.idleAnswer(ctx, false) }) { Text("Deny") } },
    )
}

/**
 * "Use NAS": always on the player. Highlighted while NAS use is accepted; works only with the VPN on (away from
 * home). Tap: the NAS and shares stored on Libraries → LAN, with their state, to choose which to use.
 */
@Composable
fun UseNasButton() {
    val v = app.vlcinterface.sources.VpnLink
    val on by v.useNas.collectAsState()
    val vpn by v.vpnUp.collectAsState()
    var pick by remember { mutableStateOf(false) }
    var needVpn by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The NAS away from home goes only through the VPN: "VPN-NAS on" / "off". Tap: on/off (several servers: pick
    // which); long-press: the list. Without the VPN: a 3 s note to start it.
    val active = on.isNotEmpty() && vpn
    val link by app.vlcinterface.player.NetGuard.link.collectAsState()
    val stS by App.instance.store.state.collectAsState()
    // Status shown on the button (the tap works as before): at home → NAS active; away with the VPN → VPN - NAS active;
    // VPN without NAS use → VPN active; NAS in use but not answering → NAS unreachable; nothing → VPN-NAS off.
    val servers = remember(stS.sources, vpn, link) { v.lanServers() }
    val home = remember(servers, vpn, link) { servers.any { v.atHome(it) } }
    val nasInUse = home || on.isNotEmpty()
    var reach by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(nasInUse, on, vpn, link, home) {
        reach = null
        while (nasInUse) {
            val list = if (home) servers.filter { v.atHome(it) } else servers.filter { it.id in on }
            reach = when {
                !home && !vpn -> false                 // away from home without the VPN: the NAS can't be reached
                list.isEmpty() -> true
                else -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    list.any { try { v.reachableNow(it) } catch (_: Exception) { false } } }
            }
            kotlinx.coroutines.delay(30_000)
        }
    }
    val label = when {
        nasInUse && reach == false -> "NAS unreachable"
        home -> "NAS active"
        on.isNotEmpty() && vpn -> "VPN - NAS active"
        on.isNotEmpty() -> "NAS unreachable"
        vpn -> "VPN active"
        else -> "VPN-NAS off"
    }
    val lit = label == "NAS active" || label == "VPN - NAS active"
    Box(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(12.dp))
        .background(if (lit) accent() else C.tile)
        .border(1.dp, if (lit) accent() else if (vpn) accent().copy(alpha = 0.6f) else C.muted.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        .combinedClickable(onClick = {
            when {
                !vpn -> needVpn = true
                active -> v.setUse(emptySet()).also { app.vlcinterface.player.NetGuard.mobileOk = false }
                v.lanServers().size == 1 -> app.vlcinterface.player.NetGuard.useNas(ctx, v.lanServers().map { it.id }.toSet())
                else -> pick = true
            }
        }, onLongClick = { if (vpn) pick = true else needVpn = true }).padding(horizontal = 12.dp, vertical = 2.dp)) {
        val col = if (lit) Color.Black else if (label == "NAS unreachable") Color(0xFFE57373) else if (vpn) accent() else C.muted
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (link.isNotEmpty()) { NetSymbol(link, col); Box(Modifier.width(6.dp)) }
            Text(label, color = col, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
    if (needVpn) {
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(3_000); needVpn = false }
        AlertDialog(onDismissRequest = { needVpn = false }, containerColor = C.panel,
            title = { Text("Start the VPN", color = C.text) },
            text = { Text("The NAS is used away from home only through the VPN. Start it, then press VPN-NAS again.", color = C.text, fontSize = 14.sp) },
            confirmButton = { STextButton({ needVpn = false; android.widget.Toast.makeText(ctx, v.startVpn(ctx), android.widget.Toast.LENGTH_LONG).show() }) {
                Text("Start the VPN", color = accent(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } })
    }
    if (pick) NasPickDialog { pick = false }
}

/** The NAS / shares from Libraries → LAN: state (home, VPN, reachable) and which to use away from home. */
@Composable
fun NasPickDialog(onClose: () -> Unit) {
    val v = app.vlcinterface.sources.VpnLink
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by App.instance.store.state.collectAsState()
    val vpn by v.vpnUp.collectAsState()
    val on by v.useNas.collectAsState()
    val servers = st.sources.filter { it.zone == app.vlcinterface.data.Zone.LAN && it.proto == "smb" }
    var chosen by remember { mutableStateOf(on.ifEmpty { v.lastUsed.ifEmpty { servers.map { it.id }.toSet() } }) }
    val reach = remember { mutableStateMapOf<String, Boolean?>() }
    val diag = remember { mutableStateMapOf<String, String>() }
    var addFor by remember { mutableStateOf<app.vlcinterface.data.Source?>(null) }
    var recheck by remember { mutableStateOf(0) }
    // Opened with the VPN up and a server away from home: the VPN network is searched afresh for it (stored
    // addresses don't matter), then the rows are checked again.
    val searchingNow by v.searching.collectAsState()
    LaunchedEffect(vpn) {
        if (vpn && servers.any { !v.atHome(it) }) {
            v.rescan(); kotlinx.coroutines.delay(1500)
            while (v.searching.value.isNotEmpty()) kotlinx.coroutines.delay(500)
            recheck++
        }
    }
    LaunchedEffect(servers.map { it.id + v.hostNow(it) + it.altHosts.joinToString() }, vpn, recheck) {
        servers.forEach { s -> reach[s.id] = null; diag.remove(s.id); launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!v.atHome(s) && vpn) { val (ok, t) = v.vpnDiagnosis(s); diag[s.id] = t; reach[s.id] = ok } else reach[s.id] = v.reachableNow(s)
        } }
    }
    addFor?.let { src ->
        var ip by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addFor = null }, containerColor = C.panel,
            title = { Text("VPN address of ${src.name.ifBlank { src.host }}", color = C.text) },
            text = { Column {
                Text("Its address on the VPN (e.g. its ZeroTier IP). Kept with its LAN address; songs stay the same.", color = C.muted, fontSize = 13.sp)
                androidx.compose.material3.OutlinedTextField(ip, { ip = it.trim() }, singleLine = true, placeholder = { Text("10.147.17.5") })
            } },
            confirmButton = { STextButton({ if (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) { v.addAddress(src, ip); addFor = null; recheck++ } }) { Text("Add", color = accent()) } },
            dismissButton = { STextButton({ addFor = null }) { Text("Cancel") } })
    }
    AlertDialog(onDismissRequest = onClose, containerColor = C.panel,
        title = { Text("NAS and network shares", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (vpn) "VPN on. Choose what to use — data goes over your current connection (mobile data too)."
                    else "VPN off: away from home the NAS can't be used. Start the VPN, then choose.", color = if (vpn) C.text else C.bad, fontSize = 13.sp)
                if (searchingNow.isNotEmpty()) Text(searchingNow, color = accent(), fontSize = 12.sp)
                if (servers.isEmpty()) Text("No NAS or share yet — add them on Libraries → LAN.", color = C.muted, fontSize = 13.sp)
                servers.forEach { s ->
                    val home = v.atHome(s)
                    val r = reach[s.id]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.tile)
                        .clickable(enabled = !home) { chosen = if (s.id in chosen) chosen - s.id else chosen + s.id }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(home || s.id in chosen, { c -> chosen = if (c) chosen + s.id else chosen - s.id }, enabled = !home)
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { s.host }, color = C.text, fontSize = 14.sp)
                            Text(diag[s.id] ?: (v.hostNow(s) + " · " + when { home -> "home network (direct)"; r == null -> "checking…"; r -> "reachable"; else -> "not reachable" }),
                                color = if (r == false && !home) C.bad else C.muted, fontSize = 12.sp)
                            // Not reachable over the VPN: find its VPN address, or type it.
                            if (!home && vpn && r == false) Row {
                                STextButton({ v.rescan()
                                    kotlinx.coroutines.MainScope().launch { kotlinx.coroutines.delay(1500); while (v.searching.value.isNotEmpty()) kotlinx.coroutines.delay(500); recheck++ } }) { Text("Search for NAS again", fontSize = 12.sp) }
                                STextButton({ addFor = s }) { Text("Add VPN address", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (!vpn) STextButton({ android.widget.Toast.makeText(ctx, v.startVpn(ctx), android.widget.Toast.LENGTH_LONG).show(); v.retryLater() }) { Text("Start the VPN", color = accent()) }
                else STextButton({ app.vlcinterface.player.NetGuard.useNas(ctx, chosen.filter { id -> servers.any { it.id == id } }.toSet()); onClose() }, enabled = chosen.isNotEmpty()) {
                    Text("Use", color = accent(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            }
        },
        dismissButton = {
            Row {
                if (on.isNotEmpty()) STextButton({ app.vlcinterface.player.NetGuard.useNas(ctx, emptySet()); onClose() }) { Text("Stop using") }
                STextButton(onClose) { Text("Close") }
            }
        },
    )
}

/** Away from the home subnet with the VPN on: use the NAS now, or later with the player's button. */
@Composable
fun NasUseQuestionHost() {
    val v = app.vlcinterface.sources.VpnLink
    val ask by v.askUse.collectAsState()
    if (!ask) return
    var pick by remember { mutableStateOf(false) }
    if (pick) { NasPickDialog { pick = false; v.askUse.value = false }; return }
    AlertDialog(onDismissRequest = { v.askUse.value = false }, containerColor = C.panel,
        title = { Text("Away from home", color = C.text) },
        text = { Text("You're not on your home network; the VPN is on. Use the NAS now? Its data goes over your current connection " +
            "(mobile data too). You can switch it any time with “Use NAS” on the player.", color = C.text, fontSize = 14.sp) },
        confirmButton = { STextButton({ pick = true }) { Text("Use NAS…", color = accent(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } },
        dismissButton = { STextButton({ v.askUse.value = false }) { Text("Not now") } })
}

/** The sound output stayed on libVLC after the app tried twice to bring its own output back. */
@Composable
fun OutputFallbackHost() {
    val player = App.instance.player
    val why by player.fallbackNotice.collectAsState()
    if (why.isEmpty()) return
    AutoInfoDialog(onDismissRequest = { player.fallbackNotice.value = "" }, containerColor = C.panel,
        title = { Text("Sound output", color = C.text) },
        text = { Text("The output is set on libVLC because: $why", color = C.text) },
        confirmButton = { STextButton({ player.fallbackNotice.value = "" }) { Text("OK", color = accent()) } },
    )
}


/** VPN-NAS switched on by hand: Wi-Fi (caching ahead and ReplayGain allowed) or mobile data (playback only)? */
@Composable
fun NasLinkQuestionHost() {
    val v = app.vlcinterface.sources.VpnLink
    val ask by v.askLink.collectAsState()
    if (!ask) return
    AlertDialog(onDismissRequest = {}, containerColor = C.panel,
        title = { Text("Wi-Fi or mobile data?", color = C.text) },
        text = { Text("Wi-Fi: songs cached ahead and ReplayGain scans/updates on the NAS too. Mobile data: the NAS only plays.", color = C.text, fontSize = 14.sp) },
        confirmButton = { STextButton({ v.answerLink(true) }) { Text("Wi-Fi", color = accent(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } },
        dismissButton = { STextButton({ v.answerLink(false) }) { Text("Mobile data") } })
}


/** A file server found on the VPN that matches no NAS: set it up as a new one? */
@Composable
fun NewVpnServerHost() {
    val v = app.vlcinterface.sources.VpnLink
    val f by v.askNew.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    f?.let { found ->
        AlertDialog(onDismissRequest = { v.skipNew(found, false) }, containerColor = C.panel,
            title = { Text("New file server on the VPN", color = C.text) },
            text = { Text("${found.nbName.ifEmpty { "A server" }} answers at ${found.ip} and isn't one of your NAS. Set it up as a new NAS? " +
                "You choose its folders in Libraries → LAN.", color = C.text, fontSize = 14.sp) },
            confirmButton = { STextButton({ v.addNew(found); android.widget.Toast.makeText(ctx, "Added — choose its folders in Libraries → LAN", android.widget.Toast.LENGTH_LONG).show() }) {
                Text("Set up", color = accent(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } },
            dismissButton = { Row {
                STextButton({ v.skipNew(found, true) }) { Text("Never") }
                STextButton({ v.skipNew(found, false) }) { Text("Not now") }
            } })
    }
}


/** Short pop-up (3 s): which network is being searched for NAS. */
@Composable
fun NasScanNoteHost() {
    val v = app.vlcinterface.sources.VpnLink
    val note by v.scanNote.collectAsState()
    if (note.isEmpty()) return
    LaunchedEffect(note) { kotlinx.coroutines.delay(3000); v.scanNote.value = "" }
    androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter, offset = androidx.compose.ui.unit.IntOffset(0, 120)) {
        Box(Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(12.dp)).background(C.popup).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(note, color = C.text, fontSize = 13.sp)
        }
    }
}


/** NAS not found on the VPN: which network should be searched? The phone's networks now are offered, or type one. */
@Composable
fun SubnetQuestionHost() {
    val v = app.vlcinterface.sources.VpnLink
    val choices by v.askSubnet.collectAsState()
    if (choices.isEmpty()) return
    var typed by remember(choices) { mutableStateOf(choices.first()) }
    val ok = v.validSubnet(typed)
    AlertDialog(onDismissRequest = { v.askSubnet.value = emptyList() }, containerColor = C.panel,
        title = { Text("NAS not found — which network to search?", color = C.text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Networks on this phone now (tap one), or type the subnet your NAS is on, e.g. its ZeroTier network:", color = C.muted, fontSize = 13.sp)
            choices.forEach { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (c == typed) accent().copy(alpha = 0.18f) else C.tile)
                    .clickable { typed = c }.padding(horizontal = 10.dp, vertical = 8.dp)) { Text(c, color = C.text, fontSize = 14.sp) }
            }
            androidx.compose.material3.OutlinedTextField(typed, { typed = it.trim() }, singleLine = true, label = { Text("Subnet (/20 … /32)") },
                isError = !ok)
            if (!ok) Text("Write it as address/size, e.g. 10.147.17.0/24 (at most 4096 addresses)", color = C.bad, fontSize = 12.sp)
            Text("It's remembered and searched again next times.", color = C.muted, fontSize = 12.sp)
        } },
        confirmButton = { STextButton({ v.searchSubnet(typed) }, enabled = ok) { Text("Search", color = accent(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } },
        dismissButton = { STextButton({ v.askSubnet.value = emptyList() }) { Text("Cancel") } })
}


/** Radio started on mobile data: a note at the beginning of the play session (closes after 3 s unless touched). */
@Composable
fun RadioMobileNoticeHost() {
    val g = app.vlcinterface.player.NetGuard
    val note by g.radioMobileNotice.collectAsState()
    if (note.isEmpty()) return
    AutoInfoDialog(onDismissRequest = { g.radioMobileNotice.value = "" }, containerColor = C.panel,
        title = { Text("On mobile data", color = C.text) },
        text = { Text(note, color = C.text, fontSize = 14.sp) },
        confirmButton = { STextButton({ g.radioMobileNotice.value = "" }) { Text("OK", color = accent()) } })
}


/** Small connection symbol: Wi-Fi arcs or mobile-data bars. */
@Composable
fun NetSymbol(kind: String, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
        val w = size.width; val h = size.height
        if (kind == "wifi") {
            val c = androidx.compose.ui.geometry.Offset(w / 2, h * 0.92f)
            for (i in 1..3) {
                val r = h * 0.28f * i
                drawArc(color, 225f, 90f, false, topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.12f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            drawCircle(color, h * 0.09f, c)
        } else {
            val bw = w / 5.5f
            for (i in 0..3) {
                val bh = h * (0.3f + 0.233f * i)
                drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(i * bw * 1.45f, h - bh), size = androidx.compose.ui.geometry.Size(bw, bh))
            }
        }
    }
}
