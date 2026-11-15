package app.vlcinterface.sources

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Album pictures from the internet, for albums whose files have no cover (embedded or cover.jpg/folder.jpg).
 * CDDB/freedb/gnudb only hold track lists, no pictures, so this uses MusicBrainz (finds the album) +
 * Cover Art Archive (its picture), both free and without an account. Pictures are stored in the app
 * (one 500 px JPEG per album) and reused offline; albums with no picture online are not asked again for 30 days.
 */
object CoverArt {
    private lateinit var dir: File
    private val lock = Mutex()
    private var lastCall = 0L
    private const val UA = "vlc-interface/0.2.0 ( https://github.com/guuguu/vlc-interface )"

    fun init(ctx: Context) { dir = File(ctx.filesDir, "covers").apply { mkdirs() } }

    private fun key(artist: String, album: String): String =
        (artist.trim().lowercase() + "|" + album.trim().lowercase()).hashCode().toUInt().toString(16)

    /** Stored picture for this album, if any (no network). */
    fun cached(artist: String, album: String): File? {
        if (!::dir.isInitialized || album.isBlank()) return null
        return File(dir, key(artist, album) + ".jpg").takeIf { it.length() > 0 }
    }

    // ---------- artist / band picture (when the album has none) ----------

    /** Stored picture of the artist or band, if any (no network). */
    fun cachedArtist(artist: String): File? {
        if (!::dir.isInitialized || !usableArtist(artist)) return null
        return File(dir, "a" + key(artist, "") + ".jpg").takeIf { it.length() > 0 }
    }

    private fun usableArtist(a: String) = a.isNotBlank() && a.trim().lowercase() !in setOf("various artists", "various", "va", "unknown", "<unknown>")

    /**
     * Picture of the artist or band: MusicBrainz finds the artist, its links point to the photo on
     * Wikimedia Commons (directly or through Wikidata). Free, no account; stored in the app like album pictures.
     */
    suspend fun getArtist(artist: String): File? = withContext(Dispatchers.IO) {
        cachedArtist(artist)?.let { return@withContext it }
        if (!::dir.isInitialized || !usableArtist(artist)) return@withContext null
        val miss = File(dir, "a" + key(artist, "") + ".none")
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 30L * 24 * 3600 * 1000) return@withContext null
        lock.withLock {
            try {
                suspend fun mb(url: String): JSONObject? {
                    val wait = 1100 - (System.currentTimeMillis() - lastCall); if (wait > 0) delay(wait)
                    lastCall = System.currentTimeMillis()
                    return Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                        if (r.isSuccessful) JSONObject(r.body!!.string()) else null
                    }
                }
                val found = mb("https://musicbrainz.org/ws/2/artist/?fmt=json&limit=3&query=" +
                    URLEncoder.encode("artist:\"${artist.replace("\"", "")}\"", "UTF-8"))
                val id = found?.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                    ?.firstOrNull { it.optInt("score") >= 95 }?.getString("id")
                var commonsFile: String? = null
                if (id != null) {
                    val rels = mb("https://musicbrainz.org/ws/2/artist/$id?fmt=json&inc=url-rels")?.optJSONArray("relations")
                    val list = rels?.let { r -> (0 until r.length()).map { r.getJSONObject(it) } } ?: emptyList()
                    fun target(type: String) = list.firstOrNull { it.optString("type") == type }?.optJSONObject("url")?.optString("resource")
                    // 1) direct "image" link to Wikimedia Commons
                    target("image")?.takeIf { "commons.wikimedia.org" in it }?.let { commonsFile = it.substringAfterLast("File:", "").ifEmpty { null } }
                    // 2) Wikidata item → its image (P18)
                    if (commonsFile == null) target("wikidata")?.substringAfterLast('/')?.let { q ->
                        val wd = Net.http.newCall(Request.Builder().url("https://www.wikidata.org/wiki/Special:EntityData/$q.json").header("User-Agent", UA).build())
                            .execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
                        commonsFile = wd?.optJSONObject("entities")?.optJSONObject(q)?.optJSONObject("claims")?.optJSONArray("P18")
                            ?.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.ifEmpty { null }
                    }
                }
                val file = commonsFile
                if (file != null) {
                    val name = java.net.URLDecoder.decode(file, "UTF-8").replace(' ', '_')
                    val img = Net.http.newCall(Request.Builder().url("https://commons.wikimedia.org/wiki/Special:FilePath/" +
                        URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "?width=500").header("User-Agent", UA).build())
                        .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                    if (img != null && img.size > 1000) {
                        val f = File(dir, "a" + key(artist, "") + ".jpg"); f.writeBytes(img); miss.delete(); return@withLock f
                    }
                }
                miss.writeText(""); miss.setLastModified(System.currentTimeMillis())
                null
            } catch (_: Exception) { null }
        }
    }

    fun count(): Int = if (::dir.isInitialized) dir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0 else 0
    fun clear() { if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() } }

    /** Stored picture, or looks it up online and stores it. Null when there is none. */
    suspend fun get(artist: String, album: String): File? = withContext(Dispatchers.IO) {
        cached(artist, album)?.let { return@withContext it }
        if (!::dir.isInitialized || album.isBlank()) return@withContext null
        val miss = File(dir, key(artist, album) + ".none")
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 30L * 24 * 3600 * 1000) return@withContext null
        lock.withLock {
            // MusicBrainz asks for at most one request per second.
            val wait = 1100 - (System.currentTimeMillis() - lastCall); if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
            try {
                fun q(s: String) = s.replace("\"", "")
                val query = "releasegroup:\"${q(album)}\"" + if (artist.isNotBlank()) " AND artist:\"${q(artist)}\"" else ""
                val url = "https://musicbrainz.org/ws/2/release-group/?fmt=json&limit=5&query=" + URLEncoder.encode(query, "UTF-8")
                val ids = Net.http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withLock null
                    val arr = JSONObject(r.body!!.string()).optJSONArray("release-groups") ?: return@withLock null
                    (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("score") >= 90 }.map { it.getString("id") }
                }
                for (id in ids) {
                    val img = Net.http.newCall(Request.Builder().url("https://coverartarchive.org/release-group/$id/front-500")
                        .header("User-Agent", UA).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                    if (img != null && img.size > 1000) {
                        val f = File(dir, key(artist, album) + ".jpg"); f.writeBytes(img); miss.delete(); return@withLock f
                    }
                }
                miss.writeText(""); miss.setLastModified(System.currentTimeMillis())
                null
            } catch (_: Exception) { null }   // offline etc.: try again next time
        }
    }
}
