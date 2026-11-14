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
                    .filter { it.isDir || isPlayable(it.name) || it.name.ext() in SUB_EXT }
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
) {
    val meta: String get() = listOfNotNull(
        tags.split(',').firstOrNull()?.trim()?.replaceFirstChar { it.uppercase() }?.ifEmpty { null },
        if (bitrate > 0) "$bitrate kbps ${codec}".trim() else codec.ifEmpty { null },
        country.ifEmpty { null },
    ).joinToString(" · ")
}

/** radio-browser.info: free, open directory of internet radio stations (no API key). */
object RadioBrowser {
    private const val BASE = "https://all.api.radio-browser.info/json"

    suspend fun search(name: String = "", tag: String = "", country: String = "", limit: Int = 60): Result<List<RadioStation>> =
        withContext(Dispatchers.IO) {
            try {
                val b = Uri.parse("$BASE/stations/search").buildUpon()
                    .appendQueryParameter("limit", limit.toString())
                    .appendQueryParameter("hidebroken", "true")
                    .appendQueryParameter("order", "clickcount")
                    .appendQueryParameter("reverse", "true")
                if (name.isNotBlank()) b.appendQueryParameter("name", name.trim())
                if (tag.isNotBlank()) b.appendQueryParameter("tag", tag.trim().lowercase())
                if (country.isNotBlank()) b.appendQueryParameter("countrycode", country.trim().uppercase())
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
                        )
                    }.filter { it.url.isNotEmpty() })
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
