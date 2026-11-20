package app.vlcinterface.player

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import org.videolan.libvlc.interfaces.IMedia

/**
 * Settings → Video "Automatic": decoding and deinterlacing chosen per video from its encoding.
 * - Decoder: the phone's hardware decoder when it can decode that codec at that size; otherwise (or after a hardware
 *   failure) the app's software decoder for that file.
 * - Deinterlacing: on for encodings that are usually interlaced (MPEG-2 / DV, TV recordings: .ts .m2ts .mts .vob .mpg,
 *   at TV sizes), off for the rest — so normal videos keep the fast direct hardware path.
 * What is learnt about a file is kept (per file), so it opens the right way at once next time.
 */
object VideoAuto {
    private lateinit var ctx: Context
    private val prefs get() = ctx.getSharedPreferences("videoAuto", Context.MODE_PRIVATE)
    /** Files already re-opened this run (never more than once, no loops). */
    private val reopened = HashSet<String>()

    fun init(c: Context) { ctx = c.applicationContext }

    fun software(uri: String): Boolean = prefs.getBoolean("sw:$uri", false)
    fun markSoftware(uri: String) = prefs.edit().putBoolean("sw:$uri", true).apply()

    private val TV_EXT = setOf("ts", "m2ts", "mts", "m2t", "vob", "mpg", "mpeg", "mpe", "m2v", "dv", "tod", "mod")
    private fun ext(uri: String) = uri.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** Deinterlace this file? Known from an earlier play, else guessed from the file type. */
    fun deinterlace(uri: String): Boolean = when (prefs.getInt("di:$uri", -1)) { 1 -> true; 0 -> false; else -> ext(uri) in TV_EXT }

    /** Re-open allowed once per file and run. */
    fun mayReopen(uri: String): Boolean = reopened.add(uri)

    private fun mimeOf(fourcc: String): String? = when (fourcc.trim().lowercase()) {
        "h264", "avc1", "x264" -> "video/avc"
        "hevc", "h265", "hvc1", "hev1" -> "video/hevc"
        "vp90", "vp9" -> "video/x-vnd.on2.vp9"
        "vp80", "vp8" -> "video/x-vnd.on2.vp8"
        "av01", "av1" -> "video/av01"
        "mp4v", "xvid", "divx", "dx50", "fmp4" -> "video/mp4v-es"
        "mpgv", "mp2v", "mpg2" -> "video/mpeg2"
        "h263", "s263" -> "video/3gpp"
        else -> null
    }

    /** Can the phone's hardware decode this codec at this size? Unknown codecs: let libVLC decide (true). */
    private fun hardwareCan(fourcc: String, w: Int, h: Int): Boolean {
        val mime = mimeOf(fourcc) ?: return true
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } &&
                    (Build.VERSION.SDK_INT < 29 || ci.isHardwareAccelerated) &&
                    try { ci.getCapabilitiesForType(mime).videoCapabilities?.let { vc -> w <= 0 || h <= 0 || vc.isSizeSupported(w, h) || vc.isSizeSupported(h, w) } ?: true }
                    catch (_: Exception) { true }
            }
        } catch (_: Exception) { true }
    }

    private fun interlacedLike(fourcc: String, w: Int, h: Int): Boolean {
        val c = fourcc.trim().lowercase()
        val tvSize = h in listOf(480, 486, 576, 1080, 1088) || (w == 720 && h in 470..580)
        return (c in setOf("mpgv", "mp2v", "mpg2", "dvsd", "dv25", "dv50", "dvh1") && tvSize)
    }

    /**
     * Called when the playing video's track is known. Returns true when the file should be re-opened with what was
     * learnt (software decoder, or deinterlacing on / off) — at most once per file and run.
     */
    fun learn(uri: String, vt: IMedia.VideoTrack, decoderAuto: Boolean, deintAuto: Boolean, deintNow: Boolean): Boolean {
        var reopen = false
        if (decoderAuto && !software(uri) && !hardwareCan(vt.codec ?: "", vt.width, vt.height)) { markSoftware(uri); reopen = true }
        if (deintAuto) {
            val want = interlacedLike(vt.codec ?: "", vt.width, vt.height) || (deintNow && ext(uri) in TV_EXT && vt.height in 400..1100)
            prefs.edit().putInt("di:$uri", if (want) 1 else 0).apply()
            if (want != deintNow) reopen = true
        }
        return reopen && mayReopen(uri)
    }
}
