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
import app.vlcinterface.player.PlayItem
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.CoroutineScope
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

    fun cancel() { job?.cancel(); _state.value = _state.value.copy(running = false, finished = true, phase = "Cancelled") }

    fun start(ctx: Context, items: List<PlayItem>, mode: RgMode, target: RgTarget, rescanTagged: Boolean) {
        if (_state.value.running) return
        val music = items.filter { it.isMusic && it.startMs < 0 }.distinctBy { it.uri }
        val cueSkipped = items.count { it.isMusic && it.startMs >= 0 }
        _state.value = RgScanState(running = true, total = music.size, phase = "Preparing")
        job = scope.launch {
            val results = ArrayList<RgResult>()
            if (cueSkipped > 0) results += RgResult("$cueSkipped .cue tracks", "skipped — part of one big file, scan that file instead", false)
            try {
                run(ctx, music, mode, target, rescanTagged, results)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                results += RgResult("Scan", "stopped: ${e.message}", false)
            }
            _state.value = _state.value.copy(running = false, finished = true, current = "", phase = "Done", results = results.toList())
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

    private suspend fun run(ctx: Context, items: List<PlayItem>, mode: RgMode, target: RgTarget, rescan: Boolean, results: ArrayList<RgResult>) {
        val groups = items.groupBy { groupKey(it, mode) }.values.toList()
        val albumMode = mode != RgMode.TRACK
        var done = 0
        // Phone files: one Android confirmation for all of them, before anything is written.
        if (target == RgTarget.FILES) {
            val phone = items.filter { it.uri.startsWith("content://") }.map { it.uri }
            if (phone.isNotEmpty()) {
                val d = kotlinx.coroutines.CompletableDeferred<Boolean>(); phoneGrant = d
                _state.value = _state.value.copy(phase = "Waiting for Android's permission to change ${phone.size} phone files", needsPhonePermission = phone)
                val ok = d.await()
                _state.value = _state.value.copy(needsPhonePermission = emptyList())
                if (!ok) { results += RgResult("Phone files", "not allowed — values kept in the app instead", false) }
                phoneAllowed = ok
            }
        }
        for (g in groups) {
            coroutineContext.ensureActive()
            // Skip rule: a track is skipped when tagged; an album only when every file in it is tagged.
            if (!rescan) {
                val tagged = g.map { it to (ReplayGain.read(ctx, it.uri, it.user, it.password, it.domain, it.smbVersion)?.let { r -> if (albumMode) r.trackGain != null && r.albumGain != null else r.trackGain != null } ?: false) }
                if (tagged.all { it.second }) {
                    g.forEach { results += RgResult(it.title, "skipped — already tagged", true) }
                    done += g.size; _state.value = _state.value.copy(done = done, results = results.toList()); continue
                }
            }
            val measured = ArrayList<Triple<PlayItem, Loudness, String>>()   // item, meter, error
            for (it in g) {
                coroutineContext.ensureActive()
                _state.value = _state.value.copy(current = it.title, phase = "Measuring")
                val m: Loudness? = try { measure(ctx, it) } catch (e: Exception) {
                    results += RgResult(it.title, "can't decode: ${e.message ?: e.javaClass.simpleName}", false); null
                }
                if (m != null) measured += Triple(it, m, "")
                done++; _state.value = _state.value.copy(done = done, results = results.toList())
            }
            if (measured.isEmpty()) continue
            val albumLufs = if (albumMode) Loudness.album(measured.map { it.second.blocks() }) else Double.NaN
            val albumPeak = measured.maxOf { it.second.peak }
            for ((it, m, _) in measured) {
                coroutineContext.ensureActive()
                val tl = m.integrated
                val values = LinkedHashMap<String, String>()
                values["REPLAYGAIN_TRACK_GAIN"] = "%.2f dB".format(java.util.Locale.US, Loudness.gainDb(tl))
                values["REPLAYGAIN_TRACK_PEAK"] = "%.6f".format(java.util.Locale.US, m.peak)
                if (albumMode) {
                    values["REPLAYGAIN_ALBUM_GAIN"] = "%.2f dB".format(java.util.Locale.US, Loudness.gainDb(albumLufs))
                    values["REPLAYGAIN_ALBUM_PEAK"] = "%.6f".format(java.util.Locale.US, albumPeak)
                }
                val summary = "track ${values["REPLAYGAIN_TRACK_GAIN"]}" + if (albumMode) " · album ${values["REPLAYGAIN_ALBUM_GAIN"]}" else ""
                _state.value = _state.value.copy(current = it.title, phase = "Writing")
                val where = save(ctx, it, values, target)
                results += RgResult(it.title, "$summary — $where", !where.startsWith("not"))
                ReplayGain.forget(it.uri)
                if (it.uri.contains("://") && !it.uri.startsWith("content://")) ServerLog.log(it.uri, "replaygain", it.uri.substringBefore("://").uppercase(), it.user, true, "${it.title}: $summary — $where")
                _state.value = _state.value.copy(results = results.toList())
            }
        }
    }

    @Volatile private var phoneAllowed = false

    // ---------- decoding ----------

    private class SmbSource(f: SmbFile) : MediaDataSource() {
        private val raf: SmbRandomAccessFile = f.openRandomAccess("r") as SmbRandomAccessFile
        private val len = f.length()
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= len) return -1
            raf.seek(position)
            return raf.read(buffer, offset, minOf(size.toLong(), len - position).toInt())
        }
        override fun getSize() = len
        override fun close() { try { raf.close() } catch (_: Exception) {} }
    }

    private fun measure(ctx: Context, item: PlayItem): Loudness {
        val ex = MediaExtractor()
        var ds: MediaDataSource? = null
        try {
            val uri = item.uri
            when {
                uri.startsWith("content://") -> ex.setDataSource(ctx, Uri.parse(uri), null)
                uri.startsWith("file://") -> ex.setDataSource(Uri.parse(uri).path!!)
                uri.startsWith("/") -> ex.setDataSource(uri)
                uri.startsWith("smb://") -> { ds = SmbSource(SmbFile(uri, SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); ex.setDataSource(ds) }
                uri.startsWith("http://") || uri.startsWith("https://") -> ex.setDataSource(uri,
                    if (item.user.isNotEmpty()) mapOf("Authorization" to okhttp3.Credentials.basic(item.user, item.password)) else emptyMap())
                else -> throw IllegalStateException("this kind of source can't be read for scanning")
            }
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalStateException("no audio track the phone can decode")
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var meter: Loudness? = null
            var floatOut = false
            var buf = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            try {
                while (true) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatOut = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            meter = Loudness(rate, ch)
                        }
                        oi >= 0 -> {
                            val ob = codec.getOutputBuffer(oi)!!
                            if (meter == null) meter = Loudness(rate, ch)
                            if (info.size > 0) {
                                ob.position(info.offset); ob.limit(info.offset + info.size); ob.order(ByteOrder.nativeOrder())
                                val samples = if (floatOut) info.size / 4 else info.size / 2
                                if (buf.size < samples) buf = FloatArray(samples)
                                if (floatOut) ob.asFloatBuffer().get(buf, 0, samples)
                                else { val sb = ob.asShortBuffer(); for (k in 0 until samples) buf[k] = sb.get(k) / 32768f }
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
        val src = App.instance.store.current.sources.firstOrNull { it.id == item.sourceId }
        val ext = item.ext.lowercase()
        val tmp = File(ctx.cacheDir, "rgscan.${ext.ifEmpty { "bin" }}")
        return try {
            download(ctx, item, tmp)
            TagWriter.write(tmp, values)
            // Check the tagged copy before it replaces anything.
            val check = ReplayGain.parse(tmp.inputStream().use { s -> ByteArray(512 * 1024).let { b -> val n = s.read(b); b.copyOf(maxOf(n, 0)) } } +
                if (tmp.length() > 512 * 1024) java.io.RandomAccessFile(tmp, "r").use { r -> r.seek(tmp.length() - 65536); ByteArray(65536).also { r.readFully(it) } } else ByteArray(0))
            if (check.trackGain == null) throw IllegalStateException("tag check failed")
            upload(ctx, item, src, tmp)
            "written to the file"
        } catch (e: Exception) {
            RgStore.put(item.uri, values)
            "not written (${e.message ?: e.javaClass.simpleName}) — kept in the app"
        } finally { tmp.delete() }
    }

    private fun download(ctx: Context, item: PlayItem, dst: File) {
        val u = item.uri
        val input = when {
            u.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(u))
            u.startsWith("file://") -> File(Uri.parse(u).path!!).inputStream()
            u.startsWith("/") -> File(u).inputStream()
            u.startsWith("smb://") -> SmbFile(u, SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)).inputStream
            u.startsWith("http") -> Net.http.newCall(Request.Builder().url(u)
                .apply { if (item.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(item.user, item.password)) }.build())
                .execute().let { r -> if (!r.isSuccessful) { r.close(); throw IllegalStateException("HTTP ${r.code}") }; r.body!!.byteStream() }
            else -> null
        } ?: throw IllegalStateException("can't read the file")
        input.use { i -> dst.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
    }

    private fun upload(ctx: Context, item: PlayItem, src: Source?, tmp: File) {
        val u = item.uri
        when {
            u.startsWith("content://") -> ctx.contentResolver.openOutputStream(Uri.parse(u), "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o, 256 * 1024) } }
            u.startsWith("file://") || u.startsWith("/") -> {
                val f = File(if (u.startsWith("/")) u else Uri.parse(u).path!!)
                val t = File(f.parentFile, f.name + ".rgtmp"); tmp.copyTo(t, true); if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
            }
            u.startsWith("smb://") -> {
                // Safe replace: write a tagged copy next to the file, then swap it in.
                val cx = SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)
                val orig = SmbFile(u, cx)
                val t = SmbFile("$u.rgtmp", cx)
                t.outputStream.use { o -> tmp.inputStream().use { it.copyTo(o, 256 * 1024) } }
                if (t.length() != tmp.length()) { t.delete(); throw IllegalStateException("copy on the server is incomplete") }
                orig.delete()
                t.renameTo(SmbFile(u, cx))
            }
            u.startsWith("http") && src?.proto == "webdav" -> {
                Net.http.newCall(Request.Builder().url(u).put(tmp.asRequestBody("application/octet-stream".toMediaType()))
                    .apply { if (item.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(item.user, item.password)) }.build())
                    .execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("server refused (HTTP ${r.code})") }
            }
            else -> throw IllegalStateException("this source is read-only")
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
                    is org.jaudiotagger.tag.flac.FlacTag -> values.forEach { (k, v) -> tag.vorbisCommentTag.setField(k, v) }
                    is org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag -> values.forEach { (k, v) -> tag.setField(k, v) }
                    is org.jaudiotagger.tag.mp4.Mp4Tag -> values.forEach { (k, v) ->
                        tag.setField(org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField("----:com.apple.iTunes:${k.lowercase()}", "com.apple.iTunes", k.lowercase(), v))
                    }
                    else -> throw IllegalStateException("can't write ReplayGain tags to .${f.extension} files")
                }
                af.commit()
            }
        }
    }
}
