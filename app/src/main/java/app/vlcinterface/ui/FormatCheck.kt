package app.vlcinterface.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import app.vlcinterface.App
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.videolan.libvlc.MediaPlayer
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/**
 * Video format analysis (requested 2026-11-21 03:02 / 03:05): with every control kept on screen, the playing video
 * and the next ones of its list (5 in all) are each shown in every picture format, upright and sideways; each one
 * is photographed (the screen with the real video picture put in its place) and measured, and everything that
 * decides the picture's size and shape is noted. All goes into one zip in Download/vlc_interface, ready to hand
 * over, so a wrong width / height can be fixed from facts instead of guesses.
 */
object FormatCheck {
    /**
     * Analysis mode (Settings → Logs → "Video format analysis"): every control of the video player stays visible
     * and an "Analyse" button is shown on it. You pick the folder and start the first video yourself, then press it.
     */
    val mode = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Shown on the player while the analysis runs ("" = not running). */
    val running = kotlinx.coroutines.flow.MutableStateFlow("")

    /** How many videos one analysis covers: the playing one and the next ones of its list. */
    const val VIDEOS = 5

    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    class Step(val name: String, val jpeg: ByteArray?, val report: String)

    /**
     * Runs the analysis on the playing video and the next ones (up to [VIDEOS]): each is shown in every format,
     * upright and sideways, photographed and measured. [formats]: the player's formats in button order; [setFormat]
     * switches the player screen to one (index) so its own label follows. Returns where the zip was saved, or null.
     */
    suspend fun run(activity: Activity, formats: List<Pair<MediaPlayer.ScaleType, String>>, setFormat: (Int) -> Unit, currentIndex: Int): String? {
        val player = App.instance.player
        val wasOrientation = activity.requestedOrientation
        // Put back at the end (2026-11-21 03:05): the first video, at the spot it was, playing or paused as it was.
        val s0 = player.state.value
        val firstItem = s0.item; val firstTime = s0.time; val wasPlaying = s0.isPlaying
        val steps = ArrayList<Step>()
        val infos = ArrayList<Pair<String, List<Pair<String, String>>>>()
        val perVideo = formats.size * 2
        try {
            for (v in 1..VIDEOS) {
                if (v > 1) {
                    // The next video of the list: wait until it plays and its size is known.
                    val before = player.state.value.item
                    running.value = "Analysis: video $v/$VIDEOS — opening the next one"
                    player.next()
                    var waited = 0
                    while (waited < 20_000) {
                        delay(250); waited += 250
                        val st = player.state.value
                        if (st.item != null && st.item !== before && st.isPlaying && st.videoW > 0) break
                    }
                    val st = player.state.value
                    if (st.item == null || st.item === before || st.item.isMusic || st.item.isRadio || st.videoW <= 0) break
                    delay(1500)
                }
                val item = player.state.value.item ?: break
                infos += item.title to player.formatDiag()
                // This video's own format, put back after its photos (each video keeps its format).
                val own = formats.indexOfFirst { it.first == player.scale }.coerceAtLeast(0)
                val startLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                for ((pass, land) in listOf(startLandscape, !startLandscape).withIndex()) {
                    if (pass == 1) {
                        activity.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        delay(2500)                                 // turned, laid out, picture re-fitted
                    }
                    val side = if (land) "sideways" else "upright"
                    formats.forEachIndexed { i, (_, label) ->
                        running.value = "Analysis: video $v/$VIDEOS · ${pass * formats.size + i + 1}/$perVideo · $side · $label"
                        setFormat(i)
                        delay(2600)                                  // libVLC re-fits the picture (and the size self-check has run)
                        running.value = ""                           // the note itself stays out of the photo
                        delay(250)
                        val shot = try { capture(activity) } catch (_: Throwable) { null }
                        val name = "v%d_%02d_%s_%s".format(Locale.US, v, pass * formats.size + i + 1, side, label.replace(":", "x"))
                        steps += Step(name, shot?.first, buildReport(activity, "video $v · ${item.title}", side, label, shot?.second))
                    }
                }
                activity.requestedOrientation = wasOrientation
                setFormat(own)
                delay(1500)
            }
        } finally {
            running.value = ""
            activity.requestedOrientation = wasOrientation
            // The player back as it was before the analysis: the first video again, same spot, same play / pause,
            // its format (the per-file formats of the other videos are put back as they were too, see setFormat).
            try {
                val st = player.state.value
                if (firstItem != null && st.item !== firstItem) {
                    val i = st.queue.indexOfFirst { it === firstItem }.takeIf { it >= 0 } ?: st.queue.indexOfFirst { it.uri == firstItem.uri }
                    if (i >= 0) {
                        player.jumpTo(i)
                        var w = 0
                        while (w < 10_000 && !(player.state.value.item?.uri == firstItem.uri && player.state.value.isPlaying)) { delay(200); w += 200 }
                    }
                }
                if (player.state.value.item?.uri == firstItem?.uri) {
                    if (firstTime > 0) player.seekTo(firstTime)
                    if (!wasPlaying) player.pause()
                }
            } catch (_: Throwable) {}
            setFormat(currentIndex)
        }
        if (steps.isEmpty()) return null
        running.value = "Analysis: packing the archive…"
        val where = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { save(activity, steps, infos) }
        running.value = ""
        mode.value = false                                   // done: the player goes back to normal
        return where
    }

    /** What was measured on one photo. */
    class Measure(val layout: Rect?, val surface: Rect?, val surfaceBuffer: String, val picture: Rect?)

    private suspend fun pixelCopyWindow(a: Activity, bmp: Bitmap): Boolean = suspendCancellableCoroutine { c ->
        try { PixelCopy.request(a.window, bmp, { r -> c.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper())) }
        catch (_: Throwable) { c.resume(false) }
    }

    private suspend fun pixelCopySurface(v: SurfaceView, bmp: Bitmap): Boolean = suspendCancellableCoroutine { c ->
        try { PixelCopy.request(v, bmp, { r -> c.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper())) }
        catch (_: Throwable) { c.resume(false) }
    }

    private fun onScreen(v: View): Rect { val p = IntArray(2); v.getLocationInWindow(p); return Rect(p[0], p[1], p[0] + v.width, p[1] + v.height) }

    /** The screen with the video picture put where its surface is, outlines of the video view (red) and surface (green). */
    private suspend fun capture(a: Activity): Pair<ByteArray, Measure> {
        val decor = a.window.decorView
        val shot = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        pixelCopyWindow(a, shot)
        val layout = App.instance.player.videoLayout
        val sv = App.instance.player.videoSurfaceView()
        var surfRect: Rect? = null; var buf = "none"
        val cv = Canvas(shot)
        if (sv != null && sv.width > 0 && sv.height > 0) {
            surfRect = onScreen(sv)
            val frame = sv.holder.surfaceFrame
            buf = "${frame.width()} × ${frame.height()}"
            val sb = Bitmap.createBitmap(frame.width().coerceAtLeast(1), frame.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            if (pixelCopySurface(sv, sb)) cv.drawBitmap(sb, null, surfRect, Paint(Paint.FILTER_BITMAP_FLAG))
            sb.recycle()
        }
        val layRect = layout?.let { onScreen(it) }
        // Where the picture really is: the non-black part inside the video view.
        val pic = layRect?.let { contentBox(shot, it) }
        val st = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        layRect?.let { st.color = Color.RED; cv.drawRect(it, st) }
        surfRect?.let { st.color = Color.GREEN; cv.drawRect(it, st) }
        pic?.let { st.color = Color.YELLOW; st.strokeWidth = 2f; cv.drawRect(it, st) }
        val out = ByteArrayOutputStream(); shot.compress(Bitmap.CompressFormat.JPEG, 70, out); shot.recycle()
        return out.toByteArray() to Measure(layRect, surfRect, buf, pic)
    }

    /** Bounding box of the not-black pixels inside [r] (the picture without its black bars); null if all dark. */
    private fun contentBox(b: Bitmap, r0: Rect): Rect? {
        val r = Rect(r0); if (!r.intersect(0, 0, b.width, b.height)) return null
        fun lit(x: Int, y: Int): Boolean { val c = b.getPixel(x, y); return Color.red(c) + Color.green(c) + Color.blue(c) > 60 }
        val stepX = maxOf(1, r.width() / 200); val stepY = maxOf(1, r.height() / 200)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var rr = -1; var bb = -1
        var y = r.top
        while (y < r.bottom) { var x = r.left; while (x < r.right) { if (lit(x, y)) { if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > bb) bb = y }; x += stepX }; y += stepY }
        return if (rr < 0) null else Rect(l, t, rr + 1, bb + 1)
    }

    private fun fmt(r: Rect?) = r?.let { "${it.width()} × ${it.height()} at (${it.left}, ${it.top})" + if (it.height() > 0) "  aspect %.4f".format(Locale.US, it.width().toDouble() / it.height()) else "" } ?: "-"

    private fun buildReport(a: Activity, video: String, side: String, label: String, m: Measure?): String = buildString {
        val dm = a.resources.displayMetrics
        appendLine("== $video · $side · $label")
        appendLine("  Screen: ${dm.widthPixels} × ${dm.heightPixels} px, density ${dm.density}")
        appendLine("  Video view (red): ${fmt(m?.layout)}")
        appendLine("  Video surface (green): ${fmt(m?.surface)}, buffer ${m?.surfaceBuffer ?: "-"}")
        appendLine("  Picture seen (yellow, non-black part): ${fmt(m?.picture)}")
        App.instance.player.formatDiag().forEach { (k, v) -> appendLine("  $k: $v") }
        // The surface against the size this format calls for (the yellow box is unreliable with the controls on screen).
        val want = m?.layout?.let { App.instance.player.expectedSurface(it.width(), it.height()) }
        val sr = m?.surface
        if (want != null && sr != null) {
            val ok = kotlin.math.abs(sr.width() - want.first) <= maxOf(3, want.first / 100) && kotlin.math.abs(sr.height() - want.second) <= maxOf(3, want.second / 100)
            appendLine("  Surface wanted for this format: ${want.first} × ${want.second} → ${if (ok) "OK" else "WRONG (off by ${sr.width() - want.first} × ${sr.height() - want.second})"}")
        }
        appendLine()
    }

    private fun save(ctx: android.content.Context, steps: List<Step>, infos: List<Pair<String, List<Pair<String, String>>>>): String? {
        val name = "vlc-interface-format-analysis_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"
        var uri: android.net.Uri? = null
        return try {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/vlc_interface")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("no place")
            ctx.contentResolver.openOutputStream(uri!!)?.use { os ->
                ZipOutputStream(os).use { z ->
                    val report = buildString {
                        appendLine("generated by vlc interface (Video format check) — ${ts.format(Date())}")
                        appendLine()
                        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { appendLine("App: vlc interface ${it.versionName}") } } catch (_: Exception) {}
                        appendLine("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
                        appendLine("Videos: ${infos.size}, photos: ${steps.count { it.jpeg != null }}")
                        appendLine("Outlines on the photos: red = video view, green = video surface, yellow = the non-black picture")
                        appendLine()
                        infos.forEachIndexed { i, (title, info) ->
                            appendLine("--- Video ${i + 1}: $title")
                            info.forEach { (k, v) -> appendLine("  $k: $v") }
                            appendLine()
                        }
                        steps.forEach { append(it.report) }
                    }
                    z.putNextEntry(ZipEntry("report.txt")); z.write(report.toByteArray()); z.closeEntry()
                    steps.forEach { st -> st.jpeg?.let { z.putNextEntry(ZipEntry(st.name + ".jpg")); z.write(it); z.closeEntry() } }
                }
            } ?: throw Exception("cannot write")
            ctx.contentResolver.update(uri!!, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            "Download/vlc_interface/$name"
        } catch (_: Throwable) {
            try { uri?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Throwable) {}
            null
        }
    }
}
