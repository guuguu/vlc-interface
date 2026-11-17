package app.vlcinterface.sources

import android.net.Uri
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Network files copied with several connections at once (1 MB pieces), for caching ahead and ReplayGain scans /
 * updates. At the start of each copy the number of connections is tuned to the bandwidth: one more is added every
 * 1.5 s while that gives clearly more speed (+15 %), one less if it gave less; the best count per server is
 * remembered for the session. Playback stays first: the copy runs at background priority, and while the playing
 * song comes from the network the copy keeps to ¾ of the measured speed and gives up a connection when playback
 * buffers. Over SMB every connection is a real one: its own login and TCP connection to the server.
 */
object ParallelFetch {
    private const val PIECE = 1 shl 20
    private const val MAX_THREADS = 8
    private const val STEP_MS = 1500L
    /** What was copied this playing session, per server and kind (for the one report in Logs). */
    private class Stat { var files = 0; var bytes = 0L; var ms = 0L; var failed = 0 }
    private val stats = java.util.concurrent.ConcurrentHashMap<String, Stat>()

    private fun record(host: String, what: String, bytes: Long, ms: Long, ok: Boolean) {
        val st = stats.getOrPut("$host|$what") { Stat() }
        synchronized(st) { if (ok) { st.files++; st.bytes += bytes; st.ms += ms } else st.failed++ }
    }

    /**
     * Once per playing session (exit, or the player unused for an hour): one report in Logs — per server and kind
     * (caching ahead, ReplayGain reads, ReplayGain updates, indexing): files, MB, average speed, connections used.
     */
    fun report() {
        if (stats.isEmpty() && gateBest.isEmpty()) return
        val lines = stats.entries.sortedBy { it.key }.map { (k, st) ->
            val (host, what) = k.split('|', limit = 2)
            val mb = st.bytes / 1048576.0
            val speed = if (st.ms > 0) st.bytes * 1000.0 / st.ms / 1048576 else 0.0
            "$what · $host: ${st.files} file(s), ${"%.1f".format(java.util.Locale.US, mb)} MB, " +
                "${"%.1f".format(java.util.Locale.US, speed)} MB/s, ${best[host] ?: 1} connection(s)" + if (st.failed > 0) ", ${st.failed} failed" else ""
        } + gateBest.map { (h, n) -> "indexing · $h: $n at once" }
        ServerLog.log("transfer", "report", "", "", true, "Network transfers this session", lines.joinToString("\n"))
        stats.clear()
    }

    /** Best connection count found per server (this session). */
    private val best = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private interface Remote { val size: Long; fun reader(lane: Int): Reader }
    private interface Reader : AutoCloseable { fun readAt(pos: Long, buf: ByteArray, len: Int): Int }
    private interface Writer : AutoCloseable { fun writeAt(lane: Int, pos: Long, buf: ByteArray, len: Int) }

    private fun hostOf(uri: String) = try { Uri.parse(uri).host ?: "" } catch (_: Exception) { "" }

    private fun smbFile(p: PlayItem, uri: String = p.uri) = SmbFile(VpnLink.route(Uri.decode(uri)), SmbProbe.fileContext(p.smbVersion, p.user, p.password, p.domain))
    /** The same file through lane [lane]'s own connection. */
    private fun smbLane(p: PlayItem, uri: String, lane: Int) = SmbFile(VpnLink.route(Uri.decode(uri)), SmbProbe.laneContext(p.smbVersion, p.user, p.password, p.domain, lane))

    private fun remote(p: PlayItem): Remote? {
        val u = p.uri
        return when {
            u.startsWith("smb://") -> {
                val f = smbFile(p); val len = f.length()
                object : Remote {
                    override val size = len
                    override fun reader(lane: Int) = object : Reader {
                        val raf = smbLane(p, u, lane).openRandomAccess("r") as SmbRandomAccessFile
                        override fun readAt(pos: Long, buf: ByteArray, len: Int): Int { raf.seek(pos); return raf.read(buf, 0, len) }
                        override fun close() { try { raf.close() } catch (_: Exception) {} }
                    }
                }
            }
            u.startsWith("http") -> {
                fun req(range: String?) = Request.Builder().url(u).apply {
                    if (p.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(p.user, p.password))
                    if (range != null) header("Range", range)
                }.build()
                // Pieces need byte ranges: a server without them gets one plain copy.
                val len = Net.http.newCall(req("bytes=0-0")).execute().use { r ->
                    if (r.code != 206) return null
                    r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: return null
                }
                object : Remote {
                    override val size = len
                    override fun reader(lane: Int) = object : Reader {
                        override fun readAt(pos: Long, buf: ByteArray, len: Int): Int =
                            Net.http.newCall(req("bytes=$pos-${pos + len - 1}")).execute().use { r ->
                                if (r.code != 206) throw IllegalStateException("HTTP ${r.code}")
                                val s = r.body!!.byteStream(); var n = 0
                                while (n < len) { val k = s.read(buf, n, len - n); if (k < 0) break; n += k }
                                n
                            }
                        override fun close() {}
                    }
                }
            }
            else -> null
        }
    }

    /** The song playing comes from the network right now (not from the phone or a cached copy). */
    private fun playingFromNetwork(): Boolean = try {
        val s = App.instance.player.state.value
        val it = s.item
        it != null && s.isPlaying &&
            (it.isRadio || ((it.uri.startsWith("smb://") || it.uri.startsWith("http")) && Prefetch.localFor(it.uri) == null))
    } catch (_: Exception) { false }

    private fun playerBuffering(): Boolean = try { App.instance.player.state.value.let { it.isPlaying && it.buffering < 100f } } catch (_: Exception) { false }

    /**
     * Copies [p] to [dst] with tuned parallel connections. [keepGoing] is checked between pieces (false: stop, the
     * partial file is left for the caller to delete). [maxBytes] > 0: bigger files aren't copied. Returns true when
     * the copy is complete; false when it stopped, was too big, or the server can't serve pieces (then [fallback]
     * is used if given).
     */
    fun download(p: PlayItem, dst: File, maxBytes: Long = 0, keepGoing: () -> Boolean = { true }, what: String = "copy",
                 progress: (Long, Long) -> Unit = { _, _ -> }, fallback: (() -> Boolean)? = null): Boolean {
        val r = try { remote(p) } catch (e: Exception) { null }
        if (r == null) return fallback?.invoke() ?: false
        if (maxBytes > 0 && r.size > maxBytes) return false
        RandomAccessFile(dst, "rw").use { out ->
            out.setLength(r.size)
            val w = object : Writer {
                override fun writeAt(lane: Int, pos: Long, buf: ByteArray, len: Int) = synchronized(out) { out.seek(pos); out.write(buf, 0, len) }
                override fun close() {}
            }
            val t0 = System.currentTimeMillis()
            return run(hostOf(p.uri), r.size, { lane -> r.reader(lane) }, w, keepGoing, progress, "Copied ${p.title}")
                .also { record(hostOf(p.uri), what, r.size, System.currentTimeMillis() - t0, it) }
        }
    }

    /**
     * Writes [src] over the network file [targetUri] (SMB) in pieces with tuned parallel connections. Returns false
     * when it isn't an SMB file (the caller sends it its own way).
     */
    fun uploadSmb(p: PlayItem, src: File, targetUri: String, progress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        if (!targetUri.startsWith("smb://")) return false
        val f = smbFile(p, targetUri)
        if (!f.exists()) f.createNewFile()
        (f.openRandomAccess("rw") as SmbRandomAccessFile).use { it.setLength(src.length()) }
        RandomAccessFile(src, "r").use { input ->
            val reader = { _: Int ->
                object : Reader {
                    override fun readAt(pos: Long, buf: ByteArray, len: Int): Int = synchronized(input) { input.seek(pos); input.read(buf, 0, len) }
                    override fun close() {}
                }
            }
            // Each lane writes through its own connection and handle.
            val handles = java.util.concurrent.ConcurrentHashMap<Int, SmbRandomAccessFile>()
            val w = object : Writer {
                override fun writeAt(lane: Int, pos: Long, buf: ByteArray, len: Int) {
                    val h = handles.getOrPut(lane) { smbLane(p, targetUri, lane).openRandomAccess("rw") as SmbRandomAccessFile }
                    synchronized(h) { h.seek(pos); h.write(buf, 0, len) }
                }
                override fun close() { handles.values.forEach { try { it.close() } catch (_: Exception) {} } }
            }
            try {
                val t0 = System.currentTimeMillis()
                val ok = run(hostOf(targetUri), src.length(), reader, w, { true }, progress, "Sent ${p.title}")
                record(hostOf(targetUri), "ReplayGain update", src.length(), System.currentTimeMillis() - t0, ok)
                if (!ok) throw IllegalStateException("copy to the server stopped")
            } finally { w.close() }
        }
        return true
    }

    /** The piece workers, their count tuned while they run. */
    private fun run(host: String, size: Long, newReader: (Int) -> Reader, w: Writer, keepGoing: () -> Boolean,
                    progress: (Long, Long) -> Unit, what: String): Boolean {
        val pieces = ((size + PIECE - 1) / PIECE).toInt()
        if (pieces == 0) return true
        val nextPiece = AtomicInteger(0)
        val doneBytes = AtomicLong(0)
        val failed = AtomicInteger(0)
        val allowed = AtomicInteger(best[host] ?: 1)
        val running = AtomicInteger(0)
        val stopF = java.util.concurrent.atomic.AtomicBoolean(false)
        val holdForPlay = java.util.concurrent.atomic.AtomicBoolean(false)
        val retry = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        // ¾ of the measured speed while playback comes from the network (bytes per second; 0 = no limit).
        val limit = AtomicLong(0)
        val windowStart = AtomicLong(System.currentTimeMillis()); val windowBytes = AtomicLong(0)

        // Lanes: each worker its own connection (SMB: own login and TCP connection), handed back when it stops.
        val freeLanes = java.util.concurrent.ConcurrentLinkedQueue((0 until MAX_THREADS).toList())
        fun worker() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val lane: Int = freeLanes.poll() ?: -1
            if (lane < 0) { running.decrementAndGet(); return }
            val rd = try { newReader(lane) } catch (e: Exception) { failed.incrementAndGet(); freeLanes += lane; running.decrementAndGet(); return }
            val buf = ByteArray(PIECE)
            try {
                while (!stopF.get()) {
                    if (running.get() > allowed.get()) break              // fewer connections asked for: this one ends
                    while (holdForPlay.get() && !stopF.get()) Thread.sleep(500)   // playback stutters: all wait
                    val i = retry.poll() ?: nextPiece.getAndIncrement().takeIf { it < pieces } ?: break
                    val pos = i.toLong() * PIECE
                    val len = minOf(PIECE.toLong(), size - pos).toInt()
                    var n = 0
                    try {
                        val tmp = ByteArray(PIECE)
                        while (n < len) {                                 // short reads: the rest of the piece read on
                            val k = rd.readAt(pos + n, tmp, len - n); if (k <= 0) break
                            System.arraycopy(tmp, 0, buf, n, k); n += k
                        }
                        if (n < len) throw IllegalStateException("short read")
                        w.writeAt(lane, pos, buf, len)
                    } catch (e: Exception) {
                        retry += i
                        if (failed.incrementAndGet() > pieces / 4 + 8) { stopF.set(true) }
                        break
                    }
                    doneBytes.addAndGet(len.toLong()); windowBytes.addAndGet(len.toLong())
                    progress(doneBytes.get(), size)
                    // Playback first: kept under the limit.
                    val lim = limit.get()
                    if (lim > 0) {
                        val el = System.currentTimeMillis() - windowStart.get()
                        val ahead = windowBytes.get() * 1000 / lim - el
                        if (ahead > 0) Thread.sleep(minOf(ahead, 2000))
                    }
                }
            } finally { try { rd.close() } catch (_: Exception) {}; freeLanes += lane; running.decrementAndGet() }
        }

        val threads = java.util.Collections.synchronizedList(ArrayList<Thread>())
        fun spawn() { running.incrementAndGet(); threads += Thread { worker() }.also { it.isDaemon = true; it.start() } }
        repeat(allowed.get()) { spawn() }

        /*
         * Connections set while the copy runs, every 1.5 s:
         *  - start: one more while speed rises clearly (+15 %), then kept;
         *  - playback stutters (buffering): one connection ended per step; at one left the copy waits until playback
         *    has been smooth for 10 s;
         *  - the network doesn't keep up (errors, or speed under 60 % of the best seen): one ended per step;
         *  - all well for 20 s: one more tried, kept only if it brings +10 %.
         */
        var tuning = best[host] == null
        var peak = 0.0; var lastSpeed = 0.0; var lastBytes = 0L; var lastAt = System.currentTimeMillis()
        var lastFailed = 0; var lastStutter = 0L; var calmSince = System.currentTimeMillis()
        var trying = false; var before = 0.0
        fun drop() { if (allowed.get() > 1) allowed.decrementAndGet(); calmSince = System.currentTimeMillis(); trying = false }
        while (true) {
            Thread.sleep(250)
            if (!keepGoing()) { stopF.set(true); holdForPlay.set(false) }
            val alive = synchronized(threads) { threads.any { it.isAlive } }
            if (!alive && (retry.isEmpty() || stopF.get())) break
            if (!alive && retry.isNotEmpty() && !stopF.get()) { spawn(); continue }
            val now = System.currentTimeMillis()
            // Stutter is checked often: playback comes first.
            if (playerBuffering()) {
                if (now - lastStutter > 1000) {
                    lastStutter = now
                    if (allowed.get() > 1) drop() else holdForPlay.set(true)
                }
                continue
            }
            if (holdForPlay.get() && now - lastStutter > 10_000) { holdForPlay.set(false); calmSince = now }
            if (now - lastAt < STEP_MS) continue
            val speed = (doneBytes.get() - lastBytes) * 1000.0 / (now - lastAt)
            lastBytes = doneBytes.get(); lastAt = now
            windowStart.set(now); windowBytes.set(0)
            if (holdForPlay.get()) continue
            limit.set(if (playingFromNetwork() && peak > 0) (peak * 0.75).toLong() else 0)
            val errs = failed.get() - lastFailed; lastFailed = failed.get()
            val n = allowed.get()
            when {
                tuning -> {
                    if (lastSpeed == 0.0 || speed > lastSpeed * 1.15) {
                        if (n < MAX_THREADS && nextPiece.get() < pieces) { allowed.set(n + 1); spawn() } else { tuning = false; best[host] = n }
                    } else {
                        if (speed < lastSpeed * 0.95 && n > 1) allowed.set(n - 1)
                        best[host] = allowed.get(); tuning = false; calmSince = now
                    }
                }
                errs > 0 || (peak > 0 && speed < peak * 0.6 && n > 1) -> { drop(); best[host] = allowed.get() }   // network can't keep up
                trying -> {                                         // one more was tried: kept if it paid off
                    trying = false
                    if (speed < before * 1.10) allowed.decrementAndGet() else best[host] = allowed.get()
                    calmSince = now
                }
                now - calmSince > 20_000 && n < MAX_THREADS && nextPiece.get() < pieces -> {
                    trying = true; before = speed; allowed.set(n + 1); spawn()
                }
            }
            peak = maxOf(peak * 0.9, speed); lastSpeed = speed
        }
        synchronized(threads) { threads.toList() }.forEach { it.join(3000) }
        if (tuning && best[host] == null) best[host] = allowed.get()
        val complete = doneBytes.get() >= size && !stopF.get()
        return complete
    }

    /**
     * A limit on how many network operations run at once (indexing: folder listings and tag reads), set like the
     * copies while indexing runs: starts at the count remembered for the server (else 2), up while it gets clearly
     * faster, down one at a time when playback stutters or the network can't keep up. Used like a Semaphore.
     */
    class Gate(private val host: String, private val counter: AtomicLong) {
        private val max = 16
        val sem = kotlinx.coroutines.sync.Semaphore(max)
        private val held = java.util.ArrayDeque<Unit>()
        @Volatile var allowed = 0; private set
        private var tuner: kotlinx.coroutines.Job? = null

        suspend fun start(scope: kotlinx.coroutines.CoroutineScope) {
            val start = (gateBest[host] ?: 2).coerceIn(1, max)
            repeat(max - start) { sem.acquire(); held.add(Unit) }
            allowed = start
            tuner = scope.launch {
                // Same rules as the copies: up while it pays off; down one per step when playback stutters or the
                // network can't keep up (under 60 % of the best); one more tried after 30 s of calm.
                var last = 0.0; var peak = 0.0; var lastCount = counter.get(); var at = System.currentTimeMillis()
                var tuning = gateBest[host] == null; var calm = at; var trying = false; var before = 0.0
                suspend fun down() { if (allowed > 1) { sem.acquire(); held.add(Unit); allowed-- }; calm = System.currentTimeMillis(); trying = false }
                fun up() { if (allowed < max && held.isNotEmpty()) { held.poll(); sem.release(); allowed++ } }
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val now = System.currentTimeMillis()
                    if (playerBuffering()) { down(); continue }
                    if (now - at < 3000) continue
                    val c = counter.get()
                    val speed = (c - lastCount) * 1000.0 / (now - at); lastCount = c; at = now
                    if (speed <= 0.0) continue                       // nothing measured (long listing): wait
                    when {
                        tuning -> if (last == 0.0 || speed > last * 1.15) { if (allowed < max) up() else tuning = false }
                                  else { if (speed < last * 0.95) down(); tuning = false; gateBest[host] = allowed; calm = now }
                        peak > 0 && speed < peak * 0.6 && allowed > 1 -> { down(); gateBest[host] = allowed }
                        trying -> { trying = false; if (speed < before * 1.10) down() else gateBest[host] = allowed; calm = now }
                        now - calm > 30_000 && allowed < max -> { trying = true; before = speed; up() }
                    }
                    peak = maxOf(peak * 0.9, speed); last = speed
                }
            }
        }

        fun stop() { tuner?.cancel() }
    }
    private val gateBest = java.util.concurrent.ConcurrentHashMap<String, Int>()
}
