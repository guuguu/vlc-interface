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
        d.alarms.sortedBy { it.hour * 60 + it.minute }.forEach { al ->
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
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Text(text, color = C.text, fontSize = 15.sp)
        if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp))
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
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader(if (Alarms.get(id) == null) "New alarm" else "Alarm", nav)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardBox {
                ToggleRow("Alarm on", "", a.on) { a = a.copy(on = it) }; Divider()
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
                Choices(listOf("Last queue" to "queue", "Playlist" to "playlist", "Radio" to "radio", "Alarm tone" to "tone"), a.source) { a = a.copy(source = it) }
                if (a.source == "playlist") {
                    if (st.playlists.isEmpty()) Label("No playlists yet")
                    Choices(st.playlists.map { it.name to it.id }, a.playlistId) { a = a.copy(playlistId = it) }
                }
                if (a.source == "radio") {
                    if (st.stations.isEmpty()) Label("No favourite stations yet")
                    Choices(st.stations.map { it.name to it.url }, a.stationUrl) { u -> a = a.copy(stationUrl = u, stationName = st.stations.firstOrNull { it.url == u }?.name ?: "") }
                }
                Divider()
                ToggleRow("Network fallback", "Network not reachable: phone songs, then the alarm tone", a.localFallback) { a = a.copy(localFallback = it) }
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
    val toneAsk = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION") val uri: Uri? = r.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            val name = uri?.let { try { android.media.RingtoneManager.getRingtone(ctx, it)?.getTitle(ctx) } catch (_: Exception) { null } } ?: ""
            val isDefault = uri == null || uri == android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            set(Alarms.current.global.copy(toneUri = if (isDefault) "" else uri.toString(), toneName = if (isDefault) "" else name))
        }
    }
    if (cityAsk) CityDialog { cityAsk = false }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        BackHeader("Alarm clock", nav)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Display")
            CardBox { ValueRow("Clock aspect", "Designs, colours, thickness, size, date, digital", "›") { nav.go(Screen.ClockAspect) } }
            SectionTitle("Location (clock and weather)")
            CardBox {
                ValueRow("City", if (g.cityName.isEmpty()) "Not chosen: the phone's time zone" else "Clock time, alarms and weather follow it, also when the phone's time zone isn't automatic",
                    if (g.cityName.isEmpty()) "Choose" else g.cityName) { cityAsk = true }
                Divider()
                ToggleRow("Weather on the clock", "Sunny, cloudy, rainy or storm, with the city's short name and time zone", g.showWeather) {
                    set(g.copy(showWeather = it)); if (it) app.vlcinterface.alarm.Weather.refresh(force = true) }
            }
            SectionTitle("Ringing")
            CardBox {
                SliderRow("Maximum level", g.maxLevel.toFloat(), 10f..100f, "${g.maxLevel} %") { set(g.copy(maxLevel = it.toInt())) }
                Text("No alarm goes above it", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                Divider()
                ValueRow("Alarm tone", "When nothing else can play, or music was already playing", g.toneName.ifEmpty { "Phone's alarm" }) {
                    toneAsk.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, g.toneUri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }))
                }
                Divider()
                ToggleRow("Vibrate", "", g.vibrate) { set(g.copy(vibrate = it)) }; Divider()
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
                ToggleRow("Snooze buttons on the lock screen", "In the alarm notification", g.lockActions) { set(g.copy(lockActions = it)) }; Divider()
                Label("Volume buttons while ringing")
                Choices(listOf("Snooze" to "snooze", "Nothing" to "nothing", "Change volume" to "volume"), g.volumeKeys) { set(g.copy(volumeKeys = it)) }
            }
            SectionTitle("Waking up")
            CardBox {
                ToggleRow("Random pattern to stop", "Tap 4 numbered dots in order", g.pattern) { set(g.copy(pattern = it)) }; Divider()
                Label("Wake check after Stop", "No sign of being awake for this long (movement, unlocking, network or place change) → a silent, vibrating question; no movement for 30 s → rings again at once, no crescendo")
                Choices(listOf("Off" to 0, "5 min" to 5, "10 min" to 10, "15 min" to 15, "20 min" to 20, "30 min" to 30), g.wakeCheckMin) { set(g.copy(wakeCheckMin = it)) }
                Divider()
                ToggleRow("Use the phone's location", "Moving more than 150 m counts as awake", g.useLocation) { on ->
                    if (!on) set(g.copy(useLocation = false))
                    else if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) set(g.copy(useLocation = true))
                    else locAsk.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
            SectionTitle("Situations")
            CardBox {
                Label("Music already playing", "Stops completely: only the alarm tone rings")
                Divider()
                ToggleRow("During a call", "Waits for the end of the call", g.callWait) { set(g.copy(callWait = it)) }; Divider()
                Label("Missed alarm", "Phone off or restarting at the alarm time")
                Choices(listOf("Ring if under 30 min late" to "ring30", "Notification only" to "notify"), g.missed) { set(g.copy(missed = it)) }
                Divider()
                Label("Low battery (15 % or less)")
                Choices(listOf("Normal" to "normal", "Alarm tone only (no network)" to "tone"), g.lowBattery) { set(g.copy(lowBattery = it)) }
            }
            SectionTitle("System")
            CardBox {
                ToggleRow("Next alarm in a notification", "", g.nextInNotif) { set(g.copy(nextInNotif = it)) }; Divider()
                val exact = AlarmScheduler.canExact(ctx)
                ValueRow("Exact alarms", if (exact) "Allowed: alarms ring on the minute, also in battery saving" else "Not allowed: alarms may be late",
                    if (exact) "✓" else "Allow") {
                    try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    Divider()
                    val fs = ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
                    ValueRow("Full-screen alarm", if (fs) "The ringing screen shows over the lock screen" else "Not allowed: only a notification", if (fs) "✓" else "Allow") {
                        try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
                    }
                }
                Divider()
                val free = Battery.unrestricted(ctx)
                ValueRow("Battery", if (free) "Unrestricted" else "Battery saving may delay the app after the alarm", if (free) "✓" else "Allow") { Battery.ask(ctx) }
            }
            Box(Modifier.height(24.dp))
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
                ToggleRow("24-hour", "", a.h24) { set(a.copy(h24 = it)) }; Divider()
                ToggleRow("Seconds", "Second hand / seconds", a.seconds) { set(a.copy(seconds = it)) }
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
                ToggleRow("Bold digits", "", a.digitalBold) { set(a.copy(digitalBold = it)) }; Divider()
                ToggleRow("Date under the time", "", a.digitalDate) { set(a.copy(digitalDate = it)) }
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
