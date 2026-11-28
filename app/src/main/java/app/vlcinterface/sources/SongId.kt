package app.vlcinterface.sources

import android.content.Context
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Identify song (MusicBrainz), only when the button is pressed.
 * Radio: the station's "now playing" text is looked up and the result goes to the log (no pop-ups).
 * Songs: the file's tags or its name are looked up; the found title / artist / album are written to the file.
 * When the file already has other names, the user is asked first (Yes / No, closes with nothing written).
 * At most two tries in all (the second with a simpler query).
 */
object SongId {
    data class Match(val title: String, val artist: String, val album: String, val score: Int)
    /** A question waiting on the song player: replace the names the file already has? */
    data class Ask(val item: PlayItem, val old: Map<String, String>, val new: Map<String, String>, val until: Long)

    private val _ask = MutableStateFlow<Ask?>(null)
    val ask: StateFlow<Ask?> = _ask
    /** Short state under the button ("Looking…", "Found", "Not found"). */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    @Volatile private var busy = false

    private fun clean(s: String) = s.replace(Regex("""[\[(][^\])]*[\])]"""), " ").replace("\"", " ").replace(Regex("\\s+"), " ").trim()

    /** "Artist - Title" (radio / file name) → (artist, title); no dash: ("", text). */
    private fun split(text: String): Pair<String, String> {
        val t = clean(text.replace('_', ' '))
        val i = t.indexOf(" - ")
        return if (i > 0) t.substring(0, i).trim() to t.substring(i + 3).trim() else "" to t
    }

    /** The queries tried in order: full, title + artist loosely, title only (2 retries at most). */
    private fun queries(artist: String, title: String): List<String> {
        val a = clean(artist); val t = clean(title)
        if (t.isEmpty()) return emptyList()
        val l = mutableListOf<String>()
        if (a.isNotEmpty()) { l += "recording:\"$t\" AND artist:\"$a\""; l += "$t $a" }
        else { l += "recording:\"$t\""; l += t }
        return l.distinct().take(2)   // "at most twice in all"
    }

    private suspend fun look(artist: String, title: String): Match? {
        for (q in queries(artist, title)) {
            val o = try { CoverArt.mb("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=1&query=" + URLEncoder.encode(q, "UTF-8")) } catch (_: Exception) { null }
            val r = o?.optJSONArray("recordings")?.optJSONObject(0) ?: continue
            val score = r.optInt("score", 0)
            if (score < 80) continue
            val ar = r.optJSONArray("artist-credit")?.let { a -> (0 until a.length()).joinToString("") { k -> a.getJSONObject(k).let { c -> c.optString("name") + c.optString("joinphrase") } } } ?: ""
            val al = r.optJSONArray("releases")?.optJSONObject(0)?.optString("title") ?: ""
            return Match(r.optString("title"), ar, al, score)
        }
        return null
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    private fun flash(s: String) {
        _status.value = s
        scope.launch { kotlinx.coroutines.delay(6000); if (_status.value == s) _status.value = "" }
    }

    /** The button. */
    suspend fun identify(ctx: Context, item: PlayItem, nowPlaying: String) {
        if (busy) return
        busy = true
        try {
            _status.value = "Looking…"
            val set = App.instance.store.current.settings
            if (item.isRadio) {
                // By sound first (2026-11-28 02:55): 12 s of what plays now, recognised by AudD — works with or without
                // the station's song text. Then (or without an AudD token) the station's song text on MusicBrainz.
                var how = "song name"
                var m: Match? = null
                if (set.auddToken.isNotBlank()) {
                    if (!app.vlcinterface.player.NativeAudio.active) ServerLog.log("audd.io", "identify", "HTTPS", "", false,
                        "Radio ${item.title}: can't listen — the app's own sound output isn't in use (Settings → Audio)")
                    else { _status.value = "Listening…"; m = radioBySound(item); how = "sound" }
                }
                val (a, t) = split(nowPlaying)
                if (m == null && t.isNotEmpty()) { _status.value = "Looking…"; m = look(a, t); how = "song name" }
                ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", m != null,
                    if (m != null) "Radio ${item.title} (by $how): → ${m.artist} – ${m.title}" + (if (m.album.isNotEmpty()) " (${m.album})" else "") + " · score ${m.score}"
                    else if (t.isEmpty() && set.auddToken.isBlank()) "Radio ${item.title}: the station sends no song name, and recognising by sound needs an AudD token (Settings → Radio → Identify song)"
                    else "Radio ${item.title}: \"${nowPlaying.ifEmpty { "no song name" }}\" — not found")
                flash(if (m == null) "Not found" else "Found ✓")
                if (m != null) showRadio(item.uri, nowPlaying, m)
                return
            }
            // Song: by sound first (AcoustID, the first 2 minutes of the file — needs an AcoustID key), then its tags
            // or its file name on MusicBrainz.
            val tagged = item.artist.isNotEmpty()
            var m: Match? = null
            if (set.acoustidKey.isNotBlank()) { _status.value = "Listening…"; m = songBySound(ctx, item, set.acoustidKey) }
            if (m == null) {
                _status.value = "Looking…"
                val (a, t) = if (tagged) item.artist to item.title else split(item.fileName.ifEmpty { item.title }.substringBeforeLast('.').replace(Regex("^\\d{1,3}[ .\\-_]+"), ""))
                m = look(a, t)
            }
            if (m == null) {
                ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", false, "${item.title}: not found")
                flash("Not found"); return
            }
            val new = linkedMapOf("TITLE" to m.title, "ARTIST" to m.artist, "ALBUM" to m.album).filterValues { it.isNotBlank() }
            val old = if (tagged) linkedMapOf("TITLE" to item.title, "ARTIST" to item.artist, "ALBUM" to item.album).filterValues { it.isNotBlank() } else emptyMap()
            val changed = new.filter { (k, v) -> old[k] != v }
            if (changed.isEmpty()) { flash("Same tags ✓"); return }
            val clash = changed.keys.any { old.containsKey(it) }
            if (clash) {
                val sec = App.instance.store.current.settings.identifyAskSec
                _ask.value = Ask(item, old, new, System.currentTimeMillis() + sec * 1000L)
                _status.value = ""
            } else write(ctx, item, new)
        } finally { busy = false }
    }

    // ---------- by sound (2026-11-28 02:55) ----------

    /** AcoustID: Chromaprint fingerprint of the file's first 2 minutes plus its length (how AcoustID matches). */
    private suspend fun songBySound(ctx: Context, item: PlayItem, key: String): Match? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val d = app.vlcinterface.player.SoundCheck.decode(ctx, item, 0L, 120) ?: return@withContext null.also {
                ServerLog.log("acoustid.org", "identify", "HTTPS", "", false, "${item.title}: the file couldn't be decoded for its fingerprint") }
            if (d.frames <= 0) return@withContext null
            val fp = Chroma.of(d.pcm, d.frames * 2, d.rate, 2) ?: return@withContext null   // decode() hands stereo-interleaved samples
            val ps = App.instance.player.state.value
            val lenMs = if (ps.item?.uri == item.uri && ps.length > 0) ps.length else if (item.durationMs > 0) item.durationMs else d.frames * 1000L / d.rate
            val body = okhttp3.FormBody.Builder().add("client", key.trim()).add("format", "json").add("meta", "recordings releasegroups compress")
                .add("duration", (lenMs / 1000).toString()).add("fingerprint", fp).build()
            val o = Net.http.newCall(okhttp3.Request.Builder().url("https://api.acoustid.org/v2/lookup").post(body).build()).execute().use { r ->
                org.json.JSONObject(r.body?.string() ?: "{}") }
            if (o.optString("status") != "ok") {
                ServerLog.log("acoustid.org", "identify", "HTTPS", "", false, "${item.title}: AcoustID said " + (o.optJSONObject("error")?.optString("message") ?: "no answer"))
                return@withContext null
            }
            val res = o.optJSONArray("results") ?: return@withContext null
            for (i in 0 until res.length()) {
                val r = res.getJSONObject(i); val score = (r.optDouble("score") * 100).toInt()
                val rec = r.optJSONArray("recordings")?.optJSONObject(0) ?: continue
                val title = rec.optString("title"); if (title.isBlank() || score < 50) continue
                val artist = rec.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString("") { k -> a.getJSONObject(k).let { x -> x.optString("name") + x.optString("joinphrase") } } } ?: ""
                val album = rec.optJSONArray("releasegroups")?.optJSONObject(0)?.optString("title") ?: ""
                return@withContext Match(title, artist, album, score)
            }
            ServerLog.log("acoustid.org", "identify", "HTTPS", "", false, "${item.title}: no match for its fingerprint")
            null
        } catch (e: Exception) {
            ServerLog.log("acoustid.org", "identify", "HTTPS", "", false, "${item.title}: ${e.message ?: e.javaClass.simpleName}"); null
        }
    }

    /** AudD: 12 s of what the app plays now (the sound engine's input), sent as a small mono 16 kHz WAV. */
    private suspend fun radioBySound(item: PlayItem): Match? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val na = app.vlcinterface.player.NativeAudio
        try {
            val seconds = 12
            if (!na.capture(seconds)) return@withContext null
            var st: LongArray? = null
            for (i in 0 until (seconds + 8) * 4) { kotlinx.coroutines.delay(250); st = na.captureState(); if (st == null || st[0] == 0L) break }
            val s0 = st ?: return@withContext null
            val inN = s0[1].toInt(); val rate = s0[4].toInt().takeIf { it > 0 } ?: 44100
            if (inN < rate * 4) { ServerLog.log("audd.io", "identify", "HTTPS", "", false, "Radio ${item.title}: too little sound captured"); return@withContext null }
            val pcm = na.captureTake(inN, s0[2].toInt())?.first ?: return@withContext null
            // Stereo at the stream's rate → mono 16 kHz (averaged; enough for recognition, ~380 KB).
            val outRate = 16000; val outN = (inN.toLong() * outRate / rate).toInt()
            val wav = java.io.ByteArrayOutputStream(44 + outN * 2)
            fun le32(v: Int) { wav.write(v and 255); wav.write((v shr 8) and 255); wav.write((v shr 16) and 255); wav.write((v shr 24) and 255) }
            fun le16(v: Int) { wav.write(v and 255); wav.write((v shr 8) and 255) }
            wav.write("RIFF".toByteArray()); le32(36 + outN * 2); wav.write("WAVEfmt ".toByteArray()); le32(16); le16(1); le16(1)
            le32(outRate); le32(outRate * 2); le16(2); le16(16); wav.write("data".toByteArray()); le32(outN * 2)
            val step = rate.toDouble() / outRate
            for (k in 0 until outN) {
                val a = (k * step).toInt().coerceAtMost(inN - 1); val b = ((k + 1) * step).toInt().coerceIn(a + 1, inN)
                var sum = 0L; for (f in a until b) sum += pcm[2 * f] + pcm[2 * f + 1]
                le16((sum / (2 * (b - a))).toInt())
            }
            val token = App.instance.store.current.settings.auddToken.trim()
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).addFormDataPart("api_token", token)
                .addFormDataPart("file", "clip.wav", wav.toByteArray().toRequestBody("audio/wav".toMediaType())).build()
            val o = Net.http.newCall(okhttp3.Request.Builder().url("https://api.audd.io/").post(body).build()).execute().use { r ->
                org.json.JSONObject(r.body?.string() ?: "{}") }
            if (o.optString("status") != "success") {
                ServerLog.log("audd.io", "identify", "HTTPS", "", false, "Radio ${item.title}: AudD said " + (o.optJSONObject("error")?.optString("error_message") ?: "no answer"))
                return@withContext null
            }
            val r = o.optJSONObject("result") ?: return@withContext null.also { ServerLog.log("audd.io", "identify", "HTTPS", "", false, "Radio ${item.title}: AudD found no song in the 12 s heard") }
            Match(r.optString("title"), r.optString("artist"), r.optString("album"), 100)
        } catch (e: Exception) {
            ServerLog.log("audd.io", "identify", "HTTPS", "", false, "Radio ${item.title}: ${e.message ?: e.javaClass.simpleName}"); null
        }
    }

    // ---------- radio: the identified song on the player (2026-11-28 request 38) ----------
    data class RadioShown(val uri: String, val artist: String, val title: String, val album: String)
    private val _radio = MutableStateFlow<RadioShown?>(null)
    /** Shown on the radio player in place of "Live" / the station's text, until the song looks over. */
    val radio: StateFlow<RadioShown?> = _radio
    private var watch: kotlinx.coroutines.Job? = null

    private fun showRadio(uri: String, np: String, m: Match) {
        _radio.value = RadioShown(uri, m.artist, m.title, m.album)
        watch?.cancel()
        watch = scope.launch { watchRadio(uri, np) }
    }

    private fun clearRadio(why: String) {
        val r = _radio.value ?: return
        _radio.value = null
        try { ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", true, "Radio: \"${r.artist} – ${r.title}\" taken off the player ($why)") } catch (_: Exception) {}
    }

    /**
     * Erased when the song looks over: pause / stop, another station, the station's own song text changing, a pause in
     * the sound (over 1.5 s near silence), or a brutal rhythm change — the bass beat's tempo moving more than 15 % for 3 s,
     * or the beat lost for 4 s and then a new one starting. The beat is followed in the sound engine's low band.
     */
    private suspend fun watchRadio(uri: String, np: String) {
        val buf = FloatArray(2)
        var avg = 0f; var lastOn = 0L; var tempo = 0f; var off = 0; var quietFrom = 0L; var notPlaying = 0; var lostAt = 0L
        val iv = ArrayDeque<Long>()
        kotlinx.coroutines.delay(3000)                          // the identification's own moment settles first
        while (true) {
            val s = App.instance.player.state.value
            if (s.item?.uri != uri) { clearRadio("another station"); return }
            if (!s.isPlaying) { if (++notPlaying >= 40) { clearRadio("paused / stopped"); return } } else notPlaying = 0
            if (s.userPaused) { clearRadio("paused"); return }
            if (s.nowPlaying != np) { clearRadio("the station's song text changed"); return }
            if (s.isPlaying && app.vlcinterface.player.NativeAudio.active) {
                val now = System.currentTimeMillis()
                app.vlcinterface.player.NativeAudio.bands(buf)
                val x = buf[0]
                if (x < 0.0008f) { if (quietFrom == 0L) quietFrom = now else if (now - quietFrom > 1500) { clearRadio("a pause in the sound — next song"); return } }
                else quietFrom = 0L
                avg += (x - avg) * 0.08f
                if (x > avg * 1.4f + 0.0005f && now - lastOn > 250) {
                    if (lostAt > 0) { clearRadio("the beat stopped and a new one started"); return }
                    if (lastOn > 0) { iv.addLast(now - lastOn); while (iv.size > 12) iv.removeFirst() }
                    lastOn = now
                }
                if (tempo > 0f && lastOn > 0 && now - lastOn > maxOf(4000f, tempo * 8f)) { if (lostAt == 0L) lostAt = now }
                if (iv.size >= 8) {
                    val med = iv.sorted()[iv.size / 2].toFloat()
                    if (tempo == 0f) tempo = med
                    else if (kotlin.math.abs(med - tempo) / tempo > 0.15f) { if (++off >= 60) { clearRadio("the rhythm changed — next song"); return } }
                    else { off = 0; tempo = tempo * 0.9f + med * 0.1f }
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }

    /** Yes on the question. */
    fun accept(ctx: Context) { val a = _ask.value ?: return; _ask.value = null; scope.launch { write(ctx.applicationContext, a.item, a.new) } }
    /** No, or the question timed out: nothing written. */
    fun decline(timedOut: Boolean) {
        val a = _ask.value ?: return; _ask.value = null
        ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", true, "${a.item.title}: " + if (timedOut) "question timed out — nothing written" else "kept the old names")
        if (timedOut) flash("Timed out")
    }

    private suspend fun write(ctx: Context, item: PlayItem, fields: Map<String, String>) {
        _status.value = "Writing…"
        val r = RgScanner.saveTags(ctx, item, fields)
        ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", !r.startsWith("not"),
            "${item.title} → ${fields.values.joinToString(" · ")}: $r")
        flash(if (r.startsWith("not")) "Not written" else if (r.startsWith("cached")) "After song ✓" else "Tags ✓")
    }
}
