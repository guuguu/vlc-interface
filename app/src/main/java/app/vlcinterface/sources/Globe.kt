package app.vlcinterface.sources

import app.vlcinterface.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Which globe picture to show for a station whose country isn't known: turned to where the station is.
 * The directory's own coordinates first; otherwise the stream server's address looked up once (ipwho.is),
 * kept per server name for the whole app run. Europe whenever nothing can be found.
 */
object Globe {
    val EUROPE = R.drawable.globe_europe
    private val byHost = ConcurrentHashMap<String, Int>()

    /** The globe for a place given by coordinates. */
    fun of(lat: Double, lon: Double): Int = when {
        lat.isNaN() || lon.isNaN() -> EUROPE
        lon < -30 -> if (lat >= 8) R.drawable.globe_namerica else R.drawable.globe_samerica
        lon < 35 -> if (lat >= 36) EUROPE else R.drawable.globe_africa
        lon < 60 -> when {
            lat >= 50 -> EUROPE
            lat < 12 && lon < 52 -> R.drawable.globe_africa
            else -> R.drawable.globe_asia
        }
        lat < -10 && lon >= 110 -> R.drawable.globe_australia
        lat < 0 && lon >= 150 -> R.drawable.globe_australia
        else -> R.drawable.globe_asia
    }

    private fun ofContinent(code: String): Int? = when (code.uppercase()) {
        "EU" -> EUROPE; "AF" -> R.drawable.globe_africa; "NA" -> R.drawable.globe_namerica
        "SA" -> R.drawable.globe_samerica; "AS" -> R.drawable.globe_asia; "OC" -> R.drawable.globe_australia
        else -> null
    }

    /** Already known for this station without asking the network (or null: look it up with [lookup]). */
    fun known(r: RadioStation): Int? =
        if (!r.lat.isNaN() && !r.lon.isNaN()) of(r.lat, r.lon) else hostOf(r.url)?.let { byHost[it] }

    /** Looks the stream server up by its address (once per server name); Europe when nothing is found. */
    suspend fun lookup(r: RadioStation): Int {
        known(r)?.let { return it }
        val host = hostOf(r.url) ?: return EUROPE
        return withContext(Dispatchers.IO) {
            val g = try {
                val ip = InetAddress.getByName(host).hostAddress ?: ""
                val req = Request.Builder().url("https://ipwho.is/$ip").header("User-Agent", Net.UA).build()
                Net.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else {
                        val o = JSONObject(resp.body?.string() ?: "{}")
                        if (!o.optBoolean("success", false)) null
                        else ofContinent(o.optString("continent_code"))
                            ?: of(o.optDouble("latitude", Double.NaN), o.optDouble("longitude", Double.NaN))
                    }
                }
            } catch (_: Exception) { null }
            (g ?: EUROPE).also { byHost[host] = it }
        }
    }

    private fun hostOf(url: String): String? =
        try { java.net.URI(url.trim()).host?.lowercase()?.ifEmpty { null } } catch (_: Exception) { null }
}
