package app.vlcinterface.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.alarm.Alarm
import app.vlcinterface.alarm.AlarmGlobal
import app.vlcinterface.alarm.AlarmScheduler
import app.vlcinterface.alarm.Alarms
import app.vlcinterface.alarm.ClockAspect
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- player: button and clock area

/** Clock button left of the player buttons: the clock replaces the album picture (until pressed again). */
@Composable
fun ClockToggleButton(size: Int = 30) {
    val d by Alarms.state.collectAsState()
    val a = accent()
    Box(Modifier.size(size.dp).liteShadow(CircleShape).clip(CircleShape).background(if (d.showClock) a.copy(alpha = 0.25f) else Color(0x24FFFFFF))
        .border(1.dp, if (d.showClock) a else Color.Transparent, CircleShape).clickable { Alarms.toggleClock() },
        contentAlignment = Alignment.Center) {
        Text("⏰", fontSize = (size * 0.5f).sp)
    }
}

/** In the picture's place: the clock, then the alarms (tap one: its page). */
@Composable
fun ClockArea(nav: Navigator, sizeDp: Int) {
    val d by Alarms.state.collectAsState()
    ClockFace(d.aspect, Modifier.padding(top = 8.dp).size(sizeDp.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF))) {
        val ctxH = LocalContext.current
        val issues = remember(resumeTick()) { alarmIssues(ctxH) }
        if (issues.isNotEmpty() && d.alarms.any { it.on }) Text("⚠  Alarms may not ring: " + issues.joinToString(" · ") { it.title } + "  ›", color = C.bad, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clickable { nav.go(Screen.AlarmSettings) }.padding(12.dp))
        d.alarms.filter { it.id != app.vlcinterface.alarm.AlarmEngine.TEST_ID }.sortedBy { it.hour * 60 + it.minute }.forEach { al ->
            Row(Modifier.fillMaxWidth().clickable { nav.go(Screen.AlarmEdit(al.id)) }.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(al.timeText, color = if (al.on) C.text else C.muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (al.name.isNotEmpty()) Text(al.name, color = C.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Alarm.daysText(al.days), color = C.muted, fontSize = 12.sp)
                }
                Switch(al.on, { Alarms.put(al.copy(on = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = accent()))
            }
            Divider()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+  Add alarm", color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable { nav.go(Screen.AlarmEdit("")) }.padding(12.dp))
            Text("⚙", color = C.muted, fontSize = 18.sp, modifier = Modifier.clickable { nav.go(Screen.AlarmSettings) }.padding(12.dp))
        }
    }
}

// ---------------------------------------------------------------- small helpers

@Composable
private fun <T> Choices(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, v) -> Pill(label, selected = v == selected) { onPick(v) } }
    }
}

@Composable
private fun Label(text: String, hint: String = "") {
    Column(Modifier.fillMaxWidth().helpOn(text).padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Text(text, color = C.text, fontSize = 15.sp)
        if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp).helpOn(label))
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = accent(), activeTrackColor = accent()))
        Text(shown, color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
    }
}

private fun minText(s: Int) = if (s == 0) "Off" else if (s < 60) "$s s" else "${s / 60} min"

// ---------------------------------------------------------------- one alarm

@Composable
fun AlarmEditScreen(nav: Navigator, id: String) {
    val st by App.instance.store.state.collectAsState()
    val start = remember(id) { Alarms.get(id) ?: Alarm() }
    var a by remember(id) { mutableStateOf(start) }
    var timeAsk by remember { mutableStateOf(false) }
    var rootAsk by remember { mutableStateOf(false) }
    var soundAsk by remember { mutableStateOf(false) }
    var rootNote by remember { mutableStateOf("") }
    val gl = Alarms.state.collectAsState().value.global
    if (rootAsk) RootFolderDialog { rootAsk = false; rootNote = "" }
    if (soundAsk) AlarmSoundDialog { soundAsk = false }
    val scroll = rememberScrollState()
    HelpHost {
    Column(Modifier.fillMaxSize()) {
        BackHeader(if (Alarms.get(id) == null) "New alarm" else "Alarm", nav)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardBox {
                HToggleRow("Alarm on", "", a.on) { a = a.copy(on = it) }; Divider()
                Row(Modifier.fillMaxWidth().clickable { timeAsk = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Time", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(a.timeText, color = accent(), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                }
                Divider()
                OutlinedTextField(a.name, { a = a.copy(name = it.take(40)) }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            SectionTitle("Days")
            CardBox {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
                        val on = a.days and (1 shl i) != 0
                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) accent() else Color.Transparent)
                            .border(1.dp, if (on) accent() else C.line, CircleShape).clickable { a = a.copy(days = a.days xor (1 shl i)) },
                            contentAlignment = Alignment.Center) { Text(l, color = if (on) Color(0xFF1A0E05) else C.text, fontWeight = FontWeight.SemiBold) }
                    }
                }
                Text(Alarm.daysText(a.days), color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
            }
            SectionTitle("What plays")
            CardBox {
                Label("What plays", "Only music stored on the phone, a radio station or the alarm sound: an alarm never depends on the NAS")
                Choices(listOf("Phone music" to "local", "Radio" to "radio", "Alarm sound" to "tone"), a.source) { v ->
                    a = a.copy(source = v)
                    if (v == "local" && Alarms.current.global.localRoot.isEmpty()) {
                        val known = knownMusicRoot()
                        if (known != null) { Alarms.setGlobal(Alarms.current.global.copy(localRoot = known.first, localVolume = known.second)); rootNote = "Music folder taken from the audio player: ${known.first}" }
                        else rootAsk = true
                    }
                }
                when (a.source) {
                    "local" -> {
                        Explain("Songs from the music folder below and all its subfolders, in random order. If none plays within 15 s, the alarm sound rings.")
                        HValueRow("Music folder", rootNote.ifEmpty { "Phone music for all alarms" }, gl.localRoot.ifEmpty { "Choose" }) { rootAsk = true }
                    }
                    "radio" -> {
                        Explain("The preferred station plays live (no buffer). Not heard within 15 s → the fallback station; not heard either → the alarm sound.")
                        if (st.stations.isEmpty()) Explain("No favourite stations yet: add some in the radio player first.")
                        Label("Preferred station")
                        Choices(st.stations.map { it.name to it.url }, a.stationUrl) { u -> a = a.copy(stationUrl = u, stationName = st.stations.firstOrNull { it.url == u }?.name ?: "",
                            fallbackUrl = if (a.fallbackUrl == u) "" else a.fallbackUrl, fallbackName = if (a.fallbackUrl == u) "" else a.fallbackName) }
                        Label("Fallback station", "Used when the preferred one can't be reached")
                        Choices(listOf("None" to "") + st.stations.filter { it.url != a.stationUrl }.map { it.name to it.url }, a.fallbackUrl) { u ->
                            a = a.copy(fallbackUrl = u, fallbackName = st.stations.firstOrNull { it.url == u }?.name ?: "") }
                    }
                    else -> Explain("Only the alarm sound below.")
                }
                Divider()
                HValueRow("Alarm sound", "The last fallback, and what rings when music was already playing (all alarms)", gl.toneName.ifEmpty { "Phone's alarm" }) { soundAsk = true }
            }
            SectionTitle("Volume")
            CardBox {
                SliderRow("Start", a.volStart.toFloat(), 0f..100f, "${a.volStart} %") { a = a.copy(volStart = it.toInt()) }
                SliderRow("Target", a.volTarget.toFloat(), 0f..100f, "${a.volTarget} %") { a = a.copy(volTarget = it.toInt()) }
                val maxL = Alarms.current.global.maxLevel
                if (a.volTarget > maxL || a.volStart > maxL) Text("Capped at $maxL % (maximum level in the alarm clock settings)", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp))
                Label("Fade-in time")
                Choices(listOf(0, 30, 60, 120, 300, 600).map { minText(it) to it }, a.fadeSec) { a = a.copy(fadeSec = it) }
            }
            SectionTitle("Snooze and stop")
            CardBox {
                Label("Snoozes allowed", "Snooze times are set in the alarm clock settings")
                Choices(listOf("None" to 0, "1" to 1, "3" to 3, "5" to 5, "No limit" to -1), a.maxSnoozes) { a = a.copy(maxSnoozes = it) }
                Label("Stop by itself after")
                Choices(listOf("5 min" to 5, "10 min" to 10, "15 min" to 15, "30 min" to 30, "60 min" to 60, "Never" to 0), a.stopAfterMin) { a = a.copy(stopAfterMin = it) }
            }
            SectionTitle("Output")
            CardBox {
                val devs by App.instance.output.devices.collectAsState()
                Choices(listOf("As it is" to "", "Phone speaker" to "speaker") + devs.filter { it.connected && it.kind != app.vlcinterface.player.OutputKind.SPEAKER }.map { it.name to it.key },
                    a.output) { a = a.copy(output = it) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (Alarms.get(id) != null) Pill("Delete", modifier = Modifier.weight(1f)) { Alarms.delete(a.id); nav.back() }
                Pill("Save", filled = true, modifier = Modifier.weight(1f)) { Alarms.put(a); nav.back() }
            }
        }
    }
    }
    if (timeAsk) {
        val tp = rememberTimePickerState(a.hour, a.minute, Alarms.current.aspect.h24)
        AlertDialog(onDismissRequest = { timeAsk = false }, containerColor = C.panel,
            text = { TimePicker(tp) },
            confirmButton = { STextButton({ a = a.copy(hour = tp.hour, minute = tp.minute); timeAsk = false }) { Text("OK", color = accent()) } },
            dismissButton = { STextButton({ timeAsk = false }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- global settings

@Composable
fun AlarmSettingsScreen(nav: Navigator) {
    val d by Alarms.state.collectAsState()
    val g = d.global
    fun set(n: AlarmGlobal) = Alarms.setGlobal(n)
    val ctx = LocalContext.current
    val locAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) set(Alarms.current.global.copy(useLocation = true))
    }
    var cityAsk by remember { mutableStateOf(false) }
    if (cityAsk) CityDialog { cityAsk = false }
    var soundAsk by remember { mutableStateOf(false) }
    var rootAsk by remember { mutableStateOf(false) }
    if (soundAsk) AlarmSoundDialog { soundAsk = false }
    if (rootAsk) RootFolderDialog { rootAsk = false }
    val scroll = rememberScrollState()
    HelpHost {
    Column(Modifier.fillMaxSize()) {
        BackHeader("Alarm clock", nav)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Alarm health")
            HealthCard()
            Text("Tip: double tap or long-press any setting for an explanation", color = C.muted, fontSize = 12.sp)
            SectionTitle("Display")
            CardBox { HValueRow("Clock aspect", "Designs, colours, thickness, size, date, digital", "›") { nav.go(Screen.ClockAspect) } }
            SectionTitle("Location (clock and weather)")
            CardBox {
                HValueRow("City", if (g.cityName.isEmpty()) "Not chosen: the phone's time zone" else "Clock time, alarms and weather follow it, also when the phone's time zone isn't automatic",
                    if (g.cityName.isEmpty()) "Choose" else g.cityName) { cityAsk = true }
                Divider()
                HToggleRow("Weather on the clock", "Sunny, cloudy, rainy or storm, with the city's short name and time zone", g.showWeather) {
                    set(g.copy(showWeather = it)); if (it) app.vlcinterface.alarm.Weather.refresh(force = true) }
            }
            SectionTitle("Ringing")
            CardBox {
                SliderRow("Maximum level", g.maxLevel.toFloat(), 10f..100f, "${g.maxLevel} %") { set(g.copy(maxLevel = it.toInt())) }
                Text("No alarm goes above it", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                Divider()
                HValueRow("Alarm sound", "When nothing else can play, or music was already playing", g.toneName.ifEmpty { "Phone's alarm" }) { soundAsk = true }
                Divider()
                HValueRow("Music folder", "Phone music played by the alarms (with its subfolders)", g.localRoot.ifEmpty { "Choose" }) { rootAsk = true }
                Divider()
                HToggleRow("Vibrate", "", g.vibrate) { set(g.copy(vibrate = it)) }; Divider()
                Label("Light blinking")
                Choices(listOf("Off" to "off", "Notification light" to "notif", "Flash light" to "flash"), g.led) { set(g.copy(led = it)) }
                Divider()
                Label("Crescendo", "How the volume rises from start to target")
                Choices(listOf("Steady" to "linear", "Gentle start" to "gentle", "Quick start" to "fast"), g.crescendo) { set(g.copy(crescendo = it)) }
                Divider()
                Label("Snooze buttons", "The 3 buttons of the ringing screen")
                listOf(0, 1, 2).forEach { i ->
                    val v = g.snoozes[i]
                    SliderRow("Button ${i + 1}", v.toFloat(), 1f..60f, "$v min") { f ->
                        val n = f.toInt(); set(when (i) { 0 -> g.copy(snooze1 = n); 1 -> g.copy(snooze2 = n); else -> g.copy(snooze3 = n) }) }
                }
                Divider()
                HToggleRow("Snooze buttons on the lock screen", "In the alarm notification", g.lockActions) { set(g.copy(lockActions = it)) }; Divider()
                Label("Volume buttons while ringing")
                Choices(listOf("Snooze" to "snooze", "Nothing" to "nothing", "Change volume" to "volume"), g.volumeKeys) { set(g.copy(volumeKeys = it)) }
            }
            SectionTitle("Waking up")
            CardBox {
                HToggleRow("Random pattern to stop", "Tap 4 numbered dots in order", g.pattern) { set(g.copy(pattern = it)) }; Divider()
                Label("Wake check after Stop", "No sign of being awake for this long (movement, unlocking, network or place change) → a silent, vibrating question; no movement for 30 s → rings again at once, no crescendo")
                Choices(listOf("Off" to 0, "5 min" to 5, "10 min" to 10, "15 min" to 15, "20 min" to 20, "30 min" to 30), g.wakeCheckMin) { set(g.copy(wakeCheckMin = it)) }
                Divider()
                HToggleRow("Use the phone's location", "Moving more than 150 m counts as awake", g.useLocation) { on ->
                    if (!on) set(g.copy(useLocation = false))
                    else if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) set(g.copy(useLocation = true))
                    else locAsk.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
            SectionTitle("Situations")
            CardBox {
                Label("Music already playing", "Stops completely: only the alarm tone rings")
                Divider()
                HToggleRow("During a call", "Waits for the end of the call", g.callWait) { set(g.copy(callWait = it)) }; Divider()
                Label("Missed alarm", "Phone off or restarting at the alarm time")
                Choices(listOf("Ring if under 30 min late" to "ring30", "Notification only" to "notify"), g.missed) { set(g.copy(missed = it)) }
                Divider()
                Label("Low battery (15 % or less)")
                Choices(listOf("Normal" to "normal", "Alarm tone only (no network)" to "tone"), g.lowBattery) { set(g.copy(lowBattery = it)) }
            }
            SectionTitle("System")
            CardBox {
                HToggleRow("Next alarm in a notification", "", g.nextInNotif) { set(g.copy(nextInNotif = it)) }; Divider()
                val exact = AlarmScheduler.canExact(ctx)
                HValueRow("Exact alarms", if (exact) "Allowed: alarms ring on the minute, also in battery saving" else "Not allowed: alarms may be late",
                    if (exact) "✓" else "Allow") {
                    try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    Divider()
                    val fs = ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
                    HValueRow("Full-screen alarm", if (fs) "The ringing screen shows over the lock screen" else "Not allowed: only a notification", if (fs) "✓" else "Allow") {
                        try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
                    }
                }
                Divider()
                val free = Battery.unrestricted(ctx)
                HValueRow("Battery", if (free) "Unrestricted" else "Battery saving may delay the app after the alarm", if (free) "✓" else "Allow") { Battery.ask(ctx) }
            }
            Box(Modifier.height(24.dp))
        }
    }
    }
}

// ---------------------------------------------------------------- clock aspect

@Composable
fun ClockAspectScreen(nav: Navigator) {
    val d by Alarms.state.collectAsState()
    val a = d.aspect
    fun set(n: ClockAspect) = Alarms.setAspect(n)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Clock aspect", nav)
        // Live preview of the chosen design: follows every change at once. Outside the disk: the player's background.
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(12.dp)).background(PLAYER_BG), contentAlignment = Alignment.Center) {
            ClockFace(a, Modifier.padding(8.dp).size(190.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Design")
            CardBox {
                Choices(listOf("1" to 1, "2" to 2, "4" to 4, "5" to 5, "8" to 8, "Digital" to 0), if (a.analog) a.design else 0) {
                    set(if (it == 0) a.copy(analog = false) else a.copy(analog = true, design = it)) }
                Divider()
                HToggleRow("24-hour", "", a.h24) { set(a.copy(h24 = it)) }; Divider()
                HToggleRow("Seconds", "Second hand / seconds", a.seconds) { set(a.copy(seconds = it)) }
            }
            SectionTitle("Colours")
            CardBox {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    HueSatBars("Disk (inside the circle)", a.diskHue, a.diskSat, ::clockDisk, round = true) { h, s -> set(a.copy(diskHue = h, diskSat = s)) }
                    HueSatBars("Ring, ticks, numbers, hands", a.mainHue, a.mainSat, ::clockMain) { h, s -> set(a.copy(mainHue = h, mainSat = s)) }
                    HueSatBars("Date", a.dateHue, a.dateSat, ::clockMain) { h, s -> set(a.copy(dateHue = h, dateSat = s)) }
                    Text("Second hand", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hue", color = C.text, fontSize = 13.sp, modifier = Modifier.width(78.dp))
                        GradientBar(a.secHue / 360f, androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f).map { Color.hsl(it, 0.9f, 0.62f) }),
                            Modifier.weight(1f)) { set(a.copy(secHue = it * 360f)) }
                        Box(Modifier.padding(start = 10.dp).size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color.hsl(a.secHue.coerceAtMost(359.9f), 0.9f, 0.62f)))
                    }
                }
            }
            SectionTitle("Sizes and thickness")
            CardBox {
                SliderRow("Circle size", a.size, 0.6f..1f, "${(a.size * 100).toInt()} %") { set(a.copy(size = it)) }
                SliderRow("Date size", a.dateSize, 0.5f..2f, "${(a.dateSize * 100).toInt()} %") { set(a.copy(dateSize = it)) }
                SliderRow("Ring", a.ringW, 0.3f..3f, "×%.1f".format(a.ringW)) { set(a.copy(ringW = it)) }
                SliderRow("Ticks", a.tickW, 0.3f..3f, "×%.1f".format(a.tickW)) { set(a.copy(tickW = it)) }
                SliderRow("Hands", a.handW, 0.4f..3f, "×%.1f".format(a.handW)) { set(a.copy(handW = it)) }
            }
            SectionTitle("Digital clock")
            CardBox {
                SliderRow("Digits size", a.digitalSize, 0.5f..1.4f, "${(a.digitalSize * 100).toInt()} %") { set(a.copy(digitalSize = it)) }
                HToggleRow("Bold digits", "", a.digitalBold) { set(a.copy(digitalBold = it)) }; Divider()
                HToggleRow("Date under the time", "", a.digitalDate) { set(a.copy(digitalDate = it)) }
            }
            SectionTitle("Night")
            CardBox {
                SliderRow("Dimming", a.nightDim.toFloat(), 0f..80f, "${a.nightDim} %") { set(a.copy(nightDim = it.toInt())) }
                Text("From 22:00 to 07:00", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
            }
            Box(Modifier.height(24.dp))
        }
    }
}


/** City chosen by hand: search by name (Open-Meteo), or back to the phone's time zone. */
@Composable
private fun CityDialog(onDone: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<List<app.vlcinterface.alarm.Weather.City>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun search() { if (q.isBlank()) return; busy = true; note = ""; scope.launch {
        res = app.vlcinterface.alarm.Weather.search(q); busy = false; if (res.isEmpty()) note = "Nothing found (or no internet)" } }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("City", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
                    STextButton({ search() }, enabled = !busy) { Text(if (busy) "…" else "Search", color = accent()) }
                }
                if (note.isNotEmpty()) Text(note, color = C.muted, fontSize = 12.sp)
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    res.forEach { c ->
                        Column(Modifier.fillMaxWidth().clickable {
                            Alarms.setGlobal(Alarms.current.global.copy(cityName = c.name, cityShort = c.short, cityLat = c.lat, cityLon = c.lon, cityTz = c.tz))
                            app.vlcinterface.alarm.Weather.refresh(force = true)
                            onDone()
                        }.padding(vertical = 8.dp)) {
                            Text(c.name + "  ·  " + c.short, color = C.text, fontSize = 15.sp)
                            Text(listOf(c.detail, c.tz).filter { it.isNotEmpty() }.joinToString(" · "), color = C.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { STextButton(onDone) { Text("Close") } },
        dismissButton = { STextButton({
            Alarms.setGlobal(Alarms.current.global.copy(cityName = "", cityShort = "", cityLat = 0.0, cityLon = 0.0, cityTz = "")); onDone() }) {
            Text("Phone's time zone") } })
}

// ---------------------------------------------------------------- explanations (double tap / long-press)

private val LocalHelp = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

private val HELP: Map<String, String> = mapOf(
    "Alarm on" to "Switches this alarm on or off without deleting it. A one-time alarm switches itself off after ringing.",
    "Time" to "When the alarm rings, in the time zone of the city chosen in the alarm clock settings (or the phone's).",
    "Days" to "The days it repeats. No day selected: it rings once, at the next time shown.",
    "What plays" to "Phone music: songs stored on the phone under the music folder. Radio: a live station, with a fallback station. Alarm sound: only the sound chosen below. Network songs from the NAS are never used, so a NAS or Wi-Fi problem can't silence an alarm.",
    "Music folder" to "The phone folder the alarms take songs from (all its subfolders too), in random order. Asked once; it is taken from the audio player when the player already plays phone music.",
    "Preferred station" to "The radio station the alarm starts with, played live without a network buffer.",
    "Fallback station" to "Played when the preferred station isn't heard within 15 s. If this one isn't heard either, the alarm sound rings.",
    "Alarm sound" to "The sound for every alarm when nothing else can play, when music was already playing, and with low battery (if chosen). Pick one in the list: each plays for 3 s so you can hear it.",
    "Start" to "Volume when the alarm begins.",
    "Target" to "Volume reached at the end of the fade-in. Never above the maximum level of the alarm clock settings.",
    "Fade-in time" to "How long the volume takes to rise from start to target (the crescendo).",
    "Snoozes allowed" to "How many times the snooze buttons can be used for this alarm. After that only Stop is offered.",
    "Stop by itself after" to "The alarm stops on its own after this time if nobody stops it. Never: it rings until stopped.",
    "Output" to "Where the alarm plays: as it is now, the phone speaker, or a connected device.",
    "City" to "The clock, the alarm times and the weather follow this city, also when the phone's time zone isn't automatic. Not chosen: the phone's time zone.",
    "Weather on the clock" to "A small icon (sunny, cloudy, rainy, storm) with the city's short name and time zone, refreshed every 30 min from Open-Meteo.",
    "Maximum level" to "No alarm ever goes above this volume: not the target, not the ring after the wake check, not the alarm sound.",
    "Vibrate" to "The phone vibrates while the alarm rings.",
    "Light blinking" to "Notification light (phones that have one) or the camera's flash light blinking while the alarm rings.",
    "Crescendo" to "How the volume rises: steady, gentle (slow at first) or quick (fast at first).",
    "Snooze buttons" to "The times of the 3 snooze buttons on the ringing screen.",
    "Button 1" to "Snooze time of the first button.", "Button 2" to "Snooze time of the second button.", "Button 3" to "Snooze time of the third button.",
    "Snooze buttons on the lock screen" to "The alarm notification gets the 3 snooze buttons, usable without unlocking.",
    "Volume buttons while ringing" to "Snooze: a volume button snoozes with the first snooze time. Nothing: they are ignored. Change volume: they change the volume (the crescendo then stops).",
    "Random pattern to stop" to "To stop, tap 4 numbered dots in order (a new pattern each time): proof you are awake enough.",
    "Wake check after Stop" to "After you stop the alarm, the app watches for signs of being awake (movement, unlocking, charger, network or place change). None for this long: a silent, vibrating 'Are you awake?' for 30 s; no movement → the alarm rings again at once, without crescendo.",
    "Use the phone's location" to "Wake check: moving more than 150 m counts as awake. Needs the location permission.",
    "Music already playing" to "When music or radio is playing as an alarm starts, it stops completely and only the alarm sound rings.",
    "During a call" to "The alarm waits for the end of the call before ringing.",
    "Missed alarm" to "If the phone was off or restarting at the alarm time: ring when it's back (if less than 30 min late), or only a notification.",
    "Low battery (15 % or less)" to "Normal, or only the alarm sound (no network) to save the battery.",
    "Next alarm in a notification" to "A quiet notification shows the next alarm.",
    "Exact alarms" to "Android permission needed for alarms to ring on the minute, also in battery saving.",
    "Full-screen alarm" to "Android permission for the ringing screen to turn the screen on over the lock screen. Without it the alarm still rings, but you only get a notification.",
    "Battery" to "Unrestricted battery use keeps the app from being held back after the alarm.",
    "Clock aspect" to "How the clock looks: designs, colours, thickness, sizes, date and digital options.",
    "Alarm health" to "Everything Android needs so alarms always ring. A red line has a button to fix it.",
    "Test alarm in 1 minute" to "Rings in 1 minute with the first alarm's settings. Press Exit and lock the phone to check that alarms ring on your phone.",
)

/** Screen with explanation pop-ups: double tap or long-press a setting. */
@Composable
private fun HelpHost(content: @Composable () -> Unit) {
    var key by remember { mutableStateOf("") }
    androidx.compose.runtime.CompositionLocalProvider(LocalHelp provides { k -> if (HELP.containsKey(k)) key = k }) { content() }
    if (key.isNotEmpty()) AlertDialog(onDismissRequest = { key = "" }, containerColor = C.panel,
        title = { Text(key, color = C.text) }, text = { Text(HELP[key] ?: "", color = C.muted, fontSize = 14.sp) },
        confirmButton = { STextButton({ key = "" }) { Text("OK", color = accent()) } })
}

@Composable
private fun Modifier.helpOn(key: String): Modifier {
    val help = LocalHelp.current
    if (!HELP.containsKey(key)) return this
    return this.combinedClickable(interactionSource = null, indication = null, onClick = {}, onDoubleClick = { help(key) }, onLongClick = { help(key) })
}

/** ToggleRow with the explanation on double tap / long-press (a single tap still switches). */
@Composable
private fun HToggleRow(label: String, hint: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    val help = LocalHelp.current
    val has = HELP.containsKey(label)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (has) Modifier.combinedClickable(onClick = { onChange(!checked) }, onDoubleClick = { help(label) }, onLongClick = { help(label) })
            else Modifier.clickable { onChange(!checked) })
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = accent()))
    }
}

@Composable
private fun HValueRow(label: String, hint: String = "", value: String, onClick: () -> Unit) {
    val help = LocalHelp.current
    val has = HELP.containsKey(label)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (has) Modifier.combinedClickable(onClick = onClick, onDoubleClick = { help(label) }, onLongClick = { help(label) }) else Modifier.clickable(onClick = onClick))
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
        }
        Text(value, color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp))
    }
}

@Composable
private fun Explain(text: String) = Text(text, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))

// ---------------------------------------------------------------- music folder, alarm sound

/** Phone music folder already used by the audio player (top folder of its phone songs), with its volume. */
fun knownMusicRoot(): Pair<String, String>? {
    val p = App.instance.player.state.value
    val fk = (listOfNotNull(p.item) + p.queue).firstOrNull { it.folderKey.startsWith("local:") }?.folderKey ?: return null
    val vol = fk.removePrefix("local:").substringBefore(':')
    val rel = fk.removePrefix("local:").substringAfter(':')
    val top = rel.substringBefore('/').takeIf { it.isNotEmpty() } ?: return null
    return ("$top/") to vol
}

@Composable
private fun RootFolderDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var folders by remember { mutableStateOf<List<app.vlcinterface.sources.LocalFolder>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        folders = try { app.vlcinterface.sources.InternalSource.folders(ctx).filter { it.songs > 0 } } catch (_: Exception) { emptyList() }
    }
    val g = Alarms.current.global
    // Top folders first (with all their songs), then each folder with songs.
    val roots = folders.orEmpty().groupBy { it.volume to (it.relativePath.substringBefore('/') + "/") }
        .map { (k, l) -> Triple(k.second, k.first, l.sumOf { it.songs }) }.sortedByDescending { it.third }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Music folder for alarms", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Alarms play songs stored on the phone from this folder and all its subfolders, in random order — no network needed. " +
                    "If none of them can play, the alarm sound rings.", color = C.muted, fontSize = 13.sp)
                if (folders == null) Text("Looking for music on the phone…", color = C.muted, fontSize = 13.sp)
                else if (roots.isEmpty()) Text("No music found on the phone.", color = C.bad, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    (roots + folders.orEmpty().filter { it.relativePath.trimEnd('/').contains('/') }
                        .map { Triple(it.relativePath, it.volume, it.songs) }).distinctBy { it.second + it.first }.forEach { (rel, vol, n) ->
                        val on = g.localRoot == rel && g.localVolume == vol
                        Row(Modifier.fillMaxWidth().clickable {
                            Alarms.setGlobal(Alarms.current.global.copy(localRoot = rel, localVolume = vol)); onDone()
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(rel, color = if (on) accent() else C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("$n songs", color = C.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { STextButton(onDone) { Text("Close") } })
}

/** Alarm sound: the phone's default or any other alarm sound; each one plays for 3 s when picked. */
@Composable
private fun AlarmSoundDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val sounds = remember {
        val out = ArrayList<Pair<String, String>>()
        try {
            val rm = android.media.RingtoneManager(ctx).apply { setType(android.media.RingtoneManager.TYPE_ALARM) }
            val c = rm.cursor
            while (c.moveToNext()) out += c.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX) to rm.getRingtoneUri(c.position).toString()
        } catch (_: Exception) {}
        out
    }
    val preview = remember { arrayOfNulls<android.media.MediaPlayer>(1) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun stopPreview() { main.removeCallbacksAndMessages(null); preview[0]?.let { try { it.stop(); it.release() } catch (_: Exception) {} }; preview[0] = null }
    fun play(uri: String) {
        stopPreview()
        val u = if (uri.isEmpty()) android.media.RingtoneManager.getActualDefaultRingtoneUri(ctx, android.media.RingtoneManager.TYPE_ALARM)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM) else Uri.parse(uri)
        try {
            preview[0] = android.media.MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, u); prepare(); start()
            }
            main.postDelayed({ stopPreview() }, 3000)
        } catch (_: Exception) {}
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { stopPreview() } }
    val g = Alarms.state.collectAsState().value.global
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Alarm sound", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rings when nothing else can play, when music was already playing, and with low battery (if chosen). " +
                    "Tap a sound: it is chosen and plays for 3 s.", color = C.muted, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    (listOf("Phone's alarm (default)" to "") + sounds).forEach { (title, uri) ->
                        val on = g.toneUri == uri
                        Row(Modifier.fillMaxWidth().clickable {
                            Alarms.setGlobal(Alarms.current.global.copy(toneUri = uri, toneName = if (uri.isEmpty()) "" else title)); play(uri)
                        }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            SRadioButton(on, null)
                            Text(title, color = if (on) accent() else C.text, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { STextButton({ stopPreview(); onDone() }) { Text("OK", color = accent()) } })
}

// ---------------------------------------------------------------- alarm health

class AlarmIssue(val title: String, val text: String, val fix: (android.content.Context) -> Unit)

/** What could stop an alarm from ringing, each with its fix. */
fun alarmIssues(ctx: android.content.Context): List<AlarmIssue> {
    val out = ArrayList<AlarmIssue>()
    fun open(c: android.content.Context, i: Intent) = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    val pkg = Uri.parse("package:" + ctx.packageName)
    if (!AlarmScheduler.canExact(ctx)) out += AlarmIssue("Exact alarms", "Not allowed: alarms may be late") { open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) }
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    if (!nm.areNotificationsEnabled()) out += AlarmIssue("Notifications", "Off: no ringing screen, no snooze buttons") {
        open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)) }
    if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out += AlarmIssue("Full-screen alarm", "Not allowed: only a notification") {
        open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) }
    if (ctx.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted) out += AlarmIssue("Battery: Restricted", "Android may block the app at alarm time") {
        open(it, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
    return out
}

/** Bumps every time the app comes back to the front (permissions may have changed in Android's settings). */
@Composable
fun resumeTick(): Int {
    var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
private fun HealthCard() {
    val ctx = LocalContext.current
    val issues = remember(resumeTick()) { alarmIssues(ctx) }
    var testNote by remember { mutableStateOf("") }
    CardBox {
        if (issues.isEmpty()) Text("✓  Everything Android needs for the alarms is allowed", color = C.ok, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().helpOn("Alarm health").padding(16.dp))
        issues.forEach { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⚠  " + i.title, color = C.bad, fontSize = 15.sp)
                    Text(i.text, color = C.muted, fontSize = 12.sp)
                }
                Pill("Fix") { i.fix(ctx) }
            }
            Divider()
        }
        val brand = Build.MANUFACTURER.lowercase()
        val sleepList = when {
            "samsung" in brand -> "Samsung: Settings → Battery → Background usage limits — keep the app out of \"Sleeping\" / \"Deep sleeping\" apps."
            brand in listOf("xiaomi", "redmi", "poco") -> "Xiaomi: allow \"Autostart\" and set Battery saver to \"No restrictions\" for the app."
            brand in listOf("huawei", "honor") -> "Huawei / Honor: App launch → manage manually, allow all for the app."
            brand in listOf("oppo", "realme", "oneplus", "vivo") -> "Allow auto-launch / background activity for the app in the phone's battery settings."
            else -> ""
        }
        if (sleepList.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sleepList, color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Pill("Open") { try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {} }
            }
            Divider()
        }
        Row(Modifier.fillMaxWidth().helpOn("Test alarm in 1 minute").padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Test alarm in 1 minute", color = C.text, fontSize = 15.sp)
                Text(testNote.ifEmpty { "Then press Exit and lock the phone" }, color = if (testNote.isEmpty()) C.muted else accent(), fontSize = 12.sp)
            }
            Pill("Test") {
                app.vlcinterface.alarm.testAlarm(ctx)
                testNote = "Rings at " + AlarmScheduler.whenText(System.currentTimeMillis() + 60_000).substringAfter(' ') + " — you can press Exit and lock the phone"
            }
        }
    }
}
