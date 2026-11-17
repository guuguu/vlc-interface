package app.vlcinterface.sources

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Ogg radio streams (FLAC, Vorbis, Opus) for saving songs:
 *  - [Watcher] follows the stream while it's recorded: each new logical stream (Icecast starts one per song, with
 *    the song's ARTIST / TITLE in its comment header) is reported with its header pages;
 *  - [pages] / [writeOgg] / [writeFlac] cut a recorded part on page boundaries into a playable file:
 *    Vorbis / Opus as .ogg / .opus (pages renumbered, positions from 0), FLAC as a real .flac file.
 */
object OggStream {
    class Chain(val offset: Long, val serial: Int, val codec: String, val rate: Int, val headerPages: ByteArray,
                val headerPackets: List<ByteArray>, val title: String, val headerLen: Int = headerPages.size)

    class Page(val pos: Int, val len: Int, val flags: Int, val granule: Long, val serial: Int, val bodyPos: Int, val lacing: IntArray)

    private fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun le64(b: ByteArray, i: Int): Long { var v = 0L; for (k in 7 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF); return v }

    private fun isOggS(b: ByteArray, i: Int) = i + 27 <= b.size && b[i] == 'O'.code.toByte() && b[i + 1] == 'g'.code.toByte() &&
        b[i + 2] == 'g'.code.toByte() && b[i + 3] == 'S'.code.toByte() && b[i + 4].toInt() == 0

    /** Every complete page in [d] (resyncs on "OggS"). */
    fun pages(d: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var i = 0
        while (i < d.size - 27) {
            if (!isOggS(d, i)) { i++; continue }
            val nseg = d[i + 26].toInt() and 0xFF
            if (i + 27 + nseg > d.size) break
            val lac = IntArray(nseg) { d[i + 27 + it].toInt() and 0xFF }
            val body = lac.sum()
            val len = 27 + nseg + body
            if (i + len > d.size) break
            out += Page(i, len, d[i + 5].toInt() and 0xFF, le64(d, i + 6), le32(d, i + 14), i + 27 + nseg, lac)
            i += len
        }
        return out
    }

    /** Packets of [pages] in order; a packet still open after the last page is dropped. */
    fun packets(d: ByteArray, pages: List<Page>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var cur = ByteArrayOutputStream()
        for (p in pages) {
            var pos = p.bodyPos
            for (l in p.lacing) {
                cur.write(d, pos, l); pos += l
                if (l < 255) { out += cur.toByteArray(); cur = ByteArrayOutputStream() }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- headers

    private fun comments(b: ByteArray, start: Int): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            var i = start
            val vlen = le32(b, i); i += 4 + vlen
            val n = le32(b, i); i += 4
            repeat(n.coerceAtMost(500)) {
                val l = le32(b, i); i += 4
                val s = String(b, i, l, Charsets.UTF_8); i += l
                val k = s.substringBefore('=').uppercase(); val v = s.substringAfter('=', "")
                if (k.isNotEmpty() && v.isNotBlank() && k !in m) m[k] = v.trim()
            }
        } catch (_: Exception) {}
        return m
    }

    /** Codec, rate (for page positions) and "ARTIST - TITLE" from a chain's header packets. */
    fun describe(packets: List<ByteArray>): Triple<String, Int, String>? {
        val p0 = packets.firstOrNull() ?: return null
        fun starts(b: ByteArray, s: String, at: Int = 0) = b.size >= at + s.length && String(b, at, s.length, Charsets.ISO_8859_1) == s
        var tags: Map<String, String> = emptyMap()
        val (codec, rate) = when {
            starts(p0, "\u0001vorbis") -> {
                packets.firstOrNull { starts(it, "\u0003vorbis") }?.let { tags = comments(it, 7) }
                "vorbis" to le32(p0, 12)
            }
            starts(p0, "OpusHead") -> {
                packets.firstOrNull { starts(it, "OpusTags") }?.let { tags = comments(it, 8) }
                "opus" to 48000
            }
            starts(p0, "\u007FFLAC") && p0.size >= 51 -> {
                packets.drop(1).firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x7F) == 4 }?.let { tags = comments(it, 4) }
                val r = ((p0[27].toInt() and 0xFF) shl 12) or ((p0[28].toInt() and 0xFF) shl 4) or ((p0[29].toInt() and 0xFF) shr 4)
                "flac" to r
            }
            else -> return null
        }
        if (rate <= 0) return null
        val a = tags["ARTIST"] ?: ""; val t = tags["TITLE"] ?: ""
        return Triple(codec, rate, listOf(a, t).filter { it.isNotBlank() }.joinToString(" - "))
    }

    /**
     * Header packets a chain needs before its audio: Vorbis 3, Opus 2, FLAC 1 + the count in its first packet
     * (0 = not given: until the first audio frame). Null when the first packet isn't known yet.
     */
    private fun headersNeeded(p0: ByteArray): Int? = when {
        p0.size >= 7 && String(p0, 0, 7, Charsets.ISO_8859_1) == "\u0001vorbis" -> 3
        p0.size >= 8 && String(p0, 0, 8, Charsets.ISO_8859_1) == "OpusHead" -> 2
        p0.size >= 9 && String(p0, 0, 5, Charsets.ISO_8859_1) == "\u007FFLAC" ->
            (((p0[7].toInt() and 0xFF) shl 8) or (p0[8].toInt() and 0xFF)).let { if (it == 0) 0 else it + 1 }
        else -> null
    }

    /**
     * Follows the recorded stream: reports each new chain (song) once its header packets are all there — counted
     * from the codec's own headers, not from page positions (some servers write those as 0 everywhere).
     */
    class Watcher(private val onChain: (Chain) -> Unit) {
        private val hdr = ByteArray(27 + 255); private var hlen = 0; private var need = 27
        private var pageStart = 0L; private var skip = 0
        private var keep: ByteArrayOutputStream? = null       // bytes of the page now read, when it belongs to the headers
        private var chainSerial = 0; private var chainOffset = 0L
        private var headers: ByteArrayOutputStream? = null     // pages of the chain being started

        fun feed(b: ByteArray, off: Int, n: Int, base: Long) {
            var i = off; val end = off + n
            while (i < end) {
                if (skip > 0) {
                    val k = minOf(skip, end - i)
                    keep?.write(b, i, k)
                    skip -= k; i += k
                    if (skip == 0) pageDone()
                    continue
                }
                if (hlen == 0) pageStart = base + (i - off)
                hdr[hlen++] = b[i++]
                if (hlen <= 4 && hdr[hlen - 1] != "OggS"[hlen - 1].code.toByte()) {
                    // Not a page start: look again (this byte may begin one).
                    if (hdr[hlen - 1] == 'O'.code.toByte()) { hdr[0] = hdr[hlen - 1]; hlen = 1; pageStart = base + (i - 1 - off) } else hlen = 0
                    continue
                }
                if (hlen == 27) need = 27 + (hdr[26].toInt() and 0xFF)
                if (hlen >= 27 && hlen == need) {
                    val flags = hdr[5].toInt() and 0xFF
                    val serial = le32(hdr, 14)
                    val body = (0 until (hdr[26].toInt() and 0xFF)).sumOf { hdr[27 + it].toInt() and 0xFF }
                    if ((flags and 2) != 0) {             // BOS: a new chain (song) starts here
                        chainSerial = serial; chainOffset = pageStart; headers = ByteArrayOutputStream()
                    }
                    val h = headers
                    keep = if (h != null && serial == chainSerial && h.size() < 2_000_000) ByteArrayOutputStream().also { it.write(hdr, 0, hlen) } else null
                    if (h != null && h.size() >= 2_000_000) headers = null      // never complete: given up
                    hlen = 0; skip = body
                    if (skip == 0) pageDone()
                }
            }
        }

        private fun pageDone() {
            val k = keep ?: return
            keep = null
            val h = headers ?: return
            h.write(k.toByteArray())
            val raw = h.toByteArray()
            val pk = packets(raw, pages(raw))
            val needed = pk.firstOrNull()?.let { headersNeeded(it) } ?: run { if (pk.isNotEmpty()) headers = null; return }
            // FLAC without a count: the headers end where the first audio frame (0xFFF8…) begins.
            val audioAt = if (needed == 0) pk.indexOfFirst { it.size >= 2 && (it[0].toInt() and 0xFF) == 0xFF && (it[1].toInt() and 0xFE) == 0xF8 } else -1
            val count = if (needed == 0) (if (audioAt > 0) audioAt else return) else needed
            if (pk.size < count) return
            headers = null
            val hp = pk.take(count)
            val (codec, rate, title) = describe(hp) ?: return
            // The header pages alone (for .ogg / .opus): pages up to the one that ends the last header packet.
            val ps = pages(raw)
            var got = 0; var lastPage = ps.size - 1
            run {
                ps.forEachIndexed { idx, pg -> got += pg.lacing.count { it < 255 }; if (got >= count) { lastPage = idx; return@run } }
            }
            val hpBytes = raw.copyOfRange(0, ps[lastPage].pos + ps[lastPage].len)
            onChain(Chain(chainOffset, chainSerial, codec, rate, hpBytes, hp, title, headerLen = hpBytes.size))
        }
    }

    // ---------------------------------------------------------------- writing

    private val crcTable = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }
    private fun crc(b: ByteArray): Int { var c = 0; for (x in b) c = (c shl 8) xor crcTable[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]; return c }

    private fun putLe(b: ByteArray, at: Int, v: Long, n: Int) { for (k in 0 until n) b[at + k] = (v ushr (8 * k)).toByte() }

    /** Vorbis / Opus: the chain's header pages, then [audio] pages renumbered with positions counted from [g0]. */
    fun writeOgg(c: Chain, d: ByteArray, audio: List<Page>, g0: Long, out: File, ends: LongArray? = null) {
        out.outputStream().buffered().use { o ->
            o.write(c.headerPages)
            var seq = pages(c.headerPages).size
            audio.forEachIndexed { idx, p ->
                val b = d.copyOfRange(p.pos, p.pos + p.len)
                var flags = p.flags and 0x01.inv() and 0x02.inv() and 0x04.inv()
                if (idx == audio.size - 1) flags = flags or 0x04
                b[5] = flags.toByte()
                // Position at the end of the page: counted from the audio when given (servers may write 0), -1 when
                // no packet ends on the page.
                val pos = if (p.lacing.none { it < 255 }) -1L else if (ends != null) ends[idx] else p.granule
                putLe(b, 6, if (pos == -1L) -1L else (pos - g0).coerceAtLeast(0), 8)
                putLe(b, 14, c.serial.toLong(), 4)
                putLe(b, 18, (seq++).toLong(), 4)
                putLe(b, 22, 0, 4); putLe(b, 22, crc(b).toLong() and 0xFFFFFFFFL, 4)
                o.write(b)
            }
        }
    }

    /** FLAC: a real .flac — "fLaC", the stream info (its length set to this part), then the FLAC frames. */
    fun writeFlac(c: Chain, d: ByteArray, audio: List<Page>, samples: Long, out: File) {
        val p0 = c.headerPackets.first()
        val info = p0.copyOfRange(17, 51)          // STREAMINFO (34 bytes)
        // Total samples: 36 bits at bit 108 of STREAMINFO; MD5 cleared (unknown for a part).
        info[13] = ((info[13].toInt() and 0xF0) or ((samples ushr 32).toInt() and 0x0F)).toByte()
        for (k in 0 until 4) info[14 + k] = (samples ushr (24 - 8 * k)).toByte()
        for (k in 18 until 34) info[k] = 0
        out.outputStream().buffered().use { o ->
            o.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            o.write(byteArrayOf(0x80.toByte(), 0, 0, 34))      // last metadata block: STREAMINFO, 34 bytes
            o.write(info)
            packets(d, audio).forEach { o.write(it) }
        }
    }

    /** Samples in one FLAC frame, from its header (block size); 0 when it isn't a frame header. */
    private fun flacFrameSamples(f: ByteArray): Int {
        if (f.size < 6 || (f[0].toInt() and 0xFF) != 0xFF || (f[1].toInt() and 0xFE) != 0xF8) return 0
        val code = (f[2].toInt() and 0xFF) shr 4
        // After byte 3: the frame / sample number in UTF-8 style (1–7 bytes), then the 8 / 16-bit block size.
        val lead = f[4].toInt() and 0xFF
        val n = when { lead < 0x80 -> 1; lead >= 0xFE -> 7; lead >= 0xFC -> 6; lead >= 0xF8 -> 5; lead >= 0xF0 -> 4; lead >= 0xE0 -> 3; lead >= 0xC0 -> 2; else -> 1 }
        val at = 4 + n
        return when (code) {
            1 -> 192
            in 2..5 -> 576 shl (code - 2)
            6 -> if (at < f.size) (f[at].toInt() and 0xFF) + 1 else 0
            7 -> if (at + 1 < f.size) (((f[at].toInt() and 0xFF) shl 8) or (f[at + 1].toInt() and 0xFF)) + 1 else 0
            in 8..15 -> 256 shl (code - 8)
            else -> 0
        }
    }

    /** Samples (48 kHz) in one Opus packet, from its TOC byte. */
    private fun opusPacketSamples(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val cfg = toc shr 3
        val per = when {
            cfg < 12 -> intArrayOf(480, 960, 1920, 2880)[cfg and 3]
            cfg < 16 -> intArrayOf(480, 960)[cfg and 1]
            else -> intArrayOf(120, 240, 480, 960)[cfg and 3]
        }
        val frames = when (toc and 3) { 0 -> 1; 1, 2 -> 2; else -> if (p.size > 1) (p[1].toInt() and 0x3F) else 0 }
        return per * frames
    }

    /**
     * Samples ending in each page, counted from the audio itself (FLAC frame headers, Opus TOC bytes) — page
     * positions are left aside, since some servers write them wrong (all 0, or never moving). Null for Vorbis.
     */
    fun pageSamples(c: Chain, d: ByteArray, pages: List<Page>): LongArray? {
        if (c.codec != "flac" && c.codec != "opus") return null
        val out = LongArray(pages.size)
        val cur = ByteArrayOutputStream()
        pages.forEachIndexed { i, p ->
            var pos = p.bodyPos
            for (l in p.lacing) {
                cur.write(d, pos, l); pos += l
                if (l < 255) {
                    val pk = cur.toByteArray(); cur.reset()
                    out[i] += (if (c.codec == "flac") flacFrameSamples(pk) else opusPacketSamples(pk)).toLong()
                }
            }
        }
        return out
    }

    fun ext(codec: String) = when (codec) { "flac" -> "flac"; "opus" -> "opus"; else -> "ogg" }
    fun mime(codec: String) = if (codec == "flac") "audio/flac" else "audio/ogg"
}
