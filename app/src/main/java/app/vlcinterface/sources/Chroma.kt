package app.vlcinterface.sources

/** Chromaprint (libchromaprint.so, MIT / LGPL parts): the audio fingerprint AcoustID looks songs up by. */
object Chroma {
    private val ok: Boolean = try { System.loadLibrary("chromaprintjni"); true } catch (_: Throwable) { false }
    @JvmStatic private external fun fingerprint(pcm: ShortArray, count: Int, rate: Int, channels: Int): String?

    /** Fingerprint of [count] interleaved 16-bit samples, or null when the library isn't there or it fails. */
    fun of(pcm: ShortArray, count: Int, rate: Int, channels: Int): String? =
        if (!ok || count <= 0) null else try { fingerprint(pcm, count, rate, channels) } catch (_: Throwable) { null }
}
