package app.vlcinterface.sources

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser

/** WebDAV folder listing (Nextcloud, ownCloud, NAS WebDAV). Files are then played by libVLC over https. */
object WebDav {
    private const val BODY = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:displayname/></d:prop></d:propfind>"""

    suspend fun list(url: String, user: String, pass: String): Result<List<Entry>> = withContext(Dispatchers.IO) {
        try {
            val target = if (url.endsWith("/")) url else "$url/"
            val req = Request.Builder().url(target)
                .method("PROPFIND", BODY.toRequestBody("application/xml".toMediaType()))
                .header("Depth", "1").header("User-Agent", Net.UA)
                .apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass)) }
                .build()
            Net.http.newCall(req).execute().use { r ->
                if (r.code == 401) return@withContext Result.failure(Exception("Login refused (401)"))
                if (!r.isSuccessful) return@withContext Result.failure(Exception("Server answered ${r.code}"))
                val base = target.toHttpUrlOrNull() ?: return@withContext Result.failure(Exception("Bad address"))
                val entries = parse(r.body?.string() ?: "").mapNotNull { (href, isDir, size) ->
                    val abs = base.resolve(href)?.toString() ?: return@mapNotNull null
                    if (abs.trimEnd('/') == target.trimEnd('/')) return@mapNotNull null
                    Entry(Urls.nameOf(abs), abs, isDir, size)
                }
                Result.success(entries.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() })))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parse(xml: String): List<Triple<String, Boolean, Long>> {
        val out = ArrayList<Triple<String, Boolean, Long>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(xml.reader())
        var href = ""; var isDir = false; var size = -1L; var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "response") { href = ""; isDir = false; size = -1 }
                    if (tag == "collection") isDir = true
                }
                XmlPullParser.TEXT -> when (tag) {
                    "href" -> href += p.text.trim()
                    "getcontentlength" -> size = p.text.trim().toLongOrNull() ?: -1
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "response" && href.isNotEmpty()) out += Triple(href, isDir, size)
                    tag = ""
                }
            }
        }
        return out
    }
}

/** Plain web server directory listings ("Index of /..."). */
object HttpIndex {
    private val link = Regex("""href\s*=\s*"([^"?#]+)"""", RegexOption.IGNORE_CASE)

    suspend fun list(url: String, user: String, pass: String): Result<List<Entry>> = withContext(Dispatchers.IO) {
        try {
            val target = if (url.endsWith("/")) url else "$url/"
            val req = Request.Builder().url(target).header("User-Agent", Net.UA)
                .apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass)) }.build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext Result.failure(Exception("Server answered ${r.code}"))
                val base = target.toHttpUrlOrNull()!!
                val html = r.body?.string() ?: ""
                val entries = link.findAll(html).map { it.groupValues[1] }
                    .filter { !it.startsWith("..") && it != "/" && !it.startsWith("mailto:") }
                    .mapNotNull { h -> base.resolve(h)?.toString() }
                    .filter { it.startsWith(target) && it != target }
                    .distinct()
                    .map { Entry(Urls.nameOf(it), it, it.endsWith("/")) }
                    .toList()
                Result.success(entries.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() })))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** M3U / M3U8 playlists (IPTV lists, radio playlists). */
object M3u {
    suspend fun load(url: String): Result<List<Entry>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext Result.failure(Exception("Server answered ${r.code}"))
                Result.success(parse(r.body?.string() ?: ""))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var title = ""; var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    title = line.substringAfterLast(',').trim()
                    group = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1) ?: ""
                }
                line.isEmpty() || line.startsWith("#") -> {}
                else -> {
                    out += Entry(title.ifEmpty { Urls.nameOf(line) }, line, false, meta = group)
                    title = ""; group = ""
                }
            }
        }
        return out
    }
}

data class RadioStation(
    val uuid: String, val name: String, val url: String, val codec: String,
    val bitrate: Int, val country: String, val tags: String, val favicon: String,
    /** The directory's coordinates for the station (NaN when it has none). */
    val lat: Double = Double.NaN, val lon: Double = Double.NaN,
) {
    val isFlac: Boolean get() = codec.contains("flac", true)
    val meta: String get() = listOfNotNull(
        tags.split(',').firstOrNull()?.trim()?.replaceFirstChar { it.uppercase() }?.ifEmpty { null },
        if (bitrate > 0) "$bitrate kbps ${codec}".trim() else codec.ifEmpty { null },
        country.ifEmpty { null },
    ).joinToString(" · ")
}

/** A coordinate from the directory: NaN when missing, null, out of range or exactly 0,0-ish placeholder. */
private fun geo(o: org.json.JSONObject, k: String, max: Double): Double {
    val v = o.opt(k)?.toString()?.toDoubleOrNull() ?: return Double.NaN
    return if (v.isNaN() || kotlin.math.abs(v) > max) Double.NaN else v
}

/** radio-browser.info: free, open directory of internet radio stations (no API key). */
object RadioBrowser {
    private const val BASE = "https://all.api.radio-browser.info/json"

    /**
     * Everything the radio directory knows about a station (looked up by its stream address):
     * label → value, empty values left out.
     */
    suspend fun details(url: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val u = Uri.parse("$BASE/stations/byurl").buildUpon().appendQueryParameter("url", url).build().toString()
            val o = Net.http.newCall(Request.Builder().url(u).header("User-Agent", Net.UA).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                JSONArray(r.body?.string() ?: "[]").optJSONObject(0)
            } ?: return@withContext emptyList()
            val labels = listOf(
                "name" to "Directory name", "homepage" to "Homepage", "url" to "Stream address (listed)",
                "url_resolved" to "Stream address (resolved)", "favicon" to "Logo", "tags" to "Tags / style",
                "country" to "Country", "countrycode" to "Country code", "state" to "Region", "language" to "Language",
                "languagecodes" to "Language codes", "codec" to "Codec", "bitrate" to "Bitrate (kbps)", "hls" to "HLS stream",
                "votes" to "Votes", "clickcount" to "Plays (last day)", "clicktrend" to "Play trend", "lastcheckok" to "Online at last check",
                "lastchecktime_iso8601" to "Last checked", "lastchangetime_iso8601" to "Last changed", "ssl_error" to "SSL error",
                "geo_lat" to "Latitude", "geo_long" to "Longitude", "has_extended_info" to "Extended info", "stationuuid" to "Directory ID",
            )
            labels.mapNotNull { (k, l) ->
                val v = o.opt(k)?.toString()?.trim().orEmpty()
                if (v.isEmpty() || v == "null" || (k == "bitrate" && v == "0")) null
                else l to when (k) { "hls", "lastcheckok", "ssl_error", "has_extended_info" -> if (v == "1" || v == "true") "yes" else "no"; else -> v }
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun search(name: String = "", tag: String = "", country: String = "", limit: Int = 60,
                       bitrateMin: Int = 0, bitrateMax: Int = 0, language: String = "", codec: String = "",
                       order: String = "clickcount"): Result<List<RadioStation>> =
        withContext(Dispatchers.IO) {
            try {
                val b = Uri.parse("$BASE/stations/search").buildUpon()
                    .appendQueryParameter("limit", limit.toString())
                    .appendQueryParameter("hidebroken", "true")
                    .appendQueryParameter("order", order)
                    .appendQueryParameter("reverse", "true")
                if (name.isNotBlank()) b.appendQueryParameter("name", name.trim())
                if (tag.isNotBlank()) b.appendQueryParameter("tag", tag.trim().lowercase())
                if (country.isNotBlank()) b.appendQueryParameter("countrycode", country.trim().uppercase())
                if (bitrateMin > 0) b.appendQueryParameter("bitrateMin", bitrateMin.toString())
                if (bitrateMax > 0) b.appendQueryParameter("bitrateMax", bitrateMax.toString())
                if (language.isNotBlank()) b.appendQueryParameter("language", language.trim().lowercase())
                if (codec.isNotBlank()) b.appendQueryParameter("codec", codec.trim())
                val req = Request.Builder().url(b.build().toString()).header("User-Agent", Net.UA).build()
                Net.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext Result.failure(Exception("Directory answered ${r.code}"))
                    val arr = JSONArray(r.body?.string() ?: "[]")
                    Result.success((0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        RadioStation(
                            o.optString("stationuuid"), o.optString("name").trim(),
                            o.optString("url_resolved").ifEmpty { o.optString("url") },
                            o.optString("codec"), o.optInt("bitrate"), o.optString("countrycode"),
                            o.optString("tags"), o.optString("favicon"),
                            geo(o, "geo_lat", 90.0), geo(o, "geo_long", 180.0),
                        )
                    }.filter { it.url.isNotEmpty() })
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

/** Playlist files: write M3U8 (relative paths when the media is under the same folder) and read M3U / PLS. */
object PlaylistFiles {
    fun toM3u8(items: List<app.vlcinterface.data.PlEntry>, saveFolderUrl: String): String {
        val sb = StringBuilder("#EXTM3U\n")
        val base = saveFolderUrl.trimEnd('/') + "/"
        items.forEach { e ->
            val sec = if (e.durationMs > 0) e.durationMs / 1000 else -1
            val label = if (e.artist.isNotEmpty()) "${e.artist} - ${e.title}" else e.title
            sb.append("#EXTINF:$sec,$label\n")
            if (e.startMs >= 0) sb.append("#EXTVLCOPT:start-time=${e.startMs / 1000.0}\n")
            if (e.endMs > 0) sb.append("#EXTVLCOPT:stop-time=${e.endMs / 1000.0}\n")
            val path = if (e.uri.startsWith(base)) android.net.Uri.decode(e.uri.removePrefix(base)) else e.uri
            sb.append(path).append('\n')
        }
        return sb.toString()
    }

    /** Returns (title, absolute uri) pairs; relative entries are resolved against [baseUrl] when given. */
    fun parse(text: String, baseUrl: String = ""): List<Pair<String, String>> {
        val t = text.removePrefix("\uFEFF")
        return if (t.trimStart().startsWith("[playlist]", ignoreCase = true)) {
            val files = HashMap<Int, String>(); val titles = HashMap<Int, String>()
            t.lineSequence().forEach { l ->
                val k = l.substringBefore('=').trim(); val v = l.substringAfter('=', "").trim()
                Regex("""(?i)file(\d+)""").matchEntire(k)?.let { files[it.groupValues[1].toInt()] = v }
                Regex("""(?i)title(\d+)""").matchEntire(k)?.let { titles[it.groupValues[1].toInt()] = v }
            }
            files.toSortedMap().map { (i, f) -> (titles[i] ?: Urls.nameOf(f)) to resolve(f, baseUrl) }.filter { it.second.isNotEmpty() }
        } else M3u.parse(t).map { it.name to resolve(it.uri, baseUrl) }.filter { it.second.isNotEmpty() }
    }

    private fun resolve(p: String, base: String): String = when {
        p.contains("://") -> p
        base.isNotEmpty() -> base.trimEnd('/') + "/" + Urls.encodePath(p.replace('\\', '/').trimStart('/'))
        else -> ""
    }
}
