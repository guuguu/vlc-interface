package app.vlcinterface.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.Zone

/** The setup wizard's pages. */
object WizardPages {
    class Page(val id: String, val title: String, val intro: String, val skip: String, val help: String)

    val pages = listOf(
        Page("perm", "Permissions", "What the app may do on the phone, and why. Tap a link to grant or set each one; the state underneath updates when you come back.",
            "Permissions not granted can be granted later in Android's settings for the app (or here again from Settings → Launch the setup wizard). " +
                "Until then the features that need them don't work.", "perms"),
        Page("import", "Import a saved setup", "Already used the app on another phone, or saved your setup? Import it here and skip the rest.",
            "Nothing imported: the app starts with its own defaults. Export / import stays in Settings.", "backup"),
        Page("library", "Library and files", "Where your songs and videos come from, and how the library is scanned.",
            "Defaults: automatic scanning, folders watched, album pictures from the internet. Sources can be added later in Library → LAN / Internet.", "library"),
        Page("network", "Network and NAS", "How the home NAS and Internet services are reached.",
            "Defaults are used; Settings → Network & NAS later.", "nas"),
        Page("vpn", "VPN", "Reaching the NAS away from home through your VPN (ZeroTier, Tailscale…).",
            "The NAS will be used only at home until the VPN is set (Settings → VPN).", "vpn"),
        Page("audio", "Audio", "Start volume, preamp, ReplayGain, loudness and the sound engine.",
            "The app's sound defaults are used (Settings → Audio).", "audio"),
        Page("playback", "Playback and gestures", "Gestures on the players, resuming and background playback.",
            "Defaults: all gestures on, resume on (Settings → Playback & gestures).", "playback"),
        Page("video", "Video", "Subtitle languages and rotation.",
            "The system's language and rotation are used (Settings → Video).", "video"),
        Page("radio", "Radio", "Radio buffer and saving songs heard on the radio.",
            "Defaults: radio kept ready to save on Wi-Fi, songs saved to Music/Saved songs (Settings → Radio).", "radio"),
        Page("look", "Look", "Colours, sizes and menus.",
            "The app's default look is kept (Settings → Look).", "look"),
        Page("alarm", "Alarm clock", "Alarm sources, ringing, weather city and the clock.",
            "Alarms work with the default sound and settings (Settings → Alarm clock).", "alarm"),
        Page("vis", "Visualisation", "Patterns and colours shown with the music.",
            "The default pattern is used (Settings → Visualisation).", "vis"),
        Page("summary", "All set", "What's set and what was skipped. Tap an item to open it.", "", "start"),
    )

    /** Pages skipped / done this session (shown on the summary). */
    val skipped = androidx.compose.runtime.mutableStateListOf<String>()
    val done = androidx.compose.runtime.mutableStateListOf<String>()
    var guided by mutableStateOf(false)
}

/** One permission on the first page. */
private class Perm(val name: String, val role: String, val without: String, val runtime: Array<String> = emptyArray(),
                   val optional: Boolean = false, val granted: (Context) -> Boolean?, val open: (Context) -> Unit)

private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
private fun start(ctx: Context, i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
private fun appDetails(ctx: Context) { start(ctx, Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }

private fun perms(): List<Perm> = listOf(
    Perm("Notifications", "Player controls in the notification shade; the alarm notice and its buttons.",
        "No player controls outside the app; alarms may ring without their notice.",
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
        granted = { androidx.core.app.NotificationManagerCompat.from(it).areNotificationsEnabled() },
        open = { c -> if (!start(c, Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, c.packageName))) appDetails(c) }),
    Perm("Music and videos", "Reading the songs and videos stored on the phone.",
        "The phone's own songs and videos don't appear in the Library (NAS and Internet sources still work).",
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
        granted = { c -> if (Build.VERSION.SDK_INT >= 33) has(c, Manifest.permission.READ_MEDIA_AUDIO) && has(c, Manifest.permission.READ_MEDIA_VIDEO) else has(c, Manifest.permission.READ_EXTERNAL_STORAGE) },
        open = ::appDetails),
    Perm("Nearby devices (Bluetooth)", "Names of Bluetooth outputs, their codec and the Bluetooth quality mode.",
        "Bluetooth outputs show without names; codec info and Bluetooth quality mode are unavailable.",
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT), granted = { has(it, Manifest.permission.BLUETOOTH_CONNECT) }, open = ::appDetails),
    Perm("Alarms and reminders", "Alarms ring exactly on the minute.",
        "Alarms may ring late or not at all.",
        granted = { (it.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms() },
        open = { c -> if (!start(c, Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}")))) appDetails(c) }),
    Perm("Full-screen notifications", "The ringing alarm screen shows above the lock screen.",
        "A ringing alarm shows only as a notification; you have to unlock the phone to stop it.",
        granted = { c -> if (Build.VERSION.SDK_INT >= 34) (c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent() else true },
        open = { c -> if (Build.VERSION.SDK_INT < 34 || !start(c, Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:${c.packageName}")))) appDetails(c) }),
    Perm("Battery unrestricted", "Playback, radio saving and alarms keep running with the screen off.",
        "Android may pause playback, radio saving and alarm preparation when the screen is off.",
        granted = { Battery.unrestricted(it) }, open = { Battery.ask(it) }),
    Perm("Calendar", "Your alarms shown in the phone's calendar.",
        "Alarms aren't shown in the calendar.",
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), optional = true,
        granted = { has(it, Manifest.permission.READ_CALENDAR) && has(it, Manifest.permission.WRITE_CALENDAR) }, open = ::appDetails),
    Perm("Location (optional)", "Weather for where you are, on the clock.",
        "Weather uses the city you choose on the Alarm clock page.",
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), optional = true,
        granted = { has(it, Manifest.permission.ACCESS_COARSE_LOCATION) }, open = ::appDetails),
    Perm("Screen saver", "The clock while the phone charges: choose \"vlc interface clock\" in Android's screen saver settings.",
        "", optional = true, granted = { null },
        open = { c -> if (!start(c, Intent(AndroidSettings.ACTION_DREAM_SETTINGS))) start(c, Intent(AndroidSettings.ACTION_DISPLAY_SETTINGS)) }),
)

/** The setup wizard: Next / Skip / Help in a fixed header; the page scrolls under it. */
@Composable
fun WizardScreen(nav: Navigator, page: Int) {
    val W = WizardPages
    val pg = W.pages[page.coerceIn(0, W.pages.lastIndex)]
    val ctx = LocalContext.current
    val store = App.instance.store
    // Permission states, read again whenever the app comes back to the front.
    var tick by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // Alarm system off (Settings): the alarm-only permissions aren't asked for.
    val alarmOn = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.systemOn
    val list = remember(alarmOn) { perms().filter { alarmOn || it.name !in setOf("Alarms and reminders", "Full-screen notifications", "Calendar") } }
    var askFor by remember { mutableStateOf<Perm?>(null) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        tick++
        // Refused for good (Android no longer asks): open the app's settings page instead.
        val p = askFor; askFor = null
        if (p != null && r.values.any { !it }) appDetails(ctx)
    }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    var skipNote by remember { mutableStateOf(false) }

    fun goTo(i: Int) { nav.replace(Screen.Wizard(i.coerceIn(0, W.pages.lastIndex))) }
    fun finish() { store.update { it.copy(settings = it.settings.copy(wizardDone = true)) }; W.skipped.clear(); W.done.clear(); nav.back() }
    fun next() { W.skipped.remove(pg.id); if (pg.id !in W.done) W.done.add(pg.id); if (page >= W.pages.lastIndex) finish() else goTo(page + 1) }
    fun check(): List<String> {
        @Suppress("UNUSED_VARIABLE") val t = tick
        return when (pg.id) {
            "perm" -> list.filter { !it.optional && it.granted(ctx) == false }.map { "${it.name}: ${it.without}" }
            "library" -> if (list[1].granted(ctx) != true && store.current.sources.isEmpty()) listOf("No phone music access and no NAS or Internet source: the Library stays empty.") else emptyList()
            "alarm" -> list.subList(3, 6).filter { it.granted(ctx) == false }.map { "${it.name}: ${it.without}" }
            else -> emptyList()
        }
    }

    CompositionLocalProvider(LocalShowNow provides true) {
    Column(Modifier.fillMaxSize()) {
        // Fixed header: never scrolls with the page.
        Scaled {
            Column(Modifier.fillMaxWidth().background(C.card)) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = (6 * sg()).dp)) {
                    Text("Setup · ${page + 1} of ${W.pages.size}", color = C.muted, fontSize = 12.sp)
                    Text(pg.title, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                // Title on its own row, the first one (18:00): "Setup · N of M" and the page title, then the buttons.
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = (6 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                    HeaderPill("‹", false) { if (page > 0) goTo(page - 1) else nav.back() }
                    Box(Modifier.weight(1f))
                    HeaderPill("Help", false) { HelpGuide.history.clear(); HelpGuide.pos = -1; nav.go(Screen.Help(pg.help)) }
                    if (pg.id != "summary") HeaderPill("Skip", false) { skipNote = true }
                    HeaderPill(if (pg.id == "summary") "Finish" else "Next", true) {
                        val m = check(); if (m.isEmpty()) next() else missing = m
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(accent()))
            }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(page) { scroll.scrollTo(0) }
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            Box(Modifier.height(6.dp))
            LinkRow("Close the wizard, use the default setup, I'll set it up later") { finish() }
            LinkRow("Guide me through the setup step by step" + if (W.guided) "  ✓" else "") {
                W.guided = true; if (pg.id == "perm" || pg.id == "summary") goTo(if (pg.id == "summary") 0 else page) }
            Text(pg.intro, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = (4 * sg()).dp))
            if (W.guided) HelpGuide.chapter(pg.help)?.pages?.firstOrNull()?.let { (t, txt) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, accent().copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(t, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(txt, color = C.muted, fontSize = 13.sp)
                }
            }
            when (pg.id) {
                "perm" -> {
                    @Suppress("UNUSED_VARIABLE") val t = tick
                    CardBox {
                        list.forEachIndexed { i, p ->
                            if (i > 0) Divider()
                            val g = p.granted(ctx)
                            Column(Modifier.fillMaxWidth().explain(p.name, p.role).padding(horizontal = 14.dp, vertical = (10 * sg()).dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(if (g == true) "Open settings ▶" else if (p.runtime.isNotEmpty()) "Grant ▶" else "Open settings ▶", color = WIZ_GREEN, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                            if (g != true && p.runtime.isNotEmpty()) { askFor = p; req.launch(p.runtime) } else p.open(ctx)
                                        }.padding(6.dp))
                                }
                                Text(p.role, color = C.muted, fontSize = 13.sp)
                                when (g) {
                                    true -> NowLine("granted ✓")
                                    false -> NowLine("not granted" + if (p.without.isNotEmpty()) " — ${p.without}" else "", bad = !p.optional)
                                    null -> NowLine("set in Android's settings")
                                }
                            }
                        }
                    }
                    SectionTitle("Granted at install")
                    CardBox {
                        Column(Modifier.padding(14.dp)) {
                            Text("Internet, network state, finding the NAS on the home network, keeping the phone awake while playing, starting again after a reboot.",
                                color = C.muted, fontSize = 13.sp)
                            NowLine("granted ✓")
                        }
                    }
                }
                "import" -> CardBox {
                    ValueRow("Import a setup file", "Opens Export / import: choose the file and the parts to apply", "Open ▶") { nav.go(Screen.Backup) }
                }
                "library" -> {
                    val st by store.state.collectAsState()
                    SectionTitle("Sources")
                    CardBox {
                        val lan = st.sources.filter { it.zone == Zone.LAN }; val net = st.sources.filter { it.zone == Zone.INTERNET }
                        ValueRow("NAS and shares", "Add a home server", "Add ▶") { nav.go(Screen.EditSource(Zone.LAN)) }
                        Column(Modifier.padding(start = 16.dp, bottom = 8.dp)) { NowLine(if (lan.isEmpty()) "none" else lan.joinToString { it.name.ifBlank { it.host } }) }
                        Divider()
                        ValueRow("Internet sources", "Add a server on the internet", "Add ▶") { nav.go(Screen.EditSource(Zone.INTERNET)) }
                        Column(Modifier.padding(start = 16.dp, bottom = 8.dp)) { NowLine(if (net.isEmpty()) "none" else net.joinToString { it.name.ifBlank { it.host } }) }
                    }
                    SectionTitle("Media folders on the phone")
                    MediaFoldersCard()
                    SettingsGroupBody(nav, "library")
                }
                "radio" -> {
                    val st by store.state.collectAsState()
                    CardBox { Column(Modifier.padding(14.dp)) { Text("Radio favourites", color = C.text, fontSize = 15.sp); NowLine("${st.stations.size} stations") } }
                    SettingsGroupBody(nav, "radio")
                }
                "alarm" -> HelpHost { Column { AlarmSettingsBody(nav) } }
                "summary" -> {
                    CardBox {
                        W.pages.dropLast(1).forEachIndexed { i, p ->
                            if (i > 0) Divider()
                            val state = when (p.id) { in W.done -> "set ✓"; in W.skipped -> "skipped — defaults used"; else -> "not visited" }
                            Column(Modifier.fillMaxWidth().clickable { goTo(i) }.padding(horizontal = 14.dp, vertical = (10 * sg()).dp)) {
                                Row { Text(p.title, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text("▶", color = WIZ_GREEN, fontSize = SYM) }
                                NowLine(state, bad = p.id in W.skipped)
                            }
                        }
                    }
                    Text("Everything stays in Settings; the wizard can be launched again from the last item of the main Settings page.", color = C.muted, fontSize = 12.sp)
                }
                else -> SettingsGroupBody(nav, pg.id)
            }
            Box(Modifier.height(24.dp))
        }
    }
    }

    if (missing.isNotEmpty()) AlertDialog(onDismissRequest = { missing = emptyList() }, containerColor = C.panel,
        title = { Text("Not everything is set", color = C.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                Text("Without these, this is what won't work or won't appear:", color = C.muted, fontSize = 13.sp)
                missing.forEach { Text("• $it", color = C.text, fontSize = 14.sp) }
            }
        },
        confirmButton = { STextButton({ missing = emptyList() }) { Text("Set it now", color = accent(), fontWeight = FontWeight.Bold) } },
        dismissButton = { STextButton({ missing = emptyList(); next() }) { Text("Go on without it") } })
    if (skipNote) AlertDialog(onDismissRequest = { skipNote = false }, containerColor = C.panel,
        title = { Text("Skip ${pg.title}?", color = C.text) },
        text = { Text(pg.skip + "\n\nYou can set all of this later in the Settings menu.", color = C.text, fontSize = 14.sp) },
        confirmButton = { STextButton({
            skipNote = false; if (pg.id !in W.skipped) W.skipped.add(pg.id); W.done.remove(pg.id); goTo(page + 1)
        }) { Text("Skip", color = accent(), fontWeight = FontWeight.Bold) } },
        dismissButton = { STextButton({ skipNote = false }) { Text("Stay") } })
}

@Composable
private fun HeaderPill(t: String, filled: Boolean, onClick: () -> Unit) {
    val a = accent()
    Box(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(18.dp)).background(if (filled) a else Color.Transparent)
        .border(1.dp, if (filled) a else C.line, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = (7 * sg()).dp),
        contentAlignment = Alignment.Center) {
        Text(t, color = if (filled) Color(0xFF1A0E05) else C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LinkRow(t: String, onClick: () -> Unit) {
    Text("› $t", color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = (6 * sg()).dp))
}
