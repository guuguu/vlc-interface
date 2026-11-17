package app.vlcinterface.sources

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Saving the song a radio station is playing.
 *
 * While a station plays (Settings → Saved songs → Keep radio ready to save), a second connection to the stream keeps
 * the last ~20 minutes on the phone, with the place each announced title (ICY "StreamTitle") began. "Save this song"
 * then cuts from where its title began to where the next one begins, moved to the quiet gap between songs when one is
 * found within ±10 s (titles often change a few seconds early or late), and checked against the song's catalogue
 * length. Saved as the station sends it (MP3 as is, AAC in an .m4a) or re-encoded to MP3 with LAME V0, with every tag
 * that can be found (title, artist, album, year, genre, track, picture, ReplayGain, where it was recorded).
 * MP3 and AAC streams only (no HLS).
 */
object RadioCapture {
    data class Mark(val title: String, val offset: Long, val at: Long)
    data class Pending(val title: String, val start: Long, val partialStart: Boolean, val station: String)
    data class Status(
        val url: String = "", val running: Boolean = false, val format: String = "", val message: String = "",
        val pending: Pending? = null, val busy: String = "", val title: String = "", val titleStartKnown: Boolean = false,
    )

    val status = MutableStateFlow(Status())
    /** Songs waiting to be cut / encoded / saved, in order (the first one is being worked on). */
    val saveQueue = MutableStateFlow<List<String>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Saving (decode, LAME, tags, copy) runs on one thread at Android's lowest priority, one song at a time, with a
     * short pause after each chunk: the live sound (its own high-priority audio thread) always goes first.
     */
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST); r.run() }, "song-saver").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    /** Between chunks: a short pause, and a full stop while background work is paused (the song resumes after). */
    private fun breathe() {
        try {
            Thread.sleep(3)
            while (app.vlcinterface.player.BackgroundWork.paused.value) Thread.sleep(300)
        } catch (_: InterruptedException) {}
    }
    private var job: Job? = null
    private val lock = Any()
    private lateinit var dir: File
    private class Seg(val file: File, val start: Long) { var length = 0L }
    private val segs = ArrayDeque<Seg>()
    private var total = 0L
    private var firstAt = 0L
    private val marks = ArrayList<Mark>()
    private var bytesPerSec = 16000.0
    private var station = ""

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    }

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "radiocap").apply { deleteRecursively(); mkdirs() }
        val player = App.instance.player
        // Follows the player and the network: on while a station plays and the setting allows it.
        scope.launch {
            combine(player.state, app.vlcinterface.player.NetGuard.cellular, App.instance.store.state) { s, cell, st ->
                val cur = s.item
                val mode = st.settings.radioCapture
                if (cur != null && cur.isRadio && !s.userPaused && !s.ended && (mode == "always" || (mode == "wifi" && !cell)))
                    cur.uri + "\u0000" + (st.stations.firstOrNull { x -> x.url == cur.uri }?.name ?: cur.title) else ""
            }.distinctUntilChanged().collect { key ->
                if (key.isEmpty()) stop() else { val (u, n) = key.split('\u0000'); start(u, n) }
            }
        }
    }

    private fun start(url: String, name: String) {
        if (status.value.running && status.value.url == url) return
        stop()
        station = name
        status.value = Status(url = url, running = true, message = "Connecting…")
        job = scope.launch {
            var tries = 0
            var target = url
            while (isActive && tries < 4) {
                try {
                    val next = capture(target) { isActive }
                    if (next != null) { target = next; continue }   // a playlist: its first stream
                } catch (e: Exception) {
                    if (!isActive) break
                    status.value = status.value.copy(message = "Recording interrupted: ${e.message ?: e.javaClass.simpleName}")
                }
                if (status.value.format == "unsupported") break
                tries++; delay(2000L * tries)
            }
            synchronized(lock) { status.value.pending?.let { status.value = status.value.copy(pending = null); finishLater(it, total, partialEnd = true) } }
            status.value = status.value.copy(running = false)
        }
    }

    fun stop() {
        val p = status.value.pending
        job?.cancel(); job = null
        try { call?.cancel() } catch (_: Exception) {}; call = null
        if (p != null) finishLater(p, total, partialEnd = true)
        synchronized(lock) {
            // Byte offsets never restart, so a song still waiting to be saved keeps its data until it's taken.
            marks.clear()
            if (p == null && saveQueue.value.isEmpty()) { segs.forEach { it.file.delete() }; segs.clear() }
        }
        status.value = status.value.copy(running = false, pending = null, message = "")
    }

    /** Reads the stream, keeps its audio and the title marks. Returns another address when it was a playlist. */
    @Volatile private var call: okhttp3.Call? = null

    private fun capture(url: String, alive: () -> Boolean): String? {
        val rq = Request.Builder().url(url).header("Icy-MetaData", "1").header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build()
        val c = http.newCall(rq); call = c
        c.execute().use { r ->
            if (!r.isSuccessful) throw Exception("server answered ${r.code}")
            val ct = (r.header("Content-Type") ?: "").lowercase()
            val body = r.body ?: throw Exception("no data")
            val input = body.byteStream()
            if ("mpegurl" in ct || "scpls" in ct || "x-scpls" in ct || url.substringBefore('?').lowercase().let { it.endsWith(".m3u") || it.endsWith(".pls") || it.endsWith(".m3u8") }) {
                val raw = java.io.ByteArrayOutputStream(); val tmp = ByteArray(8192)
                while (raw.size() < 64 * 1024) { val k = input.read(tmp); if (k < 0) break; raw.write(tmp, 0, k) }
                val text = raw.toString("UTF-8")
                if ("#EXT-X-" in text) { unsupported("HLS stream"); return null }
                return Regex("https?://\\S+").find(text.lines().filterNot { it.startsWith("#") }.joinToString("\n").replace(Regex("File\\d+="), ""))?.value
                    ?: throw Exception("empty playlist")
            }
            val fmt = when {
                "mpeg" in ct || "mp3" in ct -> "mp3"
                "aac" in ct || "mp4a" in ct -> "aac"
                else -> { unsupported(ct.ifEmpty { "unknown format" }); return null }
            }
            r.header("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull()?.takeIf { it in 8..1000 }?.let { bytesPerSec = it * 125.0 }
            val metaint = r.header("icy-metaint")?.toIntOrNull() ?: 0
            val segBytes = (bytesPerSec * 30).toLong().coerceIn(64_000, 4_000_000)
            val keepSegs = 44   // ~22 minutes
            synchronized(lock) {
                marks.clear()
                if (status.value.pending == null && saveQueue.value.isEmpty()) { segs.forEach { it.file.delete() }; segs.clear() }
                firstAt = System.currentTimeMillis()
            }
            status.value = status.value.copy(format = fmt, message = if (metaint > 0) "" else "This station sends no song titles: songs can't be found on their own")
            val buf = ByteArray(16 * 1024)
            var out: java.io.FileOutputStream? = null
            try {
                var toMeta = metaint
                while (alive()) {
                    val want = if (metaint > 0) minOf(buf.size, toMeta) else buf.size
                    val n = input.read(buf, 0, want)
                    if (n < 0) throw Exception("stream ended")
                    synchronized(lock) {
                        var seg = segs.lastOrNull()
                        if (seg == null || seg.length >= segBytes) {
                            out?.close()
                            seg = Seg(File(dir, "s${System.nanoTime()}.bin"), total); segs.addLast(seg)
                            out = java.io.FileOutputStream(seg.file)
                            while (segs.size > keepSegs) segs.removeFirst().file.delete()
                            val first = segs.first().start
                            // Marks before the oldest kept byte: only the latest one stays (its song is still in progress).
                            val old = marks.filter { it.offset < first }
                            if (old.size > 1) marks.removeAll(old.dropLast(1).toSet())
                        }
                        out!!.write(buf, 0, n); seg.length += n; total += n
                        val secs = (System.currentTimeMillis() - firstAt) / 1000.0
                        if (secs > 20) bytesPerSec = total / secs
                    }
                    if (metaint > 0) {
                        toMeta -= n
                        if (toMeta == 0) {
                            val len = input.read() * 16
                            if (len > 0) {
                                val meta = ByteArray(len); var got = 0
                                while (got < len) { val k = input.read(meta, got, len - got); if (k < 0) throw Exception("stream ended"); got += k }
                                val s = String(meta, Charsets.UTF_8).trimEnd('\u0000')
                                Regex("StreamTitle='(.*?)';").find(s)?.groupValues?.get(1)?.trim()?.let { titleChanged(it) }
                            }
                            toMeta = metaint
                        }
                    }
                }
            } finally { try { out?.close() } catch (_: Exception) {} }
        }
        return null
    }

    private fun unsupported(what: String) {
        status.value = status.value.copy(format = "unsupported", running = false, message = "Songs from this station can't be saved ($what)")
    }

    private fun titleChanged(t: String) {
        synchronized(lock) {
            if (marks.lastOrNull()?.title == t) return
            val first = marks.isEmpty()
            marks += Mark(t, total, System.currentTimeMillis())
            // The first title heard was already playing: its start was not recorded.
            status.value = status.value.copy(title = t, titleStartKnown = !first)
            status.value.pending?.let { p -> if (p.title != t) { status.value = status.value.copy(pending = null); finishLater(p, total, partialEnd = false) } }
        }
    }

    /** Ask to save the song now playing (it is written when it ends). Returns a note for the person. */
    fun saveCurrent(): String {
        synchronized(lock) {
            val s = status.value
            if (!s.running) return s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)" }
            val m = marks.lastOrNull() ?: return "The station hasn't announced a song title yet"
            if (m.title.isBlank()) return "The station announces no song now"
            val first = segs.firstOrNull()?.start ?: 0
            val partial = !s.titleStartKnown || m.offset < first
            status.value = s.copy(pending = Pending(m.title, maxOf(m.offset, first), partial, station))
            return if (partial) "“${m.title}” began before recording started: it is saved from the part recorded, when it ends"
            else "“${m.title}” will be saved when it ends"
        }
    }

    fun cancelPending() { status.value = status.value.copy(pending = null) }

    // ---------------------------------------------------------------- cutting and saving

    private fun readRange(from: Long, to: Long): ByteArray {
        synchronized(lock) {
            val out = java.io.ByteArrayOutputStream((to - from).toInt().coerceAtLeast(0))
            for (s in segs) {
                val a = maxOf(from, s.start); val b = minOf(to, s.start + s.length)
                if (b <= a) continue
                RandomAccessFile(s.file, "r").use { f -> f.seek(a - s.start); val bb = ByteArray((b - a).toInt()); f.readFully(bb); out.write(bb) }
            }
            return out.toByteArray()
        }
    }

    private fun finishLater(p: Pending, end: Long, partialEnd: Boolean) {
        val first: Long; val margin: Long
        synchronized(lock) {
            first = segs.firstOrNull()?.start ?: 0
            margin = (bytesPerSec * 12).toLong()
        }
        val from = maxOf(first, p.start - margin)
        // Recording stopped: the data is taken now (the next station starts afresh).
        val now = if (partialEnd) try { readRange(from, minOf(total, end)) } catch (_: Exception) { null } else null
        // Otherwise a bit later: the margin after the end, to find the gap after the song.
        saveQueue.value = saveQueue.value + p.title
        scope.launch {
            if (!partialEnd) delay(13_000)
            val to = minOf(synchronized(lock) { total }, end + margin)
            val data = now ?: try { readRange(from, to) } catch (e: Exception) {
                saveQueue.value = saveQueue.value - p.title; toast("Saving failed: ${e.message}"); return@launch }
            // One at a time, in the order asked (single low-priority thread).
            kotlinx.coroutines.withContext(saver) { status.value = status.value.copy(busy = "Saving “${p.title}”…"); try {
                val where = save(App.instance, p, data, (p.start - from).toInt(), (end - from).toInt().coerceAtMost(data.size), partialEnd)
                ServerLog.log("saved-songs", "save", "radio", "", true, p.title, "From ${p.station} → $where" + (if (p.partialStart) " · beginning missing" else "") + (if (partialEnd) " · end missing" else ""))
                toast("Saved: ${p.title}")
            } catch (e: Throwable) {
                ServerLog.log("saved-songs", "save", "radio", "", false, p.title, e.message ?: e.javaClass.simpleName)
                toast("Saving “${p.title}” failed: ${e.message ?: e.javaClass.simpleName}")
            } finally { status.value = status.value.copy(busy = ""); saveQueue.value = saveQueue.value - p.title } }
        }
    }

    private fun toast(s: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(App.instance, s, Toast.LENGTH_LONG).show() }

    /** One audio frame of the stream. */
    private class Frame(val pos: Int, val len: Int, val samples: Int, val rate: Int, val channels: Int)

    private val mp3Br1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val mp3Br2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val aacRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    private fun frameAt(d: ByteArray, i: Int, fmt: String): Frame? {
        if (i + 7 > d.size) return null
        val b0 = d[i].toInt() and 0xFF; val b1 = d[i + 1].toInt() and 0xFF; val b2 = d[i + 2].toInt() and 0xFF; val b3 = d[i + 3].toInt() and 0xFF
        if (b0 != 0xFF) return null
        if (fmt == "mp3") {
            if ((b1 and 0xE0) != 0xE0) return null
            val ver = (b1 shr 3) and 3; val layer = (b1 shr 1) and 3
            if (ver == 1 || layer != 1) return null
            val bi = (b2 shr 4) and 15; val si = (b2 shr 2) and 3
            if (bi == 0 || bi == 15 || si == 3) return null
            val base = intArrayOf(44100, 48000, 32000)[si]
            val rate = when (ver) { 3 -> base; 2 -> base / 2; else -> base / 4 }
            val br = (if (ver == 3) mp3Br1 else mp3Br2)[bi] * 1000
            val pad = (b2 shr 1) and 1
            val len = (if (ver == 3) 144 else 72) * br / rate + pad
            return Frame(i, len, if (ver == 3) 1152 else 576, rate, if ((b3 shr 6) == 3) 1 else 2)
        } else {
            if ((b1 and 0xF6) != 0xF0) return null
            val si = (b2 shr 2) and 15; if (si >= aacRates.size) return null
            val ch = ((b2 and 1) shl 2) or (b3 shr 6)
            val len = ((b3 and 3) shl 11) or ((d[i + 4].toInt() and 0xFF) shl 3) or ((d[i + 5].toInt() and 0xFF) shr 5)
            if (len < 7) return null
            val blocks = (d[i + 6].toInt() and 3) + 1
            return Frame(i, len, 1024 * blocks, aacRates[si], ch.coerceAtLeast(1))
        }
    }

    /** All frames in the data; a frame counts only when the next one follows right after it (no false syncs). */
    private fun frames(d: ByteArray, fmt: String): List<Frame> {
        val out = ArrayList<Frame>()
        var i = 0
        while (i < d.size - 7) {
            val f = frameAt(d, i, fmt)
            if (f != null && (f.pos + f.len >= d.size - 7 || frameAt(d, f.pos + f.len, fmt) != null)) {
                if (f.pos + f.len > d.size) break
                out += f; i += f.len
            } else i++
        }
        return out
    }

    /** Decodes a raw MP3 / ADTS file; [onPcm] gets 16-bit interleaved samples. Returns (rate, channels). */
    private fun decode(file: File, onPcm: (ShortArray, Int, Int, Int) -> Unit): Pair<Int, Int> {
        val ex = MediaExtractor(); ex.setDataSource(file.path)
        val fmt = ex.getTrackFormat(0); ex.selectTrack(0)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    oi >= 0 -> {
                        if (info.size > 0) {
                            val bb = codec.getOutputBuffer(oi)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            bb.position(info.offset); bb.limit(info.offset + info.size)
                            val sa = ShortArray(info.size / 2); bb.asShortBuffer().get(sa)
                            onPcm(sa, sa.size / ch, rate, ch)
                            breathe()
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    }
                }
            }
        } finally { try { codec.stop() } catch (_: Exception) {}; codec.release(); ex.release() }
        return rate to ch
    }

    /** The quietest 50 ms step within [from, to] seconds (null when nothing there is quiet enough to be a gap). */
    private fun quietest(rms: FloatArray, step: Double, from: Double, to: Double): Double? {
        val a = (from / step).toInt().coerceIn(0, rms.size); val b = (to / step).toInt().coerceIn(0, rms.size)
        if (b - a < 4) return null
        var best = -1; var bestV = Float.MAX_VALUE
        // 300 ms windows: a real gap, not one quiet beat.
        for (i in a until b - 5) { val v = (i until i + 6).maxOf { rms[it] }; if (v < bestV) { bestV = v; best = i + 3 } }
        if (best < 0) return null
        val db = 20 * log10(bestV.toDouble().coerceAtLeast(1e-9))
        return if (db < -38) best * step else null
    }

    private fun save(ctx: Context, p: Pending, data: ByteArray, startByte: Int, endByte: Int, partialEnd: Boolean): String {
        val fmt = status.value.format.takeIf { it == "mp3" || it == "aac" } ?: throw Exception("format not supported")
        val fr = frames(data, fmt)
        if (fr.size < 20) throw Exception("too little audio recorded")
        val clean = File(dir, "clip.${if (fmt == "mp3") "mp3" else "aac"}")
        clean.outputStream().use { o -> fr.forEach { o.write(data, it.pos, it.len) } }
        // Time of each frame (seconds) from the clip's start.
        val t = DoubleArray(fr.size + 1); for (i in fr.indices) t[i + 1] = t[i] + fr[i].samples.toDouble() / fr[i].rate
        fun timeOfByte(b: Int): Double { val i = fr.indexOfFirst { it.pos >= b }; return if (i < 0) t.last() else t[i] }
        val nomStart = timeOfByte(startByte); val nomEnd = timeOfByte(endByte)

        // Pass 1: loudness every 50 ms, to find the gaps between songs.
        val step = 0.05
        val rmsList = ArrayList<Float>()
        var acc = 0.0; var cnt = 0; var stepFrames = 0
        decode(clean) { pcm, frames, rate, ch ->
            if (stepFrames == 0) stepFrames = (rate * step).toInt()
            for (f in 0 until frames) {
                var s = 0.0; for (c in 0 until ch) { val x = pcm[f * ch + c] / 32768.0; s += x * x }
                acc += s / ch; cnt++
                if (cnt >= stepFrames) { rmsList += sqrt(acc / cnt).toFloat(); acc = 0.0; cnt = 0 }
            }
        }
        val rms = rmsList.toFloatArray()
        var cutStart = if (p.partialStart) nomStart else quietest(rms, step, nomStart - 10, nomStart + 10) ?: nomStart
        var cutEnd = if (partialEnd) nomEnd else quietest(rms, step, nomEnd - 10, nomEnd + 10) ?: nomEnd
        // The catalogue length as a second opinion: a gap near start + length wins when the title change was far off.
        val (artist, title) = app.vlcinterface.player.splitNowPlaying(p.title)
        val info = try { kotlinx.coroutines.runBlocking { SongLinks.info(artist, title) } } catch (_: Exception) { null }
        if (!partialEnd && !p.partialStart && info != null && info.lengthMs > 30_000) {
            val want = cutStart + info.lengthMs / 1000.0
            if (abs(cutEnd - want) > 12) quietest(rms, step, want - 8, want + 8)?.let { cutEnd = it }
        }
        if (cutEnd - cutStart < 10) { cutStart = nomStart; cutEnd = nomEnd }

        val s = App.instance.store.current.settings
        val reencode = s.saveFormat != "original"
        val outExt = if (reencode || fmt == "mp3") "mp3" else "m4a"
        val out = File(dir, "out.$outExt"); out.delete()
        var lufs: Double; var peak: Float
        if (reencode) {
            val r = encodeMp3(clean, cutStart, cutEnd, out); lufs = r.first; peak = r.second
        } else {
            val i0 = t.indexOfFirst { it >= cutStart - 1e-6 }.coerceIn(0, fr.size - 1)
            val i1 = t.indexOfFirst { it >= cutEnd - 1e-6 }.let { if (it < 0) fr.size else it }.coerceIn(i0 + 1, fr.size)
            val part = File(dir, "part.${clean.extension}")
            part.outputStream().use { o -> for (i in i0 until i1) o.write(data, fr[i].pos, fr[i].len) }
            val m = measure(part); lufs = m.first; peak = m.second
            if (fmt == "mp3") part.copyTo(out, true) else remuxAdts(part, out)
            part.delete()
        }
        clean.delete()
        writeTags(out, p, artist, title, info, lufs, peak)
        val name = safeName(listOf(info?.artist ?: artist, info?.title ?: title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { p.title }) + ".$outExt"
        val (where, uri) = store(ctx, out, name, if (outExt == "mp3") "audio/mpeg" else "audio/mp4")
        out.delete()
        // Into today's session playlist.
        val item = app.vlcinterface.player.PlayItem(uri, info?.title ?: title.ifBlank { p.title }, artist = info?.artist ?: artist,
            album = info?.album ?: "", trackNo = info?.trackNo ?: 0, durationMs = ((cutEnd - cutStart) * 1000).toLong(), fileName = name)
        Handler(Looper.getMainLooper()).post { try { App.instance.player.addToSession(listOf(item)) } catch (_: Exception) {} }
        return where
    }

    private fun measure(f: File): Pair<Double, Float> {
        var meter: Loudness? = null
        decode(f) { pcm, frames, rate, ch ->
            val m = meter ?: Loudness(rate, ch).also { meter = it }
            m.add(FloatArray(frames * ch) { pcm[it] / 32768f }, frames)
        }
        val m = meter ?: return Double.NEGATIVE_INFINITY to 0f
        return Loudness.gated(m.blocks()) to m.peak
    }

    /** Pass 2: decode, keep [from, to] seconds (with 15 ms fades), measure loudness, encode with LAME V0. */
    private fun encodeMp3(src: File, from: Double, to: Double, out: File): Pair<Double, Float> {
        var enc: Mp3Encoder? = null; var meter: Loudness? = null
        var pos = 0L
        out.outputStream().buffered().use { o ->
            val (rate, ch0) = decode(src) { pcm, frames, rate, ch ->
                val a = (from * rate).toLong(); val b = (to * rate).toLong(); val fade = (rate * 0.015).toLong().coerceAtLeast(1)
                val s0 = (a - pos).coerceIn(0, frames.toLong()).toInt(); val s1 = (b - pos).coerceIn(0, frames.toLong()).toInt()
                if (s1 > s0) {
                    val outCh = ch.coerceAtMost(2)
                    val buf = ShortArray((s1 - s0) * outCh)
                    for (f in s0 until s1) {
                        val n = pos + f
                        val g = minOf(1.0, (n - a).toDouble() / fade, (b - n).toDouble() / fade).coerceIn(0.0, 1.0)
                        for (c in 0 until outCh) buf[(f - s0) * outCh + c] = (pcm[f * ch + c] * g).toInt().toShort()
                    }
                    val e = enc ?: Mp3Encoder(rate, outCh).also { enc = it }
                    val m = meter ?: Loudness(rate, outCh).also { meter = it }
                    m.add(FloatArray(buf.size) { buf[it] / 32768f }, s1 - s0)
                    o.write(e.encode(buf, s1 - s0))
                }
                pos += frames
            }
            if (rate <= 0 || ch0 <= 0) throw Exception("could not decode")
            enc?.let { o.write(it.flush()) }
        }
        enc?.let { e ->
            // The finished Xing/LAME header goes over the placeholder frame LAME wrote first.
            val tag = e.lameTag()
            if (tag.isNotEmpty()) RandomAccessFile(out, "rw").use { it.seek(0); it.write(tag) }
            e.close()
        } ?: throw Exception("nothing to encode")
        val m = meter!!
        return Loudness.gated(m.blocks()) to m.peak
    }

    private fun remuxAdts(src: File, out: File) {
        val ex = MediaExtractor(); ex.setDataSource(src.path)
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val fmt = ex.getTrackFormat(0); ex.selectTrack(0)
            val tr = mux.addTrack(fmt); mux.start()
            val bb = java.nio.ByteBuffer.allocate(64 * 1024); val info = MediaCodec.BufferInfo()
            var t0 = -1L
            while (true) {
                val n = ex.readSampleData(bb, 0); if (n < 0) break
                if (t0 < 0) t0 = ex.sampleTime
                info.set(0, n, ex.sampleTime - t0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                mux.writeSampleData(tr, bb, info); ex.advance()
            }
            mux.stop()
        } finally { mux.release(); ex.release() }
    }

    private fun writeTags(f: File, p: Pending, artist: String, title: String, info: SongLinks.Info?, lufs: Double, peak: Float) {
        org.jaudiotagger.tag.TagOptionSingleton.getInstance().isAndroid = true
        val af = org.jaudiotagger.audio.AudioFileIO.read(f)
        val tag = af.tagOrCreateAndSetDefault
        fun set(k: org.jaudiotagger.tag.FieldKey, v: String) { if (v.isNotBlank()) try { tag.setField(k, v) } catch (_: Exception) {} }
        set(org.jaudiotagger.tag.FieldKey.TITLE, info?.title ?: title.ifBlank { p.title })
        set(org.jaudiotagger.tag.FieldKey.ARTIST, info?.artist ?: artist)
        set(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, info?.artist ?: artist)
        info?.let { i ->
            set(org.jaudiotagger.tag.FieldKey.ALBUM, i.album)
            if (i.year > 0) set(org.jaudiotagger.tag.FieldKey.YEAR, i.year.toString())
            set(org.jaudiotagger.tag.FieldKey.GENRE, i.genre)
            if (i.trackNo > 0) set(org.jaudiotagger.tag.FieldKey.TRACK, i.trackNo.toString())
            if (i.trackCount > 0) set(org.jaudiotagger.tag.FieldKey.TRACK_TOTAL, i.trackCount.toString())
            if (i.discNo > 0) set(org.jaudiotagger.tag.FieldKey.DISC_NO, i.discNo.toString())
        }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        set(org.jaudiotagger.tag.FieldKey.COMMENT, "Recorded from ${p.station} on $date" +
            (if (p.partialStart) " (beginning missing)" else ""))
        set(org.jaudiotagger.tag.FieldKey.ENCODER, if (f.extension == "mp3" && App.instance.store.current.settings.saveFormat != "original") "LAME ${Mp3Encoder.version} V0 (vlc interface)" else "vlc interface (stream copy)")
        // Picture: the album's from the catalogue, else the radio picture shown on the player.
        val pic = info?.artworkUrl?.takeIf { it.isNotEmpty() }?.let { fetch(it) }
            ?: App.instance.player.state.value.artworkUrl.takeIf { it.startsWith("http") }?.let { fetch(it) }
        if (pic != null) try {
            val art = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
            art.binaryData = pic; art.mimeType = "image/jpeg"; art.pictureType = 3
            tag.deleteArtworkField(); tag.setField(art)
        } catch (_: Exception) {}
        af.tag = tag
        af.commit()
        if (!lufs.isInfinite() && !lufs.isNaN()) TagWriter.write(f, mapOf(
            "REPLAYGAIN_TRACK_GAIN" to "%.2f dB".format(java.util.Locale.US, Loudness.gainDb(lufs)),
            "REPLAYGAIN_TRACK_PEAK" to "%.6f".format(java.util.Locale.US, peak)))
    }

    private fun fetch(url: String): ByteArray? = try {
        Net.http.newCall(Request.Builder().url(url).header("User-Agent", Net.UA).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (_: Exception) { null }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120)

    /**
     * The root of the phone music library: the deepest folder holding at least 80 % of the phone's songs (not an
     * album folder itself), on the volume with the most songs — e.g. Music/. Saved songs go in <root>raw/ (as the
     * station sends them) or <root>mp3/ (re-encoded).
     */
    fun libraryRoot(ctx: Context): Pair<String, String> {
        val folders = try { kotlinx.coroutines.runBlocking { InternalSource.folders(ctx) } } catch (_: Exception) { emptyList() }
            .filter { it.songs > 0 && !Regex("(^|/)(raw|mp3)/$").containsMatchIn(it.relativePath) }
        if (folders.isEmpty()) return MediaStore.VOLUME_EXTERNAL_PRIMARY to "Music/"
        val vol = folders.groupBy { it.volume }.maxByOrNull { e -> e.value.sumOf { it.songs } }!!.key
        val fs = folders.filter { it.volume == vol }
        val t = fs.sumOf { it.songs }
        var prefix = ""
        while (true) {
            val below = fs.filter { it.relativePath.startsWith(prefix) && it.relativePath.length > prefix.length }
                .groupBy { prefix + it.relativePath.removePrefix(prefix).substringBefore('/') + "/" }
                .mapValues { e -> e.value.sumOf { it.songs } }
            val best = below.maxByOrNull { it.value } ?: break
            if (best.value < t * 0.8) break
            // An album folder (songs right in it) is not a root: its parent is.
            val direct = fs.filter { it.relativePath == best.key }.sumOf { it.songs }
            if (prefix.isNotEmpty() && direct >= t * 0.2) break
            prefix = best.key
            if (direct >= t * 0.2) break
        }
        return vol to prefix.ifEmpty { "Music/" }
    }

    /** The sub-folder [name] of a chosen folder (Android folder access), made when missing. */
    private fun subDir(ctx: Context, tree: Uri, name: String): Uri {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return DocumentsContract.createDocument(ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw Exception("could not make the $name folder")
    }

    /**
     * Into <library root>/raw (original) or <library root>/mp3 (re-encoded) — next to the phone library, or in the
     * folder chosen in Settings (its raw / mp3 sub-folders). Returns (where, file address).
     */
    private fun store(ctx: Context, f: File, name: String, mime: String): Pair<String, String> {
        val sub = if (App.instance.store.current.settings.saveFormat == "original") "raw" else "mp3"
        val tree = App.instance.store.current.settings.saveFolder
        if (tree.isNotEmpty()) try {
            val dir = subDir(ctx, Uri.parse(tree), sub)
            val doc = DocumentsContract.createDocument(ctx.contentResolver, dir, mime, name.substringBeforeLast('.'))
                ?: throw Exception("folder refused the file")
            ctx.contentResolver.openOutputStream(doc)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            return (Uri.decode(tree).substringAfterLast(':').ifEmpty { "chosen folder" } + "/$sub/" + name) to doc.toString()
        } catch (e: Exception) {
            ServerLog.log("saved-songs", "folder", "local", "", false, "Chosen saved-songs folder not writable — saved next to the library", e.message ?: "")
        }
        val (vol, root) = libraryRoot(ctx)
        fun insertAt(volume: String, rel: String): Pair<String, String> {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.getContentUri(volume), cv) ?: throw Exception("could not create the file in $rel")
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}; throw e }
            return "$rel$name" to uri.toString()
        }
        return try { insertAt(vol, "$root$sub/") }
        // Android only takes songs under Music/ (and a few others): the library root there when it isn't.
        catch (e: Exception) { insertAt(MediaStore.VOLUME_EXTERNAL_PRIMARY, "Music/$sub/") }
    }
}
