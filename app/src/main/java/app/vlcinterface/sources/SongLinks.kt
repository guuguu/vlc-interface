package app.vlcinterface.sources

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.vlcinterface.data.ServerLog
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

/**
 * Internet links for a song: the best YouTube video (VEVO first, then the "Artist - Topic" channel, then others that
 * match title, artist, length and year) and its Spotify page. The song's details (album, year, genre, track, length,
 * picture) come from the iTunes catalogue; Spotify from song.link (Odesli), which maps one song across services.
 */
object SongLinks {
    data class Info(
        val artist: String, val title: String, val album: String = "", val year: Int = 0, val genre: String = "",
        val trackNo: Int = 0, val trackCount: Int = 0, val discNo: Int = 0, val lengthMs: Long = 0,
        val artworkUrl: String = "", val itunesUrl: String = "",
    )
    data class Video(val url: String, val title: String, val channel: String, val lengthSec: Int, val score: Int)
    data class Result(val info: Info?, val youtube: Video?, val spotify: String, val spotifyIsSearch: Boolean, val notes: List<String>)

    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private fun get(url: String, browser: Boolean = false): String? = try {
        val rq = Request.Builder().url(url).header("User-Agent", if (browser) BROWSER_UA else Net.UA)
            .apply { if (browser) { header("Accept-Language", "en-US,en;q=0.8"); header("Cookie", "SOCS=CAI; CONSENT=YES+1") } }.build()
        Net.http.newCall(rq).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Exception) { null }

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\((feat|ft)\\.?[^)]*\\)|\\[[^\\]]*\\]"), " ").replace("&", " and ")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Share of [a]'s words found in [b] (0..1). */
    private fun cover(a: String, b: String): Double {
        val wa = norm(a).split(' ').filter { it.isNotEmpty() }; if (wa.isEmpty()) return 0.0
        val wb = norm(b).split(' ').toSet()
        return wa.count { it in wb }.toDouble() / wa.size
    }

    /** The song in the iTunes catalogue closest to artist + title (+ year, length, album when known). */
    suspend fun info(artist: String, title: String, year: Int = 0, lengthMs: Long = 0, album: String = ""): Info? = withContext(Dispatchers.IO) {
        val q = Uri.encode("$artist $title".trim())
        val body = get("https://itunes.apple.com/search?term=$q&entity=song&limit=25") ?: return@withContext null
        val arr = try { JSONObject(body).optJSONArray("results") ?: JSONArray() } catch (_: Exception) { return@withContext null }
        var best: JSONObject? = null; var bestScore = -1e9
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optString("trackName"); val a = o.optString("artistName")
            var s = cover(title, t) * 50 + cover(t, title) * 20 + cover(artist, a) * 40
            if (norm(t) == norm(title)) s += 20
            val y = o.optString("releaseDate").take(4).toIntOrNull() ?: 0
            if (year > 0 && y > 0) s -= abs(year - y).coerceAtMost(30)
            val len = o.optLong("trackTimeMillis")
            if (lengthMs > 0 && len > 0) s -= (abs(lengthMs - len) / 1000.0).coerceAtMost(60.0) / 3
            if (album.isNotBlank()) s += cover(album, o.optString("collectionName")) * 10
            // Originals before compilations / live versions.
            val cn = norm(o.optString("collectionName"))
            if (Regex("\\b(greatest|hits|best of|collection|live|anthology|essential)\\b").containsMatchIn(cn)) s -= 4
            if (y > 0) s -= (y - 1900) / 200.0   // the earliest release wins a tie
            if (s > bestScore) { bestScore = s; best = o }
        }
        val o = best ?: return@withContext null
        if (cover(title, o.optString("trackName")) < 0.5 || cover(artist, o.optString("artistName")) < 0.5) return@withContext null
        Info(o.optString("artistName"), o.optString("trackName"), o.optString("collectionName"),
            o.optString("releaseDate").take(4).toIntOrNull() ?: 0, o.optString("primaryGenreName"),
            o.optInt("trackNumber"), o.optInt("trackCount"), o.optInt("discNumber"), o.optLong("trackTimeMillis"),
            o.optString("artworkUrl100").replace("100x100bb", "1000x1000bb"), o.optString("trackViewUrl"))
    }

    /** Spotify page of the song (via song.link from the iTunes page), else a Spotify search link. */
    private fun spotify(info: Info?, artist: String, title: String): Pair<String, Boolean> {
        if (info != null && info.itunesUrl.isNotEmpty()) {
            val b = get("https://api.song.link/v1-alpha.1/links?url=" + Uri.encode(info.itunesUrl))
            val u = try { b?.let { JSONObject(it).optJSONObject("linksByPlatform")?.optJSONObject("spotify")?.optString("url") } } catch (_: Exception) { null }
            if (!u.isNullOrBlank()) return u to false
        }
        return "https://open.spotify.com/search/" + Uri.encode("$artist $title") to true
    }

    private val lengthRx = Regex("^(?:(\\d+):)?(\\d+):(\\d+)$")
    private fun secs(t: String): Int = lengthRx.find(t.trim())?.destructured?.let { (h, m, s) -> (h.toIntOrNull() ?: 0) * 3600 + m.toInt() * 60 + s.toInt() } ?: 0

    private fun text(o: JSONObject?, key: String): String = o?.optJSONObject(key)?.let { j ->
        j.optString("simpleText").ifEmpty { j.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.getJSONObject(it).optString("text") } } ?: "" }
    } ?: ""

    /** Video results from YouTube's own search page (with "videoRenderer" entries in ytInitialData). */
    private fun youtubeSearch(q: String): List<Triple<JSONObject, String, String>> {
        val html = get("https://www.youtube.com/results?search_query=" + Uri.encode(q) + "&hl=en&gl=US", browser = true) ?: return emptyList()
        val start = html.indexOf("ytInitialData").takeIf { it >= 0 }?.let { html.indexOf('{', it) } ?: return emptyList()
        val end = html.indexOf(";</script>", start).takeIf { it > 0 } ?: return emptyList()
        val root = try { JSONObject(html.substring(start, end)) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Triple<JSONObject, String, String>>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    x.optJSONObject("videoRenderer")?.let { v ->
                        out += Triple(v, text(v, "title"), text(v, "ownerText").ifEmpty { text(v, "longBylineText") })
                    }
                    x.keys().forEach { k -> if (k != "videoRenderer") walk(x.opt(k)) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(root)
        return out
    }

    /** Piped (a public YouTube front-end) as a fallback when YouTube's page can't be read. */
    private val piped = listOf("https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://api.piped.private.coffee")
    private fun pipedSearch(q: String): List<Video> {
        for (base in piped) {
            val b = get("$base/search?q=" + Uri.encode(q) + "&filter=videos") ?: continue
            val items = try { JSONObject(b).optJSONArray("items") } catch (_: Exception) { null } ?: continue
            return (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("url").contains("watch") }.map {
                Video("https://www.youtube.com" + it.optString("url"), it.optString("title"), it.optString("uploaderName"), it.optInt("duration"), 0)
            }
        }
        return emptyList()
    }

    private val unwanted = Regex("\\b(live|cover|karaoke|instrumental|remix|lyrics?|lyric video|reaction|tutorial|sped up|slowed|8d|nightcore|1 hour|extended|acoustic|piano)\\b")

    /** Ranks a video for the song: VEVO > "Artist - Topic" > official / matching others; length and year count too. */
    fun score(v: Video, artist: String, title: String, lengthSec: Int, year: Int, publishedAgo: String = ""): Int {
        val vt = norm(v.title); val ch = norm(v.channel); val a = norm(artist)
        if (cover(title, v.title) < 0.6) return -1000
        var s = (cover(title, v.title) * 40).toInt()
        val artistMatch = cover(artist, v.title + " " + v.channel)
        if (artistMatch < 0.5) return -1000
        s += (artistMatch * 30).toInt()
        val chNoSpace = ch.replace(" ", ""); val aNoSpace = a.replace(" ", "")
        when {
            chNoSpace.endsWith("vevo") -> s += 100
            ch.endsWith(" topic") && cover(artist, ch.removeSuffix(" topic")) >= 0.6 -> s += 70
            chNoSpace == aNoSpace || chNoSpace == aNoSpace + "official" || chNoSpace == "official$aNoSpace" -> s += 45
        }
        if (Regex("\\bofficial (music )?(video|audio)\\b").containsMatchIn(vt)) s += 15
        // Live, covers, remixes … only when the song title itself says so.
        unwanted.findAll(vt).forEach { m -> if (!norm(title).contains(m.value)) s -= 35 }
        if (lengthSec > 0 && v.lengthSec > 0) s -= (abs(lengthSec - v.lengthSec) / 4).coerceAtMost(40)
        // Closest year: an upload from around the release year (YouTube says "15 years ago").
        if (year > 0) Regex("(\\d+) years? ago").find(publishedAgo)?.groupValues?.get(1)?.toIntOrNull()?.let { ago ->
            val up = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - ago
            if (up >= 2005) s -= (abs(up - year.coerceAtLeast(2005)) / 2).coerceAtMost(8)
        }
        return s
    }

    suspend fun youtube(artist: String, title: String, lengthSec: Int, year: Int): Video? = withContext(Dispatchers.IO) {
        val q = "$artist $title"
        val found = youtubeSearch(q).map { (v, t, c) ->
            val vid = v.optString("videoId")
            val len = secs(text(v, "lengthText"))
            val video = Video("https://www.youtube.com/watch?v=$vid", t, c, len, 0)
            video.copy(score = score(video, artist, title, lengthSec, year, text(v, "publishedTimeText")))
        }.ifEmpty { pipedSearch(q).map { it.copy(score = score(it, artist, title, lengthSec, year)) } }
        // Also the channel's own VEVO search, so a VEVO upload ranked lower by YouTube is not missed.
        val vevo = if (found.none { norm(it.channel).replace(" ", "").endsWith("vevo") })
            youtubeSearch("$q vevo").map { (v, t, c) ->
                val video = Video("https://www.youtube.com/watch?v=" + v.optString("videoId"), t, c, secs(text(v, "lengthText")), 0)
                video.copy(score = score(video, artist, title, lengthSec, year, text(v, "publishedTimeText")))
            } else emptyList()
        (found + vevo).filter { it.score > 0 }.maxByOrNull { it.score }
    }

    suspend fun find(artist: String, title: String, year: Int = 0, lengthMs: Long = 0, album: String = ""): Result = withContext(Dispatchers.IO) {
        val notes = ArrayList<String>()
        val info = info(artist, title, year, lengthMs, album)
        if (info == null) notes += "Not found in the iTunes catalogue — searched by name only"
        val y = if (year > 0) year else info?.year ?: 0
        val len = ((if (lengthMs > 0) lengthMs else info?.lengthMs ?: 0) / 1000).toInt()
        val yt = youtube(info?.artist ?: artist, info?.title ?: title, len, y)
        if (yt == null) notes += "No matching YouTube video"
        val (sp, spSearch) = spotify(info, artist, title)
        if (spSearch) notes += "Spotify: song page not found — a Spotify search link is used"
        ServerLog.log("song-links", "find", "https", "", yt != null && !spSearch, "$artist – $title",
            "youtube: ${yt?.let { "${it.url} (${it.channel}, ${it.score})" } ?: "none"} · spotify: $sp")
        Result(info, yt, sp, spSearch, notes)
    }
}
