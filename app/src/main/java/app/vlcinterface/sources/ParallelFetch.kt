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
 * buffers.
 */
object ParallelFetch {
    private const val PIECE = 1 shl 20
    private const val MAX_THREADS = 8
    private const val STEP_MS = 1500L
    /** Best connection count found per server (this session). */
    private val best = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private interface Remote { val size: Long; fun reader(): Reader }
    private interface Reader : AutoCloseable { fun readAt(pos: Long, buf: ByteArray, len: Int): Int }
    private interface Writer : AutoCloseable { fun writeAt(pos: Long, buf: ByteArray, len: Int) }

    private fun hostOf(uri: String) = try { Uri.parse(uri).host ?: "" } catch (_: Exception) { "" }

    private fun smbFile(p: PlayItem, uri: String = p.uri) = SmbFile(VpnLink.route(Uri.decode(uri)), SmbProbe.fileContext(p.smbVersion, p.user, p.password, p.domain))

    private fun remote(p: PlayItem): Remote? {
        val u = p.uri
        return when {
            u.startsWith("smb://") -> {
                val f = smbFile(p); val len = f.length()
                object : Remote {
                    override val size = len
                    override fun reader() = object : Reader {
                        val raf = f.openRandomAccess("r") as SmbRandomAccessFile
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
                    override fun reader() = object : Reader {
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
    fun download(p: PlayItem, dst: File, maxBytes: Long = 0, keepGoing: () -> Boolean = { true },
                 progress: (Long, Long) -> Unit = { _, _ -> }, fallback: (() -> Boolean)? = null): Boolean {
        val r = try { remote(p) } catch (e: Exception) { null }
        if (r == null) return fallback?.invoke() ?: false
        if (maxBytes > 0 && r.size > maxBytes) return false
        RandomAccessFile(dst, "rw").use { out ->
            out.setLength(r.size)
            val w = object : Writer {
                override fun writeAt(pos: Long, buf: ByteArray, len: Int) = synchronized(out) { out.seek(pos); out.write(buf, 0, len) }
                override fun close() {}
            }
            return run(hostOf(p.uri), r.size, { r.reader() }, w, keepGoing, progress, "Copied ${p.title}")
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
            val reader = {
                object : Reader {
                    override fun readAt(pos: Long, buf: ByteArray, len: Int): Int = synchronized(input) { input.seek(pos); input.read(buf, 0, len) }
                    override fun close() {}
                }
            }
            // Each connection writes through its own handle.
            val handles = java.util.concurrent.ConcurrentLinkedQueue<SmbRandomAccessFile>()
            val tl = ThreadLocal<SmbRandomAccessFile>()
            val w = object : Writer {
                override fun writeAt(pos: Long, buf: ByteArray, len: Int) {
                    val h = tl.get() ?: (f.openRandomAccess("rw") as SmbRandomAccessFile).also { tl.set(it); handles += it }
                    h.seek(pos); h.write(buf, 0, len)
                }
                override fun close() { handles.forEach { try { it.close() } catch (_: Exception) {} } }
            }
            try {
                if (!run(hostOf(targetUri), src.length(), reader, w, { true }, progress, "Sent ${p.title}")) throw IllegalStateException("copy to the server stopped")
            } finally { w.close() }
        }
        return true
    }

    /** The piece workers, their count tuned while they run. */
    private fun run(host: String, size: Long, newReader: () -> Reader, w: Writer, keepGoing: () -> Boolean,
                    progress: (Long, Long) -> Unit, what: String): Boolean {
        val pieces = ((size + PIECE - 1) / PIECE).toInt()
        if (pieces == 0) return true
        val nextPiece = AtomicInteger(0)
        val doneBytes = AtomicLong(0)
        val failed = AtomicInteger(0)
        val allowed = AtomicInteger(best[host] ?: 1)
        val running = AtomicInteger(0)
        val stopF = java.util.concurrent.atomic.AtomicBoolean(false)
        val retry = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        // ¾ of the measured speed while playback comes from the network (bytes per second; 0 = no limit).
        val limit = AtomicLong(0)
        val windowStart = AtomicLong(System.currentTimeMillis()); val windowBytes = AtomicLong(0)

        fun worker() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val rd = try { newReader() } catch (e: Exception) { failed.incrementAndGet(); running.decrementAndGet(); return }
            val buf = ByteArray(PIECE)
            try {
                while (!stopF.get()) {
                    if (running.get() > allowed.get()) break              // fewer connections asked for
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
                        w.writeAt(pos, buf, len)
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
            } finally { try { rd.close() } catch (_: Exception) {}; running.decrementAndGet() }
        }

        val threads = java.util.Collections.synchronizedList(ArrayList<Thread>())
        fun spawn() { running.incrementAndGet(); threads += Thread { worker() }.also { it.isDaemon = true; it.start() } }
        repeat(allowed.get()) { spawn() }

        // Tuning: every 1.5 s the speed is measured; one more connection while it pays off.
        var lastSpeed = 0.0; var tuning = best[host] == null; var lastBytes = 0L; var lastAt = System.currentTimeMillis()
        while (true) {
            Thread.sleep(250)
            if (!keepGoing()) { stopF.set(true) }
            val alive = synchronized(threads) { threads.any { it.isAlive } }
            if (!alive && (retry.isEmpty() || stopF.get())) break
            if (!alive && retry.isNotEmpty() && !stopF.get()) { spawn(); continue }
            val now = System.currentTimeMillis()
            if (now - lastAt < STEP_MS) continue
            val speed = (doneBytes.get() - lastBytes) * 1000.0 / (now - lastAt)
            lastBytes = doneBytes.get(); lastAt = now
            val fg = playingFromNetwork()
            if (playerBuffering() && allowed.get() > 1) { allowed.decrementAndGet() }       // playback buffers: give way
            limit.set(if (fg && lastSpeed > 0) (maxOf(speed, lastSpeed) * 0.75).toLong() else 0)
            windowStart.set(now); windowBytes.set(0)
            if (tuning) {
                val n = allowed.get()
                if (lastSpeed == 0.0 || speed > lastSpeed * 1.15) {
                    if (n < MAX_THREADS && nextPiece.get() < pieces) { allowed.set(n + 1); spawn() } else { tuning = false; best[host] = n }
                } else {
                    // No real gain (or a loss): back one and keep that.
                    val keep = if (speed < lastSpeed * 0.95 && n > 1) n - 1 else n
                    allowed.set(keep); best[host] = keep; tuning = false
                    ServerLog.log(host, "transfer", "", "", true, "Parallel copy: $keep connection(s) best for $host",
                        "${(maxOf(speed, lastSpeed) / 1048576 * 10).toLong() / 10.0} MB/s")
                }
                lastSpeed = maxOf(speed, lastSpeed)
            } else lastSpeed = maxOf(lastSpeed * 0.8, speed)
        }
        synchronized(threads) { threads.toList() }.forEach { it.join(3000) }
        if (tuning && best[host] == null) best[host] = allowed.get()
        val complete = doneBytes.get() >= size && !stopF.get()
        return complete
    }

    /**
     * A limit on how many network operations run at once (indexing: folder listings and tag reads), tuned like the
     * copies: it starts at the count remembered for the server (else 2) and goes up while the work gets clearly
     * faster (+15 % every 3 s), up to 16; one back when it got slower. Used with withPermit like a Semaphore.
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
            if (gateBest[host] != null) return
            tuner = scope.launch {
                var last = 0.0; var lastCount = counter.get(); var at = System.currentTimeMillis()
                while (true) {
                    kotlinx.coroutines.delay(3000)
                    val now = System.currentTimeMillis(); val c = counter.get()
                    val speed = (c - lastCount) * 1000.0 / (now - at); lastCount = c; at = now
                    if (speed <= 0.0) continue                       // nothing measured (long listing): wait
                    if (last == 0.0 || speed > last * 1.15) {
                        if (allowed >= max) break
                        held.poll(); sem.release(); allowed++
                    } else {
                        if (speed < last * 0.95 && allowed > 1) { sem.acquire(); held.add(Unit); allowed-- }
                        break
                    }
                    last = maxOf(last, speed)
                }
                gateBest[host] = allowed
                ServerLog.log(host, "index", "", "", true, "Indexing: $allowed at once best for $host")
            }
        }

        fun stop() { tuner?.cancel() }
    }
    private val gateBest = java.util.concurrent.ConcurrentHashMap<String, Int>()
}
