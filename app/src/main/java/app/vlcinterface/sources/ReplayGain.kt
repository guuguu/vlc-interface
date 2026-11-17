package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** ReplayGain values of one file (dB / linear peak); null = tag not present. */
data class Rg(val trackGain: Float?, val trackPeak: Float?, val albumGain: Float?, val albumPeak: Float?) {
    val any: Boolean get() = trackGain != null || albumGain != null
}

/**
 * Reads ReplayGain tags itself (so they apply with the app's own sound output):
 * ID3v2 TXXX (MP3, also UTF-16), Vorbis comments (FLAC, Ogg, Opus incl. R128_*_GAIN),
 * MP4/M4A iTunes freeform atoms and APEv2 (start or end of file).
 * Reads the first 512 KB and the last 64 KB from phone storage, SMB (incl. SMB1), HTTP/WebDAV and local files.
 */
object ReplayGain {
    private val cache = ConcurrentHashMap<String, Rg>()
    private const val HEAD = 512 * 1024

    /** Only what is written in the file itself (no cache, no values kept in the app). */
    suspend fun fromFile(ctx: Context, uri: String, user: String, pass: String, domain: String, smbVersion: String): Rg? =
        withContext(Dispatchers.IO) { try { fetch(ctx, uri, user, pass, domain, smbVersion)?.let { parse(it.first + it.second) } } catch (_: Exception) { null } }

    /** After a scan wrote new values. */
    fun forget(uri: String) { cache.remove(uri) }
    private const val TAIL = 64 * 1024

    suspend fun read(ctx: Context, uri: String, user: String, pass: String, domain: String, smbVersion: String): Rg? =
        withContext(Dispatchers.IO) {
            cache[uri]?.let { return@withContext it }
            val parts = try { fetch(ctx, uri, user, pass, domain, smbVersion) } catch (_: Exception) { null }
            // Tags in the file first; values the scanner kept in the app for files it couldn't (or wasn't asked to) write.
            val inFile = parts?.let { parse(it.first + it.second) }
            // Values the scanner kept in the app are newer than what's in the file (removed again when the file gets written).
            val rg = RgStore.get(uri) ?: inFile ?: return@withContext null
            cache[uri] = rg
            rg
        }

    private fun readFully(input: InputStream, max: Int): ByteArray {
        val out = ByteArray(max); var n = 0
        while (n < max) { val r = input.read(out, n, max - n); if (r <= 0) break; n += r }
        return out.copyOf(n)
    }

    /** A file the tag reader can read anywhere in (phone, local, SMB incl. SMB1, HTTP/WebDAV). */
    private interface Ranged : java.io.Closeable {
        val size: Long
        fun read(off: Long, len: Int): ByteArray
    }

    private fun open(ctx: Context, uri: String, user: String, pass: String, domain: String, smbVersion: String): Ranged? {
        val scheme = uri.substringBefore("://", "").lowercase()
        return when {
            scheme == "content" -> {
                val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
                val fis = java.io.FileInputStream(pfd.fileDescriptor)
                object : Ranged {
                    override val size = pfd.statSize
                    override fun read(off: Long, len: Int): ByteArray { fis.channel.position(off); return readFully(fis, len) }
                    override fun close() { fis.close(); pfd.close() }
                }
            }
            scheme == "file" || scheme.isEmpty() -> {
                val raf = RandomAccessFile(File(if (scheme == "file") Uri.parse(uri).path ?: return null else uri), "r")
                object : Ranged {
                    override val size = raf.length()
                    override fun read(off: Long, len: Int): ByteArray { raf.seek(off); val b = ByteArray(minOf(len.toLong(), size - off).toInt().coerceAtLeast(0)); raf.readFully(b); return b }
                    override fun close() = raf.close()
                }
            }
            scheme == "smb" -> {
                val f = SmbFile(VpnLink.route(android.net.Uri.decode(uri)), SmbProbe.fileContext(smbVersion, user, pass, domain))
                val raf = f.openRandomAccess("r")
                object : Ranged {
                    override val size = f.length()
                    override fun read(off: Long, len: Int): ByteArray {
                        raf.seek(off); val b = ByteArray(minOf(len.toLong(), size - off).toInt().coerceAtLeast(0))
                        var n = 0; while (n < b.size) { val r = raf.read(b, n, b.size - n); if (r <= 0) break; n += r }
                        return b.copyOf(n)
                    }
                    override fun close() = raf.close()
                }
            }
            scheme == "http" || scheme == "https" -> object : Ranged {
                override val size: Long by lazy {
                    Net.http.newCall(req().header("Range", "bytes=0-0").build()).execute().use { r ->
                        r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L }
                }
                fun req() = Request.Builder().url(uri).header("User-Agent", Net.UA)
                    .apply { if (user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(user, pass)) }
                override fun read(off: Long, len: Int): ByteArray = Net.http.newCall(req().header("Range", "bytes=$off-${off + len - 1}").build())
                    .execute().use { r -> if (!r.isSuccessful) ByteArray(0) else r.body?.byteStream()?.let { readFully(it, len) } ?: ByteArray(0) }
                override fun close() {}
            }
            else -> null   // sftp/ftp/nfs/upnp: read by libVLC only
        }
    }

    /**
     * The bytes that hold the tags: the start of the file (whole ID3v2 tag even when a big picture comes first;
     * FLAC: the Vorbis comment block even after a large embedded picture) plus the last 64 KB (APEv2, MP4 tags at the end).
     */
    private fun fetch(ctx: Context, uri: String, user: String, pass: String, domain: String, smbVersion: String): Pair<ByteArray, ByteArray>? {
        val r = open(ctx, uri, user, pass, domain, smbVersion) ?: return null
        return r.use {
            var head = r.read(0, HEAD)
            if (head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                val tagSize = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
                val total = tagSize + 20
                if (total > head.size) head = r.read(0, minOf(total, 32 shl 20))
            }
            var extra = ByteArray(0)
            if (head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC") {
                var off = 4L
                for (guard in 0 until 64) {
                    val h = if (off + 4 <= head.size) head.copyOfRange(off.toInt(), off.toInt() + 4) else r.read(off, 4)
                    if (h.size < 4) break
                    val last = h[0].toInt() and 0x80 != 0
                    val type = h[0].toInt() and 0x7F
                    val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                    if (type == 4 && off + 4 + len > head.size) extra = r.read(off + 4, len)    // VORBIS_COMMENT beyond what was read
                    off += 4 + len
                    if (last) break
                }
            }
            val tail = if (r.size > HEAD) r.read(r.size - TAIL, TAIL) else ByteArray(0)
            (head + extra) to tail
        }
    }

    // ---------- parsing ----------

    private val keys = listOf("replaygain_track_gain", "replaygain_track_peak", "replaygain_album_gain", "replaygain_album_peak")
    private val decimal = Regex("""[-+]?\d{1,3}\.\d+""")
    private val integer = Regex("""[-+]?\d{1,6}""")

    fun parse(bytes: ByteArray): Rg {
        // Search as Latin-1 text, and again with zero bytes removed (UTF-16 ID3 frames).
        val a = String(bytes, Charsets.ISO_8859_1)
        val b = String(bytes.filter { it != 0.toByte() }.toByteArray(), Charsets.ISO_8859_1)
        fun find(key: String): Float? = value(a, key) ?: value(b, key)
        var tg = find(keys[0]); val tp = find(keys[1]); var ag = find(keys[2]); val ap = find(keys[3])
        // Opus: R128 gains are Q7.8 integers relative to −23 LUFS; ReplayGain is relative to about −18 LUFS.
        if (tg == null) r128(a, "r128_track_gain")?.let { tg = it }
        if (ag == null) r128(a, "r128_album_gain")?.let { ag = it }
        return Rg(tg?.takeIf { it in -60f..30f }, tp?.takeIf { it > 0f && it < 10f }, ag?.takeIf { it in -60f..30f }, ap?.takeIf { it > 0f && it < 10f })
    }

    private fun value(text: String, key: String): Float? {
        var i = text.indexOf(key, ignoreCase = true)
        while (i >= 0) {
            // The value follows the key within a few bytes ("=", "\u0000", MP4 "data" atom header, APE flags…).
            val window = text.substring(i + key.length, minOf(text.length, i + key.length + 48))
            decimal.find(window)?.let { m -> return m.value.toFloatOrNull() }
            i = text.indexOf(key, i + key.length, ignoreCase = true)
        }
        return null
    }

    private fun r128(text: String, key: String): Float? {
        val i = text.indexOf("$key=", ignoreCase = true); if (i < 0) return null
        val v = integer.find(text.substring(i + key.length + 1, minOf(text.length, i + key.length + 12)))?.value?.toIntOrNull() ?: return null
        return v / 256f + 5f
    }
}
