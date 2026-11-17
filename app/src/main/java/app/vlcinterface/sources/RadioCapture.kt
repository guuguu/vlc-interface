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
    data class Pending(val title: String, val start: Long, val partialStart: Boolean, val station: String,
                       val at: Long = System.currentTimeMillis(), val format: String = "",
                       /** Saved by hand: "start" = Start / Stop saving, "last" = the last X minutes, "timed" = for X minutes
                        *  from now (ends at [untilAt]); "" = the announced song (ends with it, or after [songsLeft] songs). */
                       val manual: String = "", val name: String = "",
                       val untilAt: Long = 0, val songsLeft: Int = 1, val extraAfterSongMs: Long = 0,
                       /** Format chosen when saving was asked: "original" or "mp3" (kept even if changed later). */
                       val out: String = App.instance.store.current.settings.saveFormat,
                       /** Song saves: when to ask what to do if its end hasn't been found (10 min after asking). */
                       val askAt: Long = System.currentTimeMillis() + 600_000L)
    data class Status(
        val url: String = "", val running: Boolean = false, val format: String = "", val message: String = "",
        val pending: Pending? = null, val busy: String = "", val title: String = "", val titleStartKnown: Boolean = false,
    )

    val status = MutableStateFlow(Status())
    /** Songs waiting to be cut / encoded / saved, in order (the first one is being worked on). */
    val saveQueue = MutableStateFlow<List<String>>(emptyList())
    /** The song being saved now: 0…1 done, and when its work began (time-left estimate on exit). */
    val saveProgress = MutableStateFlow(0f)
    @Volatile var saveStartedAt = 0L; private set
    private var stageBase = 0f; private var stageSpan = 0f; private var stageSec = 1.0
    private fun stage(base: Float, span: Float, totalSec: Double) { stageBase = base; stageSpan = span; stageSec = totalSec.coerceAtLeast(0.1); saveProgress.value = base }
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
    /** Ogg streams: each song's logical stream with its header pages (newest last). */
    private val chains = ArrayList<OggStream.Chain>()
    private var bytesPerSec = 16000.0
    private var station = ""

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    }

    fun init(ctx: Context) {
        // App ended last time in the middle of a song: its recording and any half-made save are simply dropped.
        dir = File(ctx.cacheDir, "radiocap").apply { deleteRecursively(); mkdirs() }
        scope.launch { cleanLeftovers(ctx) }
        scope.launch { while (true) { delay(1000); try { tick() } catch (_: Exception) {} } }
        val player = App.instance.player
        // Follows the player and the network: on while a station plays and the setting allows it.
        scope.launch {
            combine(player.state, app.vlcinterface.player.NetGuard.cellular, App.instance.store.state, holding) { s, cell, st, hold ->
                val cur = s.item
                val mode = st.settings.radioCapture
                // While held (connection just changed) the recording stays as it is, waiting for the decision.
                // "Always" (any network, your choice in Settings): goes on over mobile data, and even while the
                // player was stopped by the Wi-Fi-lost pop-up.
                val netPaused = mode == "always" && app.vlcinterface.player.NetGuard.stoppedPlayback
                if (cur != null && cur.isRadio && (!s.userPaused || netPaused) && !s.ended &&
                    (hold || (if (cell) mode == "always" else mode != "off")))
                    cur.uri + "\u0000" + (st.stations.firstOrNull { x -> x.url == cur.uri }?.name ?: cur.title) else ""
            }.distinctUntilChanged().collect { key ->
                wantKey = key
                if (key.isEmpty()) stop() else { val (u, n) = key.split('\u0000'); start(u, n) }
            }
        }
    }

    // ---------------------------------------------------------------- connection changes

    /** The radio's network buffer (PlayerController: ":network-caching=3000"): the outage it can cover. */
    const val BUFFER_MS = 3000L
    /** Connection lost / changed: the recording is held — kept if the connection is back within [BUFFER_MS] and the
     *  recording's own connection never broke (nothing missing), else deleted. */
    private val holding = MutableStateFlow(false)
    @Volatile private var broke = false
    private var holdJob: Job? = null
    @Volatile private var wantKey = ""

    private fun anyNetwork() = App.instance.store.current.settings.radioCapture == "always"

    fun netLost() {
        // "Always": your choice — nothing is held, dropped or deleted; recording goes on over the new network.
        if (anyNetwork()) return
        if (holding.value || (!status.value.running && segs.isEmpty())) return
        broke = false; holding.value = true
        holdJob = scope.launch {
            delay(BUFFER_MS)
            if (holding.value) { dropAll(); holding.value = false }
        }
    }

    fun netBack() {
        if (holding.value) {
            holdJob?.cancel(); holding.value = false
            if (broke) dropAll()
            else { ServerLog.log("saved-songs", "kept", "radio", "", true, "Connection back within the buffer: recording kept"); return }
        }
        // Recording deleted meanwhile: it starts again from zero.
        if (!status.value.running) wantKey.takeIf { it.isNotEmpty() }?.split('\u0000')?.let { (u, n) -> start(u, n) }
    }

    private fun start(url: String, name: String) {
        if (status.value.running && status.value.url == url) return
        stop()
        station = name
        status.value = Status(url = url, running = true, message = "Connecting…")
        job = scope.launch {
            var tries = 0
            var target = url
            var again = false
            while (isActive && tries < 4) {
                // Held after a connection change: no new connection until it's decided (never over mobile data).
                while (holding.value && isActive) delay(200)
                try {
                    val next = capture(target, again) { isActive }
                    again = true
                    if (next != null) { target = next; continue }   // a playlist: its first stream
                } catch (e: Exception) {
                    if (!isActive) break
                    again = true
                    if (holding.value) broke = true
                    status.value = status.value.copy(message = "Recording interrupted: ${e.message ?: e.javaClass.simpleName}")
                }
                if (status.value.format == "unsupported") break
                tries++; delay(2000L * tries)
            }
            synchronized(lock) { status.value.pending?.let { status.value = status.value.copy(pending = null); finishLater(it, total, partialEnd = true) } }
            status.value = status.value.copy(running = false)
        }
    }

    /**
     * Wi-Fi lost (mobile data now) or the connection dropped: recording stops and everything recorded is deleted —
     * the kept minutes, a song waiting to end or being saved by hand (not saved), and working copies not in use.
     * A song already being encoded finishes from the copy it holds.
     */
    fun dropAll() {
        synchronized(lock) {
            status.value = status.value.copy(pending = null)
            job?.cancel(); job = null
            try { call?.cancel() } catch (_: Exception) {}; call = null
            segs.forEach { it.file.delete() }; segs.clear(); marks.clear(); chains.clear()
            if (::dir.isInitialized && saveQueue.value.isEmpty())
                dir.listFiles()?.forEach { it.delete() }
            status.value = status.value.copy(running = false, message = "Recording stopped: connection changed (recording deleted)")
        }
        ServerLog.log("saved-songs", "drop", "radio", "", true, "Connection changed: radio recording deleted")
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

    private fun capture(url: String, reconnect: Boolean, alive: () -> Boolean): String? {
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
                "ogg" in ct || "opus" in ct -> "ogg"
                else -> { unsupported(ct.ifEmpty { "unknown format" }); return null }
            }
            r.header("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull()?.takeIf { it in 8..1000 }?.let { bytesPerSec = it * 125.0 }
            val metaint = r.header("icy-metaint")?.toIntOrNull() ?: 0
            val segBytes = (bytesPerSec * 30).toLong().coerceIn(64_000, 4_000_000)
            val keepSegs = 44   // ~22 minutes
            synchronized(lock) {
                // A new connection continues nothing: what was recorded before has a hole after it, so a song in
                // progress can't be saved whole (dropped) and the kept minutes start again.
                // ("Always": nothing is dropped — the song is kept, with the short jump where the stream broke.)
                if (reconnect && anyNetwork()) {
                    if (status.value.pending != null) ServerLog.log("saved-songs", "gap", "radio", "", true,
                        "Stream reconnected while “${status.value.pending?.title}” plays: kept (may have a short jump)")
                } else if (reconnect && status.value.pending != null) {
                    ServerLog.log("saved-songs", "drop", "radio", "", false, "“${status.value.pending?.title}” not saved: the stream was interrupted")
                    status.value = status.value.copy(pending = null)
                }
                if (!(reconnect && anyNetwork())) marks.clear()
                if (!(reconnect && anyNetwork()) && status.value.pending == null && saveQueue.value.isEmpty()) { segs.forEach { it.file.delete() }; segs.clear() }
                if (!(reconnect && anyNetwork())) chains.clear()
                firstAt = System.currentTimeMillis()
            }
            status.value = status.value.copy(format = fmt, message = if (metaint > 0 || fmt == "ogg") "" else "This station sends no song titles: save by hand (Start / Stop, or the last minutes)")
            // Ogg: song titles come with each new logical stream (and its header pages are kept for cutting).
            val watcher = if (fmt == "ogg") OggStream.Watcher { c ->
                synchronized(lock) { chains += c; while (chains.size > 60) chains.removeAt(0) }
                if (c.title.isNotBlank()) titleChanged(c.title, c.offset)
            } else null
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
                        out!!.write(buf, 0, n); seg.length += n
                        watcher?.feed(buf, 0, n, total)
                        total += n
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

    private fun titleChanged(t: String, at: Long = total) {
        synchronized(lock) {
            if (marks.lastOrNull()?.title == t) return
            val first = marks.isEmpty()
            marks += Mark(t, at, System.currentTimeMillis())
            // The first title heard was already playing: its start was not recorded.
            status.value = status.value.copy(title = t, titleStartKnown = !first)
            // The announced song ended (saving by hand goes on until you stop it): extended by songs or minutes, or saved.
            status.value.pending?.let { p ->
                if (p.manual.isEmpty() && p.title != t) when {
                    p.songsLeft > 1 -> status.value = status.value.copy(pending = p.copy(songsLeft = p.songsLeft - 1))
                    p.extraAfterSongMs > 0 -> status.value = status.value.copy(pending = p.copy(manual = "timed",
                        untilAt = System.currentTimeMillis() + p.extraAfterSongMs, extraAfterSongMs = 0))
                    else -> { status.value = status.value.copy(pending = null); finishLater(p, at, partialEnd = false) }
                }
            }
        }
    }

    /** Ask to save the song now playing (it is written when it ends). Returns a note for the person. */
    fun saveCurrent(name: String = ""): String {
        synchronized(lock) {
            val s = status.value
            if (!s.running) return s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)" }
            val m = marks.lastOrNull() ?: return "The station hasn't announced a song title yet"
            if (m.title.isBlank()) return "The station announces no song now"
            val first = segs.firstOrNull()?.start ?: 0
            val partial = !s.titleStartKnown || m.offset < first
            status.value = s.copy(pending = Pending(m.title, maxOf(m.offset, first), partial, station, m.at, s.format, name = name.trim()))
            return if (partial) "Song start not found: the whole recording (${((total - first) / bytesPerSec / 60).toInt()} min) is saved, up to the song's end"
            else "“${m.title}” will be saved when it ends"
        }
    }

    fun cancelPending() { status.value = status.value.copy(pending = null) }

    /** Saving for [minutes] from now (start and end moved to the gap between songs); can be stopped or extended. */
    fun saveFor(minutes: Int, name: String): String {
        synchronized(lock) {
            val s = status.value
            if (!s.running) return s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)" }
            if (s.pending != null) return "Already saving"
            val now = System.currentTimeMillis()
            status.value = s.copy(pending = Pending(marks.lastOrNull()?.title ?: "", total, false, station, now, s.format, "timed", name.trim(),
                untilAt = now + minutes * 60_000L))
            return "Saving for $minutes min"
        }
    }

    /** Longer: by [ms] (timed: later end; the announced song: that much after it ends). */
    fun extend(ms: Long): String {
        synchronized(lock) {
            val p = status.value.pending ?: return "Nothing is being saved"
            status.value = status.value.copy(pending = when (p.manual) {
                "timed" -> p.copy(untilAt = maxOf(p.untilAt, System.currentTimeMillis()) + ms)
                "", "gap" -> p.copy(extraAfterSongMs = p.extraAfterSongMs + ms)
                else -> return "Saving goes on until you stop it"
            })
            return "Extended by ${ms / 60_000} min"
        }
    }

    /** The announced song: the next one too. */
    fun addSong(): String {
        synchronized(lock) {
            val p = status.value.pending?.takeIf { it.manual.isEmpty() || it.manual == "gap" } ?: return "Only when saving to the end of a song"
            status.value = status.value.copy(pending = p.copy(songsLeft = p.songsLeft + 1))
            return "The next song is saved too"
        }
    }

    /** Stop now: what's recorded so far is saved (end moved to the nearest gap). */
    fun stopNow(): String {
        synchronized(lock) {
            val p = status.value.pending ?: return "Nothing is being saved"
            status.value = status.value.copy(pending = null)
            finishLater(p, total, partialEnd = false)
            return "Stopped: saved up to now"
        }
    }

    /** "No song end found in 10 minutes": show the question (stop and save / continue / discard). */
    val endQuestion = MutableStateFlow(false)

    fun answerStop(): String { endQuestion.value = false; return stopNow() }
    fun answerDiscard() { endQuestion.value = false; cancelPending() }
    fun answerContinue(minutes: Int): String {
        synchronized(lock) {
            endQuestion.value = false
            val p = status.value.pending ?: return "Nothing is being saved"
            status.value = status.value.copy(pending = p.copy(manual = "timed", untilAt = System.currentTimeMillis() + minutes * 60_000L))
            return "Saving $minutes min more"
        }
    }

    /** Timed saves end by themselves (checked every second); song saves ask after 10 min without an end. */
    private fun tick() {
        val p = status.value.pending
        val songMode = p != null && (p.manual.isEmpty() || p.manual == "gap")
        if (!songMode && endQuestion.value) endQuestion.value = false
        if (songMode && !endQuestion.value && System.currentTimeMillis() >= p!!.askAt) endQuestion.value = true
        if (p == null) return
        if (p.manual == "timed" && p.untilAt > 0 && System.currentTimeMillis() >= p.untilAt) synchronized(lock) {
            if (status.value.pending === p) { status.value = status.value.copy(pending = null); finishLater(p, total, partialEnd = false) }
        }
    }

    /** Seconds of the station kept now (what "the last X minutes" can reach). */
    fun recordedSec(): Double = synchronized(lock) { (total - (segs.firstOrNull()?.start ?: total)) / bytesPerSec }

    /** Saving by hand, A: from now (moved to the nearest gap within ±10 s) until [stopSaving]. */
    fun startSaving(name: String): String {
        synchronized(lock) {
            val s = status.value
            if (!s.running) return s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)" }
            if (s.pending != null) return "Already saving"
            status.value = s.copy(pending = Pending(marks.lastOrNull()?.title ?: "", total, false, station, System.currentTimeMillis(), s.format, "start", name.trim()))
            return "Saving from now — tap Stop saving at the end"
        }
    }

    fun stopSaving(): String {
        synchronized(lock) {
            val p = status.value.pending?.takeIf { it.manual == "start" } ?: return "Nothing is being saved"
            status.value = status.value.copy(pending = null)
            finishLater(p, total, partialEnd = false)
            return "Saving stopped: the song is cut at the nearest gap and saved"
        }
    }

    /** Saving by hand, B: the last [minutes] minutes, both ends moved to the gap between songs. */
    fun saveLast(minutes: Int, name: String): String {
        synchronized(lock) {
            val s = status.value
            if (!s.running) return s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)" }
            val first = segs.firstOrNull()?.start ?: return "Nothing recorded yet"
            val start = maxOf(first, total - (minutes * 60 * bytesPerSec).toLong())
            val p = Pending(marks.lastOrNull { it.offset <= start }?.title ?: "", start, false, station,
                System.currentTimeMillis() - minutes * 60_000L, s.format, "last", name.trim())
            finishLater(p, total, partialEnd = true)
            return "Saving the last $minutes min"
        }
    }

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
            kotlinx.coroutines.withContext(saver) { status.value = status.value.copy(busy = "Saving “${p.title}”…"); saveStartedAt = System.currentTimeMillis(); saveProgress.value = 0f; try {
                val where = save(App.instance, p, data, (p.start - from).toInt(), (end - from).toInt().coerceAtMost(data.size), partialEnd, from)
                ServerLog.log("saved-songs", "save", "radio", "", true, p.title, "From ${p.station} → $where" + (if (p.partialStart) " · beginning missing" else "") + (if (partialEnd) " · end missing" else ""))
                toast((if (p.out == "original") "Saved: " else "Encoding ended: ") + where.substringAfterLast('/'), long = false)
            } catch (e: Throwable) {
                ServerLog.log("saved-songs", "save", "radio", "", false, p.title.ifBlank { p.name.ifBlank { "(no title)" } },
                    (e.message ?: e.javaClass.simpleName) + (if (diag.isNotEmpty()) " — $diag" else ""))
                toast("Saving “${p.title}” failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // Working copies (the cut, the part, the made file before it's stored) never stay, saved or not:
                // only the one file in raw/ or mp3/ remains.
                dir.listFiles { f -> f.name.startsWith("clip.") || f.name.startsWith("part.") || f.name.startsWith("out.") }?.forEach { it.delete() }
                status.value = status.value.copy(busy = ""); saveQueue.value = saveQueue.value - p.title
            } }
        }
    }

    private fun toast(s: String, long: Boolean = true) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(App.instance, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }

    /**
     * Start-up clean-up after the app ended mid-save: files Android still holds as unfinished (ours) and
     * "….part" files in the chosen folder's raw / mp3 are deleted.
     */
    private fun cleanLeftovers(ctx: Context) {
        try {
            val args = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ctx.packageName))
            }
            val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            ctx.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                while (c.moveToNext()) try { ctx.contentResolver.delete(android.content.ContentUris.withAppendedId(coll, c.getLong(0)), null, null) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val tree = App.instance.store.current.settings.saveFolder
        if (tree.isNotEmpty()) for (sub in listOf("raw", "mp3")) try {
            val t = Uri.parse(tree)
            val d = findDir(ctx, t, sub) ?: continue
            val did = DocumentsContract.getDocumentId(d)
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(t, did),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1).endsWith(".part"))
                    try { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0))) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

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
    private fun decode(file: File, progress: Boolean = true, onPcm: (ShortArray, Int, Int, Int) -> Unit): Pair<Int, Int> {
        val ex = MediaExtractor(); ex.setDataSource(file.path)
        val fmt = ex.getTrackFormat(0); ex.selectTrack(0)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false; var doneSec = 0.0
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
                            doneSec += sa.size.toDouble() / ch / rate
                            if (progress) saveProgress.value = stageBase + stageSpan * (doneSec / stageSec).toFloat().coerceIn(0f, 1f)
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

    private const val STEP = 0.05

    /** Loudness (RMS) every 50 ms of a file. */
    private fun rmsOf(f: File, progress: Boolean = true): FloatArray {
        val rmsList = ArrayList<Float>()
        var acc = 0.0; var cnt = 0; var stepFrames = 0
        decode(f, progress) { pcm, frames, rate, ch ->
            if (stepFrames == 0) stepFrames = (rate * STEP).toInt()
            for (fr in 0 until frames) {
                var s = 0.0; for (c in 0 until ch) { val x = pcm[fr * ch + c] / 32768.0; s += x * x }
                acc += s / ch; cnt++
                if (cnt >= stepFrames) { rmsList += sqrt(acc / cnt).toFloat(); acc = 0.0; cnt = 0 }
            }
        }
        return rmsList.toFloatArray()
    }

    /** The gaps between songs: centres (seconds) of runs of 300 ms windows below -38 dBFS. */
    private fun gaps(rms: FloatArray): List<Double> {
        val out = ArrayList<Double>()
        var runStart = -1
        for (i in 0..rms.size - 6) {
            val quiet = 20 * log10((i until i + 6).maxOf { rms[it] }.toDouble().coerceAtLeast(1e-9)) < -38
            if (quiet && runStart < 0) runStart = i
            if (!quiet && runStart >= 0) { out += ((runStart + i - 1) / 2.0 + 3) * STEP; runStart = -1 }
        }
        if (runStart >= 0) out += ((runStart + rms.size - 6) / 2.0 + 3) * STEP
        return out
    }

    // ---------------------------------------------------------------- the whole song, found by the gaps

    /** Low-priority thread that looks for the song's start and end while it plays. */
    private val finder = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST); r.run() }, "song-finder").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    /** Decodes [from, to) and returns (gap byte offsets, their times from [from]). */
    private fun gapsIn(p: Pending, from: Long, to: Long): List<Pair<Long, Double>> {
        val data = readRange(from, to)
        val clip = clipOf(p.copy(start = from), data, from)
        val f = File(dir, "scan.${clip.cleanExt}")
        try {
            clip.write(0, clip.n, f)
            val rms = rmsOf(f, progress = false)
            return gaps(rms).map { g -> (from + clip.pos[clip.indexOfTime(g).coerceAtMost(clip.n - 1)]) to g }
        } finally { f.delete() }
    }

    /**
     * "This song" without announced titles: its start is the last gap before now (up to 10 min back, else the
     * start of the recording); its end, the first gap at least a minute after the start — watched every 10 s,
     * at most 15 min. Can be extended (+ minutes, + next song), stopped or cancelled like the others.
     */
    fun saveWholeSong(name: String): String {
        val p: Pending
        synchronized(lock) {
            val s = status.value
            if (!s.running) return s.message.ifEmpty { "Recording is off for this station (Settings → Saved songs)" }
            if (s.pending != null) return "Already saving"
            p = Pending(marks.lastOrNull()?.title ?: "", total, false, station, System.currentTimeMillis(), s.format, "gap", name.trim())
            status.value = s.copy(pending = p)
        }
        scope.launch(finder) { findSong(p) }
        return "Finding where this song began and where it ends…"
    }

    private suspend fun findSong(p0: Pending) {
        var p = p0
        val req = p0.start
        fun same() = status.value.pending?.let { it.manual == "gap" && it.at == p.at } == true
        try {
            // Start: the last gap before now.
            val first = synchronized(lock) { segs.firstOrNull()?.start ?: req }
            val from = maxOf(first, req - (bytesPerSec * 600).toLong())
            val before = if (req - from > bytesPerSec * 3) gapsIn(p, from, req) else emptyList()
            val start = before.lastOrNull { it.first < req - (bytesPerSec * 1).toLong() }
            synchronized(lock) {
                if (!same()) return
                p = p.copy(start = start?.first ?: from, partialStart = start == null,
                    at = System.currentTimeMillis() - (((req - (start?.first ?: from)) / bytesPerSec) * 1000).toLong())
                status.value = status.value.copy(pending = p)
            }
            if (start == null) toast("Song start not found: the whole recording (${((req - from) / bytesPerSec / 60).toInt()} min) is saved, up to the end you chose", long = false)
            // End: the first gap at least a minute into the song.
            var scanFrom = maxOf(p.start, req - (bytesPerSec * 2).toLong())
            var minEnd = p.start + (bytesPerSec * 60).toLong()
            while (true) {
                delay(10_000)
                if (!same()) return
                val to = synchronized(lock) { total }
                if (to - scanFrom < bytesPerSec * 6) continue
                val end = gapsIn(p, scanFrom, to).firstOrNull { it.first >= minEnd }
                scanFrom = to - (bytesPerSec * 2).toLong()
                if (end == null) continue
                synchronized(lock) {
                    val c = status.value.pending ?: return
                    if (!same()) return
                    when {
                        c.songsLeft > 1 -> {      // + next song: go on to the next gap
                            p = c.copy(songsLeft = c.songsLeft - 1); status.value = status.value.copy(pending = p)
                            minEnd = end.first + (bytesPerSec * 60).toLong()
                        }
                        c.extraAfterSongMs > 0 -> status.value = status.value.copy(pending = c.copy(manual = "timed",
                            untilAt = System.currentTimeMillis() + c.extraAfterSongMs, extraAfterSongMs = 0))
                        else -> { status.value = status.value.copy(pending = null); finishLater(c, end.first, partialEnd = false) }
                    }
                }
                if (status.value.pending?.manual != "gap") return
            }
        } catch (e: Exception) {
            ServerLog.log("saved-songs", "find", "radio", "", false, "Could not find the song's start / end", e.message ?: e.javaClass.simpleName)
            synchronized(lock) { if (same()) status.value = status.value.copy(pending = null) }
        }
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

    /**
     * A recorded part, in units the stream can be cut at (MP3 / AAC frames, Ogg pages): their place in the data,
     * the time each begins ([t], seconds; one more entry = the end), and how a run of them becomes a playable file.
     */
    /** What the last cut was made of (in the Saved songs log when a save fails). */
    @Volatile private var diag = ""

    private class Clip(val pos: IntArray, val t: DoubleArray, val cleanExt: String, val origExt: String, val origMime: String,
                       val write: (Int, Int, File) -> Unit, val remuxToM4a: Boolean = false) {
        val n get() = pos.size
        fun indexOfByte(b: Int): Int = pos.indexOfFirst { it >= b }.let { if (it < 0) n else it }
        fun indexOfTime(x: Double): Int = t.indexOfFirst { it >= x - 1e-6 }.let { if (it < 0) n else it }.coerceIn(0, n)
    }

    private fun clipOf(p: Pending, data: ByteArray, from: Long): Clip {
        when (p.format) {
            "mp3", "aac" -> {
                val fr = frames(data, p.format)
                if (fr.size < 20) throw Exception("too little audio recorded")
                val t = DoubleArray(fr.size + 1); for (i in fr.indices) t[i + 1] = t[i] + fr[i].samples.toDouble() / fr[i].rate
                val raw = if (p.format == "mp3") "mp3" else "aac"
                diag = "${p.format} ${fr.first().rate} Hz · ${fr.size} frames · ${"%.1f".format(java.util.Locale.US, t.last())} s"
                return Clip(IntArray(fr.size) { fr[it].pos }, t, raw, if (p.format == "mp3") "mp3" else "m4a",
                    if (p.format == "mp3") "audio/mpeg" else "audio/mp4",
                    { a, b, f -> f.outputStream().buffered().use { o -> for (i in a until b) o.write(data, fr[i].pos, fr[i].len) } },
                    remuxToM4a = p.format == "aac")
            }
            "ogg" -> {
                // The logical stream (song) the part begins in; pages of another one (the song before) are left out.
                val c = synchronized(lock) { chains.lastOrNull { it.offset <= from + 1 + maxOf(0L, p.start - from) } ?: chains.lastOrNull() }
                    ?: throw Exception("the stream's start (Ogg headers) wasn't recorded")
                // The song's audio pages: same logical stream, after its header pages (by place, not by page position:
                // some servers write positions as 0 everywhere).
                val hdrEnd = c.offset + c.headerLen
                val all = OggStream.pages(data).filter { it.serial == c.serial && from + it.pos >= hdrEnd && (it.flags and 2) == 0 }
                // A part must start on a page that begins a packet.
                val pg = all.dropWhile { (it.flags and 1) != 0 }
                if (pg.size < 10) throw Exception("too little audio recorded")
                val g = LongArray(pg.size)
                val counted = OggStream.pageSamples(c, data, pg)
                val g0: Long
                if (counted != null && counted.sum() > 0) {
                    // FLAC / Opus: positions counted from the audio itself (sample count at the end of each page).
                    var acc = 0L; for (i in pg.indices) { acc += counted[i]; g[i] = acc }
                    g0 = 0
                } else {
                    // Vorbis: the page positions (granule = position at the end of each page).
                    var last = -1L
                    for (i in pg.indices) { if (pg[i].granule != -1L) last = pg[i].granule; g[i] = last }
                    val first = g.indexOfFirst { it >= 0 }.coerceAtLeast(0)
                    val step = if (pg.size > first + 1 && g[first + 1] > g[first]) g[first + 1] - g[first] else 0L
                    g0 = (g[first] - step).coerceAtLeast(0)
                    for (i in 0 until first) g[i] = g0
                }
                if ((g.last() - g0) <= 0) throw Exception("the stream's positions can't be read (${c.codec})")
                val t = DoubleArray(pg.size + 1); for (i in pg.indices) t[i + 1] = maxOf(t[i], (g[i] - g0).toDouble() / c.rate)
                diag = "ogg ${c.codec} ${c.rate} Hz · ${pg.size} pages · ${"%.1f".format(java.util.Locale.US, t.last())} s" +
                    (if (counted != null) " (counted from frames)" else " (page positions)")
                return Clip(IntArray(pg.size) { pg[it].pos }, t, OggStream.ext(c.codec), OggStream.ext(c.codec), OggStream.mime(c.codec), { a, b, f ->
                    val base = if (a == 0) g0 else g[a - 1]
                    val part = pg.subList(a, b)
                    if (c.codec == "flac") OggStream.writeFlac(c, data, part, (g[b - 1] - base).coerceAtLeast(0), f)
                    else OggStream.writeOgg(c, data, part, base, f, g.copyOfRange(a, b))
                })
            }
            else -> throw Exception("format not supported")
        }
    }

    private fun save(ctx: Context, p: Pending, data: ByteArray, startByte: Int, endByte: Int, partialEnd: Boolean, from: Long = 0): String {
        diag = ""
        val clip = clipOf(p, data, from)
        val t = clip.t
        val clean = File(dir, "clip.${clip.cleanExt}")
        clip.write(0, clip.n, clean)
        val nomStart = t[clip.indexOfByte(startByte)]; val nomEnd = t[clip.indexOfByte(endByte)]

        // Pass 1: loudness every 50 ms, to find the gaps between songs.
        stage(0f, 0.35f, t.last())
        val step = STEP
        val rms = rmsOf(clean)
        val decodedSec = rms.size * step
        diag += " · decoded ${"%.1f".format(java.util.Locale.US, decodedSec)} s"
        if (decodedSec < 1) throw Exception("the recorded audio could not be decoded (${clip.cleanExt})")
        // Start: the gap within ±10 s (not when the song began before recording). End: the gap within ±10 s; for
        // "the last X minutes" only before now (nothing after it is recorded yet); none when recording stopped.
        var cutStart = if (p.partialStart) nomStart else quietest(rms, step, nomStart - 10, nomStart + 10) ?: nomStart
        var cutEnd = when {
            p.manual == "last" -> quietest(rms, step, nomEnd - 10, nomEnd) ?: nomEnd
            partialEnd -> nomEnd
            else -> quietest(rms, step, nomEnd - 10, nomEnd + 10) ?: nomEnd
        }
        val (artist, title) = app.vlcinterface.player.splitNowPlaying(p.name.ifBlank { p.title })
        // Song details from the catalogue — not over mobile data unless allowed.
        val info = if (app.vlcinterface.player.NetGuard.blocked() || title.isBlank()) null
            else try { kotlinx.coroutines.runBlocking { SongLinks.info(artist, title) } } catch (_: Exception) { null }
        // The catalogue length as a second opinion (announced songs): a gap near start + length wins when the
        // title change was far off.
        if (p.manual.isEmpty() && !partialEnd && !p.partialStart && info != null && info.lengthMs > 30_000) {
            val want = cutStart + info.lengthMs / 1000.0
            if (abs(cutEnd - want) > 12) quietest(rms, step, want - 8, want + 8)?.let { cutEnd = it }
        }
        if (cutEnd - cutStart < 10) { cutStart = nomStart; cutEnd = nomEnd }
        // Never past what decodes (a part longer than the decoded audio would leave nothing to encode).
        cutEnd = cutEnd.coerceAtMost(decodedSec); cutStart = cutStart.coerceIn(0.0, (cutEnd - 1).coerceAtLeast(0.0))
        diag += " · song ${"%.1f".format(java.util.Locale.US, nomStart)}–${"%.1f".format(java.util.Locale.US, nomEnd)} s, cut ${"%.1f".format(java.util.Locale.US, cutStart)}–${"%.1f".format(java.util.Locale.US, cutEnd)} s"
        if (cutEnd - cutStart < 1) throw Exception("nothing recorded between the song's start and end")

        val s = App.instance.store.current.settings
        val reencode = p.out != "original"
        val outExt = if (reencode) "mp3" else clip.origExt
        val out = File(dir, "out.$outExt")
        var lufs = 0.0; var peak = 0f
        fun produce() {
            out.delete()
            stage(0.35f, 0.6f, if (reencode) t.last() else (cutEnd - cutStart))
            if (reencode) {
                val r = encodeMp3(clean, cutStart, cutEnd, out); lufs = r.first; peak = r.second
            } else {
                val i0 = clip.indexOfTime(cutStart).coerceAtMost(clip.n - 1)
                val i1 = clip.indexOfTime(cutEnd).coerceIn(i0 + 1, clip.n)
                val part = File(dir, "part.${clip.cleanExt}")
                clip.write(i0, i1, part)
                val m = measure(part); lufs = m.first; peak = m.second
                if (clip.remuxToM4a) remuxAdts(part, out) else part.copyTo(out, true)
                part.delete()
            }
        }
        // Low priority only makes it slower, never different — and it's checked anyway before it's saved: the file
        // must read back whole (its length = the cut). If not, made again once at normal priority; a file that
        // still fails is never saved.
        val want = cutEnd - cutStart
        produce()
        if (!readsBack(out, want)) {
            val tid = android.os.Process.myTid()
            android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_DEFAULT)
            try { produce() } finally { android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_LOWEST) }
            if (!readsBack(out, want)) { out.delete(); clean.delete(); throw Exception("the made file did not check out — not saved") }
        }
        clean.delete()
        stage(0.95f, 0.05f, 1.0)
        try { writeTags(out, p, artist, title, info, lufs, peak) }
        catch (e: Exception) {
            // Opus files may not take tags here: the song is still saved, without them.
            if (outExt != "opus") throw e
            ServerLog.log("saved-songs", "tags", "radio", "", false, "Tags not written (.opus)", e.message ?: "")
        }
        // No proper song name (nothing announced, the station's own name, "Live", ads …): YY_MM_DD_hr_min.
        val nice = listOf(info?.artist ?: artist, info?.title ?: title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { p.name.ifBlank { p.title } }.trim()
        val proper = Regex("\\p{L}").containsMatchIn(nice) && !nice.equals(p.station, true) && !nice.contains("http", true) &&
            !Regex("^(live|unknown|untitled|advert|advertisement|ad|ads|commercial|news|jingle|station id|n/a)$", RegexOption.IGNORE_CASE).matches(nice)
        val name = (if (proper) safeName(nice) else java.text.SimpleDateFormat("yy_MM_dd_HH_mm", java.util.Locale.US).format(java.util.Date(p.at))) + ".$outExt"
        val (where, uri) = store(ctx, out, name, if (reencode) "audio/mpeg" else clip.origMime, p.out)
        out.delete()
        // Into today's session playlist.
        val item = app.vlcinterface.player.PlayItem(uri, info?.title ?: title.ifBlank { name.substringBeforeLast('.') }, artist = info?.artist ?: artist,
            album = info?.album ?: "", trackNo = info?.trackNo ?: 0, durationMs = ((cutEnd - cutStart) * 1000).toLong(), fileName = name)
        Handler(Looper.getMainLooper()).post { try { App.instance.player.addToSession(listOf(item)) } catch (_: Exception) {} }
        return where
    }

    /** The file opens and its length matches [wantSec] (±1.5 s or 2 %). */
    private fun readsBack(f: File, wantSec: Double): Boolean = try {
        if (!f.exists() || f.length() < 1000) false else {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.path)
                val fm = if (ex.trackCount > 0) ex.getTrackFormat(0) else null
                val us = if (fm != null && fm.containsKey(MediaFormat.KEY_DURATION)) fm.getLong(MediaFormat.KEY_DURATION) else -1L
                us > 0 && abs(us / 1e6 - wantSec) <= maxOf(1.5, wantSec * 0.02)
            } finally { ex.release() }
        }
    } catch (_: Exception) { false }

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
        set(org.jaudiotagger.tag.FieldKey.ENCODER, if (f.extension == "mp3" && p.out != "original") "LAME ${Mp3Encoder.version} V0 (vlc interface)" else "vlc interface (stream copy)")
        // Picture: the album's from the catalogue, else the radio picture shown on the player.
        val pic = if (app.vlcinterface.player.NetGuard.blocked()) null else info?.artworkUrl?.takeIf { it.isNotEmpty() }?.let { fetch(it) }
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

    private fun findDir(ctx: Context, tree: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
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
    private fun store(ctx: Context, f: File, name: String, mime: String, out: String): Pair<String, String> {
        val sub = if (out == "original") "raw" else "mp3"
        val tree = App.instance.store.current.settings.saveFolder
        if (tree.isNotEmpty()) try {
            val dir = subDir(ctx, Uri.parse(tree), sub)
            // Written as "<name>.part" and renamed when complete: an app ended mid-copy leaves only a .part (cleaned up).
            val tmp = DocumentsContract.createDocument(ctx.contentResolver, dir, "application/octet-stream", "$name.part")
                ?: throw Exception("folder refused the file")
            try { ctx.contentResolver.openOutputStream(tmp)!!.use { o -> f.inputStream().use { it.copyTo(o) } } }
            catch (e: Exception) { try { DocumentsContract.deleteDocument(ctx.contentResolver, tmp) } catch (_: Exception) {}; throw e }
            val doc = DocumentsContract.renameDocument(ctx.contentResolver, tmp, name) ?: tmp
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
