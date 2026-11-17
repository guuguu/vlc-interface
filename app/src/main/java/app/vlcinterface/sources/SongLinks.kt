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

    /** Why the last Spotify look-up steps failed (in the song-links log). */
    private val why = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun getCode(url: String): Pair<Int, String?> = try {
        val rq = Request.Builder().url(url).header("User-Agent", Net.UA).build()
        Net.http.newCall(rq).execute().use { r -> r.code to (if (r.isSuccessful) r.body?.string() else null) }
    } catch (e: Exception) { -1 to (e.message ?: e.javaClass.simpleName) }

    /** The song on Deezer (free search, no key): (its page, its ISRC code). */
    private fun deezer(artist: String, title: String): Pair<String, String>? {
        val q = Uri.encode("artist:\"$artist\" track:\"$title\"")
        val (code, body) = getCode("https://api.deezer.com/search?q=$q&limit=10")
        if (code != 200 || body == null) { why += "Deezer: answered $code"; return null }
        val arr = try { JSONObject(body).optJSONArray("data") } catch (_: Exception) { null } ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (cover(title, o.optString("title")) >= 0.6 && cover(artist, o.optJSONObject("artist")?.optString("name") ?: "") >= 0.6) {
                val isrc = getCode("https://api.deezer.com/track/" + o.optLong("id")).second?.let { b ->
                    try { JSONObject(b).optString("isrc") } catch (_: Exception) { "" } } ?: ""
                return o.optString("link") to isrc
            }
        }
        why += "Deezer: not found"
        return null
    }

    private val spotifyTrack = Regex("""https?://open\.spotify\.com/(?:intl-[a-z]+/)?track/([A-Za-z0-9]{22})""")

    // -------- 1. Spotify's own search (your free developer Client ID / Secret, Settings → Network)
    @Volatile private var spToken = ""; @Volatile private var spTokenUntil = 0L
    private fun spotifyApi(artist: String, title: String, isrc: String): String? {
        val st = app.vlcinterface.App.instance.store.current.settings
        if (st.spotifyId.isBlank() || st.spotifySecret.isBlank()) return null
        try {
            if (System.currentTimeMillis() > spTokenUntil) {
                val rq = Request.Builder().url("https://accounts.spotify.com/api/token")
                    .header("Authorization", okhttp3.Credentials.basic(st.spotifyId.trim(), st.spotifySecret.trim()))
                    .post(okhttp3.FormBody.Builder().add("grant_type", "client_credentials").build()).build()
                Net.http.newCall(rq).execute().use { r ->
                    if (!r.isSuccessful) { why += "Spotify: sign-in refused (${r.code}) — check Client ID / Secret"; return null }
                    val o = JSONObject(r.body?.string() ?: "{}")
                    spToken = o.optString("access_token"); spTokenUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000
                }
            }
            for (q in listOfNotNull(isrc.takeIf { it.isNotBlank() }?.let { "isrc:$it" }, "track:\"$title\" artist:\"$artist\"", "$artist $title")) {
                val rq = Request.Builder().url("https://api.spotify.com/v1/search?type=track&limit=10&q=" + Uri.encode(q))
                    .header("Authorization", "Bearer $spToken").build()
                val items = Net.http.newCall(rq).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}").optJSONObject("tracks")?.optJSONArray("items") else null } ?: continue
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val a = o.optJSONArray("artists")?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.getJSONObject(it).optString("name") } } ?: ""
                    if (q.startsWith("isrc:") || (cover(title, o.optString("name")) >= 0.6 && cover(artist, a) >= 0.5))
                        return o.optJSONObject("external_urls")?.optString("spotify")?.takeIf { it.isNotBlank() }
                }
            }
            why += "Spotify: not found in its catalogue"
        } catch (e: Exception) { why += "Spotify: ${e.message ?: e.javaClass.simpleName}" }
        return null
    }

    // -------- 2. ISRC → MusicBrainz → its Spotify link
    private fun musicBrainz(isrc: String): String? {
        if (isrc.isBlank()) return null
        val rq = Request.Builder().url("https://musicbrainz.org/ws/2/isrc/$isrc?inc=url-rels&fmt=json")
            .header("User-Agent", "vlc-interface/1.0 ( https://github.com/guuguu/vlc-interface )").build()
        val body = try { Net.http.newCall(rq).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } } catch (_: Exception) { null }
            ?: run { why += "MusicBrainz: ISRC $isrc not known"; return null }
        spotifyTrack.find(body)?.let { return it.value }
        why += "MusicBrainz: no Spotify link for ISRC $isrc"
        return null
    }

    // -------- 3. Web search: the first open.spotify.com/track result for "artist title"
    private fun webSearch(artist: String, title: String): String? {
        val q = Uri.encode("$artist $title site:open.spotify.com/track")
        for ((name, url) in listOf("DuckDuckGo" to "https://html.duckduckgo.com/html/?q=$q", "Bing" to "https://www.bing.com/search?q=$q")) {
            val html = get(url, browser = true)
            if (html == null) { why += "$name: no answer"; continue }
            // DuckDuckGo wraps results (…?uddg=<address>): unwrapped first.
            val text = Uri.decode(html.replace("&amp;", "&"))
            spotifyTrack.find(text)?.let { return "https://open.spotify.com/track/" + it.groupValues[1] }
            why += "$name: no Spotify track found"
        }
        return null
    }

    /**
     * Spotify page of the song: Spotify's own search (with your developer ID), else the ISRC through MusicBrainz,
     * else a web search; when none finds it, a Spotify search link.
     */
    private fun spotify(info: Info?, artist: String, title: String, @Suppress("UNUSED_PARAMETER") yt: Video?): Pair<String, Boolean> {
        why.clear()
        val a = info?.artist ?: artist; val t = info?.title ?: title
        val isrc = deezer(a, t)?.second ?: ""
        spotifyApi(a, t, isrc)?.let { return it to false }
        musicBrainz(isrc)?.let { return it to false }
        webSearch(a, t)?.let { return it to false }
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
        // Wi-Fi lost, mobile data not allowed: nothing is looked up.
        if (app.vlcinterface.player.NetGuard.blocked())
            return@withContext Result(null, null, "https://open.spotify.com/search/" + Uri.encode("$artist $title"), true,
                listOf("On mobile data: links aren't looked up (Wi-Fi lost)"))
        val info = info(artist, title, year, lengthMs, album)
        if (info == null) notes += "Not found in the iTunes catalogue — searched by name only"
        val y = if (year > 0) year else info?.year ?: 0
        val len = ((if (lengthMs > 0) lengthMs else info?.lengthMs ?: 0) / 1000).toInt()
        val yt = youtube(info?.artist ?: artist, info?.title ?: title, len, y)
        if (yt == null) notes += "No matching YouTube video"
        val (sp, spSearch) = spotify(info, artist, title, yt)
        if (spSearch) notes += "Spotify: song page not found — a Spotify search link is used" + (if (why.isNotEmpty()) " (" + why.joinToString("; ") + ")" else "")
        ServerLog.log("song-links", "find", "https", "", yt != null && !spSearch, "$artist – $title",
            "youtube: ${yt?.let { "${it.url} (${it.channel}, ${it.score})" } ?: "none"} · spotify: $sp" + (if (why.isNotEmpty()) " · " + why.joinToString("; ") else ""))
        Result(info, yt, sp, spSearch, notes)
    }
}
