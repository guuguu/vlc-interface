package app.vlcinterface.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.vlcinterface.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Sound check (2026-11-21 22:28): what really happens to the playing song on this phone, measured, not assumed.
 *
 *  1. 10 s of what libVLC hands the app's sound engine and of what the engine writes to Android are captured.
 *  2. The same passage of the file is decoded a second, independent way — with Android's own decoder.
 *  3. Decoders: libVLC's samples are compared with Android's, sample by sample (same file, same passage).
 *  4. Engine: what goes out is compared with what came in (1.4 ms later, the limiter's look-ahead): untouched,
 *     only a level change (ReplayGain, preamp, volume), or more (tone, treble, limiter, rate conversion).
 *  5. Output: rates, conversion and who does it, Bluetooth codec and link rate, quality mode, volumes, sound engine.
 */
object SoundCheck {
    class Report(val title: String, val sections: List<Pair<String, List<Pair<String, String>>>>, val note: String)

    private fun db(x: Double) = if (x <= 0.0) "−∞ dB" else "%.1f dB".format(java.util.Locale.US, 20 * log10(x))

    suspend fun run(ctx: Context, progress: (String) -> Unit): Report = withContext(Dispatchers.Default) {
        val player = App.instance.player
        val item = player.state.value.item
        fun fail(why: String) = Report("Sound check", listOf("Sound check" to listOf("Result" to why)), "")
        if (item == null) return@withContext fail("Play something first: a song, a video or a radio station (the check listens to what plays).")
        if (!NativeAudio.active) return@withContext fail("The app's own sound output isn't in use (Settings → Audio → sound engine is libVLC's), so there is nothing of the app's to check.")
        if (!player.state.value.isPlaying) return@withContext fail("It's paused: press play, then start the check.")
        val seconds = 10
        val posMs = player.state.value.time
        if (!NativeAudio.capture(seconds)) return@withContext fail("Not enough memory for the capture.")
        // 1. Listen.
        var st: LongArray? = null
        for (i in 0 until (seconds + 8) * 4) {
            delay(250)
            st = NativeAudio.captureState()
            if (st == null || st[0] == 0L) break
            progress("Listening… ${minOf(seconds, i / 4)} / $seconds s")
        }
        val s0 = st ?: return@withContext fail("The capture couldn't be read.")
        val inN = s0[1].toInt(); val outN = s0[2].toInt()
        val got = NativeAudio.captureTake(inN, outN) ?: return@withContext fail("The capture couldn't be read.")
        if (inN < 4096) return@withContext fail("Too little sound was captured (was it paused or switched?). Try again.")
        val capIn = got.first; val capOut = got.second
        val src = s0[4].toInt(); val dev = s0[5].toInt()
        val eqOn = s0[6] != 0L; val trebleOn = s0[7] != 0L
        val gainL = s0[8] / 1e6; val gainR = s0[9] / 1e6; val appVol = s0[10] / 1e6; val vlcVol = s0[11] / 1e6
        val limFrames = s0[3]

        // 2. Android's own decoder, same passage (a few seconds around it, aligned below).
        progress("Decoding the file with Android's decoder…")
        // A live radio stream can't be read a second time at the same spot: decoding isn't compared there.
        val dec = if (item.isRadio) null else try { decode(ctx, item, ((posMs - 4000).coerceAtLeast(0)) * 1000L, seconds + 10) } catch (e: Exception) { null }

        // 3. Decoders compared.
        progress("Comparing…")
        val decRows = ArrayList<Pair<String, String>>()
        if (item.isRadio) decRows += "Live radio" to "a live stream can't be decoded a second time at the same moment — decoding not compared; engine and output below are measured"
        else if (dec == null) decRows += "Android's decoder" to "couldn't open this file (${item.uri.substringBefore(':')})"
        else {
            decRows += "File (Android reads)" to "${dec.rate} Hz · ${dec.channels} ch · ${dec.encoding}"
            decRows += "libVLC hands the engine" to "$src Hz · 2 ch · 16-bit"
            when {
                dec.rate != src -> decRows += "Rate" to "libVLC converted ${dec.rate} → $src Hz before the engine (soxr) — samples can't be compared one to one"
                dec.channels > 2 -> decRows += "Channels" to "the file has ${dec.channels} channels; libVLC mixes them to stereo — not compared"
                !item.isMusic && dec.frames == 0 -> decRows += "Video" to "Android's decoder gave no sound for this video — not compared"
                else -> {
                    val m = compareDecoders(capIn, inN, dec.pcm, dec.frames)
                    if (m == null) decRows += "Same samples?" to "couldn't line the two up (they differ by more than rounding)"
                    else {
                        decRows += "Same samples?" to when {
                            m.maxDiff == 0 -> "identical — every sample (${m.frames} frames)"
                            m.maxDiff <= 1 -> "identical to the last bit, except rounding (±1 of 32768): ${"%.2f".format(java.util.Locale.US, m.diffPct)} % of samples"
                            else -> "different: up to ${m.maxDiff} of 32768 on ${"%.2f".format(java.util.Locale.US, m.diffPct)} % of samples"
                        }
                        decRows += "Difference level" to (if (m.maxDiff == 0) "none" else db(m.errRms) + " under the music")
                        if (dec.encodingBits > 16) decRows += "Bit depth" to "the file has more than 16 bits; libVLC hands 16-bit (its app output only accepts 16-bit)"
                    }
                }
            }
        }

        // 4. Engine: out vs in.
        val engRows = ArrayList<Pair<String, String>>()
        engRows += "Digital level" to "left ${db(gainL)} · right ${db(gainR)} (ReplayGain, preamps, balance, app volume ${"%.0f".format(appVol * 100)} %, libVLC volume ${"%.0f".format(vlcVol * 100)} %)"
        engRows += "Tone (loudness bands)" to if (eqOn) "on" else "off"
        engRows += "Treble filter" to if (trebleOn) "on" else "off"
        engRows += "Limiter" to if (limFrames == 0L) "never acted" else "acted on ${"%.1f".format(java.util.Locale.US, limFrames * 100.0 / inN)} % of the time"
        if (src != dev) engRows += "Rate conversion" to "in the app: $src → $dev Hz (windowed sinc)"
        else {
            engRows += "Rate conversion" to "none ($src Hz in and out)"
            val e = compareEngine(capIn, inN, capOut, outN)
            engRows += "Out vs in" to when {
                e.identical -> "bit-identical (only 1.4 ms later) — the engine doesn't touch the sound"
                e.levelOnly -> "only a level change of ${db(e.gain)}, nothing else (residual ${db(e.residual)})"
                else -> "level ${db(e.gain)} and more (residual ${db(e.residual)} under the music — tone / treble / limiter)"
            }
        }

        // 4b. Dynamics, bandwidth and tone, measured on what came in and what went out (2026-11-28 13:34).
        progress("Measuring dynamics and bandwidth…")
        val mIn = FloatArray(inN) { i -> (capIn[i * 2] + capIn[i * 2 + 1]) / 65536f }
        val mOut = FloatArray(outN) { i -> (capOut[i * 2] + capOut[i * 2 + 1]) / 2f }
        val dIn = dynamics(mIn, src, capIn, inN); val dOut = dynamics(mOut, dev, null, 0)
        val dynRows = ArrayList<Pair<String, String>>()
        dynRows += "Peak (in → out)" to "${dIn.peakDb} → ${dOut.peakDb}"
        dynRows += "Average level, RMS (in → out)" to "${dIn.rmsDb} → ${dOut.rmsDb}"
        dynRows += "Crest factor, peak − RMS (in → out)" to "${dIn.crest} → ${dOut.crest}" +
            dIn.crestVal.let { a -> dOut.crestVal.let { b -> if (a - b > 0.5f) " — the engine took ${"%.1f".format(java.util.Locale.US, a - b)} dB of punch (limiter)" else " — dynamics kept" } }
        dynRows += "Loudness range, 0.4 s blocks 10–95 % (in → out)" to "${dIn.range} → ${dOut.range}"
        dynRows += "Clipped samples in libVLC's sound" to if (dIn.clipped == 0) "none" else "${dIn.clipped} at full scale (clipping already in the file or in libVLC's decoding)"
        val spIn = spectrum(mIn, src); val spOut = spectrum(mOut, dev)
        val bwRows = ArrayList<Pair<String, String>>()
        bwRows += "Highest frequency with sound (in → out)" to "${spIn.rolloff} → ${spOut.rolloff}" +
            (spIn.rolloffHz.let { h -> if (h in 1..16_500) " — the source stops near ${h / 1000} kHz (typical of lossy coding or a low bitrate)" else "" })
        bwRows += "Lowest frequency with sound (in)" to spIn.lowEnd
        if (spIn.bands.isNotEmpty() && spOut.bands.isNotEmpty()) {
            val line = BANDS.indices.joinToString(" · ") { i -> "${BAND_NAMES[i]} ${"%+.1f".format(java.util.Locale.US, (spOut.bands[i] - spIn.bands[i]) - (spOut.mean - spIn.mean))}" }
            bwRows += "Tone change by the engine, per band (out − in, level change removed)" to line + " dB"
            val treble = listOf(6, 7, 8).map { spOut.bands[it] - spIn.bands[it] }.average() - (spOut.mean - spIn.mean)
            if (treble < -1.5) bwRows += "Treble" to "%.1f dB less from 4 kHz up — sounds dull / dampened (Treble, Treble band, Loudness)".format(java.util.Locale.US, -treble)
            else if (treble > 1.5) bwRows += "Treble" to "%.1f dB more from 4 kHz up — sounds brighter".format(java.util.Locale.US, treble)
        }
        val fileRows = fileInfo(ctx, item)

        // 5. Output.
        val outRows = ArrayList<Pair<String, String>>()
        outRows += "Output" to player.currentOutputName.ifEmpty { "phone default" }
        outRows += "Stream to Android" to "$dev Hz · 32-bit float · AAudio (no audio session)"
        val bt = try { BtCodec.info(ctx) } catch (_: Throwable) { null }
        if (bt != null) {
            outRows += "Bluetooth link" to bt
            outRows += "Bluetooth quality mode" to (if (BtQuality.on()) "on — the stream is opened at the link's rate" else "off — Android converts to the link's rate, as for every app")
            val link = BtCodec.linkRateHz
            if (link > 0 && link != dev) outRows += "Rate to the link" to "Android converts $dev → $link Hz"
        }
        outRows += "Phone volume" to "${player.volumeNowPct().toInt()} %"
        outRows += "Phone sound effects" to "an app can't read them; with no audio session, per-app effects (e.g. Dolby, Adapt Sound) may apply differently than to other players"

        Report("Sound check · ${item.title}" + when { item.isRadio -> " (radio)"; !item.isMusic -> " (video)"; else -> "" },
            listOf("Source (format, bitrate)" to fileRows, "Decoding (libVLC vs Android)" to decRows, "App's sound engine" to engRows,
                "The app's corrections now" to (try { player.chainReport() } catch (_: Throwable) { emptyList() }),
                "Dynamics (measured)" to dynRows, "Bandwidth and tone (measured)" to bwRows, "Output" to outRows),
            "Captured $seconds s from ${fmt(posMs)}. Share or copy this to compare.")
    }

    private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    // ---------- dynamics, bandwidth, format (2026-11-28 13:34) ----------
    private class Dyn(val peakDb: String, val rmsDb: String, val crest: String, val crestVal: Float, val range: String, val clipped: Int)
    private fun dbf(x: Double) = if (x <= 1e-9) "−∞ dBFS" else "%.1f dBFS".format(java.util.Locale.US, 20 * log10(x))

    private fun dynamics(x: FloatArray, rate: Int, raw: ShortArray?, rawN: Int): Dyn {
        if (x.isEmpty() || rate <= 0) return Dyn("—", "—", "—", 0f, "—", 0)
        var pk = 0.0; var sum = 0.0
        for (v in x) { val a = abs(v.toDouble()); if (a > pk) pk = a; sum += v * v.toDouble() }
        val rms = sqrt(sum / x.size)
        val crest = if (rms > 0) (20 * log10(pk / rms)).toFloat() else 0f
        val blk = (rate * 0.4).toInt().coerceAtLeast(1)
        val levels = ArrayList<Double>()
        var i = 0
        while (i + blk <= x.size) { var s = 0.0; for (k in i until i + blk) s += x[k] * x[k].toDouble(); val r = sqrt(s / blk); if (r > 1e-5) levels += 20 * log10(r); i += blk }
        levels.sort()
        val range = if (levels.size < 4) "—" else "%.1f dB".format(java.util.Locale.US, levels[(levels.size * 0.95).toInt().coerceAtMost(levels.size - 1)] - levels[(levels.size * 0.10).toInt()])
        var clipped = 0
        if (raw != null) for (k in 0 until rawN * 2) if (raw[k] >= 32767 || raw[k] <= -32768) clipped++
        return Dyn(dbf(pk), dbf(rms), "%.1f dB".format(java.util.Locale.US, crest), crest, range, clipped)
    }

    private val BANDS = intArrayOf(40, 100, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000)
    private val BAND_NAMES = listOf("40", "100", "250", "500", "1k", "2k", "4k", "8k", "12k", "16k")
    private class Spec(val bands: DoubleArray, val mean: Double, val rolloff: String, val rolloffHz: Int, val lowEnd: String)

    /** Average spectrum (8192-point FFT, Hann, up to 48 windows), band levels and where the sound ends. */
    private fun spectrum(x: FloatArray, rate: Int): Spec {
        val n = 8192
        if (x.size < n || rate <= 0) return Spec(DoubleArray(0), 0.0, "—", 0, "—")
        val pow = DoubleArray(n / 2)
        val win = DoubleArray(n) { 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (n - 1)) }
        val hops = ((x.size - n) / (n / 2) + 1).coerceAtMost(48)
        val step = if (hops <= 1) 0 else (x.size - n) / (hops - 1)
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (h in 0 until hops) {
            val off = h * step
            for (k in 0 until n) { re[k] = x[off + k] * win[k]; im[k] = 0.0 }
            fft(re, im)
            for (k in 0 until n / 2) pow[k] += re[k] * re[k] + im[k] * im[k]
        }
        val dbs = DoubleArray(n / 2) { k -> 10 * log10(pow[k] / hops + 1e-20) }
        val hz = rate.toDouble() / n
        fun bandDb(f: Int): Double {                      // ⅓-octave around f
            val lo = (f / 1.122 / hz).toInt().coerceIn(1, n / 2 - 1); val hi = (f * 1.122 / hz).toInt().coerceIn(lo, n / 2 - 1)
            var s = 0.0; for (k in lo..hi) s += pow[k] / hops; return 10 * log10(s / (hi - lo + 1) + 1e-20)
        }
        val bands = DoubleArray(BANDS.size) { if (BANDS[it] < rate / 2) bandDb(BANDS[it]) else -200.0 }
        val mean = bands.filter { it > -199 }.average()
        // Where the sound ends: the highest bin (smoothed over ~200 Hz) above (loudest − 70 dB).
        val top = dbs.maxOrNull() ?: 0.0
        val sm = (200 / hz).toInt().coerceAtLeast(1)
        var roll = 0
        for (k in n / 2 - sm - 1 downTo sm) { var s = 0.0; for (j in k - sm until k + sm) s += dbs[j]; if (s / (2 * sm) > top - 70) { roll = (k * hz).toInt(); break } }
        var low = 0
        for (k in 1 until n / 2) if (dbs[k] > top - 40) { low = (k * hz).toInt(); break }
        return Spec(bands, mean, if (roll > 0) "%.1f kHz".format(java.util.Locale.US, roll / 1000.0) else "—", roll, if (low > 0) "$low Hz" else "—")
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len; val wr = kotlin.math.cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci; val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai; re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** The source: codec, rate, channels, bits, bitrate (the file's own, or the station's). */
    private fun fileInfo(ctx: Context, item: PlayItem): List<Pair<String, String>> {
        val r = ArrayList<Pair<String, String>>()
        if (item.isRadio) {
            val meta = try { App.instance.store.current.stations.firstOrNull { it.url == item.uri }?.meta ?: "" } catch (_: Exception) { "" }
            r += "Station" to (meta.ifEmpty { "no details from the directory" })
            val br = Regex("(\\d{2,4})\\s*k").find(meta)?.groupValues?.get(1)?.toIntOrNull()
            if (br != null) r += "Bitrate" to "$br kbit/s" + when { meta.contains("flac", true) -> " (lossless)"; br < 128 -> " — low: highs above ~15 kHz are usually cut"; br < 192 -> " — highs above ~16 kHz are usually cut"; else -> "" }
            return r
        }
        val ex = MediaExtractor(); var ds: android.media.MediaDataSource? = null
        try {
            val u = item.uri
            when {
                u.startsWith("content://") -> ex.setDataSource(ctx, Uri.parse(u), null)
                u.startsWith("file://") -> ex.setDataSource(Uri.parse(u).path!!)
                u.startsWith("/") -> ex.setDataSource(u)
                u.startsWith("smb://") -> { ds = app.vlcinterface.sources.RgScanner.SmbSource(jcifs.smb.SmbFile(app.vlcinterface.sources.VpnLink.route(Uri.decode(u)),
                    app.vlcinterface.sources.SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain))); ex.setDataSource(ds) }
                u.startsWith("http") -> ex.setDataSource(u, emptyMap())
                else -> { r += "File" to "can't be read here"; return r }
            }
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: run { r += "File" to "no sound track found"; return r }
            val f = ex.getTrackFormat(ti)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            val lossless = mime.contains("flac") || mime.contains("raw") || mime.contains("wav") || mime.contains("alac") || mime.contains("ape")
            r += "Codec" to mime.substringAfter("audio/") + if (lossless) " (lossless)" else " (lossy)"
            r += "Sample rate / channels" to "${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz · ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch"
            if (f.containsKey("bits-per-sample")) r += "Bit depth" to "${f.getInteger("bits-per-sample")} bit" + if (f.getInteger("bits-per-sample") > 16) " — libVLC hands the app 16 bit" else ""
            val durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            val size = ds?.size ?: try { if (u.startsWith("content://")) ctx.contentResolver.openAssetFileDescriptor(Uri.parse(u), "r")?.use { it.length } ?: 0L else java.io.File(Uri.parse(u).path ?: u).length() } catch (_: Exception) { 0L }
            val br = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                else if (size > 0 && durUs > 0) (size * 8 * 1000 / durUs).toInt() else 0
            if (br > 0) r += "Bitrate" to "$br kbit/s" + (if (!lossless && br < 192) " — low for lossy: highs and detail may be missing" else "") +
                (if (!f.containsKey(MediaFormat.KEY_BIT_RATE)) " (average, from size and length)" else "")
        } catch (e: Exception) { r += "File" to "couldn't be read (${e.message ?: e.javaClass.simpleName})" }
        finally { try { ex.release() } catch (_: Exception) {}; try { ds?.close() } catch (_: Exception) {} }
        return r
    }

    // ---------- independent decode ----------
    class Decoded(val pcm: ShortArray, val frames: Int, val rate: Int, val channels: Int, val encoding: String, val encodingBits: Int)

    internal fun decode(ctx: Context, item: PlayItem, fromUs: Long, seconds: Int): Decoded? {
        val ex = MediaExtractor()
        var ds: android.media.MediaDataSource? = null
        try {
            val u = item.uri
            when {
                u.startsWith("content://") -> ex.setDataSource(ctx, Uri.parse(u), null)
                u.startsWith("file://") -> ex.setDataSource(Uri.parse(u).path!!)
                u.startsWith("/") -> ex.setDataSource(u)
                u.startsWith("smb://") -> {
                    ds = app.vlcinterface.sources.RgScanner.SmbSource(jcifs.smb.SmbFile(app.vlcinterface.sources.VpnLink.route(Uri.decode(u)),
                        app.vlcinterface.sources.SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)))
                    ex.setDataSource(ds)
                }
                u.startsWith("http") -> ex.setDataSource(u, if (item.user.isNotEmpty()) mapOf("Authorization" to okhttp3.Credentials.basic(item.user, item.password)) else emptyMap())
                else -> return null
            }
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val fileBits = if (fmt.containsKey("bits-per-sample")) fmt.getInteger("bits-per-sample") else 0
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var enc = AudioFormat.ENCODING_PCM_16BIT
            val maxFrames = rate * seconds
            var out = ShortArray(0); var frames = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false; var started = false
            try {
                while (frames < maxFrames) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    } else if (oi >= 0) {
                        if (info.size > 0 && info.presentationTimeUs >= fromUs - 200_000) started = true
                        if (started && info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size); ob.order(ByteOrder.nativeOrder())
                            val bytes = when (enc) { AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3; else -> 2 }
                            val n = info.size / bytes / ch
                            if (ch <= 2) {
                                if (out.size < (frames + n) * 2) out = out.copyOf(maxOf((frames + n) * 2, out.size * 2))
                                val base = ob.position()
                                for (k in 0 until n) for (c in 0 until 2) {
                                    val idx = k * ch + if (ch == 1) 0 else c
                                    val v: Int = when (enc) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> Math.round((ob.getFloat(base + idx * 4) * 32768f).coerceIn(-32768f, 32767f))
                                        AudioFormat.ENCODING_PCM_32BIT -> ob.getInt(base + idx * 4) shr 16
                                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> { val o = base + idx * 3; ((ob.get(o + 1).toInt() and 0xFF) or (ob.get(o + 2).toInt() shl 8)) }
                                        else -> ob.getShort(base + idx * 2).toInt()
                                    }
                                    out[(frames + k) * 2 + c] = v.toShort()
                                }
                                frames += n
                            } else frames += n
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally { try { codec.stop() } catch (_: Exception) {}; codec.release() }
            val encName = when (enc) { AudioFormat.ENCODING_PCM_FLOAT -> "float"; AudioFormat.ENCODING_PCM_32BIT -> "32-bit"; AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"; else -> "16-bit" } +
                if (fileBits > 0) " (file $fileBits-bit)" else ""
            val bits = maxOf(fileBits, when (enc) { AudioFormat.ENCODING_PCM_16BIT -> 16; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24; else -> 24 })
            return Decoded(out, if (ch <= 2) frames else 0, rate, ch, encName, if (fileBits > 0) fileBits else bits)
        } finally { ex.release(); try { ds?.close() } catch (_: Exception) {} }
    }

    // ---------- comparisons ----------
    class DecMatch(val frames: Int, val maxDiff: Int, val diffPct: Double, val errRms: Double)

    /** Lines libVLC's capture up with Android's decode (search for the passage), then compares every sample. */
    private fun compareDecoders(cap: ShortArray, capN: Int, dec: ShortArray, decN: Int): DecMatch? {
        // A stretch of the capture with real sound in it to search with.
        var start = 0
        while (start < capN - 2048 && abs(cap[start * 2].toInt()) < 64) start += 256
        val probe = 32
        var found = -1
        val last = decN - (capN - start)
        for (off in 0..maxOf(-1, last)) {
            var ok = true
            for (k in 0 until probe) {
                if (abs(dec[(off + k) * 2] - cap[(start + k) * 2]) > 2 || abs(dec[(off + k) * 2 + 1] - cap[(start + k) * 2 + 1]) > 2) { ok = false; break }
            }
            if (ok) { found = off; break }
        }
        if (found < 0) return null
        val n = minOf(capN - start, decN - found)
        var maxD = 0; var diffs = 0L; var e2 = 0.0; var s2 = 0.0
        for (k in 0 until n) for (c in 0 until 2) {
            val a = cap[(start + k) * 2 + c].toInt(); val b = dec[(found + k) * 2 + c].toInt()
            val d = abs(a - b); if (d > maxD) maxD = d; if (d != 0) diffs++
            e2 += (a - b).toDouble() * (a - b); s2 += b.toDouble() * b
        }
        return DecMatch(n, maxD, diffs * 100.0 / (n * 2.0), if (s2 > 0) sqrt(e2 / s2) else 0.0)
    }

    class EngMatch(val identical: Boolean, val levelOnly: Boolean, val gain: Double, val residual: Double)

    /** Engine output vs its input (same rate): out[n + 63] against in[n] / 32768 — the limiter's look-ahead delay. */
    private fun compareEngine(inp: ShortArray, inN: Int, out: FloatArray, outN: Int): EngMatch {
        val d = 63
        val n = minOf(inN, outN - d)
        if (n <= 0) return EngMatch(false, false, 0.0, 1.0)
        var exact = true; var xy = 0.0; var xx = 0.0
        for (k in 0 until n) for (c in 0 until 2) {
            val x = inp[k * 2 + c] / 32768.0; val y = out[(k + d) * 2 + c].toDouble()
            if (y != (inp[k * 2 + c] / 32768f).toDouble()) exact = false
            xy += x * y; xx += x * x
        }
        val g = if (xx > 0) xy / xx else 0.0
        var r2 = 0.0; var s2 = 0.0
        for (k in 0 until n) for (c in 0 until 2) {
            val x = inp[k * 2 + c] / 32768.0; val y = out[(k + d) * 2 + c].toDouble()
            r2 += (y - g * x) * (y - g * x); s2 += (g * x) * (g * x)
        }
        val res = if (s2 > 0) sqrt(r2 / s2) else 0.0
        return EngMatch(exact, !exact && res < 1e-5, g, res)
    }
}
