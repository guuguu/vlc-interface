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
