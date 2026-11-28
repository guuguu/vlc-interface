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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.draw.alpha
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
import app.vlcinterface.alarm.ShortAlarm
import app.vlcinterface.alarm.ShortAlarms
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

/** Under the clock: the alarms (tap one: its page). [compact]: sideways, small chips in a row. */
@Composable
fun AlarmMiniList(nav: Navigator, compact: Boolean = false) {
    val d by Alarms.state.collectAsState()
    val ctxH = LocalContext.current
    val issues = remember(resumeTick()) { alarmIssues(ctxH) }
    // One-time alarms that already rang are not shown here (2026-11-21 12:30): they're in Alarm settings → History.
    val list = d.alarms.filter { it.id != app.vlcinterface.alarm.AlarmEngine.TEST_ID && !Alarms.isPast(it) }.sortedBy { it.hour * 60 + it.minute }
    val warn = issues.isNotEmpty() && list.any { it.on }
    // Permissions missing (typically right after a new install, 2026-11-21 11:41): the alarm area is red and any touch
    // on it (an alarm, its switch, + Add, ⚙, ⚠) first shows a pop-up with a link to each setting to fix; "Later" goes
    // on with what was touched. Fixed meanwhile (checked again on coming back to the app): it just goes on.
    val bad = issues.isNotEmpty()
    var gate by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guard(act: () -> Unit) { if (alarmIssues(ctxH).isNotEmpty()) gate = act else act() }
    val pendingAct = gate
    if (pendingAct != null) {
        if (issues.isEmpty()) androidx.compose.runtime.LaunchedEffect(pendingAct) { gate = null; pendingAct() }
        else AlarmPermissionDialog(issues, onLater = { gate = null; pendingAct() }, onClose = { gate = null })
    }
    val redBg = Color(0x40E53935)
    if (compact) {
        FlowRow(Modifier.fillMaxWidth().then(if (bad) Modifier.clip(RoundedCornerShape(10.dp)).background(redBg).border(1.dp, C.bad, RoundedCornerShape(10.dp)).padding(4.dp) else Modifier), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy((4 * sg()).dp)) {
            if (warn || bad) Text("⚠", color = C.bad, fontSize = SYM, modifier = Modifier.clickable { guard { nav.go(Screen.AlarmSettings) } }.padding(4.dp))
            list.forEach { al ->
                Text(al.timeText, color = if (al.on) C.text else C.muted, fontSize = 13.sp, fontWeight = if (al.on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).clickable { guard { nav.go(Screen.AlarmEdit(al.id)) } }.padding(horizontal = 8.dp, vertical = (3 * sg()).dp))
            }
            Text("+", color = accent(), fontSize = SYM, modifier = Modifier.clickable { guard { nav.go(Screen.AlarmEdit("")) } }.padding(horizontal = 6.dp, vertical = (2 * sg()).dp))
            Text("⚙", color = C.text, fontSize = SYM, modifier = Modifier.clickable { guard { nav.go(Screen.AlarmSettings) } }.padding(horizontal = 6.dp, vertical = (2 * sg()).dp))
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (bad) redBg else Color(0x14FFFFFF))
        .then(if (bad) Modifier.border(1.dp, C.bad, RoundedCornerShape(12.dp)) else Modifier)) {
        if (bad) Text("⚠  Alarms may not ring: " + issues.joinToString(" · ") { it.title } + "  ›", color = C.bad, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clickable { gate = { nav.go(Screen.AlarmSettings) } }.padding(12.dp))
        // Row height from Look → "Alarm rows under the clock" (50 % = half the old 56 dp rows); every alarm listed,
        // the list scrolls on its own when they don't all fit.
        val k = App.instance.store.state.collectAsState().value.settings.alarmRowPct.coerceIn(50, 100) / 100f
        val rowH = (56 * k).dp
        val maxRows = 5
        val listScroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().heightIn(max = (rowH + 1.dp) * maxRows).verticalScroll(listScroll)) {
            list.forEach { al -> AlarmRow(nav, al, k, guard = { a -> guard(a) }); Divider() }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+  Add alarm", color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable { guard { nav.go(Screen.AlarmEdit("")) } }.padding(12.dp))
            Text("⚙", color = C.text, fontSize = SYM, modifier = Modifier.clickable { guard { nav.go(Screen.AlarmSettings) } }.padding(12.dp))
        }
    }
}

/** One alarm row: time, name / when, and a wide switch; switching it confirms with a short note. Height scaled by [k]. */
@Composable
fun AlarmRow(nav: Navigator, al: Alarm, k: Float, guard: (() -> Unit) -> Unit = { it() }) {
    Row(Modifier.fillMaxWidth().height((56 * k).dp).clickable { guard { nav.go(Screen.AlarmEdit(al.id)) } }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(al.timeText, color = if (al.on) C.text else C.muted, fontSize = (12 + 8 * (k - 0.5f) * 2).sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            if (k >= 0.75f) {
                if (al.name.isNotEmpty()) Text(al.name, color = C.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Alarm.whenText(al), color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(listOf(al.name, Alarm.whenText(al)).filter { it.isNotEmpty() }.joinToString(" · "), color = C.muted,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WideSwitch(al.on, height0 = (14 + 12 * (k - 0.5f) * 2).dp, holdMs = 1000L, tapNote = "Hold 1 s to switch this alarm") { on -> guard {
            Alarms.put(al.copy(on = on))
            val what = listOf(al.timeText, Alarm.daysText(al.days).takeIf { al.mode == "weekly" && al.days != 0 } ?: "", al.name).filter { it.isNotEmpty() }.joinToString(" ")
            if (on) AlarmSetNote.show(al.copy(on = true)) else ShortNote.show("$what won't ring")
        } }
    }
}

// ---------------------------------------------------------------- small helpers

@Composable
private fun <T> Choices(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = (6 * sg()).dp), horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
        options.forEach { (label, v) -> Pill(label, selected = v == selected) { onPick(v) } }
    }
}

@Composable
private fun Label(text: String, hint: String = "") {
    Column(Modifier.anchor(text).fillMaxWidth().helpOn(text).padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Text(text, color = C.text, fontSize = 15.sp)
        if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.anchor(label).fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp).helpOn(label))
        ThinSlider(value, onChange, Modifier.weight(1f), valueRange = range, steps = steps)
        Text(shown, color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
    }
}

private fun minText(s: Int) = if (s == 0) "Off" else if (s < 60) "$s s" else "${s / 60} min"

// ---------------------------------------------------------------- one alarm

@Composable
fun AlarmEditScreen(nav: Navigator, id: String) {
    val st by App.instance.store.state.collectAsState()
    // New alarm (or one without stations): the stations chosen last come already selected.
    val start = remember(id) {
        val g = Alarms.current.global
        val a0 = Alarms.get(id) ?: Alarms.newFromLast(id)
        if (a0.stationUrl.isEmpty() && g.lastStationUrl.isNotEmpty())
            a0.copy(stationUrl = g.lastStationUrl, stationName = g.lastStationName, fallbackUrl = g.lastFallbackUrl, fallbackName = g.lastFallbackName)
        else a0
    }
    var a by remember(id) { mutableStateOf(start) }
    // No Save button: leaving the page (back, home, ✕, another page) saves the alarm if something was set by hand;
    // untouched (new or existing), nothing is saved again. A deleted alarm isn't saved back.
    var deleted by remember(id) { mutableStateOf(false) }
    val latest by androidx.compose.runtime.rememberUpdatedState(a)
    val gone by androidx.compose.runtime.rememberUpdatedState(deleted)
    androidx.compose.runtime.DisposableEffect(id) { onDispose { if (!gone && latest != start) { Alarms.put(latest); if (latest.on) AlarmSetNote.show(latest) } } }
    var timeAsk by remember { mutableStateOf(false) }
    var dateAsk by remember { mutableStateOf(false) }
    var datesAsk by remember { mutableStateOf(false) }
    var helpMsg by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Helper: what the alarm will now do, and how to get another repetition (Settings → Alarm clock → Helpers).
    fun showHelp(x: Alarm) {
        if (!AutoHelp.ok("alarm", "repetition")) return
        val title = when (x.mode) {
            "weekly" -> if (x.days == 0) "No day chosen yet" else "Rings every " + Alarm.daysText(x.days).replace(" ", ", ") +
                (if (x.date.isNotEmpty()) " from " + Alarm.whenText(x.copy(mode = "once")) else "")
            "dates" -> Alarm.whenText(x).let { if (it == "No dates left") "No dates chosen yet" else "Rings only on: $it" }
            else -> "Rings one time only" + (if (x.date.isNotEmpty()) ", on " + Alarm.whenText(x) else ", the next time the clock shows " + x.timeText)
        }
        helpMsg = title to "One time only: use only the Date button, don't select any day disk.\n" +
            "Repeated alarm: choose the start date on the calendar and the day buttons here.\n" +
            "Several dates: choose specific dates on the monthly calendar."
    }
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
        Box(Modifier.padding(horizontal = 20.dp)) { ShortAlarmsLink(nav) }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            CardBox {
                HToggleRow("Alarm on", "", a.on) { a = a.copy(on = it) }; Divider()
                Row(Modifier.fillMaxWidth().clickable { timeAsk = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Time", color = C.text, fontSize = 15.sp, modifier = Modifier.anchor("Time").weight(1f))
                    Text(a.timeText, color = accent(), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                }
                Divider()
                OutlinedTextField(a.name, { a = a.copy(name = it.take(40)) }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            SectionTitle("Repeat")
            CardBox {
                Label("Repeat")
                Choices(listOf("Once" to "once", "Weekly days" to "weekly", "Specific dates" to "dates"), a.mode) { m ->
                    a = a.copy(mode = m); if (m == "dates" && a.dates.isEmpty()) datesAsk = true else showHelp(a)
                }
                when (a.mode) {
                    "weekly" -> {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
                                val on = a.days and (1 shl i) != 0
                                Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) accent() else Color.Transparent)
                                    .border(1.dp, if (on) accent() else C.line, CircleShape).clickable { a = a.copy(days = a.days xor (1 shl i)); showHelp(a) },
                                    contentAlignment = Alignment.Center) { Text(l, color = if (on) Color(0xFF1A0E05) else C.text, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        HValueRow("Start date", if (a.date.isEmpty()) "Not set: from now" else "Repeats from this date (nothing before it)",
                            if (a.date.isEmpty()) "Set" else Alarm.whenText(a.copy(mode = "once"))) { dateAsk = true }
                        if (a.date.isNotEmpty()) Text("Clear the start date", color = accent(), fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp).clickable { a = a.copy(date = "") })
                    }
                    "dates" -> {
                        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = Alarms.zone() }.format(java.util.Date())
                        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = (8 * sg()).dp), horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                            a.dates.filter { it >= today }.sorted().forEach { d ->
                                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(Alarm.whenText(Alarm(mode = "once", date = d)), color = C.text, fontSize = 13.sp)
                                    Text("✕", color = C.text, fontSize = SYM, modifier = Modifier.clickable { a = a.copy(dates = a.dates - d); showHelp(a) }.padding(horizontal = 6.dp))
                                }
                            }
                            Pill("+ Add dates") { datesAsk = true }
                        }
                    }
                    else -> {
                        HValueRow("Date", if (a.date.isEmpty()) "Not set: the next time the clock shows it" else "Rings one time only, on this date",
                            if (a.date.isEmpty()) "Set" else Alarm.whenText(a)) { dateAsk = true }
                        if (a.date.isNotEmpty()) Text("Clear the date", color = accent(), fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp).clickable { a = a.copy(date = "") })
                    }
                }
                Text(Alarm.whenText(a), color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
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
                        HValueRow("Music folder", rootNote.ifEmpty { "Phone music for all alarms" }, musicRootText(gl)) { rootAsk = true }
                    }
                    "radio" -> {
                        Explain("The preferred station plays live (no buffer). Not heard within 15 s → the fallback station; not heard either → the alarm sound.")
                        if (st.stations.isEmpty()) Explain("No favourite stations yet: add some in the radio player first.")
                        Label("Preferred station")
                        // The alarm's own station stays selected even if it's no longer a favourite.
                        fun keep(url: String, name: String) = if (url.isNotEmpty() && st.stations.none { it.url == url })
                            listOf("${name.ifEmpty { url.substringAfter("://").take(30) }} (not in favourites)" to url) else emptyList()
                        Choices(st.stations.map { it.name to it.url } + keep(a.stationUrl, a.stationName), a.stationUrl) { u -> a = a.copy(stationUrl = u, stationName = st.stations.firstOrNull { it.url == u }?.name ?: a.stationName,
                            fallbackUrl = if (a.fallbackUrl == u) "" else a.fallbackUrl, fallbackName = if (a.fallbackUrl == u) "" else a.fallbackName) }
                        Label("Fallback station", "Used when the preferred one can't be reached")
                        Choices(listOf("None" to "") + st.stations.filter { it.url != a.stationUrl }.map { it.name to it.url } + keep(a.fallbackUrl, a.fallbackName),
                            a.fallbackUrl) { u -> a = a.copy(fallbackUrl = u, fallbackName = st.stations.firstOrNull { it.url == u }?.name ?: if (u == a.fallbackUrl) a.fallbackName else "") }
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
                if (a.volTarget > maxL || a.volStart > maxL) Text("Capped at $maxL % (maximum level in the alarm clock settings)", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
                Label("Fade-in time")
                Choices(listOf(0, 30, 60, 120, 300, 600).map { minText(it) to it }, a.fadeSec) { a = a.copy(fadeSec = it) }
                Divider()
                // This alarm's own choice wins over the alarm clock settings' Vibrate (kept with the alarm).
                HToggleRow("Vibrate (this alarm)", "", a.vibrate ?: Alarms.current.global.vibrate) { a = a.copy(vibrate = it) }
                Divider()
                Label("Light blinking (this alarm)")
                Choices(listOf("Off" to "off", "Screen flash" to "screen", "Flash light" to "flash"), a.led ?: Alarms.current.global.led) { a = a.copy(led = it) }
                if ((a.led ?: Alarms.current.global.led) == "screen")
                    FlashColours(a.ledColor ?: Alarms.current.global.ledColor) { a = a.copy(ledColor = it) }
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
            Row(Modifier.fillMaxWidth().padding(vertical = (14 * sg()).dp), horizontalArrangement = Arrangement.spacedBy((10 * sg()).dp)) {
                if (Alarms.get(id) != null) Pill("Delete alarm", modifier = Modifier.weight(1f)) { deleted = true; Alarms.delete(a.id); nav.back() }
            }
            // History of past alarms at the bottom of the alarm page (moved here from the alarm clock settings, 13:43).
            AlarmHistory(nav, exclude = a.id)
            Box(Modifier.height(24.dp))
        }
    }
    }
    helpMsg?.let { (t, txt) ->
        AutoInfoDialog(onDismissRequest = { helpMsg = null }, confirmButton = { STextButton({ helpMsg = null }) { Text("OK", color = accent()) } },
            title = { Text(t, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }, text = { Text(txt, color = C.muted, fontSize = 13.sp) })
    }
    if (datesAsk) MonthDatesDialog(a.dates) { picked -> datesAsk = false; if (picked != null) { a = a.copy(mode = "dates", dates = picked); showHelp(a) } }
    if (dateAsk) {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = utc }
        val initial = a.date.takeIf { it.isNotEmpty() }?.let { try { fmt.parse(it)?.time } catch (_: Exception) { null } }
            ?: (System.currentTimeMillis() + Alarms.zone().getOffset(System.currentTimeMillis()))
        val dp = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initial)
        androidx.compose.material3.DatePickerDialog(onDismissRequest = { dateAsk = false },
            confirmButton = { STextButton({ dp.selectedDateMillis?.let { a = a.copy(date = fmt.format(java.util.Date(it))) }; dateAsk = false; showHelp(a) }) { Text("OK", color = accent()) } },
            dismissButton = { STextButton({ dateAsk = false }) { Text("Cancel") } }) {
            androidx.compose.material3.DatePicker(dp)
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
    val scroll = rememberScrollState()
    HelpHost {
    Column(Modifier.fillMaxSize()) {
        BackHeader("Alarm clock", nav)
        Column(Modifier.weight(1f).anchorViewport(scroll, "alarm").verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
            ShortAlarmsLink(nav)
            AlarmSettingsBody(nav)
            ShortAlarmsSection()
            Box(Modifier.height(24.dp))
        }
    }
    }
}

/** The alarm clock settings (also shown on the setup wizard's alarm page). */
@Composable
fun ColumnScope.AlarmSettingsBody(nav: Navigator) {
    val d by Alarms.state.collectAsState()
    val g = d.global
    // Alarm system switched off (Settings): this page keeps the button that switches it on again and the clock's
    // own settings (clock aspect, screen saver, city and weather); every alarm setting is hidden.
    if (!g.systemOn) AlarmSystemButton()
    fun set(n: AlarmGlobal) = Alarms.setGlobal(n)
    val ctx = LocalContext.current
    val locAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) set(Alarms.current.global.copy(useLocation = true))
    }
    var cityAsk by remember { mutableStateOf(false) }
    if (cityAsk) CityDialog { cityAsk = false }
    var soundAsk by remember { mutableStateOf(false) }
    var rootAsk by remember { mutableStateOf(false) }
    var calAsk by remember { mutableStateOf(false) }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.all { it }) calAsk = true }
    if (calAsk) CalendarPickDialog { calAsk = false }
    if (soundAsk) AlarmSoundDialog { soundAsk = false }
    if (rootAsk) RootFolderDialog { rootAsk = false }
            if (g.systemOn) {
                SectionTitle("Alarm health")
                HealthCard()
            }
            Text("Tip: double tap or long-press any setting for an explanation", color = C.muted, fontSize = 13.sp)
            SectionTitle("Display")
            CardBox {
                HValueRow("Clock aspect", "Designs, colours, thickness, size, date, digital", "›") { nav.go(Screen.ClockAspect) }
                Divider()
                ScreenSaverRow()
            }
            SectionTitle("Location (clock and weather)")
            CardBox {
                HValueRow("City", if (g.cityName.isEmpty()) "Not chosen: the phone's time zone" else "Clock time, alarms and weather follow it, also when the phone's time zone isn't automatic",
                    if (g.cityName.isEmpty()) "Choose" else g.cityName) { cityAsk = true }
                Divider()
                HToggleRow("Weather on the clock", "Sun, clouds, rain, snow or storm, with the city's short name, its time zone and the temperature now > in 6 hours", g.showWeather) {
                    set(g.copy(showWeather = it)); if (it) app.vlcinterface.alarm.Weather.refresh(force = true) }
                if (g.showWeather) {
                    var wHelp by remember { mutableStateOf(false) }
                    Label("Weather shows", "Now: the weather right now · At noon: today's forecast for 12:00 at the city")
                    Choices(listOf("Now" to false, "At noon" to true), g.weatherNoon) { v ->
                        set(g.copy(weatherNoon = v)); app.vlcinterface.alarm.Weather.refresh(force = true)
                        if (g.helpers) wHelp = true
                    }
                    if (wHelp) HelperDialog("Weather shows", HELP["Weather shows"] ?: "") { wHelp = false }
                    // The line under the city (2026-11-22 06:44 / 06:47): time zone, temperature now > in 6 hours, or both alternating.
                    var lHelp by remember { mutableStateOf(false) }
                    Label("Under the city", "All: the time zone and the temperature now > in 6 hours, changing every 5 s")
                    Choices(listOf("All" to "both", "Time zone" to "tz", "Temperatures" to "temp"), g.wxLine) { v ->
                        set(g.copy(wxLine = v)); if (g.helpers) lHelp = true
                    }
                    if (lHelp) HelperDialog("Under the city", HELP["Under the city"] ?: "") { lHelp = false }
                }
            }
            if (!g.systemOn) return
            SectionTitle("Ringing")
            CardBox {
                SliderRow("Maximum level", g.maxLevel.toFloat(), 10f..100f, "${g.maxLevel} %") { set(g.copy(maxLevel = it.toInt())) }
                Text("No alarm goes above it", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                Divider()
                HValueRow("Alarm sound", "When nothing else can play, or music was already playing", g.toneName.ifEmpty { "Phone's alarm" }) { soundAsk = true }
                Divider()
                HValueRow("Music folder", "Phone music played by the alarms (with its subfolders)" + if (g.localRoot.isEmpty()) " — not chosen, the current one is used" else "", musicRootText(g)) { rootAsk = true }
                Divider()
                HToggleRow("Vibrate (default)", "", g.vibrate) { set(g.copy(vibrate = it)) }; Divider()
                Label("Light blinking (default)")
                Choices(listOf("Off" to "off", "Screen flash" to "screen", "Flash light" to "flash"), g.led) { set(g.copy(led = it)) }
                if (g.led == "screen") FlashColours(g.ledColor) { set(g.copy(ledColor = it)) }
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
            SectionTitle("Alarm page and calendar")
            CardBox {
                HToggleRow("Helpers", "Short pop-ups explaining a setting when you change it — each once per session", g.helpers) { set(g.copy(helpers = it)) }
                // Helpers per settings page (2026-11-28 01:15).
                if (g.helpers) {
                    val stH = app.vlcinterface.App.instance.store.state.collectAsState().value.settings
                    Text("Helpers per page", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    AutoHelp.PAGES.forEach { (k, name) ->
                        HToggleRow("Helpers: $name", "", k !in stH.helpersOffPages) { on ->
                            app.vlcinterface.App.instance.store.update { st -> st.copy(settings = st.settings.copy(
                                helpersOffPages = if (on) st.settings.helpersOffPages - k else (st.settings.helpersOffPages + k).distinct())) }
                        }
                    }
                }
                Divider()
                HToggleRow("Show alarms in my calendar", if (g.calSync && g.calName.isNotEmpty()) "In: ${g.calName}" else
                    "Alarms as events in the phone's calendar (your account already on the phone syncs them)", g.calSync) { on ->
                    if (!on) set(g.copy(calSync = false))
                    else if (app.vlcinterface.alarm.AlarmCalendar.allowed(ctx)) calAsk = true
                    else calPerm.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }
                if (g.calSync) { Divider(); HValueRow("Calendar", "Where the alarms appear", g.calName.ifEmpty { "Choose" }) { calAsk = true } }
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
}

// ---------------------------------------------------------------- clock aspect

@Composable
fun ClockAspectScreen(nav: Navigator) {
    val d by Alarms.state.collectAsState()
    val a = d.aspect
    fun set(n: ClockAspect) = Alarms.setAspect(n)
    val scroll = rememberScrollState()
    var dimPreviewAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var dimTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    if (dimTick < 0) return   // read: the preview redraws when the 3 s dimming preview ends
    Column(Modifier.fillMaxSize()) {
        BackHeader("Clock aspect", nav)
        // Live preview of the chosen design: follows every change at once. Outside the disk: the player's background.
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(12.dp)).background(PLAYER_BG), contentAlignment = Alignment.Center) {
            val showDim = System.currentTimeMillis() - dimPreviewAt < 3000
            androidx.compose.runtime.LaunchedEffect(dimPreviewAt) { kotlinx.coroutines.delay(3100); dimTick++ }
            ClockFace(a, Modifier.padding(8.dp).size(190.dp).then(if (showDim) Modifier.alpha(1f - a.dim / 100f) else Modifier))
            if (showDim) Text("Charging screen preview", color = C.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        }
        Column(Modifier.weight(1f).anchorViewport(scroll, "clock").verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = (8 * sg()).dp)) {
                    HueSatBars("Disk (inside the circle)", a.diskHue, a.diskSat, ::clockDisk, round = true) { h, s -> set(a.copy(diskHue = h, diskSat = s)) }
                    HueSatBars("Ring, ticks, numbers, hands", a.mainHue, a.mainSat, ::clockMain) { h, s -> set(a.copy(mainHue = h, mainSat = s)) }
                    HueSatBars("Date", a.dateHue, a.dateSat, ::clockMain) { h, s -> set(a.copy(dateHue = h, dateSat = s)) }
                    Text("Second hand", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hue", color = C.text, fontSize = 13.sp, modifier = Modifier.anchor("Hue").width(78.dp))
                        GradientBar(a.secHue / 360f, androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f).map { Color.hsl(it, 0.9f, 0.62f) }),
                            Modifier.weight(1f)) { set(a.copy(secHue = it * 360f)) }
                        Box(Modifier.padding(start = 10.dp).size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color.hsl(a.secHue.coerceAtMost(359.9f), 0.9f, 0.62f)))
                    }
                }
            }
            SectionTitle("Dimming and thickness")
            CardBox {
                SliderRow("Charging dimming", a.dim.toFloat(), 0f..85f, "${a.dim} %") { set(a.copy(dim = it.toInt())); dimPreviewAt = System.currentTimeMillis() }
                Text("Only the clock shown while charging (screen saver); the preview above shows it while you move the slider",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                val key = if (a.analog) "${a.design}" else "d"
                val tk = a.thickFor()
                SliderRow("Thickness", tk, 0.5f..2.5f, "×%.1f".format(tk)) { set(a.copy(thick = a.thick + (key to it))) }
                Text(if (a.analog) "Lines and numbers of design ${a.design} only" else "Digits of the digital clock only", color = C.muted, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
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
                Text("From 22:00 to 07:00", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
            }
            // 2026-11-22 19:27: the full-screen clock (double tap on the player's clock) keeps the screen on at this brightness.
            SectionTitle("Full screen")
            // Helpers on (2026-11-23 01:16): the first change of each of these shows its helper.
            val fsHelp = LocalHelp.current
            val fsHelpers = Alarms.state.collectAsState().value.global.helpers
            val fsShown = remember { mutableSetOf<String>() }
            fun fsHint(k: String) { if (fsHelpers && fsShown.add(k) && AutoHelp.ok("clock", k)) fsHelp(k) }
            CardBox {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Full-screen brightness", color = C.text, fontSize = 14.sp, modifier = Modifier.anchor("Full-screen clock brightness").weight(1f).helpOn("Full-screen clock brightness"))
                    Pill("Phone's own", selected = a.fsBright <= 0f, filled = a.fsBright <= 0f) { set(a.copy(fsBright = if (a.fsBright <= 0f) 0.6f else -1f)); fsHint("Full-screen clock brightness") }
                }
                if (a.fsBright > 0f) SliderRow("Full-screen clock brightness", a.fsBright, 0.01f..1f, "${(a.fsBright * 100).toInt()} %") {
                    set(a.copy(fsBright = (Math.round(it * 100f) / 100f).coerceIn(0.01f, 1f))); fsHint("Full-screen clock brightness") }
                Text("The screen stays on while the clock is full screen. There, slide up / down on the left half for the brightness (kept here), on the right half for the sound volume.",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
                Divider()
                // 19:52: the playing name gliding along the full-screen clock — its letter size (same upright and sideways) and colour.
                SliderRow("Scrolling name size", a.tickerSp.toFloat(), 16f..96f, "${a.tickerSp} sp") { set(a.copy(tickerSp = (Math.round(it / 2f) * 2).coerceIn(16, 96))); fsHint("Scrolling name") }
                var tickColour by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Scrolling name colour", color = C.text, fontSize = 14.sp, modifier = Modifier.anchor("Scrolling name colour").weight(1f).helpOn("Scrolling name"))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color(a.tickerColor)).border(1.dp, C.muted, CircleShape).clickable { tickColour = true })
                    STextButton({ tickColour = true }) { Text("Change") }
                    STextButton({ set(a.copy(tickerColor = 0xFFFFFFFFL, tickerSp = 36)) }) { Text("Reset") }
                }
                if (tickColour) ColorPickDialog(a.tickerColor, onDismiss = { tickColour = false }) { c -> set(a.copy(tickerColor = c)); tickColour = false; fsHint("Scrolling name") }
                // Preview of the name at its size and colour.
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("Artist – Title", color = Color(a.tickerColor), fontSize = a.tickerSp.sp, maxLines = 1, softWrap = false)
                }
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
        title = { Text("City", color = C.text, modifier = Modifier.anchor("City")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
                    STextButton({ search() }, enabled = !busy) { Text(if (busy) "…" else "Search", color = accent()) }
                }
                if (note.isNotEmpty()) Text(note, color = C.muted, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    res.forEach { c ->
                        Column(Modifier.fillMaxWidth().clickable {
                            Alarms.setGlobal(Alarms.current.global.copy(cityName = c.name, cityShort = c.short, cityLat = c.lat, cityLon = c.lon, cityTz = c.tz))
                            app.vlcinterface.alarm.Weather.refresh(force = true)
                            onDone()
                        }.padding(vertical = (8 * sg()).dp)) {
                            Text(c.name + "  ·  " + c.short, color = C.text, fontSize = 15.sp)
                            Text(listOf(c.detail, c.tz).filter { it.isNotEmpty() }.joinToString(" · "), color = C.muted, fontSize = 13.sp)
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

internal val HELP: Map<String, String> = mapOf(
    "Scrolling name size" to "The letter size of the name of what's playing gliding along the full-screen clock — the same upright and sideways (16–96 sp, default 36). Its colour is set just under it; Reset puts back white and 36 sp.",
    "Scrolling name" to "The name of what's playing gliding along the full-screen clock (double tap on the clock in the song or radio player): \"Scrolling name size\" is its letter size, the same upright and sideways (16–96 sp, default 36), and \"Scrolling name colour\" its colour (any colour from the colour editor; default white). No frame and no background. Upright it runs along the bottom under the clock; sideways in the space beside the clock, which keeps its full size. Reset puts back white and 36 sp.",
    "Full-screen clock brightness" to "The full-screen clock: a double tap on the clock in the song or radio player opens it (a double tap there goes back; a long press on the clock opens Clock aspect). It turns with the phone, keeps the screen on, carries the play / pause button and the name of what's playing, and closes by itself when an alarm rings; the night dimming dims all of it. Slide up / down on its left half for the brightness, on its right half for the sound volume.\n\nFull-screen brightness: the screen's brightness while the clock is full screen. \"Phone's own\" keeps the phone's brightness; otherwise a level of its own, 1–100 %. The screen stays on while the full-screen clock is shown, and the clock turns with the phone. Sliding up or down on the left half of the full screen changes it too, and is kept here (the right half sets the sound volume). Leaving gives the phone its own brightness back.",
    "Under the city" to "The small line under the city's name on the clock. All: the time zone (e.g. UTC+1) and the temperature at the " +
        "city now > 6 hours later (e.g. 15°>18°), changing every 5 seconds. Time zone or Temperatures: always that one. " +
        "The temperature now is measured at the nearest airport weather station (within 35 km; else the forecast for the city), the one in 6 hours is the forecast; refreshed every 15 min. Until they're known the time zone is shown.",
    "Weather shows" to "What the weather icon on the clock shows for your city. 'At noon': today's forecast for 12:00 at the city (its own local time) — handy in the morning to see what the day will be; after midnight it moves to the new day's noon. " +
        "'Now': the weather right now. Either way it's refreshed every 15 minutes (only with the weather on). Icons: sun, cloud, raindrops (light rain), cloud with rain, snowflakes (light snow), cloud with snow, storm. Default: At noon.",
    "Alarm on" to "Switches this alarm on or off without deleting it. A one-time alarm switches itself off after ringing.",
    "Time" to "When the alarm rings, in the time zone of the city chosen in the alarm clock settings (or the phone's).",
    "Days" to "The days it repeats. No day selected: it rings once, at the next time shown.",
    "Repeat" to "Once: rings one time (on the date you choose, or the next time the clock shows it). Weekly days: every week on the chosen days, from a start date. Specific dates: only on the dates you tap on the monthly calendar.",
    "Helpers" to "After you choose days or dates on an alarm page, a small pop-up says what the alarm will do and how to get another repetition. It closes by itself.",
    "Show alarms in my calendar" to "Each alarm also appears as an event in the phone's calendar, so you see it in your account's calendar on any device. The app uses the calendar already on the phone: no account details are asked or stored. The events have no reminders (the app rings) and follow every change.",
    "Calendar" to "The calendar the alarms are written to. Your main account's calendar comes first.",
    "Date" to "A date for the alarm. With no day selected it rings once, on that date. With days selected it starts repeating from that date (nothing before it).",
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
    "Weather on the clock" to "A small icon (sunny, cloudy, rainy, storm) with the city's short name and, under it, the time zone and / or the temperature now > in 6 hours (see Under the city), refreshed every 15 min: forecast from Open-Meteo, the temperature now from the nearest airport weather station.",
    "Maximum level" to "No alarm ever goes above this volume: not the target, not the ring after the wake check, not the alarm sound.",
    "Vibrate (default)" to "The phone vibrates while an alarm rings — for alarms that have no Vibrate choice of their own. An alarm's own Vibrate (on its page, under Volume) always wins.",
    "Vibrate (this alarm)" to "Vibrate while this alarm rings. Kept with this alarm and used instead of the alarm clock settings' Vibrate.",
    "Light blinking (default)" to "Screen flash: half of the ringing screen flashes in the chosen colour at full brightness — the clock, the alarm's name and Snooze / Stop / the pattern stay on the other half. Flash light: the camera's light blinks. Moving the phone stops either flashing (sound and vibration go on). For alarms that have no light choice of their own; an alarm's own choice (on its page, under Volume) always wins.",
    "Light blinking (this alarm)" to "Screen flash: half of the ringing screen flashes in the chosen colour at full brightness, the alarm details and Snooze / Stop / the pattern on the other half. Flash light: the camera's light blinks. Moving the phone stops the flashing. Kept with this alarm and used instead of the alarm clock settings' choice.",
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
    "Clock screen saver while charging" to "Android can show an app's screen saver while the phone charges, over the locked phone. This one is your chosen clock, dimmed, drifting very slowly so the screen doesn't burn in. Apps can't draw on the lock screen itself; this is Android's way. Turned on in Android's Screen saver page (the button opens it).",
    "Alarm health" to "Everything Android needs so alarms always ring. A red line has a button to fix it.",
    "Test alarm in 1 minute" to "Rings in 1 minute with the first alarm's settings. Press Exit and lock the phone to check that alarms ring on your phone.",
)

/** Screen with explanation pop-ups: double tap or long-press a setting. */
@Composable
fun HelpHost(content: @Composable () -> Unit) {
    var key by remember { mutableStateOf("") }
    androidx.compose.runtime.CompositionLocalProvider(LocalHelp provides { k -> key = k }) { content() }
    if (key.isNotEmpty()) HelperDialog(key, HELP[key] ?: SettingHelp.text(key, "")) { key = "" }
}

@Composable
private fun Modifier.helpOn(key: String): Modifier {
    val help = LocalHelp.current
    return this.combinedClickable(interactionSource = null, indication = null, onClick = {}, onDoubleClick = { help(key) }, onLongClick = { help(key) })
}

/** ToggleRow with the explanation on double tap / long-press (a single tap still switches). */
@Composable
private fun HToggleRow(label: String, hint: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    val help = LocalHelp.current
    val has = true      // every setting explains itself (own text, or the general help texts)
    Row(Modifier.anchor(label).fillMaxWidth().heightIn(min = 56.dp)
        .then(if (has) Modifier.combinedClickable(onClick = { onChange(!checked) }, onDoubleClick = { help(label) }, onLongClick = { help(label) })
            else Modifier.clickable { onChange(!checked) })
        .padding(horizontal = 16.dp, vertical = (6 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
        }
        AppSwitch(checked, onChange)
    }
}

@Composable
private fun HValueRow(label: String, hint: String = "", value: String, onClick: () -> Unit) {
    val help = LocalHelp.current
    val has = true      // every setting explains itself (own text, or the general help texts)
    Row(Modifier.anchor(label).fillMaxWidth().heightIn(min = 56.dp)
        .then(if (has) Modifier.combinedClickable(onClick = onClick, onDoubleClick = { help(label) }, onLongClick = { help(label) }) else Modifier.clickable(onClick = onClick))
        .padding(horizontal = 16.dp, vertical = (6 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
            if (LocalShowNow.current && value.isNotBlank()) NowLine(value)
        }
        Text(value, color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp))
    }
}

@Composable
private fun Explain(text: String) = Text(text, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = (2 * sg()).dp))

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
        title = { Text("Music folder for alarms", color = C.text, modifier = Modifier.anchor("Music folder for alarms")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
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
                        }.padding(vertical = (8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(rel, color = if (on) accent() else C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("$n songs", color = C.muted, fontSize = 13.sp)
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
    // Your own sound file (kept readable after a restart).
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val name = try { ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null } } catch (_: Exception) { null } ?: "My sound"
            Alarms.setGlobal(Alarms.current.global.copy(toneUri = u.toString(), toneName = name.substringBeforeLast('.')))
            play(u.toString())
        }
    }
    val own = g.toneUri.isNotEmpty() && g.toneUri.startsWith("content://") && !g.toneUri.startsWith("content://media/")
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Alarm sound", color = C.text, modifier = Modifier.anchor("Alarm sound")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy((4 * sg()).dp)) {
                Text("Rings when nothing else can play, when music was already playing, and with low battery (if chosen). " +
                    "Tap a sound: it is chosen and plays for 3 s. Browse… picks your own sound file.", color = C.muted, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().clickable { browse.launch(arrayOf("audio/*")) }.padding(vertical = (9 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                        SRadioButton(own, null)
                        Text(if (own) "My file: " + g.toneName else "Browse… (your own file)", color = if (own) accent() else C.text, fontSize = 14.sp,
                            modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("📂", fontSize = 16.sp)
                    }
                    (listOf("Phone's alarm (default)" to "") + sounds).forEach { (title, uri) ->
                        val on = g.toneUri == uri
                        Row(Modifier.fillMaxWidth().clickable {
                            Alarms.setGlobal(Alarms.current.global.copy(toneUri = uri, toneName = if (uri.isEmpty()) "" else title)); play(uri)
                        }.padding(vertical = (9 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
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

class AlarmIssue(val title: String, val text: String, val kind: String = "", val fix: (android.content.Context) -> Unit)

/** What could stop an alarm from ringing, each with its fix. */
fun alarmIssues(ctx: android.content.Context): List<AlarmIssue> {
    val out = ArrayList<AlarmIssue>()
    // Each fix opens the Android page where exactly that permission is granted for this app (2026-11-21 11:42); if a
    // phone doesn't have that page, the next closest one, ending with the app's own info page.
    fun open(c: android.content.Context, vararg tries: Intent) {
        for (i in tries) { try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {} }
    }
    val pkg = Uri.parse("package:" + ctx.packageName)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    if (!AlarmScheduler.canExact(ctx)) out += AlarmIssue("Exact alarms", "Not allowed: alarms may be late", "exact") {
        open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg), details) }
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    if (!nm.areNotificationsEnabled()) out += AlarmIssue("Notifications", "Off: no ringing screen, no snooze buttons", "notif") {
        open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName), details) }
    if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out += AlarmIssue("Full-screen alarm", "Not allowed: only a notification", "fullscreen") {
        open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg), details) }
    if (ctx.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted) out += AlarmIssue("Battery: Restricted", "Android may block the app at alarm time", "battery") {
        // The app's battery page (Unrestricted / Optimised / Restricted); not every phone has a direct link to it.
        open(it, Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details) }
    return out
}

/**
 * Pop-up when the alarms lack a permission (e.g. after a new install): each missing one with a button that opens its
 * Android setting; "Later" goes on with what was touched, ✕ / outside closes it.
 */
/**
 * Opens what grants one alarm permission. Notifications on Android 13+: Android's own "Allow notifications?" request
 * first; when Android won't ask any more (refused before), the app's notification switch page.
 */
@Composable
fun rememberIssueOpener(): (AlarmIssue) -> Unit {
    val ctx = LocalContext.current
    var notifIssue by remember { mutableStateOf<AlarmIssue?>(null) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        val i = notifIssue; notifIssue = null
        if (!ok) i?.fix?.invoke(ctx)
    }
    return { i ->
        if (i.kind == "notif" && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifIssue = i
            try { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { notifIssue = null; i.fix(ctx) }
        } else i.fix(ctx)
    }
}

@Composable
fun AlarmPermissionDialog(issues: List<AlarmIssue>, onLater: () -> Unit, onClose: () -> Unit) {
    val openIssue = rememberIssueOpener()
    AlertDialog(onDismissRequest = onClose, containerColor = C.panel,
        title = { Text("⚠  Alarms need permissions", color = C.bad) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
                Text("Android hasn't given the app everything an alarm needs to ring on time (usual after a new install). Tap Grant: Android's page for that permission opens — allow it, then come back.",
                    color = C.text, fontSize = 14.sp)
                issues.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.title, color = C.bad, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(i.text, color = C.muted, fontSize = 12.sp)
                        }
                        Pill("Grant") { openIssue(i) }
                    }
                }
            }
        },
        confirmButton = { STextButton(onLater) { Text("Later", color = accent()) } },
        dismissButton = { STextButton(onClose) { Text("Close") } })
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
    val openIssue = rememberIssueOpener()
    var testNote by remember { mutableStateOf("") }
    CardBox {
        if (issues.isEmpty()) Text("✓  Everything Android needs for the alarms is allowed", color = C.ok, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().helpOn("Alarm health").padding(16.dp))
        issues.forEach { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = (8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⚠  " + i.title, color = C.bad, fontSize = 15.sp)
                    Text(i.text, color = C.muted, fontSize = 13.sp)
                }
                Pill("Grant") { openIssue(i) }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = (8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sleepList, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Pill("Open") { try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {} }
            }
            Divider()
        }
        Row(Modifier.fillMaxWidth().helpOn("Test alarm in 1 minute").padding(horizontal = 16.dp, vertical = (8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Test alarm in 1 minute", color = C.text, modifier = Modifier.anchor("Test alarm in 1 minute"), fontSize = 15.sp)
                Text(testNote.ifEmpty { "Then press Exit and lock the phone" }, color = if (testNote.isEmpty()) C.muted else accent(), fontSize = 12.sp)
            }
            Pill("Test") {
                app.vlcinterface.alarm.testAlarm(ctx)
                testNote = "Rings at " + AlarmScheduler.whenText(System.currentTimeMillis() + 60_000).substringAfter(' ') + " — you can press Exit and lock the phone"
            }
        }
    }
}

/** The music folder the alarms use: chosen one, else the one the audio player uses, else all phone music. */
fun musicRootText(g: AlarmGlobal): String = g.localRoot.ifEmpty { knownMusicRoot()?.first?.let { "$it (player)" } ?: "All phone music" }

/** Clock as Android's screen saver while charging: checked automatically, offered only where the phone has it. */
@Composable
private fun ScreenSaverRow() {
    val ctx = LocalContext.current
    val st = remember(resumeTick()) { app.vlcinterface.alarm.ClockDream.status(ctx) }
    val (hint, value) = when {
        !st.available -> "This phone has no screen saver setting (removed by the phone maker)" to "Not available"
        st.chosen == true && st.enabled != false && st.whileCharging != false -> "On: the dimmed clock shows while the phone charges" to "✓"
        st.chosen == true -> "Chosen, but Android's screen saver is off or not set to \"While charging\"" to "Turn on"
        st.chosen == false -> "In Android: Screen saver → \"vlc interface clock\", start \"While charging\"" to "Set up"
        else -> "Android doesn't tell whether it's on: check in its Screen saver page" to "Open"
    }
    HValueRow("Clock screen saver while charging", hint, value) { if (st.available) app.vlcinterface.alarm.ClockDream.open(ctx) }
}


/** Monthly calendar where several days can be tapped; ‹ › move between months. [onDone] null = cancelled. */
@Composable
private fun MonthDatesDialog(initial: List<String>, onDone: (List<String>?) -> Unit) {
    val zone = Alarms.zone()
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = zone } }
    val today = remember { fmt.format(java.util.Date()) }
    var picked by remember { mutableStateOf(initial.filter { it >= today }.toSet()) }
    var month by remember { mutableStateOf(java.util.Calendar.getInstance(zone).apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }
    val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = month }
    val title = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault()).apply { timeZone = zone }.format(java.util.Date(month))
    val first = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7          // Monday = 0
    val daysIn = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    fun shift(n: Int) { month = java.util.Calendar.getInstance(zone).apply { timeInMillis = month; add(java.util.Calendar.MONTH, n) }.timeInMillis }
    AlertDialog(onDismissRequest = { onDone(null) }, containerColor = C.panel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = C.text, fontSize = 26.sp, modifier = Modifier.clickable { shift(-1) }.padding(horizontal = 12.dp))
                Text(title.replaceFirstChar { it.uppercase() }, color = C.text, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("›", color = C.text, fontSize = 26.sp, modifier = Modifier.clickable { shift(1) }.padding(horizontal = 12.dp))
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
                val cells = first + daysIn
                for (r in 0 until (cells + 6) / 7) Row(Modifier.fillMaxWidth().padding(vertical = (2 * sg()).dp)) {
                    for (c in 0 until 7) {
                        val day = r * 7 + c - first + 1
                        Box(Modifier.weight(1f).height(38.dp), contentAlignment = Alignment.Center) {
                            if (day in 1..daysIn) {
                                val key = "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, day)
                                val past = key < today
                                val on = key in picked
                                Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) accent() else Color.Transparent)
                                    .border(1.dp, if (key == today) C.muted else Color.Transparent, CircleShape)
                                    .clickable(enabled = !past) { picked = if (on) picked - key else picked + key }, contentAlignment = Alignment.Center) {
                                    Text("$day", color = if (on) Color(0xFF1A0E05) else if (past) C.muted.copy(alpha = 0.4f) else C.text, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                Text("${picked.size} dates chosen", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { STextButton({ onDone(picked.sorted()) }) { Text("OK", color = accent()) } },
        dismissButton = { STextButton({ onDone(null) }) { Text("Cancel") } })
}


/** Which of the phone's calendars gets the alarms (the main account's first, already selected). */
@Composable
private fun CalendarPickDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cals = remember { app.vlcinterface.alarm.AlarmCalendar.calendars(ctx) }
    val g = Alarms.current.global
    var sel by remember { mutableStateOf(cals.firstOrNull { it.id == g.calId } ?: cals.firstOrNull()) }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Show alarms in", color = C.text, modifier = Modifier.anchor("Show alarms in")) },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                if (cals.isEmpty()) Text("No calendar on this phone can take events. Add your account in Android's settings first.", color = C.muted, fontSize = 13.sp)
                cals.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { sel = c }.padding(vertical = (8 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                        SRadioButton(sel?.id == c.id, null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(c.name + if (c.primary) "  (main)" else "", color = C.text, fontSize = 14.sp)
                            if (c.account.isNotEmpty() && c.account != c.name) Text(c.account, color = C.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { STextButton({
            sel?.let { c -> Alarms.setGlobal(Alarms.current.global.copy(calSync = true, calId = c.id, calName = c.name)) }
            onDone()
        }, enabled = sel != null) { Text("OK", color = accent()) } },
        dismissButton = { STextButton(onDone) { Text("Cancel") } })
}

/**
 * Alarm settings → History (2026-11-21 12:30): one-time alarms that already rang (or whose dates are over), newest
 * first — time, name, when it was set for and when it rang; a tap opens it. Each has a switch (on: set again for its
 * next time, back in front of the player) and a delete button.
 */
@Composable
private fun AlarmHistory(nav: Navigator, exclude: String = "") {
    val d by Alarms.state.collectAsState()
    // The alarm open on this page isn't listed under itself.
    val used = d.alarms.filter { Alarms.isPast(it) && it.id != app.vlcinterface.alarm.AlarmEngine.TEST_ID && it.id != exclude }.sortedByDescending { Alarms.pastAt(it) }
    val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
    SectionTitle("History")
    // Always shown (2026-11-21 13:41): empty, it says so instead of the section being missing.
    if (used.isEmpty()) {
        CardBox {
            Text("No past alarms yet. One-time alarms that have rung, and alarms whose dates are over, are listed here.",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        return
    }
    CardBox {
        used.forEachIndexed { i, al ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().clickable { nav.replace(Screen.AlarmEdit(al.id)) }.padding(start = 16.dp, end = 4.dp, top = (6 * sg()).dp, bottom = (6 * sg()).dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(al.timeText, color = C.muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(listOf(al.name, Alarm.whenText(al)).filter { it.isNotEmpty() }.joinToString(" · "), color = C.text, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Alarms.pastAt(al).takeIf { it > 0 }?.let { t -> Text("Rang " + fmt.format(java.util.Date(t)), color = C.muted, fontSize = 12.sp) }
                }
                // On: set again for its next time (a passed date is dropped) — it leaves the history.
                AppSwitch(false, { on ->
                    if (on) {
                        // The next time the clock shows its time: today if still ahead, else tomorrow.
                        val re = al.copy(on = true, used = 0L, date = "", dates = emptyList(), mode = "once")
                        Alarms.put(re)
                        AlarmSetNote.show(re)
                    }
                })
                Text("✕", color = C.bad, fontSize = SYM, modifier = Modifier.clickable {
                    Alarms.delete(al.id); ShortNote.show("${al.timeText} deleted from the history")
                }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}



/** Short pop-up at the top of the page whenever an alarm is set: its time, the day it rings next and its sound. */
object AlarmSetNote {
    var alarm by mutableStateOf<Alarm?>(null)
    var stamp by androidx.compose.runtime.mutableLongStateOf(0L)
    fun show(a: Alarm) {
        alarm = a
        stamp = System.currentTimeMillis()
    }
}

@Composable
fun AlarmSetNoteHost(modifier: Modifier) {
    val a = AlarmSetNote.alarm ?: return
    val st = AlarmSetNote.stamp
    val sec = app.vlcinterface.App.instance.store.state.collectAsState().value.settings.shortPopupSec.coerceAtLeast(4)
    androidx.compose.runtime.LaunchedEffect(st) { kotlinx.coroutines.delay(sec * 1000L); if (AlarmSetNote.stamp == st) AlarmSetNote.alarm = null }
    val z = Alarms.zone()
    val at = app.vlcinterface.alarm.AlarmScheduler.nextTime(a)
    val cal = java.util.Calendar.getInstance(z).apply { timeInMillis = at }
    val now = java.util.Calendar.getInstance(z)
    val dayFmt = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).apply { timeZone = z }
    val rel = when {
        at <= 0L -> ""
        cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) -> "Today, "
        else -> now.apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }.let {
            if (cal.get(java.util.Calendar.YEAR) == it.get(java.util.Calendar.YEAR) && cal.get(java.util.Calendar.DAY_OF_YEAR) == it.get(java.util.Calendar.DAY_OF_YEAR)) "Tomorrow, " else ""
        }
    }
    val dateLine = if (at <= 0L) "No date left to ring" else rel + dayFmt.format(java.util.Date(at)) +
        (if (a.mode == "weekly" && a.days != 0) " · " + Alarm.daysText(a.days) else if (a.mode == "dates" && a.dates.size > 1) " · ${a.dates.size} dates" else "")
    val sound = when (a.source) {
        "radio" -> "Radio" + (a.stationName.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        "tone" -> "Alarm sound"
        else -> "Phone music"
    }
    val acc = accent()
    Box(modifier.padding(top = 56.dp, start = 20.dp, end = 20.dp).clip(RoundedCornerShape(18.dp))
        .background(Color(0xF02A2E35)).border(1.dp, acc, RoundedCornerShape(18.dp))
        .clickable { AlarmSetNote.alarm = null }.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⏰  Alarm set · ${a.timeText}" + (a.name.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), color = acc, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dateLine, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Sound: $sound", color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Colours for the screen flash (white first, the default): a row of round swatches, the chosen one ringed. */
private val FLASH_COLOURS = listOf(0xFFFFFFFF, 0xFFFF3B30, 0xFFFF8A3D, 0xFFFFD60A, 0xFF34C759, 0xFF32D3E6, 0xFF0A84FF, 0xFFBF5AF2).map { it.toInt() }

@Composable
private fun FlashColours(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Colour", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
        FLASH_COLOURS.forEach { c ->
            val on = c == selected
            Box(Modifier.size(26.dp).clip(CircleShape).border(if (on) 3.dp else 1.dp, if (on) accent() else Color(0xFF444444), CircleShape)
                .padding(if (on) 5.dp else 2.dp).clip(CircleShape).background(Color(c)).clickable { onPick(c) })
        }
    }
}


/**
 * The whole alarm system on / off (2026-11-21 14:18): a big button on the main Settings page (and, while it's off, the
 * only thing on the Alarm clock page and the wizard's alarm page).
 * Off (after a confirmation): a ringing / snoozed alarm ends, nothing is set with Android any more, no next-alarm
 * notification or calendar events, and every alarm display and option is hidden (alarms under the clock, alarm
 * settings, alarm permissions in the wizard). The clock stays (14:32). The alarms are kept.
 * On: everything comes back, every alarm is set again, and the permissions the alarms need are asked for.
 */
@Composable
fun AlarmSystemButton() {
    val d by Alarms.state.collectAsState()
    val on = d.global.systemOn
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<List<AlarmIssue>>(emptyList()) }
    val a = accent()
    CardBox {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
                .background(if (on) Color(0x26E53935) else a)
                .border(2.dp, if (on) C.bad else a, RoundedCornerShape(16.dp))
                .clickable {
                    if (on) confirm = true
                    else {
                        Alarms.setSystem(ctx, true)
                        ShortNote.show("Alarm system on — alarms set again")
                        ask = alarmIssues(ctx)
                    }
                }.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(if (on) "⏻  Turn off the alarm system" else "⏻  Turn on the alarm system", color = if (on) C.bad else Color(0xFF1A0E05),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text(if (on) "Alarms and every alarm option are on. The clock isn't affected."
                else "The alarm system is off: nothing rings, the alarms and their options are hidden. The clock stays. Your alarms are kept — turn it on to bring everything back.",
                color = C.muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = C.panel,
        title = { Text("Turn off the alarm system?", color = C.text) },
        text = { Text("No alarm will ring. A ringing or snoozed alarm ends now. The alarms under the clock, the alarm settings and the alarm permissions are hidden everywhere; the clock, its look, city and weather stay. Your alarms and settings are kept for when you turn it on again.", color = C.text, fontSize = 14.sp) },
        confirmButton = { STextButton({ confirm = false; Alarms.setSystem(ctx, false); ShortNote.show("Alarm system off") }) { Text("Turn off", color = C.bad) } },
        dismissButton = { STextButton({ confirm = false }) { Text("Cancel") } })
    // Turned on: what the alarms still need from Android, with Grant buttons.
    if (ask.isNotEmpty()) AlarmPermissionDialog(ask, onLater = { ask = emptyList() }, onClose = { ask = emptyList() })
}


/** At the top of the alarm settings pages (2026-11-28 01:17): the short notification alarms, linked to their section. */
@Composable
fun ShortAlarmsLink(nav: Navigator) {
    val n = ShortAlarms.list.collectAsState().value.count { it.on }
    Text("⏱ Short notification alarms" + (if (n > 0) " ($n on)" else "") + " — for daily moments: pills, exercises … ›", color = accent(), fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
            SettingsAnchor.go("alarm", null, "Short notification alarms")
            if (nav.top !is Screen.AlarmSettings) nav.go(Screen.AlarmSettings)
        }.padding(vertical = 8.dp))
}

/** The list of short notification alarms, at the bottom of the alarm settings page. */
@Composable
fun ShortAlarmsSection() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (ShortAlarms.list.value.isEmpty()) ShortAlarms.load(ctx) }
    val list = ShortAlarms.list.collectAsState().value
    var edit by remember { mutableStateOf<ShortAlarm?>(null) }
    SectionTitle("Short notification alarms", Modifier.anchor("Short notification alarms"))
    CardBox {
        Text("For daily moments (taking pills, exercises …): a short vibration and a short notification sound, once, and a notification you can swipe away. Not in the calendar, not in front of the player.",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        list.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { edit = a }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.timeText, color = if (a.on) C.text else C.muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("  " + a.label.ifEmpty { "Reminder" } + " · " + (if (a.days == 0) "one time" else Alarm.daysText(a.days)), color = C.muted, fontSize = 14.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                AppSwitch(a.on, { ShortAlarms.put(ctx, a.copy(on = it)) })
            }
            Divider()
        }
        Text("+ Add a short alarm", color = accent(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clickable { edit = ShortAlarm(java.util.UUID.randomUUID().toString()) }.padding(16.dp))
    }
    edit?.let { e -> ShortAlarmDialog(e, onClose = { edit = null }) }
}

@Composable
private fun ShortAlarmDialog(a0: ShortAlarm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf(a0) }
    var hh by remember { mutableStateOf("%02d".format(a0.hour)) }
    var mm by remember { mutableStateOf("%02d".format(a0.minute)) }
    val exists = ShortAlarms.list.value.any { it.id == a0.id }
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose, containerColor = C.panel,
        title = { Text(if (exists) "Short alarm" else "New short alarm", color = C.text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Name (e.g. Pills)", a.label) { a = a.copy(label = it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DigitField(hh, "hh", 2, Modifier.fillMaxWidth()) { hh = it } }
                Text(":", color = C.text, fontSize = 22.sp)
                Box(Modifier.weight(1f)) { DigitField(mm, "mm", 2, Modifier.fillMaxWidth()) { mm = it } }
            }
            Text("Days (none = one time only)", color = C.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                    val on = a.days and (1 shl i) != 0
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) accent() else C.card).clickable { a = a.copy(days = a.days xor (1 shl i)) },
                        contentAlignment = Alignment.Center) { Text(d, color = if (on) Color(0xFF1A0E05) else C.text, fontSize = 14.sp) }
                }
            }
        } },
        confirmButton = { STextButton({
            val h = hh.toIntOrNull()?.coerceIn(0, 23) ?: a.hour; val m = mm.toIntOrNull()?.coerceIn(0, 59) ?: a.minute
            ShortAlarms.put(ctx, a.copy(hour = h, minute = m, on = true)); onClose()
        }) { Text("Save") } },
        dismissButton = { Row {
            if (exists) STextButton({ ShortAlarms.remove(ctx, a0.id); onClose() }) { Text("Delete", color = C.bad) }
            STextButton(onClose) { Text("Cancel") }
        } })
}
