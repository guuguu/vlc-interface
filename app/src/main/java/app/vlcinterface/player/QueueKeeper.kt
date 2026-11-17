package app.vlcinterface.player

import android.content.Context
import app.vlcinterface.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

/**
 * The queue and the song in it (with its position) kept over a restart of the app or the phone: written while it
 * changes (and every few seconds of playback), brought back paused at the same spot when the app opens. Passwords
 * aren't written: they come from the source (Libraries) when the queue is brought back.
 */
object QueueKeeper {
    private lateinit var dir: File
    /** Kept per kind — "audio", "video", "radio" — so each player has its own last queue to come back to. */
    private fun file(kind: String) = File(dir, "queue-$kind.json")
    private val lastKindFile get() = File(dir, "queue-last.txt")

    fun kindOf(it: PlayItem?): String = when { it == null -> ""; it.isRadio -> "radio"; it.isMusic -> "audio"; else -> "video" }
    fun has(kind: String) = ::dir.isInitialized && file(kind).isFile
    @Volatile private var lastKey = ""
    @Volatile private var lastSave = 0L

    fun init(ctx: Context) {
        dir = ctx.filesDir
        File(dir, "queue.json").let { old -> if (old.isFile) { val k = "audio"; if (!file(k).exists()) old.renameTo(file(k)) else old.delete() } }
        val p = App.instance.player
        // Saved when the queue, the song or the order changes; the position every 5 s while playing.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            p.state.collect { s ->
                if (s.queue.isEmpty()) return@collect                      // a stop doesn't wipe what was kept
                val key = "${s.index}|${s.queue.size}|${s.queue.firstOrNull()?.uri}|${s.queue.getOrNull(s.index)?.uri}|${s.origin}|${s.isPlaying}"
                val now = System.currentTimeMillis()
                if (key != lastKey || now - lastSave > 5_000) {
                    lastKey = key; lastSave = now
                    save(s, p.originalOrder())
                }
            }
        }
    }

    private fun itemJson(it: PlayItem) = JSONObject().put("uri", it.uri).put("title", it.title).put("radio", it.isRadio)
        .put("folderKey", it.folderKey).put("sourceId", it.sourceId).put("user", it.user).put("artist", it.artist)
        .put("album", it.album).put("trackNo", it.trackNo).put("durationMs", it.durationMs).put("startMs", it.startMs)
        .put("endMs", it.endMs).put("artwork", it.artwork).put("smbVersion", it.smbVersion).put("domain", it.domain)
        .put("fileName", it.fileName)

    private fun itemOf(o: JSONObject): PlayItem {
        val src = App.instance.store.current.sources.firstOrNull { it.id == o.optString("sourceId") }
        return PlayItem(o.getString("uri"), o.optString("title"), o.optBoolean("radio"), o.optString("folderKey"),
            o.optString("sourceId"), src?.user ?: o.optString("user"), src?.password ?: "", o.optString("artist"),
            o.optString("album"), o.optInt("trackNo"), o.optLong("durationMs"), o.optLong("startMs", -1), o.optLong("endMs", -1),
            o.optString("artwork"), o.optString("smbVersion"), src?.domain ?: o.optString("domain"), o.optString("fileName"))
    }

    /** Written now (also from exit, before the player stops). */
    fun save(s: PlayerState, ordered: List<PlayItem>?) {
        if (!::dir.isInitialized || s.queue.isEmpty()) return
        val kind = kindOf(s.queue.getOrNull(s.index)).ifEmpty { return }
        val file = file(kind)
        try {
            val o = JSONObject()
                .put("queue", JSONArray().apply { s.queue.forEach { put(itemJson(it)) } })
                .put("index", s.index).put("time", s.time)
                .put("origin", JSONObject().put("kind", s.origin.kind).put("sourceId", s.origin.sourceId).put("uri", s.origin.uri)
                    .put("title", s.origin.title).put("playlistId", s.origin.playlistId).put("localFolderId", s.origin.localFolderId))
            if (ordered != null) o.put("ordered", JSONArray().apply { ordered.forEach { put(itemJson(it)) } })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString()); tmp.renameTo(file)
            lastKindFile.writeText(kind)
        } catch (_: Exception) {}
    }

    /**
     * The kept queue of [kind] back (at app start: the kind used last), its song ready and paused at the kept
     * position. [force]: replaces what's loaded now (the player button of another kind).
     */
    fun restore(p: PlayerController, kind: String? = null, force: Boolean = false): Boolean {
        if (!::dir.isInitialized) return false
        val k = kind ?: try { lastKindFile.readText().trim() } catch (_: Exception) { "audio" }
        val file = file(k)
        if (!file.isFile) return false
        try {
            val o = JSONObject(file.readText())
            val qa = o.getJSONArray("queue")
            val q = (0 until qa.length()).map { itemOf(qa.getJSONObject(it)) }
            if (q.isEmpty()) return false
            val ordered = o.optJSONArray("ordered")?.let { a -> (0 until a.length()).map { itemOf(a.getJSONObject(it)) } }
            val og = o.optJSONObject("origin")
            val origin = if (og == null) Origin() else Origin(og.optString("kind", "single"), og.optString("sourceId"), og.optString("uri"),
                og.optString("title"), og.optString("playlistId"), og.optString("localFolderId"))
            p.restoreQueue(q, o.optInt("index").coerceIn(0, q.size - 1), o.optLong("time"), origin, ordered, force)
            return true
        } catch (_: Exception) { return false }
    }
}
