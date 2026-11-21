package app.vlcinterface.sources

import app.vlcinterface.data.CrashLog

import android.content.Context
import android.net.Uri
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

/**
 * Network songs cached ahead: the next network song in the queue is copied to the phone while the current one
 * plays, and played from there. When the queue (or the song playing) changes, the new next network song is cached
 * instead. Only the playing song's copy and the next one are kept; all is emptied on start, exit and "Clear all caches".
 * Follows the network rules: nothing on mobile data unless allowed, no NAS while it can't be used, not on low battery.
 */
object Prefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.guard)
    private lateinit var dir: File
    private var job: Job? = null
    @Volatile private var target = ""                            // uri being cached now
    @Volatile private var gen = 0                                // each new start; an older copy in progress stops
    /** Queue changed less than 2 min after the previous change: no caching (network only) until 5 min without changes. */
    @Volatile private var pausedUntil = 0L
    private var lastQueueChange = 0L
    private var lastQueue: List<String>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private const val BUSY_MS = 2 * 60_000L
    private const val CALM_MS = 5 * 60_000L
    private val done = java.util.concurrent.ConcurrentHashMap<String, File>()   // uri → complete copy
    private const val MAX_BYTES = 100L * 1024 * 1024             // bigger songs aren't cached
    private const val MIN_FREE = 2L * 1024 * 1024 * 1024         // never under 2 GB free

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "prefetch")
        dir.deleteRecursively(); dir.mkdirs()                     // a fresh start (also after a crash)
        scope.launch {
            App.instance.player.state
                .map { s -> s.index to s.queue.map { it.uri } }
                .distinctUntilChanged()
                .collect { (_, q) -> onChange(q) }
        }
    }

    /** The complete local copy of [uri], or null (none while caching is paused: played from the network). */
    fun localFor(uri: String): File? = if (paused()) null else done[uri]?.takeIf { it.isFile && it.length() > 0 }

    private fun paused() = System.currentTimeMillis() < pausedUntil

    /**
     * The queue itself changed (added, removed, order — e.g. sequential ↔ random): what was being cached is dropped
     * and the new next song is cached from scratch. Changes closer than 2 min apart pause caching until the queue
     * has been left alone for 5 min.
     */
    @Synchronized private fun onChange(q: List<String>) {
        val now = System.currentTimeMillis()
        val changed = lastQueue != null && lastQueue != q
        lastQueue = q
        if (changed) {
            if (now - lastQueueChange < BUSY_MS || paused()) {
                if (!paused()) ServerLog.log("cache", "pause", "", "", true, "Caching ahead paused: queue changed often (network only for 5 min without changes)")
                pausedUntil = now + CALM_MS
                main.removeCallbacksAndMessages(null)
                main.postDelayed({ scope.launch { update() } }, CALM_MS + 500)
            }
            lastQueueChange = now
            restart()
        }
        update()
    }

    /** Copy in progress dropped (its partial file deleted). */
    private fun restart() { gen++; job?.cancel(); target = ""; if (::dir.isInitialized) dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }

    private fun isNetwork(p: PlayItem): Boolean = !p.isRadio && p.isMusic && (p.uri.startsWith("smb://") || p.uri.startsWith("http"))

    private fun allowed(p: PlayItem): Boolean = try {
        !(app.vlcinterface.player.NetGuard.blocked() && app.vlcinterface.player.NetGuard.needsInternet(p.uri)) &&
            !VpnLink.waitsForUse(p.uri) && !VpnLink.ignored(p.uri) && !VpnLink.heavyBlocked(p.uri) &&
            !app.vlcinterface.player.BatteryGuard.busyBlocked(silent = true)
    } catch (_: Exception) { false }

    /** Queue or song changed: cache the next network song; drop copies no longer needed. */
    @Synchronized private fun update() {
        if (!::dir.isInitialized) return
        val s = App.instance.player.state.value
        val cur = s.queue.getOrNull(s.index)
        val next = s.queue.drop(s.index + 1).firstOrNull { isNetwork(it) && it.uri != cur?.uri && allowed(it) }
        if (paused()) {
            restart()
            done.keys.filter { it != cur?.uri }.forEach { k -> done.remove(k)?.delete() }
            return
        }
        val keep = setOfNotNull(cur?.uri, next?.uri)
        done.keys.filter { it !in keep }.forEach { k -> done.remove(k)?.delete() }
        if (next == null) { if (target !in keep) { job?.cancel(); target = "" }; return }
        if (next.uri == target || localFor(next.uri) != null) return
        job?.cancel()
        target = next.uri
        val g = ++gen
        job = scope.launch { fetch(next, g) }
    }

    private fun fetch(p: PlayItem, g: Int) {
        dir.mkdirs()
        if (dir.usableSpace < MIN_FREE) return
        val name = Integer.toHexString(p.uri.hashCode()) + "." + p.ext.ifEmpty { "bin" }
        val tmp = File(dir, "$name.part"); val dst = File(dir, name)
        try {
            val u = p.uri
            // Several connections at once, tuned to the bandwidth; playback keeps priority (see ParallelFetch).
            val ok = ParallelFetch.download(p, tmp, MAX_BYTES, keepGoing = { gen == g && !paused() }, what = "cache ahead") { if (p.uri.startsWith("http")) single(p, tmp, g) else false }
            if (!ok || gen != g || paused()) { tmp.delete(); return }
            tmp.renameTo(dst)
            done[u] = dst
        } catch (e: Exception) {
            tmp.delete()
        } finally { if (gen == g) target = "" }
    }

    /** One plain connection (a web server that can't serve pieces). */
    private fun single(p: PlayItem, tmp: File, g: Int): Boolean {
        val u = p.uri
        val r = Net.http.newCall(Request.Builder().url(u)
            .apply { if (p.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(p.user, p.password)) }.build()).execute()
        r.use {
            if (!r.isSuccessful) return false
            val size = r.body?.contentLength() ?: -1L
            if (size > MAX_BYTES) return false
            r.body!!.byteStream().use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                while (gen == g && !paused()) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n) }
            } }
            return gen == g && !paused() && (size <= 0 || tmp.length() == size)
        }
    }

    /** Look again now (e.g. VPN-NAS unlocked on Wi-Fi). */
    fun kick() { if (::dir.isInitialized) scope.launch { update() } }

    /** Exit: copies removed. */
    fun clear() { job?.cancel(); target = ""; done.clear(); if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() } }
}
