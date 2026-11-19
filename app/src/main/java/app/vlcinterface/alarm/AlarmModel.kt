package app.vlcinterface.alarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One alarm (its own subpage, opened from the clock shown in the player's picture area). */
data class Alarm(
    val id: String = UUID.randomUUID().toString(),
    val on: Boolean = true,
    val name: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    /** Days: bit 0 = Monday … bit 6 = Sunday. 0 = once. */
    val days: Int = 0,
    /** Date "yyyy-MM-dd" ("" = none): a single alarm rings on that date; a repeating one starts repeating from it. */
    val date: String = "",
    /** Repeat: "once", "weekly" (days, from [date]) or "dates" (only on [dates]). */
    val mode: String = "once",
    /** Specific dates "yyyy-MM-dd" (mode "dates"). */
    val dates: List<String> = emptyList(),
    /** "local" (music stored on the phone, under the root folder), "radio" (preferred → fallback station), "tone". */
    val source: String = "local",
    val playlistId: String = "",
    val stationUrl: String = "",
    val stationName: String = "",
    val fallbackUrl: String = "",
    val fallbackName: String = "",
    val volStart: Int = 10,
    val volTarget: Int = 60,
    /** Fade-in (crescendo) time in seconds. */
    val fadeSec: Int = 60,
    /** Snoozes allowed: -1 = unlimited. */
    val maxSnoozes: Int = 3,
    /** Rings at most this long (minutes), 0 = until stopped. */
    val stopAfterMin: Int = 30,
    /** "" = as it is, "speaker" = phone speaker, otherwise an output key. */
    val output: String = "",
    /** Network songs not reachable: phone songs instead (then the alarm tone). */
    val localFallback: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("on", on).put("name", name).put("hour", hour).put("minute", minute)
        .put("days", days).put("date", date).put("mode", mode).put("dates", JSONArray(dates)).put("source", source).put("playlistId", playlistId).put("stationUrl", stationUrl).put("stationName", stationName).put("fallbackUrl", fallbackUrl).put("fallbackName", fallbackName)
        .put("volStart", volStart).put("volTarget", volTarget).put("fadeSec", fadeSec).put("maxSnoozes", maxSnoozes)
        .put("stopAfterMin", stopAfterMin).put("output", output).put("localFallback", localFallback)

    val timeText: String get() = "%02d:%02d".format(hour, minute)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(o.optString("id", UUID.randomUUID().toString()), o.optBoolean("on", true), o.optString("name"),
            o.optInt("hour", 7), o.optInt("minute"), o.optInt("days"), o.optString("date"),
            o.optString("mode", if (o.optInt("days") != 0) "weekly" else "once").let { if (it in listOf("once", "weekly", "dates")) it else "once" },
            o.optJSONArray("dates")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optString("source", "local").let { if (it == "radio" || it == "tone") it else "local" }, o.optString("playlistId"),
            o.optString("stationUrl"), o.optString("stationName"), o.optString("fallbackUrl"), o.optString("fallbackName"), o.optInt("volStart", 10), o.optInt("volTarget", 60),
            o.optInt("fadeSec", 60), o.optInt("maxSnoozes", 3), o.optInt("stopAfterMin", 30), o.optString("output"),
            o.optBoolean("localFallback", true))

        private val DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        /** Days, with the date when there is one ("Thu 4 Dec" / "Mon–Fri from 4 Dec"). */
        private fun shortDate(s: String): String = try {
            java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s)!!)
        } catch (_: Exception) { s }

        fun whenText(a: Alarm): String {
            if (a.mode == "dates") {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = Alarms.zone() }.format(java.util.Date())
                val left = a.dates.filter { it >= today }.sorted()
                return when (left.size) { 0 -> "No dates left"; 1 -> shortDate(left[0]); else -> shortDate(left[0]) + " + ${left.size - 1} more dates" }
            }
            if (a.mode == "once") return if (a.date.isEmpty()) "Once" else shortDate(a.date)
            if (a.date.isEmpty()) return daysText(a.days)
            val d = try { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(a.date) } catch (_: Exception) { null } ?: return daysText(a.days)
            val shown = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(d)
            return if (a.days == 0) shown else daysText(a.days) + " from " + shown
        }

        fun daysText(d: Int): String = when (d) {
            0 -> "Once"
            0x7F -> "Every day"
            0x1F -> "Mon–Fri"
            0x60 -> "Weekend"
            else -> DAY.filterIndexed { i, _ -> d and (1 shl i) != 0 }.joinToString(" ")
        }
    }
}

/** Alarm clock settings that apply to every alarm (Settings → Alarm clock): technical items only. */
data class AlarmGlobal(
    val vibrate: Boolean = true,
    /** "off", "notif" (notification light), "flash" (camera flash light). */
    val led: String = "off",
    /** Crescendo curve: "linear", "gentle" (slow start), "fast" (quick start). */
    val crescendo: String = "linear",
    val snooze1: Int = 5,
    val snooze2: Int = 10,
    val snooze3: Int = 15,
    /** Stop needs a random pattern. */
    val pattern: Boolean = true,
    /** Wake check after a manual stop: minutes with no sign of being awake before ringing again (0 = off). */
    val wakeCheckMin: Int = 10,
    val useLocation: Boolean = false,
    /** Snooze / Stop in the lock-screen notification. */
    val lockActions: Boolean = true,
    /** Volume buttons while ringing: "snooze", "nothing", "volume". */
    val volumeKeys: String = "snooze",
    /** No alarm goes above this volume (%). */
    val maxLevel: Int = 100,
    /** Alarm tone: "" = the phone's alarm sound, else a ringtone address picked by hand. */
    val toneUri: String = "",
    val toneName: String = "",
    /** During a call: waits for the end of the call. */
    val callWait: Boolean = true,
    /** Missed alarm (phone off / restarted): "ring30" = rings if less than 30 min late, "notify" = notification only. */
    val missed: String = "ring30",
    /** Low battery (15 % or less): "normal" or "tone" (alarm tone only, no network). */
    val lowBattery: String = "normal",
    /** Next alarm shown in a notification. */
    val nextInNotif: Boolean = true,
    /** Helper pop-ups on the alarm page (what the alarm will do). */
    val helpers: Boolean = true,
    /** Alarms shown in the phone's calendar (Android's calendar, the account already on the phone). */
    val calSync: Boolean = false,
    val calId: Long = -1,
    val calName: String = "",
    /** City chosen by hand: the clock's time zone, the alarms' times and the weather follow it ("" = the phone's). */
    val cityName: String = "",
    val cityShort: String = "",
    val cityLat: Double = 0.0,
    val cityLon: Double = 0.0,
    val cityTz: String = "",
    val showWeather: Boolean = true,
    /** Phone music root folder for the alarms (relative path, e.g. "Music/"; "" = not chosen yet) and its volume. */
    val localRoot: String = "",
    val localVolume: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("vibrate", vibrate).put("led", led).put("crescendo", crescendo)
        .put("snooze1", snooze1).put("snooze2", snooze2).put("snooze3", snooze3).put("pattern", pattern)
        .put("wakeCheckMin", wakeCheckMin).put("useLocation", useLocation).put("lockActions", lockActions)
        .put("volumeKeys", volumeKeys).put("maxLevel", maxLevel).put("toneUri", toneUri).put("toneName", toneName).put("callWait", callWait)
        .put("missed", missed).put("lowBattery", lowBattery).put("nextInNotif", nextInNotif)
        .put("helpers", helpers).put("calSync", calSync).put("calId", calId).put("calName", calName)
        .put("cityName", cityName).put("cityShort", cityShort).put("cityLat", cityLat).put("cityLon", cityLon).put("cityTz", cityTz)
        .put("showWeather", showWeather).put("localRoot", localRoot).put("localVolume", localVolume)

    val snoozes: List<Int> get() = listOf(snooze1, snooze2, snooze3)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) AlarmGlobal() else AlarmGlobal(o.optBoolean("vibrate", true), o.optString("led", "off"),
            o.optString("crescendo", "linear"), o.optInt("snooze1", 5), o.optInt("snooze2", 10), o.optInt("snooze3", 15),
            o.optBoolean("pattern", true), o.optInt("wakeCheckMin", 10), o.optBoolean("useLocation"), o.optBoolean("lockActions", true),
            o.optString("volumeKeys", "snooze"), o.optInt("maxLevel", 100), o.optString("toneUri"), o.optString("toneName"), o.optBoolean("callWait", true),
            o.optString("missed", "ring30"), o.optString("lowBattery", "normal"), o.optBoolean("nextInNotif", true),
            o.optBoolean("helpers", true), o.optBoolean("calSync", false), o.optLong("calId", -1), o.optString("calName"),
            o.optString("cityName"), o.optString("cityShort"), o.optDouble("cityLat", 0.0), o.optDouble("cityLon", 0.0), o.optString("cityTz"),
            o.optBoolean("showWeather", true), o.optString("localRoot"), o.optString("localVolume"))
    }
}

/** How the clock looks (Settings → Alarm clock → Clock aspect). Hues 0…360, saturations 0…1. */
data class ClockAspect(
    val analog: Boolean = true,
    /** Analog designs: 1, 2, 4, 5, 8. */
    val design: Int = 1,
    val h24: Boolean = true,
    val seconds: Boolean = true,
    val diskHue: Float = 220f,
    val diskSat: Float = 0.35f,
    val mainHue: Float = 52f,
    val mainSat: Float = 0.95f,
    val secHue: Float = 4f,
    val dateHue: Float = 52f,
    val dateSat: Float = 0.95f,
    val dateSize: Float = 1f,
    val ringW: Float = 1f,
    val tickW: Float = 1f,
    val handW: Float = 1f,
    /** Circle size 0.6…1 of the picture area. */
    val size: Float = 1f,
    val digitalSize: Float = 1f,
    val digitalBold: Boolean = false,
    val digitalDate: Boolean = true,
    /** Night (22:00–07:00): clock dimmed by this %. */
    val nightDim: Int = 40,
    /** Dimming of the charging screen saver clock only, %. */
    val dim: Int = 25,
    /** Line / font thickness per design: "1", "2", "4", "5", "8", "d" (digital). */
    val thick: Map<String, Float> = emptyMap(),
) {
    fun thickFor(analog: Boolean = this.analog, design: Int = this.design): Float = thick[if (analog) "$design" else "d"] ?: 1f
    fun toJson(): JSONObject = JSONObject().put("analog", analog).put("design", design).put("h24", h24).put("seconds", seconds)
        .put("diskHue", diskHue.toDouble()).put("diskSat", diskSat.toDouble()).put("mainHue", mainHue.toDouble()).put("mainSat", mainSat.toDouble())
        .put("secHue", secHue.toDouble()).put("dateHue", dateHue.toDouble()).put("dateSat", dateSat.toDouble()).put("dateSize", dateSize.toDouble())
        .put("ringW", ringW.toDouble()).put("tickW", tickW.toDouble()).put("handW", handW.toDouble()).put("size", size.toDouble())
        .put("digitalSize", digitalSize.toDouble()).put("digitalBold", digitalBold).put("digitalDate", digitalDate).put("nightDim", nightDim).put("dim", dim)
        .put("thick", JSONObject().apply { thick.forEach { (k, v) -> put(k, v.toDouble()) } })

    companion object {
        fun fromJson(o: JSONObject?): ClockAspect {
            if (o == null) return ClockAspect()
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            return ClockAspect(o.optBoolean("analog", true), o.optInt("design", 1).let { if (it in listOf(1, 2, 4, 5, 8)) it else 1 },
                o.optBoolean("h24", true), o.optBoolean("seconds", true), f("diskHue", 220f), f("diskSat", 0.35f), f("mainHue", 52f),
                f("mainSat", 0.95f), f("secHue", 4f), f("dateHue", 52f), f("dateSat", 0.95f), f("dateSize", 1f), f("ringW", 1f),
                f("tickW", 1f), f("handW", 1f), f("size", 1f), f("digitalSize", 1f), o.optBoolean("digitalBold"),
                o.optBoolean("digitalDate", true), o.optInt("nightDim", 40), o.optInt("dim", 25),
                o.optJSONObject("thick")?.let { t -> t.keys().asSequence().associateWith { k -> t.optDouble(k, 1.0).toFloat() } } ?: emptyMap())
        }
    }
}

/** Full-screen visualisation (Settings → Visualisation). */
data class VisSettings(
    /** "pattern" (the pattern's own colours), "sunset", "ocean", "forest", "neon", "fire", "aurora", "custom". */
    val palette: String = "pattern",
    val hue1: Float = 20f,
    val hue2: Float = 200f,
    val hue3: Float = 300f,
    /** Colour brightness 0.2…1. */
    val brightness: Float = 0.85f,
    /** Pattern: "clouds", "lava", "nebula", "tides", "plasma", "silk", "sunset", "ocean", "embers". */
    val effect: String = "clouds",
    /** Movement strength (bass sensitivity) 0.2…2. */
    val move: Float = 1f,
    /** Colour mixing strength 0…1. */
    val mix: Float = 0.6f,
    /** Frequency range followed (Hz). */
    val fLo: Float = 30f,
    val fHi: Float = 150f,
    /** Spot definition: 0 = very diffused … 1 = very sharp. */
    val sharp: Float = 0.25f,
    /** Colour change speed 0.2…3. */
    val colourSpeed: Float = 1f,
) {
    fun toJson(): JSONObject = JSONObject().put("palette", palette).put("hue1", hue1.toDouble()).put("hue2", hue2.toDouble())
        .put("hue3", hue3.toDouble()).put("brightness", brightness.toDouble()).put("effect", effect).put("move", move.toDouble())
        .put("mix", mix.toDouble()).put("fLo", fLo.toDouble()).put("fHi", fHi.toDouble()).put("sharp", sharp.toDouble()).put("colourSpeed", colourSpeed.toDouble())
    companion object {
        val EFFECTS = listOf("clouds", "lava", "nebula", "tides", "plasma", "silk", "sunset", "ocean", "embers",
            "ink", "reaction", "phase", "chladni", "mandel", "epicycles")
        fun fromJson(o: JSONObject?) = if (o == null) VisSettings() else VisSettings(o.optString("palette", "pattern"),
            o.optDouble("hue1", 20.0).toFloat(), o.optDouble("hue2", 200.0).toFloat(), o.optDouble("hue3", 300.0).toFloat(),
            o.optDouble("brightness", 0.85).toFloat(), o.optString("effect", "clouds").let { if (it in EFFECTS) it else "clouds" },
            o.optDouble("move", 1.0).toFloat(), o.optDouble("mix", 0.6).toFloat(), o.optDouble("fLo", 30.0).toFloat(), o.optDouble("fHi", 150.0).toFloat(),
            o.optDouble("sharp", 0.25).toFloat(), o.optDouble("colourSpeed", 1.0).toFloat())
    }
}

data class AlarmData(
    val alarms: List<Alarm> = emptyList(),
    val global: AlarmGlobal = AlarmGlobal(),
    val aspect: ClockAspect = ClockAspect(),
    /** The clock replaces the picture in the audio player (kept until the clock button is pressed again). */
    val showClock: Boolean = false,
    val vis: VisSettings = VisSettings(),
)

/** Alarms and their settings, kept in their own file (not in the app's main settings). */
object Alarms {
    private lateinit var ctx: Context
    private val _state = MutableStateFlow(AlarmData())
    val state: StateFlow<AlarmData> = _state
    val current: AlarmData get() = _state.value
    private val prefs get() = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun init(c: Context) {
        ctx = c.applicationContext
        try {
            val p = prefs
            val a = JSONArray(p.getString("alarms", "[]"))
            _state.value = AlarmData((0 until a.length()).map { Alarm.fromJson(a.getJSONObject(it)) },
                AlarmGlobal.fromJson(p.getString("global", null)?.let { JSONObject(it) }),
                ClockAspect.fromJson(p.getString("aspect", null)?.let { JSONObject(it) }),
                p.getBoolean("showClock", false),
                VisSettings.fromJson(p.getString("vis", null)?.let { JSONObject(it) }))
        } catch (_: Exception) {}
    }

    private fun save(d: AlarmData) {
        _state.value = d
        prefs.edit().putString("alarms", JSONArray().apply { d.alarms.forEach { put(it.toJson()) } }.toString())
            .putString("global", d.global.toJson().toString()).putString("aspect", d.aspect.toJson().toString())
            .putBoolean("showClock", d.showClock).putString("vis", d.vis.toJson().toString()).apply()
    }

    fun get(id: String): Alarm? = current.alarms.firstOrNull { it.id == id }

    fun put(a: Alarm) {
        val list = current.alarms
        save(current.copy(alarms = if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a))
        AlarmScheduler.reschedule(ctx)
    }

    fun delete(id: String) { save(current.copy(alarms = current.alarms.filterNot { it.id == id })); AlarmScheduler.reschedule(ctx) }
    fun setGlobal(g: AlarmGlobal) { save(current.copy(global = g)); AlarmScheduler.reschedule(ctx) }
    fun setAspect(a: ClockAspect) = save(current.copy(aspect = a))
    fun toggleClock() = save(current.copy(showClock = !current.showClock))
    fun setVis(v: VisSettings) = save(current.copy(vis = v))

    /** Alarms, alarm settings, clock aspect and visualisation for the setup file. */
    fun exportJson(): JSONObject {
        val d = current
        return JSONObject().put("alarms", JSONArray().apply { d.alarms.forEach { put(it.toJson()) } }).put("global", d.global.toJson())
            .put("aspect", d.aspect.toJson()).put("showClock", d.showClock).put("vis", d.vis.toJson())
    }

    /** From a setup file: alarms replaced or added (merge); settings applied. This phone's calendar choice stays. */
    fun importJson(o: JSONObject, replace: Boolean) {
        val d = current
        val a = o.optJSONArray("alarms") ?: JSONArray()
        val inc = (0 until a.length()).map { Alarm.fromJson(a.getJSONObject(it)) }
        val list = if (replace) inc else d.alarms + inc.filter { n -> d.alarms.none { it.id == n.id } }
        val g = o.optJSONObject("global")?.let { AlarmGlobal.fromJson(it).copy(calSync = d.global.calSync, calId = d.global.calId, calName = d.global.calName) } ?: d.global
        save(d.copy(alarms = list, global = g, aspect = o.optJSONObject("aspect")?.let { ClockAspect.fromJson(it) } ?: d.aspect,
            showClock = o.optBoolean("showClock", d.showClock), vis = o.optJSONObject("vis")?.let { VisSettings.fromJson(it) } ?: d.vis))
        AlarmScheduler.reschedule(ctx)
    }

    /** Time zone of the city chosen by hand, else the phone's. */
    fun zone(): java.util.TimeZone = current.global.cityTz.takeIf { it.isNotEmpty() }?.let { java.util.TimeZone.getTimeZone(it) }
        ?: java.util.TimeZone.getDefault()

    // Bookkeeping per alarm: when it is planned, when it last rang (missed-alarm check).
    fun planned(id: String): Long = prefs.getLong("planned_$id", 0)
    fun setPlanned(id: String, t: Long) = prefs.edit().putLong("planned_$id", t).apply()
    fun lastRung(id: String): Long = prefs.getLong("rung_$id", 0)
    fun setRung(id: String, t: Long) = prefs.edit().putLong("rung_$id", t).apply()
    /** The alarm ringing / watched now ("" = none): a restarted app picks it up again. */
    var active: String
        get() = prefs.getString("active", "") ?: ""
        set(v) { prefs.edit().putString("active", v).commit() }
    var activePhase: String
        get() = prefs.getString("activePhase", "") ?: ""
        set(v) { prefs.edit().putString("activePhase", v).commit() }
}
