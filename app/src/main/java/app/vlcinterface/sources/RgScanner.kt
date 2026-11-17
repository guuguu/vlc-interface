package app.vlcinterface.sources

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
)

/**
 * ReplayGain scanner (like foobar2000's): decodes with Android's decoders, measures EBU R128 loudness,
 * writes REPLAYGAIN_* tags into the files (or keeps them in the app), album gain gated over the whole album.
 */
object RgScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RgScanState())
    val state: StateFlow<RgScanState> = _state
    private var job: Job? = null

    /** Phone files can only be changed after Android's own confirmation; the screen asks and calls back. */
    @Volatile private var phoneGrant: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    fun phonePermissionResult(ok: Boolean) { phoneGrant?.complete(ok) }

    fun reportSeen() { _state.value = _state.value.copy(reportPending = false, confirmTitle = "") }

    fun cancel() { job?.cancel(); _state.value = _state.value.copy(running = false, finished = true, phase = "Cancelled") }

    /**
     * [onlyWithoutRg]: only files with no ReplayGain record of any kind (file or app) are scanned; the rest are skipped.
     * [confirmTitle]: when not empty, a confirmation pop-up says what was done when the scan ends.
     */
    fun start(ctx: Context, items: List<PlayItem>, mode: RgMode, target: RgTarget, rescanTagged: Boolean,
              onlyWithoutRg: Boolean = false, confirmTitle: String = "") {
        if (_state.value.running) return
        if (app.vlcinterface.player.BatteryGuard.busyBlocked()) return     // battery 15 % or less: only playing
        val music = items.filter { it.isMusic && it.startMs < 0 }.distinctBy { it.uri }
        val cueSkipped = items.count { it.isMusic && it.startMs >= 0 }
        _state.value = RgScanState(running = true, total = music.size, phase = "Preparing")
        RgScanService.start(ctx)
        job = scope.launch {
            val results = ArrayList<RgResult>()
            if (cueSkipped > 0) results += RgResult("$cueSkipped .cue tracks", "skipped — part of one big file, scan that file instead", false)
            try {
                run(ctx, music, mode, target, rescanTagged, results, onlyWithoutRg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                results += RgResult("Scan", "stopped: ${e.message}", false)
            }
            _state.value = _state.value.copy(running = false, finished = true, current = "", phase = "Done", results = results.toList(),
                reportPending = results.any { !it.ok } || confirmTitle.isNotEmpty(), confirmTitle = confirmTitle)
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
                u.startsWith("smb://") -> { ds = SmbSource(SmbFile(Uri.decode(u), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); r.setDataSource(ds) }
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
        val workers = items.size.coerceIn(1, if (items.any { it.uri.startsWith("smb://", true) }) 2 else 4)
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
        parallel(toMeasure, workers) { it ->
            fracs[it.uri] = 0f
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
            if (m != null && !bad) meters[it.uri] = m
            fracs.remove(it.uri)
            update { s -> s.copy(done = s.done + 1, measured = s.measured + 1, problems = s.problems + if (m == null || bad) 1 else 0, results = snapshot()) }
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
            when {
                uri.startsWith("content://") -> ex.setDataSource(ctx, Uri.parse(uri), null)
                uri.startsWith("file://") -> ex.setDataSource(Uri.parse(uri).path!!)
                uri.startsWith("/") -> ex.setDataSource(uri)
                uri.startsWith("smb://") -> { ds = SmbSource(SmbFile(Uri.decode(uri), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); ex.setDataSource(ds) }
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
                                    onProgress((t.toFloat() / durUs).coerceIn(0f, 1f))
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
        return try {
            download(ctx, item, tmp)
            TagWriter.write(tmp, values)
            // Check the tagged copy before it replaces anything.
            val check = ReplayGain.parse(tmp.inputStream().use { s -> ByteArray(512 * 1024).let { b -> val n = s.read(b); b.copyOf(maxOf(n, 0)) } } +
                if (tmp.length() > 512 * 1024) java.io.RandomAccessFile(tmp, "r").use { r -> r.seek(tmp.length() - 65536); ByteArray(65536).also { r.readFully(it) } } else ByteArray(0))
            if (check.trackGain == null) throw IllegalStateException("tag check failed")
            // The file the player has open is never rewritten under it: the tagged copy waits until the song is over.
            if (App.instance.player.state.value.item?.uri == item.uri) {
                RgStore.put(item.uri, values)
                addPending(ctx, item, tmp)
                return "cached — written to the file after this song"
            }
            try { upload(ctx, item, src, tmp) } catch (e: Exception) {
                if (!inUse(e)) throw e
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
        } finally { tmp.delete() }
    }

    private fun download(ctx: Context, item: PlayItem, dst: File) {
        val u = item.uri
        val input = when {
            u.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(u))
            u.startsWith("file://") -> File(Uri.parse(u).path!!).inputStream()
            u.startsWith("/") -> File(u).inputStream()
            u.startsWith("smb://") -> SmbFile(Uri.decode(u), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)).inputStream
            u.startsWith("http") -> Net.http.newCall(Request.Builder().url(u)
                .apply { if (item.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(item.user, item.password)) }.build())
                .execute().let { r -> if (!r.isSuccessful) { r.close(); throw IllegalStateException("HTTP ${r.code}") }; r.body!!.byteStream() }
            else -> null
        } ?: throw IllegalStateException("can't read the file")
        input.use { i -> dst.outputStream().use { o -> copy(i, o, "Reading the file") } }
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

    private fun upload(ctx: Context, item: PlayItem, src: Source?, tmp: File) {
        val u = item.uri
        when {
            u.startsWith("content://") -> ctx.contentResolver.openOutputStream(Uri.parse(u), "wt")!!.use { o -> tmp.inputStream().use { copy(it, o, "Saving", tmp.length()) } }
            u.startsWith("file://") || u.startsWith("/") -> {
                val f = File(if (u.startsWith("/")) u else Uri.parse(u).path!!)
                val t = File(f.parentFile, f.name + ".rgtmp"); tmp.copyTo(t, true); if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
            }
            u.startsWith("smb://") -> {
                // Safe replace: write a tagged copy next to the file, then swap it in.
                val cx = SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)
                val orig = SmbFile(Uri.decode(u), cx)
                val t = SmbFile(Uri.decode(u) + ".rgtmp", cx)
                t.outputStream.use { o -> tmp.inputStream().use { copy(it, o, "Saving to the server", tmp.length()) } }
                if (t.length() != tmp.length()) { t.delete(); throw IllegalStateException("copy on the server is incomplete") }
                try { orig.delete() } catch (e: Exception) { try { t.delete() } catch (_: Exception) {}; throw e }
                t.renameTo(SmbFile(Uri.decode(u), cx))
            }
            u.startsWith("http") && src?.proto == "webdav" -> {
                Net.http.newCall(Request.Builder().url(u).put(tmp.asRequestBody("application/octet-stream".toMediaType()))
                    .apply { if (item.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(item.user, item.password)) }.build())
                    .execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("server refused (HTTP ${r.code})") }
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
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
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
                u.startsWith("smb://") -> { ds = RgScanner.SmbSource(SmbFile(Uri.decode(u), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); r.setDataSource(ds) }
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
                u.startsWith("smb://") -> { ds = RgScanner.SmbSource(SmbFile(Uri.decode(u), SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); r.setDataSource(ds) }
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
        tags += "Location" to Uri.decode(item.uri).let { u -> if (u.startsWith("content://")) item.folderKey.removePrefix("local:").ifEmpty { u } else u.substringBeforeLast('/') }
        val fileRg = ReplayGain.fromFile(ctx, item.uri, item.user, item.password, item.domain, item.smbVersion)
        Info(tags, fileRg, RgStore.get(item.uri), err)
    }
}
