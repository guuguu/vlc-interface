package app.vlcinterface.sources

import android.content.Context
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
            if (item.isRadio) {
                val (a, t) = split(nowPlaying)
                val m = if (t.isEmpty()) null else look(a, t)
                ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", m != null,
                    if (m == null) "Radio ${item.title}: \"${nowPlaying.ifEmpty { "no song name" }}\" — not found"
                    else "Radio ${item.title}: \"$nowPlaying\" → ${m.artist} – ${m.title}" + (if (m.album.isNotEmpty()) " (${m.album})" else "") + " · score ${m.score}")
                flash(if (m == null) "Not found · logged" else "Found · logged")
                return
            }
            // Song: its tags first, else its file name.
            val tagged = item.artist.isNotEmpty()
            val (a, t) = if (tagged) item.artist to item.title else split(item.fileName.ifEmpty { item.title }.substringBeforeLast('.').replace(Regex("^\\d{1,3}[ .\\-_]+"), ""))
            val m = look(a, t)
            if (m == null) {
                ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", false, "${item.title}: not found")
                flash("Not found"); return
            }
            val new = linkedMapOf("TITLE" to m.title, "ARTIST" to m.artist, "ALBUM" to m.album).filterValues { it.isNotBlank() }
            val old = if (tagged) linkedMapOf("TITLE" to item.title, "ARTIST" to item.artist, "ALBUM" to item.album).filterValues { it.isNotBlank() } else emptyMap()
            val changed = new.filter { (k, v) -> old[k] != v }
            if (changed.isEmpty()) { flash("Tags already match"); return }
            val clash = changed.keys.any { old.containsKey(it) }
            if (clash) {
                val sec = App.instance.store.current.settings.identifyAskSec
                _ask.value = Ask(item, old, new, System.currentTimeMillis() + sec * 1000L)
                _status.value = ""
            } else write(ctx, item, new)
        } finally { busy = false }
    }

    /** Yes on the question. */
    fun accept(ctx: Context) { val a = _ask.value ?: return; _ask.value = null; scope.launch { write(ctx.applicationContext, a.item, a.new) } }
    /** No, or the question timed out: nothing written. */
    fun decline(timedOut: Boolean) {
        val a = _ask.value ?: return; _ask.value = null
        ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", true, "${a.item.title}: " + if (timedOut) "question timed out — nothing written" else "kept the old names")
        if (timedOut) flash("Timed out · nothing written")
    }

    private suspend fun write(ctx: Context, item: PlayItem, fields: Map<String, String>) {
        _status.value = "Writing…"
        val r = RgScanner.saveTags(ctx, item, fields)
        ServerLog.log("musicbrainz.org", "identify", "HTTPS", "", !r.startsWith("not"),
            "${item.title} → ${fields.values.joinToString(" · ")}: $r")
        flash(if (r.startsWith("not")) "Not written" else if (r.startsWith("cached")) "Written after this song" else "Tags written")
    }
}
