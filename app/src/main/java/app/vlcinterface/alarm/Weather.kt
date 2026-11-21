package app.vlcinterface.alarm

import app.vlcinterface.data.CrashLog

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Weather for the city chosen by hand (Open-Meteo: free, no account): sunny, cloudy, light rain / rain, light snow / snow,
 * storm — right now, or today's noon forecast (Alarm clock → "Weather shows").
 * City search (name → place, coordinates, time zone) uses Open-Meteo's geocoding.
 */
object Weather {
    data class City(val name: String, val detail: String, val lat: Double, val lon: Double, val tz: String) {
        /** Short identifier shown on the clock, e.g. "MAD". */
        val short: String get() = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("[^A-Za-z]"), "").take(3).uppercase()
    }

    /** -1 = unknown, 0 sunny, 1 cloudy, 2 rain (cloud), 3 storm, 4 light rain (drops alone), 5 light snow (flakes alone), 6 snow (cloud). */
    data class Now(val stage: Int = -1, val at: Long = 0, val key: String = "")

    private val _now = MutableStateFlow(Now())
    val now: StateFlow<Now> = _now
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.guard)
    @Volatile private var busy = false
    private var prefsCtx: Context? = null

    fun init(ctx: Context) {
        prefsCtx = ctx.applicationContext
        val p = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        _now.value = Now(p.getInt("wStage", -1), p.getLong("wAt", 0), p.getString("wKey", "") ?: "")
    }

    fun stageOf(code: Int): Int = when (code) {
        0, 1 -> 0
        2, 3, 45, 48 -> 1
        in 95..99 -> 3
        51, 53, 55, 56, 57, 61, 80 -> 4          // drizzle, light rain, light showers
        63, 65, 66, 67, 81, 82 -> 2              // rain, freezing rain, heavier showers
        71, 77, 85 -> 5                          // light snow, snow grains, light snow showers
        73, 75, 86 -> 6                          // snow, heavy snow showers
        else -> 1
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "vlc-interface")
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    suspend fun search(q: String): List<City> = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=en&name=" + URLEncoder.encode(q.trim(), "UTF-8")))
            val a = o.optJSONArray("results") ?: return@withContext emptyList()
            (0 until a.length()).map { i -> a.getJSONObject(i).let { r ->
                City(r.optString("name"), listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                    r.optDouble("latitude"), r.optDouble("longitude"), r.optString("timezone")) } }
        } catch (_: Exception) { emptyList() }
    }

    /** Refreshed at most every 30 min (or at once after a city change). */
    fun refresh(force: Boolean = false) {
        val g = Alarms.current.global
        if (g.cityName.isEmpty() || !g.showWeather || busy) return
        val key = "${g.cityLat},${g.cityLon},${if (g.weatherNoon) "noon" else "now"}"
        val n = _now.value
        if (!force && n.key == key && System.currentTimeMillis() - n.at < 30 * 60_000L) return
        busy = true
        scope.launch {
            try {
                val o = JSONObject(get("https://api.open-meteo.com/v1/forecast?current=weather_code&hourly=weather_code&forecast_days=2&timezone=auto&latitude=${g.cityLat}&longitude=${g.cityLon}"))
                val cur = o.getJSONObject("current")
                val code = if (!g.weatherNoon) cur.getInt("weather_code") else {
                    // Today's 12:00 at the city (its own local date), from the hourly forecast.
                    val day = cur.optString("time").substringBefore('T')
                    val h = o.getJSONObject("hourly"); val times = h.getJSONArray("time"); val codes = h.getJSONArray("weather_code")
                    val i = (0 until times.length()).firstOrNull { times.getString(it) == "${day}T12:00" }
                    if (i != null) codes.getInt(i) else cur.getInt("weather_code")
                }
                val v = Now(stageOf(code), System.currentTimeMillis(), key)
                _now.value = v
                prefsCtx?.getSharedPreferences("alarms", Context.MODE_PRIVATE)?.edit()?.putInt("wStage", v.stage)?.putLong("wAt", v.at)?.putString("wKey", key)?.apply()
            } catch (_: Exception) {
                // Kept as it was; tried again in 5 min.
                _now.value = n.copy(at = System.currentTimeMillis() - 25 * 60_000L)
            } finally { busy = false }
        }
    }
}
