package app.vlcinterface.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val at: Long,
    val action: String,
    val protocol: String,
    val user: String,
    val ok: Boolean,
    val message: String,
    val detail: String = "",
    val ms: Long = 0,
)

data class ServerInfo(val key: String, val host: String, val protocols: Set<String>, val count: Int, val last: LogEntry?)

/** One separate log per network server (saved or only attempted). Never contains passwords. */
object ServerLog {
    private const val MAX = 1000
    private lateinit var dir: File
    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so screens can refresh. */
    val version: StateFlow<Int> = _version

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        Thread { purgeOld() }.apply { isDaemon = true }.start()
    }

    // ---------- monthly purge (2026-11-21 12:10) ----------
    /** Entries older than this are removed, oldest first, from every log (crashes and errors included). */
    private const val KEEP_MS = 30L * 86_400_000L
    @Volatile private var lastPurge = 0L

    /** Removes every entry older than a month from every log (an emptied log is deleted). Runs at start and daily. */
    @Synchronized
    fun purgeOld() {
        if (!::dir.isInitialized) return
        val cut = System.currentTimeMillis() - KEEP_MS
        lastPurge = System.currentTimeMillis()
        var changed = false
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".jsonl") }.forEach { f ->
            try {
                val lines = f.readLines().filter { it.isNotBlank() }
                // Lines are in time order: the old ones are at the start, dropped first.
                val keep = lines.dropWhile { l -> (try { JSONObject(l).optLong("at") } catch (_: Exception) { 0L }) < cut }
                if (keep.size != lines.size) {
                    changed = true
                    if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            } catch (_: Exception) {}
        }
        if (changed) _version.value++
    }

    /** One page per server: "smb://user@nas:445/share/x", "nas/share" and "NAS" all give "nas". */
    fun keyOf(host: String): String = host.lowercase().replace(Regex("^[a-z0-9+.-]+://"), "")
        .substringBefore('/').substringAfterLast('@').let { a -> if (a.count { it == ':' } == 1) a.substringBefore(':') else a }
        .ifEmpty { "unknown" }.replace(Regex("[^a-z0-9._:-]"), "_")

    private fun file(key: String) = File(dir, "$key.jsonl")

    @Synchronized
    fun add(host: String, e: LogEntry) {
        if (!::dir.isInitialized) return
        val f = file(keyOf(host))
        val o = JSONObject().put("at", e.at).put("action", e.action).put("protocol", e.protocol).put("user", e.user)
            .put("ok", e.ok).put("message", e.message).put("detail", e.detail).put("ms", e.ms).put("host", host)
        f.appendText(o.toString() + "\n")
        val lines = f.readLines()
        if (lines.size > MAX + 100) f.writeText(lines.takeLast(MAX).joinToString("\n", postfix = "\n"))
        _version.value++
        if (System.currentTimeMillis() - lastPurge > 86_400_000L) Thread { purgeOld() }.apply { isDaemon = true }.start()
    }

    fun log(host: String, action: String, protocol: String, user: String, ok: Boolean, message: String, detail: String = "", startedAt: Long = 0) =
        add(host, LogEntry(System.currentTimeMillis(), action, protocol, user, ok, message, detail,
            if (startedAt > 0) System.currentTimeMillis() - startedAt else 0))

    fun entries(key: String): List<LogEntry> {
        val f = file(key)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            try {
                val o = JSONObject(l)
                LogEntry(o.optLong("at"), o.optString("action"), o.optString("protocol"), o.optString("user"),
                    o.optBoolean("ok"), o.optString("message"), o.optString("detail"), o.optLong("ms"))
            } catch (_: Exception) { null }
        }.reversed()
    }

    /** Every server with a log, plus every [saved] server (host to protocol) even before anything was logged. */
    fun servers(saved: List<Pair<String, String>> = emptyList()): List<ServerInfo> {
        if (!::dir.isInitialized) return emptyList()
        val logged = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".jsonl") }.map { f ->
            val key = f.name.removeSuffix(".jsonl")
            val es = entries(key)
            ServerInfo(key, key, es.map { it.protocol.substringBefore(' ') }.filter { it.isNotEmpty() }.toSet(), es.size, es.firstOrNull())
        }
        val known = logged.map { it.key }.toSet()
        val empty = saved.filter { (h, _) -> h.isNotBlank() && keyOf(h) !in known }.distinctBy { keyOf(it.first) }
            .map { (h, p) -> ServerInfo(keyOf(h), keyOf(h), setOf(p.uppercase()), 0, null) }
        return logged.sortedByDescending { it.last?.at ?: 0 } + empty
    }

    /** Every log there is (one per server, plus "deleted-files", "replaygain", "sound-engine", "player"…). */
    fun allKeys(): List<String> = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".jsonl") }.map { it.name.removeSuffix(".jsonl") }

    /** Fixed log names. */
    const val DELETED = "deleted-files"
    const val REPLAYGAIN = "replaygain"

    fun clear(key: String) {
        file(key).writeText("")
        _version.value++
    }

    /** Text export: first line "generated by …" as the user wants for every log file. */
    fun exportText(key: String, appVersion: String): Pair<String, String> {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val body = StringBuilder("generated by vlc interface $appVersion\n")
        body.append("server: $key\n\n")
        entries(key).reversed().forEach { e ->
            body.append("${fmt.format(Date(e.at))}  ${if (e.ok) "OK  " else "FAIL"}  ${e.action}  ${e.protocol}  user=${e.user.ifEmpty { "guest" }}  ${e.message}")
            if (e.detail.isNotEmpty()) body.append("  [${e.detail}]")
            if (e.ms > 0) body.append("  ${e.ms} ms")
            body.append('\n')
        }
        return "vlc-interface-log_${key}_$ts.txt" to body.toString()
    }
}
