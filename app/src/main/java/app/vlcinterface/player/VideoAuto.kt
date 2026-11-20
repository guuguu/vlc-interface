package app.vlcinterface.player

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import org.videolan.libvlc.interfaces.IMedia
import kotlinx.coroutines.launch

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
    /** One entry per video file ("f:" + its address): everything chosen or learnt for it, as JSON. */
    private val prefs get() = ctx.getSharedPreferences("videoFiles", Context.MODE_PRIVATE)
    /** Files already re-opened this run (never more than once, no loops). */
    private val reopened = HashSet<String>()

    fun init(c: Context) { ctx = c.applicationContext }

    // ---- per-file record --------------------------------------------------------------------------------
    // dec: "hw" / "sw" chosen by hand for this file ("" = Automatic) · di: "on" / "off" by hand ("" = Automatic)
    // swA: Automatic learnt that the hardware can't decode it · diA: Automatic learnt 1 / 0 · scale: picture format
    // aId / aName: audio track chosen · sId / sName: subtitle track chosen (-1 = none)
    private fun rec(uri: String): org.json.JSONObject =
        try { org.json.JSONObject(prefs.getString("f:$uri", null) ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
    private fun edit(uri: String, f: (org.json.JSONObject) -> Unit) {
        val o = rec(uri); f(o)
        prefs.edit().apply { if (o.length() == 0) remove("f:$uri") else putString("f:$uri", o.toString()) }.apply()
    }
    fun str(uri: String, k: String): String = rec(uri).optString(k, "")
    fun setStr(uri: String, k: String, v: String) = edit(uri) { if (v.isEmpty()) it.remove(k) else it.put(k, v) }
    fun int(uri: String, k: String): Int? = rec(uri).let { if (it.has(k)) it.optInt(k) else null }
    fun setInt(uri: String, k: String, v: Int) = edit(uri) { it.put(k, v) }
    /** Every video file that has something saved. */
    fun files(): Set<String> = prefs.all.keys.filter { it.startsWith("f:") }.map { it.removePrefix("f:") }.toSet()
    fun forget(uri: String) = prefs.edit().remove("f:$uri").apply()

    /** Subtitles the app saved next to this video on a server (source id, folder, name). */
    fun subs(uri: String): List<Triple<String, String, String>> = rec(uri).optJSONArray("subs")?.let { a ->
        (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { Triple(it.optString(0), it.optString(1), it.optString(2)) } } } ?: emptyList()
    fun addSub(uri: String, srcId: String, folder: String, name: String) = edit(uri) { o ->
        val a = o.optJSONArray("subs") ?: org.json.JSONArray()
        val have = (0 until a.length()).any { a.optJSONArray(it)?.optString(2) == name && a.optJSONArray(it)?.optString(1) == folder }
        if (!have) a.put(org.json.JSONArray().put(srcId).put(folder).put(name))
        o.put("subs", a)
    }

    /** Decoder chosen by hand for this file, or "" (Automatic / the general setting). */
    fun manualDecoder(uri: String) = str(uri, "dec")
    fun manualDeinterlace(uri: String) = str(uri, "di")

    fun software(uri: String): Boolean = rec(uri).optBoolean("swA", false)
    fun markSoftware(uri: String) = edit(uri) { it.put("swA", true) }

    private val TV_EXT = setOf("ts", "m2ts", "mts", "m2t", "vob", "mpg", "mpeg", "mpe", "m2v", "dv", "tod", "mod")
    private fun ext(uri: String) = uri.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** Deinterlace this file? Known from an earlier play, else guessed from the file type. */
    fun deinterlace(uri: String): Boolean = when (int(uri, "diA")) { 1 -> true; 0 -> false; else -> ext(uri) in TV_EXT }

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
            setInt(uri, "diA", if (want) 1 else 0)
            if (want != deintNow) reopen = true
        }
        return reopen && mayReopen(uri)
    }
}

/**
 * Per-file video data is forgotten when the file is gone from the library: phone files are checked when the app
 * starts (and when their folder is shown); network files when their folder is listed in Libraries and the file
 * isn't there any more. Covers what Automatic learnt, choices by hand, picture format, tracks, Hue & sat,
 * resume position and the audio / subtitle delays kept for that file.
 */
object VideoFileCleanup {
    private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "m2t", "vob", "mpg", "mpeg", "mpe",
        "m2v", "wmv", "flv", "3gp", "ogv", "dv", "tod", "mod", "rm", "rmvb", "asf", "divx", "f4v")

    private fun isVideo(uri: String, st: app.vlcinterface.data.AppState): Boolean {
        if (uri in VideoAuto.files() || uri in st.settings.pictureByFile || "file:$uri" in st.subOffsets || "file:$uri" in st.audioOffsets) return true
        return uri.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase() in VIDEO_EXT
    }

    /** Every video file the app keeps something for. */
    private fun known(): Set<String> {
        val st = app.vlcinterface.App.instance.store.current
        val out = HashSet<String>()
        out += VideoAuto.files()
        out += st.settings.pictureByFile.keys
        out += st.subOffsets.keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }
        out += st.audioOffsets.keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }
        out += st.recent.map { it.uri }.filter { isVideo(it, st) }
        return out
    }

    fun forget(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val set = uris.toSet()
        // Downloaded subtitles of these videos (app copies and the ones saved next to the video) are deleted too.
        val app0 = app.vlcinterface.App.instance
        set.forEach { u ->
            val written = VideoAuto.subs(u)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { app.vlcinterface.sources.SubtitleFiles.forgetVideo(app0, u, written) } catch (_: Exception) {}
            }
        }
        set.forEach { VideoAuto.forget(it) }
        app.vlcinterface.App.instance.store.update { st ->
            st.copy(
                settings = st.settings.copy(pictureByFile = st.settings.pictureByFile - set),
                subOffsets = st.subOffsets.filterKeys { it.removePrefix("file:") !in set || !it.startsWith("file:") },
                audioOffsets = st.audioOffsets.filterKeys { it.removePrefix("file:") !in set || !it.startsWith("file:") },
                recent = st.recent.filter { it.uri !in set },
            )
        }
    }

    /** Phone files: forgotten when the file no longer exists. */
    fun checkPhone(ctx: Context) {
        val gone = known().filter { u ->
            when {
                u.startsWith("content://") -> try {
                    ctx.contentResolver.query(android.net.Uri.parse(u), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { !it.moveToFirst() } ?: true
                } catch (_: SecurityException) { false } catch (_: Exception) { true }
                u.startsWith("file://") -> !java.io.File(android.net.Uri.parse(u).path ?: "").exists()
                u.startsWith("/") -> !java.io.File(u).exists()
                else -> false
            }
        }
        forget(gone)
    }

    /** A folder was just listed (network or phone path): its video files that aren't there any more are forgotten. */
    fun folderListed(folderUri: String, present: Collection<String>) {
        val dir = folderUri.trimEnd('/')
        val here = present.toSet()
        val st = app.vlcinterface.App.instance.store.current
        forget(known().filter { it.substringBeforeLast('/') == dir && it !in here && isVideo(it, st) })
    }
}
