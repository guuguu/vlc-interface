package app.vlcinterface.sources

/** MP3 encoding with LAME 3.100 (libmp3lame.so, LGPL; bundled unchanged). VBR V0 = highest VBR quality. */
class Mp3Encoder(val rate: Int, val channels: Int, vbrQuality: Int = 0) : AutoCloseable {
    private var h: Long = if (loaded) nInit(rate, channels.coerceIn(1, 2), vbrQuality) else 0L
    init { require(loaded && h != 0L) { "MP3 encoder could not start ($rate Hz, $channels ch)" } }

    /** [pcm]: interleaved 16-bit samples, [frames] per channel. Returns the MP3 bytes made so far. */
    fun encode(pcm: ShortArray, frames: Int): ByteArray = nEncode(h, pcm, frames, channels.coerceIn(1, 2))
    fun flush(): ByteArray = nFlush(h)
    /** Xing/LAME header frame (length, gapless info): written over the file's first frame at the end. */
    fun lameTag(): ByteArray = nTag(h)
    override fun close() { if (h != 0L) nClose(h); h = 0 }

    companion object {
        private val loaded = try { System.loadLibrary("mp3lame"); System.loadLibrary("lamejni"); true } catch (_: Throwable) { false }
        val version: String get() = if (!loaded) "" else try { nVersion() } catch (_: Throwable) { "" }
        @JvmStatic private external fun nInit(rate: Int, channels: Int, vbrQuality: Int): Long
        @JvmStatic private external fun nEncode(h: Long, pcm: ShortArray, frames: Int, channels: Int): ByteArray
        @JvmStatic private external fun nFlush(h: Long): ByteArray
        @JvmStatic private external fun nTag(h: Long): ByteArray
        @JvmStatic private external fun nClose(h: Long)
        @JvmStatic private external fun nVersion(): String
    }
}
