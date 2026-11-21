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
import android.view.ViewGroup
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
 * Video format check (requested 2026-11-21 03:02): the playing video is shown in every picture format, upright and
 * sideways; each one is photographed (the screen with the real video picture put in its place) and measured, and
 * everything that decides the picture's size and shape is noted. All goes into one zip in Download/vlc_interface,
 * ready to hand over, so a wrong width / height can be fixed from facts instead of guesses.
 */
object FormatCheck {
    /** Shown on the player while the check runs ("" = not running). */
    val running = kotlinx.coroutines.flow.MutableStateFlow("")

    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    class Step(val name: String, val jpeg: ByteArray?, val report: String)

    /**
     * Runs the check. [formats]: the player's formats in button order; [setFormat] switches the player screen to one
     * (index) so its own label follows. Returns where the zip was saved, or null.
     */
    suspend fun run(activity: Activity, formats: List<Pair<MediaPlayer.ScaleType, String>>, setFormat: (Int) -> Unit, currentIndex: Int): String? {
        val player = App.instance.player
        val wasOrientation = activity.requestedOrientation
        val startLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val steps = ArrayList<Step>()
        val total = formats.size * 2
        try {
            for ((pass, land) in listOf(startLandscape, !startLandscape).withIndex()) {
                if (pass == 1) {
                    activity.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    delay(2500)                                 // turned, laid out, picture re-fitted
                }
                val side = if (land) "sideways" else "upright"
                formats.forEachIndexed { i, (_, label) ->
                    running.value = "Format check ${pass * formats.size + i + 1}/$total: $side · $label"
                    setFormat(i)
                    delay(1800)                                  // libVLC re-fits the picture
                    running.value = ""                           // the note itself stays out of the photo
                    delay(250)
                    val shot = try { capture(activity) } catch (_: Throwable) { null }
                    val name = "%02d_%s_%s".format(Locale.US, steps.size + 1, side, label.replace(":", "x"))
                    steps += Step(name, shot?.first, buildReport(activity, side, label, shot?.second))
                }
            }
        } finally {
            running.value = ""
            activity.requestedOrientation = wasOrientation
            setFormat(currentIndex)
        }
        return save(activity, steps, player.formatDiag())
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

    /** The video surface inside libVLC's view: the biggest visible SurfaceView (the other one carries subtitles). */
    private fun videoSurface(root: View?): SurfaceView? {
        val found = ArrayList<SurfaceView>()
        fun walk(v: View) { if (v is SurfaceView && v.visibility == View.VISIBLE) found += v; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        root?.let { walk(it) }
        return found.maxByOrNull { it.width.toLong() * it.height }
    }

    /** The screen with the video picture put where its surface is, outlines of the video view (red) and surface (green). */
    private suspend fun capture(a: Activity): Pair<ByteArray, Measure> {
        val decor = a.window.decorView
        val shot = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        pixelCopyWindow(a, shot)
        val layout = App.instance.player.videoLayout
        val sv = videoSurface(layout)
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
        val out = ByteArrayOutputStream(); shot.compress(Bitmap.CompressFormat.JPEG, 82, out); shot.recycle()
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

    private fun buildReport(a: Activity, side: String, label: String, m: Measure?): String = buildString {
        val dm = a.resources.displayMetrics
        appendLine("== $side · $label")
        appendLine("  Screen: ${dm.widthPixels} × ${dm.heightPixels} px, density ${dm.density}")
        appendLine("  Video view (red): ${fmt(m?.layout)}")
        appendLine("  Video surface (green): ${fmt(m?.surface)}, buffer ${m?.surfaceBuffer ?: "-"}")
        appendLine("  Picture seen (yellow, non-black part): ${fmt(m?.picture)}")
        App.instance.player.formatDiag().forEach { (k, v) -> appendLine("  $k: $v") }
        // What the picture's shape should be (from the app's picture size) against what is on the screen.
        val s = App.instance.player.state.value
        val want = if (s.videoW > 0 && s.videoH > 0) s.videoW.toDouble() / s.videoH else 0.0
        val seen = m?.picture?.takeIf { it.height() > 0 }?.let { it.width().toDouble() / it.height() }
        if (want > 0 && seen != null) appendLine("  Shape seen vs app's size: %.1f %% (%s)".format(Locale.US, (seen / want - 1) * 100,
            when (label) { "Fill" -> "stretched on purpose"; "Crop" -> "cut on purpose (compare the surface)"; "16:9", "4:3" -> "forced shape on purpose"; else -> "should be about 0" }))
        appendLine()
    }

    private fun save(ctx: android.content.Context, steps: List<Step>, info: List<Pair<String, String>>): String? {
        val name = "vlc-interface-format-check_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"
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
                        appendLine("Outlines on the photos: red = video view, green = video surface, yellow = the non-black picture")
                        appendLine()
                        info.forEach { (k, v) -> appendLine("$k: $v") }
                        appendLine()
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
