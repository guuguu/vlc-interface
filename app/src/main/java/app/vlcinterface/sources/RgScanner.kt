package app.vlcinterface.sources

import app.vlcinterface.data.CrashLog

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.TagCache
import app.vlcinterface.data.Source
import app.vlcinterface.data.PlEntry
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.coroutineContext

/** foobar2000-style scan modes. */
enum class RgMode(val label: String) {
    TRACK("Per file (track gain only)"),
    SINGLE_ALBUM("Selection as one album"),
    ALBUMS_BY_TAGS("As albums (by tags)"),
    ALBUMS_BY_FOLDERS("As albums (by folders)"),
}

enum class RgTarget { FILES, APP }

data class RgResult(val name: String, val status: String, val ok: Boolean, val detail: String = "")

data class RgScanState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val current: String = "",
    val phase: String = "",
    val results: List<RgResult> = emptyList(),
    val finished: Boolean = false,
    val needsPhonePermission: List<String> = emptyList(),
    // Live counters (shown on the scan page while it works).
    val tagsRead: Int = 0,
    val measured: Int = 0,
    val saved: Int = 0,
    val skipped: Int = 0,
    val problems: Int = 0,
    /** 0…1 of the whole job, including the part of the file being measured / saved right now. */
    val progress: Float = 0f,
    /** A failure report waits to be shown as a pop-up (cleared when seen). */
    val reportPending: Boolean = false,
    /** Quick scan (playlist): a "done" confirmation waits to be shown, with this title. */
    val confirmTitle: String = "",
    /** When this scan started (time-left estimate). */
    val startedAt: Long = 0,
)

/**
 * ReplayGain scanner (like foobar2000's): decodes with Android's decoders, measures EBU R128 loudness,
 * writes REPLAYGAIN_* tags into the files (or keeps them in the app), album gain gated over the whole album.
 */
object RgScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.guard)
    private val _state = MutableStateFlow(RgScanState())
    val state: StateFlow<RgScanState> = _state
    private var job: Job? = null

    /** Phone files can only be changed after Android's own confirmation; the screen asks and calls back. */
    @Volatile private var phoneGrant: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    fun phonePermissionResult(ok: Boolean) { phoneGrant?.complete(ok) }

    fun reportSeen() { _state.value = _state.value.copy(reportPending = false, confirmTitle = "") }

    /** Stops the scan running now; the queued ones follow. */
    private fun cancelled() = job?.isActive == false
    @Volatile private var copyThreads = 4
    /** Copies being made by the download workers (uri → ready copy, null if it couldn't be made). */
    private val ready = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<File?>>()
    /** Most space the scan's copies may take on the phone. */
    private const val COPY_LIMIT = 500L * 1024 * 1024
    /** Bytes of downloads under way (counted before they land). */
    private val reserved = java.util.concurrent.atomic.AtomicLong(0)
    /** Songs whose copies are measured (oldest first): let go first when room is needed. */
    private val measuredCopies = LinkedHashSet<String>()

    private fun copiesBytes() = copies.values.sumOf { if (it.isFile) it.length() else 0L }

    /** Waits until a copy of [size] bytes fits under 500 MB (one file alone always fits). */
    private fun waitForRoom(item: PlayItem, size: Long) {
        var shown = false
        while (!cancelled()) {
            synchronized(reserved) {
                val used = copiesBytes() + reserved.get()
                if (used + size <= COPY_LIMIT || (used == 0L)) { reserved.addAndGet(size); return }
                // Room needed: a measured copy goes (its tag update reads the song again).
                val u = synchronized(measuredCopies) { measuredCopies.firstOrNull()?.also { measuredCopies.remove(it) } }
                if (u != null) { copies.remove(u)?.delete(); return@synchronized }
            }
            if (!shown) { shown = true; update { st -> st.copy(phase = "Downloads waiting: 500 MB of copies on the phone") } }
            Thread.sleep(1000)
        }
    }

    /** A NAS song away from home: waits until VPN-NAS is unlocked with "Wi-Fi" (or you're home). */
    private fun waitNasWifi(it: PlayItem) {
        var shown = false
        while (VpnLink.heavyBlocked(it.uri) && !cancelled()) {
            if (!shown) { shown = true; update { s -> s.copy(current = it.title, phase = "Waiting: VPN-NAS on mobile data (answer Wi-Fi to go on)") } }
            Thread.sleep(2000)
        }
    }
    fun cancel() { job?.cancel(); _state.value = _state.value.copy(running = false, finished = true, phase = "Cancelled") }
    /** Stops the scan running now and drops the queue. */
    fun cancelAll() { queue.clear(); queued.value = emptyList(); cancel() }

    /** Scans asked for while one runs wait here and start one after the other. */
    private class Queued(val ctx: Context, val items: List<PlayItem>, val mode: RgMode, val target: RgTarget, val rescan: Boolean,
                         val onlyWithoutRg: Boolean, val confirmTitle: String, val label: String, val silent: Boolean = false)

    /**
     * End of a silent folder scan (automatic ReplayGain): the folder, and each scanned song with its new track gain
     * (null = couldn't be measured). Shown for 5 s, then cleared.
     */
    data class AutoSummary(val folder: String, val songs: List<Pair<String, Float?>>)

    /** Network songs scanned less than 10 days ago and skipped: one named in a short pop-up, [again] scans them after all. */
    class RecentAsk(val songs: List<Pair<String, Long>>, val uris: Set<String>, val count: Int, val again: () -> Unit)
    val recentAsk = MutableStateFlow<RecentAsk?>(null)
    @Volatile private var lastRecentNote = 0L
    val autoDone = MutableStateFlow<AutoSummary?>(null)
    private val queue = java.util.concurrent.ConcurrentLinkedDeque<Queued>()
    private var current: Queued? = null
    /** What waits in the queue (shown on the scan page), oldest first. */
    val queued = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    fun dropQueued(i: Int) { queue.elementAtOrNull(i)?.let { queue.remove(it) }; queued.value = queue.map { it.label } }
    /**
     * Player's pause button: the scan running now stops and goes back to the front of the queue (files it already
     * saved are skipped when it runs again); nothing starts until [resume].
     */
    fun pause() {
        if (_state.value.running) {
            current?.let { c -> queue.addFirst(Queued(c.ctx, c.items, c.mode, c.target, false, c.onlyWithoutRg, c.confirmTitle, c.label)) }
            queued.value = queue.map { it.label }
            job?.cancel(); _state.value = _state.value.copy(running = false, finished = true, phase = "Paused")
        }
    }
    fun resume() = startNext()
    /** The scan running now reads files over the internet (stopped when Wi-Fi is lost). */
    fun currentNeedsInternet(): Boolean = current?.items?.any { app.vlcinterface.player.NetGuard.needsInternet(it.uri) } == true
    private fun startNext() {
        if (_state.value.running || app.vlcinterface.player.BackgroundWork.paused.value) return
        val q = queue.poll() ?: return
        queued.value = queue.map { it.label }
        start(q.ctx, q.items, q.mode, q.target, q.rescan, q.onlyWithoutRg, q.confirmTitle, q.silent)
    }

    /**
     * [onlyWithoutRg]: only files with no ReplayGain record of any kind (file or app) are scanned; the rest are skipped.
     * [confirmTitle]: when not empty, a confirmation pop-up says what was done when the scan ends.
     */
    fun start(ctx: Context, items: List<PlayItem>, mode: RgMode, target: RgTarget, rescanTagged: Boolean,
              onlyWithoutRg: Boolean = false, confirmTitle: String = "", silent: Boolean = false, recentOk: Boolean = false) {
        if (app.vlcinterface.player.BatteryGuard.busyBlocked()) return     // battery 15 % or less: only playing
        val music = items.filter { it.isMusic && it.startMs < 0 }.distinctBy { it.uri }
        // Network songs scanned in the last 10 days (2026-11-21 12:06): first a pop-up says when, and you choose.
        if (!silent && !recentOk) {
            val recent = music.filter { RgScanDates.isNetwork(it.uri) }.mapNotNull { m -> RgScanDates.recent(m.uri)?.let { m to it } }
            if (recent.isNotEmpty()) {
                // 12:07: songs measured in the last 10 days are skipped; a short pop-up (5 s) names one of them — at
                // most once an hour of scanning — with "Scan again" to include them after all.
                val skip = recent.map { it.first.uri }.toSet()
                val rest = items.filter { it.uri !in skip }
                if (rest.any { it.isMusic }) start(ctx, rest, mode, target, rescanTagged, onlyWithoutRg, confirmTitle, false, recentOk = true)
                val now = System.currentTimeMillis()
                if (now - lastRecentNote > 3_600_000L) {
                    lastRecentNote = now
                    val (m, t) = recent.first()
                    recentAsk.value = RecentAsk(listOf(m.title to t), skip, recent.size) {
                        start(ctx, items.filter { it.uri in skip }, mode, target, rescanTagged, onlyWithoutRg, confirmTitle, false, recentOk = true)
                    }
                }
                return
            }
        }
        if (_state.value.running || app.vlcinterface.player.BackgroundWork.paused.value) {
            // Busy (or background work paused): queued, started when the scans before it are done.
            val label = confirmTitle.ifEmpty { if (music.size == 1) music[0].title else "${music.size} songs" }
            queue.add(Queued(ctx, items, mode, target, rescanTagged, onlyWithoutRg, confirmTitle, label, silent))
            queued.value = queue.map { it.label }
            return
        }
        val cueSkipped = items.count { it.isMusic && it.startMs >= 0 }
        current = Queued(ctx, items, mode, target, rescanTagged, onlyWithoutRg, confirmTitle,
            confirmTitle.ifEmpty { if (music.size == 1) music[0].title else "${music.size} songs" }, silent)
        // Which songs had no ReplayGain at all before (silent scans list only those in their summary).
        val missing = if (silent) music.filter { RgStore.get(it.uri)?.any != true }.map { it.uri }.toSet() else emptySet()
        _state.value = RgScanState(running = true, total = music.size, phase = "Preparing", startedAt = System.currentTimeMillis())
        if (!silent) RgScanService.start(ctx)              // silent: no notification, no progress, no report
        job = scope.launch {
            val results = ArrayList<RgResult>()
            if (cueSkipped > 0) results += RgResult("$cueSkipped .cue tracks", "skipped — part of one big file, scan that file instead", false)
            try {
                run(ctx, music, mode, target, rescanTagged, results, onlyWithoutRg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dropCopies(); throw e
            } catch (e: Exception) {
                results += RgResult("Scan", "stopped: ${e.message}", false)
            } finally { dropCopies() }
            _state.value = _state.value.copy(running = false, finished = true, current = "", phase = "Done", results = results.toList(),
                reportPending = !silent && (results.any { !it.ok } || confirmTitle.isNotEmpty()), confirmTitle = if (silent) "" else confirmTitle)
            if (silent) {
                // Songs that got a value now, then those that couldn't be measured (songs with ReplayGain in the file
                // were skipped and aren't listed).
                val done = music.filter { it.uri in missing }.mapNotNull { m -> RgStore.get(m.uri)?.trackGain?.let { m.title to it } } +
                    results.filter { !it.ok }.map { it.name to null }
                if (done.isNotEmpty()) autoDone.value = AutoSummary(confirmTitle, done)
            }
            // ReplayGain log: one line for the scan, one per file that had a problem.
            try {
                val L = app.vlcinterface.data.ServerLog
                val bad = results.filter { !it.ok }
                L.log(L.REPLAYGAIN, "scan", mode.name.lowercase(), "", bad.isEmpty(),
                    "${confirmTitle.ifEmpty { "Scan" }}: ${music.size} files · saved ${_state.value.saved} · skipped ${_state.value.skipped} · problems ${bad.size}",
                    "save: ${target.name.lowercase()}")
                bad.take(200).forEach { r -> L.log(L.REPLAYGAIN, "file", "", "", false, "${r.name}: ${r.status}") }
            } catch (_: Exception) {}
        }
        // Done or stopped: the next queued scan starts (a moment later, so the finished one's report is posted first).
        job?.invokeOnCompletion { scope.launch { kotlinx.coroutines.delay(1500); startNext() } }
    }

    /** How many albums the items make in this mode (shown in the scan dialog). */
    fun albumCount(items: List<PlayItem>, mode: RgMode): Int = items.filter { it.isMusic }.map { groupKey(it, mode) }.distinct().size

    fun groupKey(it: PlayItem, mode: RgMode): String = when (mode) {
        RgMode.TRACK -> it.uri
        RgMode.SINGLE_ALBUM -> "all"
        RgMode.ALBUMS_BY_FOLDERS -> it.folderKey.ifEmpty { it.uri.substringBeforeLast('/') }
        // foobar2000 default pattern: %album artist% | %date% | %album%. Files without an album tag:
        // their folder is the album, so every album in a mixed selection gets its own album gain.
        RgMode.ALBUMS_BY_TAGS -> if (it.album.isBlank()) "folder:" + it.folderKey.ifEmpty { it.uri.substringBeforeLast('/') } else
            (TagCache.get(it.uri)?.let { t -> "${t.albumArtist.ifEmpty { t.artist }}|${t.year}|" } ?: "${it.artist}||") + it.album
    }

    /** The physical folder a file is in. */
    private fun physicalFolder(ctx: Context, item: PlayItem): String {
        val u = item.uri
        if (u.startsWith("content://")) try {
            ctx.contentResolver.query(Uri.parse(u), arrayOf(android.provider.MediaStore.MediaColumns.VOLUME_NAME, android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.getString(1) != null) return "phone:${c.getString(0)}:${c.getString(1)}"
            }
        } catch (_: Exception) {}
        return if (u.startsWith("content://")) item.folderKey.ifEmpty { u } else Uri.decode(u).substringBefore('?').substringBeforeLast('/')
    }

    /** Album tags read from the file itself (album artist / artist, year, album title). */
    private data class FileTags(val albumArtist: String, val year: String, val album: String)

    private fun fileTags(ctx: Context, item: PlayItem): FileTags? {
        val r = android.media.MediaMetadataRetriever()
        var ds: MediaDataSource? = null
        return try {
            val u = item.uri
            when {
                u.startsWith("content://") -> r.setDataSource(ctx, Uri.parse(u))
                u.startsWith("file://") -> r.setDataSource(Uri.parse(u).path)
                u.startsWith("/") -> r.setDataSource(u)
                u.startsWith("smb://") -> { ds = SmbSource(SmbFile(VpnLink.route(Uri.decode(u)), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); r.setDataSource(ds) }
                u.startsWith("http") -> r.setDataSource(u, if (item.user.isNotEmpty()) mapOf("Authorization" to okhttp3.Credentials.basic(item.user, item.password)) else emptyMap())
                else -> return null
            }
            fun m(k: Int) = r.extractMetadata(k)?.trim().orEmpty()
            val album = m(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            FileTags(m(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).ifEmpty { m(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) },
                m(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR).ifEmpty { m(android.media.MediaMetadataRetriever.METADATA_KEY_DATE).take(4) }, album)
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {}; ds?.close() }
    }

    // Overall progress: reading tags 10 % (albums by tags only), measuring 70 %, saving 20 %.
    @Volatile private var tagWeight = 0f
    /** Progress inside the files being measured right now (several at once). */
    private val fracs = java.util.concurrent.ConcurrentHashMap<String, Float>()
    @Synchronized private fun update(f: (RgScanState) -> RgScanState) {
        val s = f(_state.value)
        val n = s.total.coerceAtLeast(1).toFloat()
        val mw = 0.7f + (0.1f - tagWeight)
        val inFile = fracs.values.sum()
        val p = tagWeight * (s.tagsRead / n) + mw * ((s.measured + s.skipped + inFile) / n) + 0.2f * ((s.saved + s.skipped) / n)
        _state.value = s.copy(progress = p.coerceIn(0f, 1f))
    }

    /** Up to 4 files at once: one thread per file when there are fewer than 4. */
    private suspend fun <T> parallel(list: List<T>, workers: Int, block: suspend (T) -> Unit) {
        val sem = kotlinx.coroutines.sync.Semaphore(workers)
        kotlinx.coroutines.coroutineScope {
            list.map { x -> async(Dispatchers.IO) { sem.withPermit { coroutineContext.ensureActive(); block(x) } } }.forEach { it.await() }
        }
    }

    private suspend fun run(ctx: Context, items: List<PlayItem>, mode: RgMode, target: RgTarget, rescan: Boolean, results: ArrayList<RgResult>,
                            onlyWithoutRg: Boolean = false) {
        tagWeight = if (mode == RgMode.ALBUMS_BY_TAGS) 0.1f else 0f; fracs.clear()
        // One thread per file, at most 4 at once — 2 when files come from a network share (small servers limit
        // how many files can be open at once).
        // Network songs now travel once (copy for measuring; only the tag bytes go back): 4 at a time.
        val workers = items.size.coerceIn(1, 4)
        // Connections per copy: shared between the download workers (set below).
        copyThreads = maxOf(2, 8 / workers)
        fun addResult(r: RgResult) = synchronized(results) { results += r }
        fun snapshot() = synchronized(results) { results.toList() }
        // Albums by tags: grouped by the tags inside each file (album artist | year | album title), read now,
        // so every album in a mixed selection gets its own album gain. No album title in the file → its folder.
        val tagKey = java.util.concurrent.ConcurrentHashMap<String, String>()
        if (mode == RgMode.ALBUMS_BY_TAGS) {
            val read = java.util.concurrent.atomic.AtomicInteger()
            parallel(items, workers) { it ->
                update { s -> s.copy(phase = "Reading album tags" + if (workers > 1) " ($workers at a time)" else "", current = it.title) }
                val t = fileTags(ctx, it)
                tagKey[it.uri] = if (t != null && t.album.isNotBlank()) "${t.albumArtist.lowercase()}|${t.year}|${t.album.lowercase()}" else "folder:" + physicalFolder(ctx, it)
                val n = read.incrementAndGet(); update { s -> s.copy(tagsRead = n) }
            }
        }
        // Albums by folders: the real folder each file is in (phone: its storage path; network: its parent folder).
        if (mode == RgMode.ALBUMS_BY_FOLDERS) items.forEach { tagKey[it.uri] = "folder:" + physicalFolder(ctx, it) }
        val groups = items.groupBy { tagKey[it.uri] ?: groupKey(it, mode) }.values.toList()
        val albumMode = mode != RgMode.TRACK
        // Phone files: one Android confirmation for all of them, before anything is written.
        if (target == RgTarget.FILES) {
            val phone = items.filter { it.uri.startsWith("content://") }.map { it.uri }
            if (phone.isNotEmpty()) {
                val d = kotlinx.coroutines.CompletableDeferred<Boolean>(); phoneGrant = d
                update { s -> s.copy(phase = "Waiting for Android's permission to change ${phone.size} phone files", needsPhonePermission = phone) }
                val ok = d.await()
                update { s -> s.copy(needsPhonePermission = emptyList()) }
                if (!ok) addResult(RgResult("Phone files", "not allowed — values kept in the app instead", false))
                phoneAllowed = ok
            }
        }
        // Skip rule: a track is skipped when tagged; an album only when every file in it is tagged.
        val todo = java.util.Collections.synchronizedList(ArrayList<List<PlayItem>>())
        if (rescan) todo += groups else {
            update { s -> s.copy(phase = "Checking existing tags") }
            parallel(groups, groups.size.coerceIn(1, 4)) { g ->
                // Values of exactly 0.00 dB come from the earlier failed scans (16-bit read as float): scan those again.
                fun real(v: Float?) = v != null && v != 0f
                val tagged = g.all { ReplayGain.read(ctx, it.uri, it.user, it.password, it.domain, it.smbVersion)?.let { r ->
                    if (onlyWithoutRg) r.trackGain != null || r.albumGain != null   // any record at all → leave it alone
                    else if (albumMode) real(r.trackGain) && real(r.albumGain) else real(r.trackGain) } ?: false }
                if (tagged) {
                    g.forEach { addResult(RgResult(it.title, if (onlyWithoutRg) "skipped — already has ReplayGain" else "skipped — already tagged", true)) }
                    update { s -> s.copy(done = s.done + g.size, skipped = s.skipped + g.size, results = snapshot()) }
                } else todo += g
            }
        }
        // Measuring: the expensive part, $workers files at a time.
        val meters = java.util.concurrent.ConcurrentHashMap<String, Loudness>()
        val toMeasure = synchronized(todo) { todo.toList() }.flatten()
        // Dedicated download workers: network songs are copied ahead, in list order, while others are measured —
        // the network and the phone work at the same time. Never more than 500 MB of copies on the phone: a
        // download waits until there's room (measured copies are let go first; their tag update reads them again).
        val net = toMeasure.filter { (it.uri.startsWith("smb://") || it.uri.startsWith("http")) && Prefetch.localFor(it.uri) == null }
        net.forEach { ready[it.uri] = kotlinx.coroutines.CompletableDeferred() }
        measuredCopies.clear(); reserved.set(0)
        // One file downloading at a time: small servers (router USB shares) slow down badly — even refuse —
        // with many connections; its connection count is still tuned (more only when it really is faster).
        val dlWorkers = net.size.coerceIn(0, 1)
        copyThreads = 8
        val dl = if (net.isEmpty()) null else scope.launch {
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            kotlinx.coroutines.coroutineScope {
                repeat(dlWorkers) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            val i = next.getAndIncrement(); if (i >= net.size) break
                            val item = net[i]
                            waitNasWifi(item)
                            val size = try { ParallelFetch.sizeOf(item) } catch (_: Exception) { 0L }
                            waitForRoom(item, size)
                            val f = try { netCopy(ctx, item, copyThreads) { f -> fracs[item.uri] = f * 0.5f; update { st -> st } } } catch (_: Exception) { null }
                            reserved.addAndGet(-size)
                            ready[item.uri]?.complete(f)
                        }
                    }
                }
            }
        }
        try {
        parallel(toMeasure, workers) { it ->
            fracs[it.uri] = fracs[it.uri] ?: 0f
            waitNasWifi(it)
            update { s -> s.copy(current = it.title, phase = "Measuring" + if (workers > 1) " ($workers at a time)" else "") }
            // A refused / dropped network connection is tried again (after 1 s, then 3 s) before it counts as a problem.
            var m: Loudness? = null
            for (attempt in 0..2) {
                try { m = measure(ctx, it) { f -> fracs[it.uri] = f; update { s -> s } }; break } catch (e: Exception) {
                    val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
                    val network = msg.contains("connect", true) || msg.contains("timed out", true) || msg.contains("reset", true) ||
                        msg.contains("transport", true) || e is java.io.IOException
                    if (network && attempt < 2) { kotlinx.coroutines.delay(if (attempt == 0) 1000L else 3000L); continue }
                    addResult(RgResult(it.title, "can't decode: ${e.message ?: e.javaClass.simpleName}" +
                        if (attempt > 0) " (tried ${attempt + 1} times)" else "", false))
                    break
                }
            }
            // A measurement that came out silent / invalid is a failure, never written as 0.00 dB.
            val bad = m != null && (m.integrated.let { v -> v.isNaN() || v.isInfinite() } || m.peak.isNaN() || m.peak > 4f)
            if (bad) addResult(RgResult(it.title, "measurement failed (no usable sound decoded) — nothing written", false))
            if (m != null && !bad) { meters[it.uri] = m; RgScanDates.record(ctx, it.uri) }
            synchronized(measuredCopies) { measuredCopies += it.uri }   // its copy may go when room is needed
            fracs.remove(it.uri)
            update { s -> s.copy(done = s.done + 1, measured = s.measured + 1, problems = s.problems + if (m == null || bad) 1 else 0, results = snapshot()) }
        }
        } finally {
            dl?.cancel(); ready.values.forEach { it.complete(null) }; ready.clear()
        }
        // Values per album, then saving — also several files at once.
        val writes = ArrayList<Pair<PlayItem, Map<String, String>>>()
        for (g in synchronized(todo) { todo.toList() }) {
            val measured = g.mapNotNull { it0 -> meters[it0.uri]?.let { it0 to it } }
            if (measured.isEmpty()) continue
            val albumLufs = if (albumMode) Loudness.album(measured.map { it.second.blocks() }) else Double.NaN
            val albumPeak = measured.maxOf { it.second.peak }
            for ((it, m) in measured) {
                val values = LinkedHashMap<String, String>()
                values["REPLAYGAIN_TRACK_GAIN"] = "%.2f dB".format(java.util.Locale.US, Loudness.gainDb(m.integrated))
                values["REPLAYGAIN_TRACK_PEAK"] = "%.6f".format(java.util.Locale.US, m.peak)
                if (albumMode) {
                    values["REPLAYGAIN_ALBUM_GAIN"] = "%.2f dB".format(java.util.Locale.US, Loudness.gainDb(albumLufs))
                    values["REPLAYGAIN_ALBUM_PEAK"] = "%.6f".format(java.util.Locale.US, albumPeak)
                }
                writes += it to values
                // The playing song: its new value is used at once (only for the rest of this song).
                App.instance.player.applyScannedRg(it.uri, values)
            }
        }
        parallel(writes, writes.size.coerceIn(1, 4)) { (it, values) ->
            val summary = "track ${values["REPLAYGAIN_TRACK_GAIN"]}" + if (albumMode) " · album ${values["REPLAYGAIN_ALBUM_GAIN"]}" else ""
            waitNasWifi(it)
            update { s -> s.copy(current = it.title, phase = "Writing") }
            val where = save(ctx, it, values, target)
            addResult(RgResult(it.title, "$summary — $where", !where.startsWith("not")))
            ReplayGain.forget(it.uri)
            if (it.uri.contains("://") && !it.uri.startsWith("content://")) ServerLog.log(it.uri, "replaygain", it.uri.substringBefore("://").uppercase(), it.user, true, "${it.title}: $summary — $where")
            update { s -> s.copy(saved = s.saved + 1, problems = s.problems + if (where.startsWith("not")) 1 else 0, results = snapshot()) }
        }
    }

    @Volatile private var phoneAllowed = false

    // ---------- decoding ----------

    /**
     * SMB file for Android's decoder. The decoder asks for many small pieces; each one was a network round trip,
     * which made scanning a NAS crawl. Now it reads 1 MB at a time and serves the small pieces from memory.
     */
    internal class SmbSource(f: SmbFile) : MediaDataSource() {
        private val raf: SmbRandomAccessFile = f.openRandomAccess("r") as SmbRandomAccessFile
        private val len = f.length()
        private val block = ByteArray(1 shl 20)
        private var blockStart = -1L
        private var blockLen = 0
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= len) return -1
            if (blockStart < 0 || position < blockStart || position >= blockStart + blockLen) {
                raf.seek(position)
                var n = 0
                val want = minOf(block.size.toLong(), len - position).toInt()
                while (n < want) { val r = raf.read(block, n, want - n); if (r <= 0) break; n += r }
                blockStart = position; blockLen = n
                if (n <= 0) return -1
            }
            val from = (position - blockStart).toInt()
            val count = minOf(size, blockLen - from)
            System.arraycopy(block, from, buffer, offset, count)
            return count
        }
        override fun getSize() = len
        override fun close() { try { raf.close() } catch (_: Exception) {} }
    }

    private fun measure(ctx: Context, item: PlayItem, onProgress: (Float) -> Unit = {}): Loudness {
        val ex = MediaExtractor()
        var ds: MediaDataSource? = null
        try {
            val uri = item.uri
            // Network file: copied first with several connections (tuned to the bandwidth), then read from the phone;
            // kept for the tag update that follows. A cached copy (next song) is used as it is.
            // Copied ahead by the download workers (waits for it if still coming), else copied here.
            val pending = ready[uri]
            val local = (if (pending != null) kotlinx.coroutines.runBlocking { pending.await() } else null)   // copy from the download worker
                ?: netCopy(ctx, item, copyThreads) { f -> onProgress(f * 0.5f) }                             // none: one more try here
            val base = if (local != null && (uri.startsWith("smb://") || uri.startsWith("http"))) 0.5f else 0f
            when {
                local != null -> ex.setDataSource(local.path)
                uri.startsWith("content://") -> ex.setDataSource(ctx, Uri.parse(uri), null)
                uri.startsWith("file://") -> ex.setDataSource(Uri.parse(uri).path!!)
                uri.startsWith("/") -> ex.setDataSource(uri)
                uri.startsWith("smb://") -> { ds = SmbSource(SmbFile(VpnLink.route(Uri.decode(uri)), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); ex.setDataSource(ds) }
                uri.startsWith("http://") || uri.startsWith("https://") -> ex.setDataSource(uri,
                    if (item.user.isNotEmpty()) mapOf("Authorization" to okhttp3.Credentials.basic(item.user, item.password)) else emptyMap())
                else -> throw IllegalStateException("this kind of source can't be read for scanning")
            }
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalStateException("no audio track the phone can decode")
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var lastReport = 0L
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // No output format is requested: some decoders (FLAC) say "float" because it was asked for, but still
            // deliver 16-bit samples — read as floats they are garbage, every measurement failed and 0.00 dB was written.
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var meter: Loudness? = null
            var enc = AudioFormat.ENCODING_PCM_16BIT
            var buf = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var lastOut = System.currentTimeMillis()
            try {
                while (true) {
                    // Safety: a decoder that stops answering after the end of the file must not hang the scan.
                    if (inDone && System.currentTimeMillis() - lastOut > 5000) break
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else {
                                val t = ex.sampleTime
                                codec.queueInputBuffer(ii, 0, n, t, 0); ex.advance()
                                // Progress inside the file, so a long track on a slow NAS never looks stuck.
                                val now = System.currentTimeMillis()
                                if (durUs > 0 && now - lastReport > 500) {
                                    lastReport = now
                                    onProgress(base + (1f - base) * (t.toFloat() / durUs).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            meter = Loudness(rate, ch)
                        }
                        oi >= 0 -> {
                            lastOut = System.currentTimeMillis()
                            val ob = codec.getOutputBuffer(oi)!!
                            if (meter == null) meter = Loudness(rate, ch)
                            if (info.size > 0) {
                                ob.position(info.offset); ob.limit(info.offset + info.size); ob.order(ByteOrder.nativeOrder())
                                val bytes = when (enc) { AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3; AudioFormat.ENCODING_PCM_8BIT -> 1; else -> 2 }
                                val samples = info.size / bytes
                                if (buf.size < samples) buf = FloatArray(samples)
                                when (enc) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> ob.asFloatBuffer().get(buf, 0, samples)
                                    AudioFormat.ENCODING_PCM_32BIT -> { val ib2 = ob.asIntBuffer(); for (k in 0 until samples) buf[k] = ib2.get(k) / 2147483648f }
                                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> { val base = ob.position(); for (k in 0 until samples) {
                                        val o = base + k * 3
                                        val v = (ob.get(o).toInt() and 0xFF) or ((ob.get(o + 1).toInt() and 0xFF) shl 8) or (ob.get(o + 2).toInt() shl 16)
                                        buf[k] = v / 8388608f } }
                                    AudioFormat.ENCODING_PCM_8BIT -> { for (k in 0 until samples) buf[k] = ((ob.get(ob.position() + k).toInt() and 0xFF) - 128) / 128f }
                                    else -> { val sb = ob.asShortBuffer(); for (k in 0 until samples) buf[k] = sb.get(k) / 32768f }
                                }
                                // Never let a bad sample (NaN / infinite) spoil the measurement.
                                for (k in 0 until samples) { val x = buf[k]; if (x.isNaN() || x.isInfinite()) buf[k] = 0f else if (x > 4f) buf[k] = 4f else if (x < -4f) buf[k] = -4f }
                                meter.add(buf, samples / ch)
                            }
                            codec.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally { try { codec.stop() } catch (_: Exception) {}; codec.release() }
            return meter ?: throw IllegalStateException("no audio decoded")
        } finally {
            ex.release(); ds?.close()
        }
    }

    // ---------- saving ----------

    /** Where the values went, in words (starts with "not" when nothing could be saved). */
    private suspend fun save(ctx: Context, item: PlayItem, values: Map<String, String>, target: RgTarget): String {
        if (target == RgTarget.APP || (item.uri.startsWith("content://") && !phoneAllowed)) {
            RgStore.put(item.uri, values); return "kept in the app"
        }
        // Read-only share: kept in the app straight away (counts as saved, nothing is sent to the share).
        if (WriteGuard.isReadOnly(item)) { RgStore.put(item.uri, values); return "kept in the app (share is read-only)" }
        val src = App.instance.store.current.sources.firstOrNull { it.id == item.sourceId }
        val ext = item.ext.lowercase()
        val tmp = File(ctx.cacheDir, "rgscan-${java.util.UUID.randomUUID()}.${ext.ifEmpty { "bin" }}")
        val net = item.uri.startsWith("smb://") || item.uri.startsWith("http")
        val orig = if (net) File(tmp.path + ".orig") else null
        return try {
            download(ctx, item, tmp)
            orig?.let { tmp.copyTo(it, true) }                 // untagged, for the in-place update / the check
            TagWriter.write(tmp, values)
            // Check the tagged copy before it replaces anything.
            val check = ReplayGain.parse(tmp.inputStream().use { s -> ByteArray(512 * 1024).let { b -> val n = s.read(b); b.copyOf(maxOf(n, 0)) } } +
                if (tmp.length() > 512 * 1024) java.io.RandomAccessFile(tmp, "r").use { r -> r.seek(tmp.length() - 65536); ByteArray(65536).also { r.readFully(it) } } else ByteArray(0))
            if (check.trackGain == null) throw IllegalStateException("tag check failed")
            // The file the player has open is never rewritten under it: the tagged copy waits until the song is over.
            if (App.instance.player.state.value.item?.uri == item.uri) {
                RgStore.put(item.uri, values)
                // Written later with the full swap: the copy is checked against the server now.
                if (orig != null && !ParallelFetch.matchesRemote(item, item.uri, orig)) throw IllegalStateException("the copy read from the server didn't match it — nothing changed")
                addPending(ctx, item, tmp)
                return "cached — written to the file after this song"
            }
            try { upload(ctx, item, src, tmp, orig) } catch (e: Exception) {
                if (!inUse(e)) throw e
                if (orig != null && !ParallelFetch.matchesRemote(item, item.uri, orig)) throw IllegalStateException("the copy read from the server didn't match it — nothing changed")
                // The server refuses while the player has the file open: the tagged copy is cached and written
                // as soon as the player is done with that file. Until then the app uses the values itself.
                RgStore.put(item.uri, values)
                addPending(ctx, item, tmp)
                return "cached — written to the file once it is no longer playing"
            }
            RgStore.remove(item.uri)       // the file now holds the newest values
            "written to the file"
        } catch (e: Exception) {
            RgStore.put(item.uri, values)
            // Refused for good (read-only share): marked, and this song's values count as kept in the app.
            if (WriteGuard.isDenied(e)) { WriteGuard.markReadOnly(item.sourceId, item.uri, e.message ?: "refused"); "kept in the app (share is read-only)" }
            else "not written (${e.message ?: e.javaClass.simpleName}) — kept in the app"
        } finally { tmp.delete(); orig?.delete() }
    }

    /**
     * Network files copied for this scan (uri → copy), reused by the tag update, then deleted. One per file: with
     * several files measured at once, one copy no longer deletes another that is still being read.
     */
    private val copies = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** Copies of this scan removed (end of the scan, cancel). */
    private fun dropCopies() { copies.values.forEach { it.delete() }; copies.clear() }

    /**
     * A phone copy of a network song for scanning (parallel connections), or null (read over the network).
     * [onProgress] gets 0…1 of the copy (shown as the first half of the file's progress).
     */
    private fun netCopy(ctx: Context, item: PlayItem, threads: Int, onProgress: (Float) -> Unit): File? {
        val u = item.uri
        if (!(u.startsWith("smb://") || u.startsWith("http"))) return null
        Prefetch.localFor(u)?.let { return it }
        copies[u]?.let { if (it.isFile) return it }
        if (ctx.cacheDir.usableSpace < 2L * 1024 * 1024 * 1024) return null
        val f = File(ctx.cacheDir, "rgcopy-${Integer.toHexString(u.hashCode())}-${System.nanoTime()}.${item.ext.ifEmpty { "bin" }}")
        return try {
            if (ParallelFetch.download(item, f, 300L * 1024 * 1024, keepGoing = { !cancelled() }, what = "ReplayGain read",
                    maxThreads = threads, progress = { d, t -> if (t > 0) onProgress(d.toFloat() / t) })) { copies[u] = f; f }
            else { f.delete(); null }
        } catch (_: Exception) { f.delete(); null }
    }

    private fun download(ctx: Context, item: PlayItem, dst: File) {
        val u = item.uri
        val net = u.startsWith("smb://") || u.startsWith("http")
        // This copy is tagged and written back over the original: it must be exactly the file on the server —
        // checked whole (SHA-256 against a fresh read) whichever way it was made.
        // Already copied for the scan (or cached ahead): taken from the phone once it's checked.
        val pct = { what: String -> { d: Long, t: Long -> if (t > 0) update { s -> s.copy(current = item.title, phase = "$what ${d * 100 / t}%") } } }
        (copies[u]?.takeIf { it.isFile } ?: Prefetch.localFor(u))?.let { c ->
            copies.remove(u)?.let { if (it != c) it.delete() }
            c.copyTo(dst, true); if (c.name.startsWith("rgcopy-")) c.delete(); return
        }
        if (net && ParallelFetch.download(item, dst, what = "ReplayGain read", progress = pct("Reading the file"))) return
        val input = when {
            u.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(u))
            u.startsWith("file://") -> File(Uri.parse(u).path!!).inputStream()
            u.startsWith("/") -> File(u).inputStream()
            u.startsWith("smb://") -> SmbFile(VpnLink.route(Uri.decode(u)), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)).inputStream
            u.startsWith("http") -> Net.http.newCall(Request.Builder().url(u)
                .apply { if (item.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(item.user, item.password)) }.build())
                .execute().let { r -> if (!r.isSuccessful) { r.close(); throw IllegalStateException("HTTP ${r.code}") }; r.body!!.byteStream() }
            else -> null
        } ?: throw IllegalStateException("can't read the file")
        input.use { i -> dst.outputStream().use { o -> copy(i, o, "Reading the file"); o.fd.sync() } }

    }

    /** Copies with progress on the scan page (big files over the network take a while). */
    private fun copy(i: java.io.InputStream, o: java.io.OutputStream, what: String, total: Long = -1) {
        val quiet = quietCopy.get() == true
        val buf = ByteArray(256 * 1024); var n = 0L; var last = 0L
        while (true) {
            val r = i.read(buf); if (r < 0) break
            o.write(buf, 0, r); n += r
            val now = System.currentTimeMillis()
            if (!quiet && now - last > 500) { last = now; update { s -> s.copy(phase = "$what " + if (total > 0) "${n * 100 / total}%" else "${n / 1_000_000} MB") } }
        }
    }

    private fun upload(ctx: Context, item: PlayItem, src: Source?, tmp: File, orig: File? = null) {
        val u = item.uri
        // Only the tags changed and the file kept its size (they fit in its padding): just those bytes are written
        // on the server — no whole-file transfer.
        if (u.startsWith("smb://") && orig != null && orig.length() == tmp.length() && patchInPlace(ctx, item, orig, tmp)) return
        // Whole file replaced: the untagged copy must be exactly the file on the server (checked whole).
        if (orig != null && (u.startsWith("smb://") || u.startsWith("http")) &&
            !ParallelFetch.matchesRemote(item, u, orig, progress = { d, t -> if (t > 0) update { s -> s.copy(current = item.title, phase = "Checking the copy against the server ${d * 100 / t}%") } }))
            throw IllegalStateException("the copy read from the server didn't match it — nothing changed")
        when {
            u.startsWith("content://") -> ctx.contentResolver.openOutputStream(Uri.parse(u), "wt")!!.use { o -> tmp.inputStream().use { copy(it, o, "Saving", tmp.length()) } }
            u.startsWith("file://") || u.startsWith("/") -> {
                val f = File(if (u.startsWith("/")) u else Uri.parse(u).path!!)
                val t = File(f.parentFile, f.name + ".rgtmp"); tmp.copyTo(t, true); if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
            }
            u.startsWith("smb://") -> {
                // Safe replace: write a tagged copy next to the file, then swap it in.
                val cx = SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)
                val orig = SmbFile(VpnLink.route(Uri.decode(u)), cx)
                val t = SmbFile(VpnLink.route(Uri.decode(u) + ".rgtmp"), cx)
                // Several connections at once (tuned); one plain stream if that fails.
                val par = try { ParallelFetch.uploadSmb(item, tmp, u + ".rgtmp") { d, t -> if (t > 0) update { s -> s.copy(current = item.title, phase = "Sending and checking ${d * 100 / t}%") } } } catch (_: Exception) { false }
                if (!par) t.outputStream.use { o -> tmp.inputStream().use { copy(it, o, "Saving to the server", tmp.length()) } }
                if (t.length() != tmp.length()) { t.delete(); throw IllegalStateException("copy on the server is incomplete") }
                // The tagged copy on the server read back whole and compared before it replaces anything.
                if (!par && !ParallelFetch.matchesRemote(item, u + ".rgtmp", tmp)) { t.delete(); throw IllegalStateException("copy on the server didn't match — original kept") }
                // Swap without a moment where the song is gone: original → .rgold, copy → original, then .rgold
                // removed; any failure puts the original back.
                val old = SmbFile(VpnLink.route(Uri.decode(u) + ".rgold"), cx)
                try { if (old.exists()) old.delete() } catch (_: Exception) {}
                orig.renameTo(old)
                try { t.renameTo(SmbFile(VpnLink.route(Uri.decode(u)), cx)) } catch (e: Exception) {
                    try { old.renameTo(SmbFile(VpnLink.route(Uri.decode(u)), cx)) } catch (_: Exception) {}
                    try { t.delete() } catch (_: Exception) {}
                    throw IllegalStateException("couldn't swap in the tagged copy — original kept")
                }
                try { old.delete() } catch (_: Exception) {}
            }
            u.startsWith("http") && src?.proto == "webdav" -> {
                // Sent next to the file, read back and compared, then moved over it (the original is never half-written).
                fun auth(b: Request.Builder) = b.apply { if (item.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(item.user, item.password)) }
                val t = "$u.rgtmp"
                Net.http.newCall(auth(Request.Builder().url(t).put(tmp.asRequestBody("application/octet-stream".toMediaType()))).build())
                    .execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("server refused (HTTP ${r.code})") }
                if (!ParallelFetch.matchesRemote(item, t, tmp)) {
                    try { Net.http.newCall(auth(Request.Builder().url(t).delete()).build()).execute().close() } catch (_: Exception) {}
                    throw IllegalStateException("copy on the server didn't match — original kept")
                }
                Net.http.newCall(auth(Request.Builder().url(t).method("MOVE", null).header("Destination", u).header("Overwrite", "T")).build())
                    .execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("server refused the swap (HTTP ${r.code}) — original kept") }
            }
            else -> throw IllegalStateException("this source is read-only")
        }
    }
    // ---------- cached writes (file was in use) ----------

    private val quietCopy = ThreadLocal<Boolean>()
    private fun inUse(e: Throwable): Boolean {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return m.contains("used by another process", true) || m.contains("SHARING_VIOLATION", true)
    }
    private fun pendingDir(ctx: Context) = File(ctx.filesDir, "rg-pending").apply { mkdirs() }
    private fun pendingIndex(ctx: Context) = File(pendingDir(ctx), "index.json")

    /** Tagged copies waiting to be written: cached file name → song (+ how often it was tried). */
    @Synchronized private fun readPending(ctx: Context): org.json.JSONArray =
        try { org.json.JSONArray(pendingIndex(ctx).readText()) } catch (_: Exception) { org.json.JSONArray() }
    @Synchronized private fun writePending(ctx: Context, a: org.json.JSONArray) { try { pendingIndex(ctx).writeText(a.toString()) } catch (_: Exception) {} }

    /**
     * In-place tag update: the byte ranges that differ between the untagged copy and the tagged one (only the tag
     * area) are written straight into the file on the server. Safe:
     *  - the server's bytes there must still equal the copy's (the file didn't change meanwhile), else nothing is
     *    written;
     *  - a journal with the original and new bytes is saved on the phone first, so a crash or a lost connection
     *    mid-write is put right at the next start;
     *  - every written range is read back and compared; any mismatch puts the original bytes back.
     * Bytes outside the tag area are never sent, so a transfer error elsewhere in the copy can't reach the server.
     * Returns false when it doesn't apply (too many changes) — the full swap is used then.
     */
    private fun patchInPlace(ctx: Context, item: PlayItem, orig: File, tmp: File): Boolean {
        val ranges = ArrayList<LongArray>()                      // [pos, len]
        java.io.RandomAccessFile(orig, "r").use { a -> java.io.RandomAccessFile(tmp, "r").use { b ->
            val n = orig.length(); val ba = ByteArray(1 shl 16); val bb = ByteArray(1 shl 16)
            var pos = 0L; var start = -1L; var lastDiff = -1L
            while (pos < n) {
                val len = minOf(ba.size.toLong(), n - pos).toInt()
                a.seek(pos); a.readFully(ba, 0, len); b.seek(pos); b.readFully(bb, 0, len)
                for (i in 0 until len) if (ba[i] != bb[i]) {
                    val at = pos + i
                    if (start < 0) start = at
                    else if (at - lastDiff > 4096) { ranges += longArrayOf(start, lastDiff - start + 1); start = at }
                    lastDiff = at
                }
                pos += len
            }
            if (start >= 0) ranges += longArrayOf(start, lastDiff - start + 1)
        } }
        if (ranges.isEmpty()) return true                        // nothing to change
        if (ranges.size > 32 || ranges.sumOf { it[1] } > 2L * 1024 * 1024) return false
        fun read(f: File, at: Long, len: Int) = java.io.RandomAccessFile(f, "r").use { r -> ByteArray(len).also { r.seek(at); r.readFully(it) } }
        val before = ranges.map { read(orig, it[0], it[1].toInt()) }
        val after = ranges.map { read(tmp, it[0], it[1].toInt()) }
        val u = item.uri
        val f = SmbFile(VpnLink.route(Uri.decode(u)), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))
        update { s -> s.copy(current = item.title, phase = "Writing the tags") }
        if (f.length() != orig.length()) throw IllegalStateException("the file changed on the server — nothing changed")
        val jdir = File(ctx.filesDir, "rgpatch").apply { mkdirs() }
        val journal = File(jdir, Integer.toHexString(u.hashCode()) + "-" + System.nanoTime() + ".json")
        fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        val ja = org.json.JSONArray()
        ranges.forEachIndexed { i, rg -> ja.put(JSONObject().put("pos", rg[0]).put("old", b64(before[i])).put("new", b64(after[i]))) }
        java.io.FileOutputStream(journal).use { o -> o.write(JSONObject().put("uri", u).put("sourceId", item.sourceId)
            .put("smbVersion", item.smbVersion).put("ranges", ja).toString().toByteArray()); o.fd.sync() }
        (f.openRandomAccess("rw") as jcifs.smb.SmbRandomAccessFile).use { raf ->
            fun get(at: Long, len: Int) = ByteArray(len).also { b -> raf.seek(at); var k = 0; while (k < len) { val n = raf.read(b, k, len - k); if (n <= 0) break; k += n } }
            // The server still holds exactly what was read: else nothing is written.
            ranges.forEachIndexed { i, rg -> if (!get(rg[0], rg[1].toInt()).contentEquals(before[i])) { journal.delete(); throw IllegalStateException("the file changed on the server — nothing changed") } }
            try {
                ranges.forEachIndexed { i, rg -> raf.seek(rg[0]); raf.write(after[i]) }
                ranges.forEachIndexed { i, rg -> if (!get(rg[0], rg[1].toInt()).contentEquals(after[i])) throw IllegalStateException("read-back mismatch") }
            } catch (e: Exception) {
                try {
                    ranges.forEachIndexed { i, rg -> raf.seek(rg[0]); raf.write(before[i]) }
                    if (ranges.withIndex().all { (i, rg) -> get(rg[0], rg[1].toInt()).contentEquals(before[i]) }) journal.delete()
                } catch (_: Exception) {}
                throw IllegalStateException("tags couldn't be written safely (${e.message}) — original kept")
            }
        }
        journal.delete()
        return true
    }

    /**
     * App start: an in-place update cut off (crash, connection lost) is finished or undone — each range is either
     * all new (done) or put back to the original bytes.
     */
    fun recoverPatches(ctx: Context) {
        val jdir = File(ctx.filesDir, "rgpatch")
        val list = jdir.listFiles()?.filter { it.name.endsWith(".json") } ?: return
        if (list.isEmpty()) return
        scope.launch {
            for (j in list) try {
                val o = JSONObject(j.readText())
                val s = App.instance.store.current.sources.firstOrNull { it.id == o.optString("sourceId") } ?: continue
                val f = SmbFile(VpnLink.route(Uri.decode(o.getString("uri"))), SmbProbe.fileContext(o.optString("smbVersion"), s.user, s.password, s.domain))
                val a = o.getJSONArray("ranges")
                val rs = (0 until a.length()).map { a.getJSONObject(it) }
                fun dec(x: String) = android.util.Base64.decode(x, android.util.Base64.NO_WRAP)
                (f.openRandomAccess("rw") as jcifs.smb.SmbRandomAccessFile).use { raf ->
                    fun get(at: Long, len: Int) = ByteArray(len).also { b -> raf.seek(at); var k = 0; while (k < len) { val n = raf.read(b, k, len - k); if (n <= 0) break; k += n } }
                    val allNew = rs.all { r -> dec(r.getString("new")).let { get(r.getLong("pos"), it.size).contentEquals(it) } }
                    if (!allNew) rs.forEach { r -> raf.seek(r.getLong("pos")); raf.write(dec(r.getString("old"))) }
                }
                j.delete()
                app.vlcinterface.data.ServerLog.log(app.vlcinterface.data.ServerLog.REPLAYGAIN, "recover", "", "", true,
                    "Interrupted tag update put right: ${Uri.decode(o.getString("uri")).substringAfterLast('/')}")
            } catch (_: Exception) { /* not reachable now: tried again at the next start */ }
        }
    }

    @Synchronized private fun addPending(ctx: Context, item: PlayItem, tmp: File) {
        val a = readPending(ctx)
        // A newer copy for the same file replaces the older one.
        val keep = org.json.JSONArray()
        for (k in 0 until a.length()) { val o = a.getJSONObject(k)
            if (o.optJSONObject("e")?.optString("uri") == item.uri) File(pendingDir(ctx), o.optString("f")).delete() else keep.put(o) }
        val name = "${java.util.UUID.randomUUID()}.${item.ext.lowercase().ifEmpty { "bin" }}"
        if (!tmp.renameTo(File(pendingDir(ctx), name))) { tmp.copyTo(File(pendingDir(ctx), name), true); tmp.delete() }
        keep.put(org.json.JSONObject().put("f", name).put("e", MusicLibrary.toPlEntry(item).toJson()).put("tries", 0))
        writePending(ctx, keep)
        ServerLog.log(item.uri, "replaygain", item.uri.substringBefore("://").uppercase(), item.user, true,
            "${item.title}: file in use by the player — tagged copy cached, written when it is no longer playing")
    }

    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Writes the cached copies whose files the player is not using (a moment after a song change / stop). */
    fun flushPendingSoon(ctx: Context) {
        val app = ctx.applicationContext
        if (readPending(app).length() == 0 || !flushing.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + CrashLog.guard).launch {
            try {
                kotlinx.coroutines.delay(1500)          // the player has really let go of the previous file
                val playing = App.instance.player.state.value.item?.uri
                val a = readPending(app); val keep = org.json.JSONArray()
                for (k in 0 until a.length()) {
                    val o = a.getJSONObject(k)
                    val f = File(pendingDir(app), o.optString("f"))
                    val item = MusicLibrary.fromPlEntry(PlEntry.fromJson(o.getJSONObject("e")))
                    if (!f.exists()) continue
                    if (item.uri == playing) { keep.put(o); continue }
                    // Share read-only meanwhile: the values stay in the app, the waiting copy is dropped.
                    if (WriteGuard.isReadOnly(item)) { f.delete(); continue }
                    try {
                        quietCopy.set(true)
                        upload(app, item, App.instance.store.current.sources.firstOrNull { it.id == item.sourceId }, f)
                        RgStore.remove(item.uri); f.delete()
                        ServerLog.log(item.uri, "replaygain", item.uri.substringBefore("://").uppercase(), item.user, true,
                            "${item.title}: cached ReplayGain written to the file")
                    } catch (e: Exception) {
                        val tries = o.optInt("tries") + 1
                        // Values stay in the app either way; give up on the file after many tries.
                        if (tries >= 20) { f.delete()
                            ServerLog.log(item.uri, "replaygain", item.uri.substringBefore("://").uppercase(), item.user, false,
                                "${item.title}: cached ReplayGain could not be written (${e.message}) — kept in the app")
                        } else keep.put(o.put("tries", tries))
                    } finally { quietCopy.set(false) }
                }
                // Copies added while this ran are kept too.
                val now = readPending(app); val seen = (0 until a.length()).map { a.getJSONObject(it).optString("f") }.toSet()
                for (k in 0 until now.length()) now.getJSONObject(k).let { if (it.optString("f") !in seen) keep.put(it) }
                writePending(app, keep)
            } finally { flushing.set(false) }
        }
    }
}

/** ReplayGain values measured by the scanner and kept inside the app (for files that weren't written). */
/** When each song was last measured for ReplayGain (any scan, any target), to warn before scanning it again. */
object RgScanDates {
    const val DAYS = 10
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("rgScanDates", Context.MODE_PRIVATE)
    fun record(ctx: Context, uri: String) = try { prefs(ctx).edit().putLong(uri, System.currentTimeMillis()).apply() } catch (_: Exception) {}
    /** When [uri] was measured, if less than [DAYS] days ago. */
    fun recent(uri: String): Long? = try {
        prefs(app.vlcinterface.App.instance).getLong(uri, 0L).takeIf { it > 0 && System.currentTimeMillis() - it < DAYS * 86_400_000L }
    } catch (_: Exception) { null }
    fun isNetwork(uri: String) = uri.contains("://") && !uri.startsWith("content://") && !uri.startsWith("file://")
}

object RgStore {
    private lateinit var file: File
    private val map = HashMap<String, Map<String, String>>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "replaygain.json")
        try {
            if (file.exists()) JSONObject(file.readText()).let { o ->
                o.keys().forEach { k -> val v = o.getJSONObject(k); map[k] = v.keys().asSequence().associateWith { v.getString(it) } }
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun put(uri: String, values: Map<String, String>) {
        map[uri] = values
        try { file.writeText(JSONObject(map.mapValues { JSONObject(it.value) }).toString()) } catch (_: Exception) {}
    }

    @Synchronized fun remove(uri: String) {
        if (map.remove(uri) != null) try { file.writeText(JSONObject(map.mapValues { JSONObject(it.value) }).toString()) } catch (_: Exception) {}
    }

    @Synchronized fun get(uri: String): Rg? = map[uri]?.let { v ->
        fun f(k: String) = v[k]?.replace("dB", "")?.trim()?.toFloatOrNull()
        Rg(f("REPLAYGAIN_TRACK_GAIN"), f("REPLAYGAIN_TRACK_PEAK"), f("REPLAYGAIN_ALBUM_GAIN"), f("REPLAYGAIN_ALBUM_PEAK"))
    }
}

/** Writes REPLAYGAIN_* tags with jaudiotagger: ID3v2 TXXX (MP3), Vorbis comments (FLAC, Ogg), iTunes freeform (M4A). */
object TagWriter {
    init { Logger.getLogger("org.jaudiotagger").level = Level.OFF }

    fun write(f: File, values: Map<String, String>) {
        val af = org.jaudiotagger.audio.AudioFileIO.read(f)
        when (af) {
            is org.jaudiotagger.audio.mp3.MP3File -> {
                val tag = af.iD3v2TagAsv24 ?: org.jaudiotagger.tag.id3.ID3v24Tag()
                values.forEach { (k, v) ->
                    val frame = org.jaudiotagger.tag.id3.ID3v24Frame(org.jaudiotagger.tag.id3.ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
                    frame.body = org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX(org.jaudiotagger.tag.id3.valuepair.TextEncoding.ISO_8859_1, k, v)
                    tag.setField(frame)
                }
                af.setID3v2Tag(tag)
                af.commit()
            }
            else -> {
                val tag = af.tagOrCreateAndSetDefault
                when (tag) {
                    // Old values written by other programs in another letter case (replaygain_… / REPLAYGAIN_…)
                    // are removed first, so the file never holds two different values for the same thing.
                    is org.jaudiotagger.tag.flac.FlacTag -> values.forEach { (k, v) ->
                        for (old in setOf(k, k.lowercase())) try { tag.vorbisCommentTag.deleteField(old) } catch (_: Exception) {}
                        tag.vorbisCommentTag.setField(k, v)
                    }
                    is org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag -> values.forEach { (k, v) ->
                        for (old in setOf(k, k.lowercase())) try { tag.deleteField(old) } catch (_: Exception) {}
                        tag.setField(k, v)
                    }
                    is org.jaudiotagger.tag.mp4.Mp4Tag -> values.forEach { (k, v) ->
                        for (old in setOf(k, k.lowercase())) try { tag.deleteField("----:com.apple.iTunes:$old") } catch (_: Exception) {}
                        tag.setField(org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField("----:com.apple.iTunes:${k.lowercase()}", "com.apple.iTunes", k.lowercase(), v))
                    }
                    else -> throw IllegalStateException("can't write ReplayGain tags to .${f.extension} files")
                }
                af.commit()
            }
        }
    }
}


/** "Tags & ReplayGain" for one file: the tag records Android reads from it, plus the ReplayGain values. */
object MediaInfo {
    data class Info(val tags: List<Pair<String, String>>, val fileRg: Rg?, val appRg: Rg?, val error: String = "")

    private val lines = android.util.LruCache<String, String>(200)

    /**
     * "Artist - Album - Year - Song" from the file's own tags (player's Up next). Parts the tags don't have are
     * left out; remembered, so each file is read once. Null when the tags can't be read.
     */
    suspend fun upNextLine(ctx: Context, item: PlayItem): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        lines.get(item.uri)?.let { return@withContext it }
        val r = android.media.MediaMetadataRetriever()
        var ds: MediaDataSource? = null
        try {
            val u = item.uri
            when {
                u.startsWith("content://") -> r.setDataSource(ctx, Uri.parse(u))
                u.startsWith("file://") -> r.setDataSource(Uri.parse(u).path)
                u.startsWith("/") -> r.setDataSource(u)
                u.startsWith("smb://") -> { ds = RgScanner.SmbSource(SmbFile(VpnLink.route(Uri.decode(u)), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); r.setDataSource(ds) }
                u.startsWith("http") -> r.setDataSource(u, if (item.user.isNotEmpty()) mapOf("Authorization" to okhttp3.Credentials.basic(item.user, item.password)) else emptyMap())
                else -> return@withContext null
            }
            fun t(k: Int) = r.extractMetadata(k)?.trim().orEmpty()
            val year = t(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR).ifEmpty { t(android.media.MediaMetadataRetriever.METADATA_KEY_DATE).take(4) }
                .takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
            val line = listOf(
                t(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST).ifEmpty { t(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) }.ifEmpty { item.artist },
                t(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).ifEmpty { item.album },
                year,
                t(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).ifEmpty { item.title },
            ).filter { it.isNotBlank() }.joinToString(" - ")
            if (line.isNotEmpty()) lines.put(item.uri, line)
            line.ifEmpty { null }
        } catch (_: Exception) { null } finally {
            try { r.release() } catch (_: Exception) {}
            try { ds?.close() } catch (_: Exception) {}
        }
    }

    suspend fun read(ctx: Context, item: PlayItem): Info = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val r = android.media.MediaMetadataRetriever()
        var ds: MediaDataSource? = null
        val tags = ArrayList<Pair<String, String>>()
        var err = ""
        try {
            val u = item.uri
            when {
                u.startsWith("content://") -> r.setDataSource(ctx, Uri.parse(u))
                u.startsWith("file://") -> r.setDataSource(Uri.parse(u).path)
                u.startsWith("/") -> r.setDataSource(u)
                u.startsWith("smb://") -> { ds = RgScanner.SmbSource(SmbFile(VpnLink.route(Uri.decode(u)), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); r.setDataSource(ds) }
                u.startsWith("http") -> r.setDataSource(u, if (item.user.isNotEmpty()) mapOf("Authorization" to okhttp3.Credentials.basic(item.user, item.password)) else emptyMap())
                else -> throw IllegalStateException("tags can't be read from this kind of source")
            }
            fun add(label: String, key: Int, f: (String) -> String = { it }) { r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { tags += label to f(it) } }
            add("Title", android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            add("Artist", android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            add("Album artist", android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            add("Album", android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            add("Year", android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
            add("Date", android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
            add("Track", android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            add("Disc", android.media.MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            add("Genre", android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
            add("Composer", android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            add("Writer", android.media.MediaMetadataRetriever.METADATA_KEY_WRITER)
            add("Compilation", android.media.MediaMetadataRetriever.METADATA_KEY_COMPILATION)
            add("Duration", android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) { ms ->
                ms.toLongOrNull()?.let { val t = it / 1000; if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) } ?: ms }
            add("Bitrate", android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE) { b -> b.toLongOrNull()?.let { "${it / 1000} kbit/s" } ?: b }
            add("Sample rate", android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) { "$it Hz" }
            add("Bits per sample", android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            add("Format", android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w != null && h != null) tags += "Video" to "$w × $h"
            add("Rotation", android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) { "$it°" }
            if (r.embeddedPicture != null) tags += "Embedded picture" to "yes"
        } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
        finally { try { r.release() } catch (_: Exception) {}; ds?.close() }
        tags += "File" to item.fileName.ifEmpty { Uri.decode(item.uri).substringAfterLast('/') }
        // The folder on the phone / network (never a content:// address).
        tags += "Location" to PhysicalPath.of(ctx, item).substringBeforeLast('/')
        val fileRg = ReplayGain.fromFile(ctx, item.uri, item.user, item.password, item.domain, item.smbVersion)
        Info(tags, fileRg, RgStore.get(item.uri), err)
    }
}
