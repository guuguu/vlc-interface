package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private var job: Job? = null
    @Volatile private var target = ""                            // uri being cached now
    private val done = java.util.concurrent.ConcurrentHashMap<String, File>()   // uri → complete copy
    private const val MAX_BYTES = 400L * 1024 * 1024             // bigger songs aren't cached
    private const val MIN_FREE = 2L * 1024 * 1024 * 1024         // never under 2 GB free

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "prefetch")
        dir.deleteRecursively(); dir.mkdirs()                     // a fresh start (also after a crash)
        scope.launch {
            App.instance.player.state
                .map { s -> s.index to s.queue.map { it.uri } }
                .distinctUntilChanged()
                .collect { update() }
        }
    }

    /** The complete local copy of [uri], or null. */
    fun localFor(uri: String): File? = done[uri]?.takeIf { it.isFile && it.length() > 0 }

    private fun isNetwork(p: PlayItem): Boolean = !p.isRadio && p.isMusic && (p.uri.startsWith("smb://") || p.uri.startsWith("http"))

    private fun allowed(p: PlayItem): Boolean = try {
        !(app.vlcinterface.player.NetGuard.blocked() && app.vlcinterface.player.NetGuard.needsInternet(p.uri)) &&
            !VpnLink.waitsForUse(p.uri) && !VpnLink.ignored(p.uri) &&
            !app.vlcinterface.player.BatteryGuard.busyBlocked(silent = true)
    } catch (_: Exception) { false }

    /** Queue or song changed: cache the next network song; drop copies no longer needed. */
    private fun update() {
        if (!::dir.isInitialized) return
        val s = App.instance.player.state.value
        val cur = s.queue.getOrNull(s.index)
        val next = s.queue.drop(s.index + 1).firstOrNull { isNetwork(it) && it.uri != cur?.uri && allowed(it) }
        val keep = setOfNotNull(cur?.uri, next?.uri)
        done.keys.filter { it !in keep }.forEach { k -> done.remove(k)?.delete() }
        if (next == null) { if (target !in keep) { job?.cancel(); target = "" }; return }
        if (next.uri == target || localFor(next.uri) != null) return
        job?.cancel()
        target = next.uri
        job = scope.launch { fetch(next) }
    }

    private fun fetch(p: PlayItem) {
        dir.mkdirs()
        if (dir.usableSpace < MIN_FREE) return
        val name = Integer.toHexString(p.uri.hashCode()) + "." + p.ext.ifEmpty { "bin" }
        val tmp = File(dir, "$name.part"); val dst = File(dir, name)
        try {
            val u = p.uri
            val (input, size) = when {
                u.startsWith("smb://") -> {
                    val f = SmbFile(VpnLink.route(Uri.decode(u)), SmbProbe.fileContext(p.smbVersion, p.user, p.password, p.domain))
                    f.inputStream to f.length()
                }
                else -> {
                    val r = Net.http.newCall(Request.Builder().url(u)
                        .apply { if (p.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(p.user, p.password)) }.build()).execute()
                    if (!r.isSuccessful) { r.close(); throw IllegalStateException("HTTP ${r.code}") }
                    r.body!!.byteStream() to (r.body?.contentLength() ?: -1L)
                }
            }
            if (size > MAX_BYTES) { input.close(); return }
            input.use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                while (scope.isActive && target == u) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n) }
            } }
            if (target != u || (size > 0 && tmp.length() != size)) { tmp.delete(); return }
            tmp.renameTo(dst)
            done[u] = dst
            ServerLog.log("cache", "next", "", "", true, "Next song cached: ${p.title}", "${dst.length() / 1024} KB")
        } catch (e: Exception) {
            tmp.delete()
        } finally { if (target == p.uri) target = "" }
    }

    /** Exit: copies removed. */
    fun clear() { job?.cancel(); target = ""; done.clear(); if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() } }
}
