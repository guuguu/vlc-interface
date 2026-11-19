package app.vlcinterface.ui

import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.vlcinterface.alarm.Alarms
import app.vlcinterface.alarm.VisSettings
import app.vlcinterface.player.NativeAudio
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

private val PALETTES: List<Pair<String, String>> = listOf("pattern" to "Pattern colours", "sunset" to "Sunset", "ocean" to "Ocean",
    "forest" to "Forest", "neon" to "Neon", "fire" to "Fire", "aurora" to "Aurora", "custom" to "Custom")

private val EFFECT_NAMES = listOf("clouds" to "Colour clouds", "lava" to "Lava glow", "nebula" to "Nebula", "tides" to "Tides",
    "plasma" to "Plasma", "silk" to "Silk bands", "sunset" to "Sunset sky", "ocean" to "Deep ocean", "embers" to "Embers")

private fun c(vararg v: Long) = v.map { Color(it) }

/** Each pattern's own colours: 4 spot colours + 3 background colours (top / middle / bottom). */
private fun patternColours(effect: String): Pair<List<Color>, List<Color>> = when (effect) {
    "lava" -> c(0xFFFF5400, 0xFFFFBD00, 0xFFD00000, 0xFFFF8C3C) to c(0xFF2A0006, 0xFF4A000C, 0xFF6A0010)
    "nebula" -> c(0xFFF72585, 0xFF4CC9F0, 0xFF7209B7, 0xFFFFFFFF) to c(0xFF0B0020, 0xFF2A0A6B, 0xFF10002B)
    "tides" -> c(0xFF00B4D8, 0xFF90E0EF, 0xFF0077B6, 0xFFCAF0F8) to c(0xFF023E8A, 0xFF0077B6, 0xFF03045E)
    "plasma" -> c(0xFF7209B7, 0xFFF72585, 0xFF4361EE, 0xFF4CC9F0) to c(0xFF240046, 0xFF3A0CA3, 0xFF10002B)
    "silk" -> c(0xFFEDF2F4, 0xFF8D99AE, 0xFFEF233C, 0xFFFFD166) to c(0xFF1B1D2E, 0xFF2B2D42, 0xFF1B1D2E)
    "sunset" -> c(0xFFFF6B35, 0xFFF72585, 0xFFFFD166, 0xFF3A0CA3) to c(0xFF3A0CA3, 0xFFF72585, 0xFFFFD166)
    "ocean" -> c(0xFF48CAE4, 0xFFCAF0F8, 0xFF0077B6, 0xFF90E0EF) to c(0xFF0096C7, 0xFF0077B6, 0xFF03045E)
    "embers" -> c(0xFFF48C06, 0xFFFFBA08, 0xFFD00000, 0xFFFFE6B4) to c(0xFF1A0208, 0xFF2A0410, 0xFF6A040F)
    else -> c(0xFFFF6B35, 0xFFF72585, 0xFF7209B7, 0xFFFFD166) to c(0xFF050505, 0xFF050505, 0xFF050505)
}

private fun paletteColours(v: VisSettings): List<Color>? = when (v.palette) {
    "sunset" -> c(0xFFFF6B35, 0xFFF72585, 0xFF7209B7, 0xFFFFD166)
    "ocean" -> c(0xFF0077B6, 0xFF00B4D8, 0xFF90E0EF, 0xFF3A0CA3)
    "forest" -> c(0xFF2D6A4F, 0xFF52B788, 0xFFB7E4C7, 0xFFFFD60A)
    "neon" -> c(0xFFFF00FF, 0xFF00FFFF, 0xFF39FF14, 0xFFFFFF00)
    "fire" -> c(0xFFD00000, 0xFFFF5400, 0xFFFFBD00, 0xFFFF0054)
    "aurora" -> c(0xFF00F5D4, 0xFF9B5DE5, 0xFFF15BB5, 0xFF00BBF9)
    "custom" -> listOf(v.hue1, v.hue2, v.hue3, (v.hue1 + v.hue3) / 2).map { Color.hsl(it.coerceIn(0f, 359.9f), 0.9f, 0.55f) }
    else -> null
}

/** Spot colours: the chosen combination, or the pattern's own. */
fun visColours(v: VisSettings): List<Color> = paletteColours(v) ?: patternColours(v.effect).first

/** Background colours: the pattern's own, tinted toward the chosen combination when there is one. */
private fun visBackground(v: VisSettings): List<Color> {
    val own = patternColours(v.effect).second
    val p = paletteColours(v) ?: return own
    return own.mapIndexed { i, o -> lerpC(o, p[i % p.size], 0.25f).let { Color(it.red * 0.55f, it.green * 0.55f, it.blue * 0.55f) } }
}

private fun lerpC(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

/** Button under the clock button: the full-screen visualisation. */
@Composable
fun VisButton(nav: Navigator, size: Int = 30) {
    val cols = visColours(Alarms.state.collectAsState().value.vis)
    Box(Modifier.size(size.dp).liteShadow(CircleShape).clip(CircleShape).background(Color(0x24FFFFFF)).clickable { nav.go(Screen.Visualisation) },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size((size * 0.7f).dp)) {
            val r = this.size.minDimension * 0.26f
            drawCircle(cols[0], r, Offset(this.size.width * 0.36f, this.size.height * 0.4f), alpha = 0.9f)
            drawCircle(cols[1], r, Offset(this.size.width * 0.64f, this.size.height * 0.4f), alpha = 0.9f)
            drawCircle(cols[2], r, Offset(this.size.width * 0.5f, this.size.height * 0.66f), alpha = 0.9f)
        }
    }
}

// ---------------------------------------------------------------- motion from the music

/** Levels and phases of one animation: lo / hi = lower / upper part of the frequency range (0…1, smoothed). */
private class VisMotion {
    var t by mutableFloatStateOf(0f)
    var lo = 0f
    var hi = 0f
    var pb = 0f
    var ps = 0f
    var refLo = 0.02f
    var refHi = 0.02f
}

/**
 * Frame loop: band levels from the app's sound output (or a gentle simulated beat in previews / other engines).
 * Levels are normalised to the music (slow automatic gain) and smoothed with limited speed: no flashing.
 */
@Composable
private fun rememberVisMotion(v: VisSettings, live: Boolean): VisMotion {
    val m = remember { VisMotion() }
    val vs by rememberUpdatedState(v)
    LaunchedEffect(live) {
        val buf = FloatArray(2)
        var last = 0L
        var clock = 0f
        while (true) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1e9f).coerceIn(0f, 0.1f)
                last = n; clock += dt
                val playing = try { app.vlcinterface.App.instance.player.state.value.isPlaying } catch (_: Throwable) { false }
                var a: Float
                var b: Float
                if (live && NativeAudio.active) {
                    NativeAudio.bandRange(vs.fLo, vs.fHi)
                    NativeAudio.bands(buf)
                    // Automatic gain: follows the loudest recent level, slowly letting go.
                    m.refLo = max(m.refLo * (1f - dt * 0.15f), max(buf[0], 0.004f))
                    m.refHi = max(m.refHi * (1f - dt * 0.15f), max(buf[1], 0.004f))
                    a = (buf[0] / m.refLo).coerceIn(0f, 1f).pow(1.6f)
                    b = (buf[1] / m.refHi).coerceIn(0f, 1f).pow(1.6f)
                } else if (!live || playing) {
                    // Simulated: kick on the beat (120 BPM) for the low part, off-beat hats for the high part.
                    a = exp(-(clock % 0.5f) / 0.13f) + 0.25f * exp(-((clock + 0.25f) % 2f) / 0.3f)
                    b = 0.7f * exp(-((clock + 0.125f) % 0.25f) / 0.05f) * (0.6f + 0.4f * sin(clock * 1.3f))
                    a = (a * 0.7f).coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
                } else { a = 0f; b = 0f }
                val k = vs.move.coerceIn(0.2f, 2f)
                a = (a * k).coerceIn(0f, 1f); b = (b * k).coerceIn(0f, 1f)
                // Gentle: rises quickly but limited, falls slowly (no flashing).
                fun follow(cur: Float, target: Float) = cur + (target - cur) * ((if (target > cur) 7f else 2.2f) * dt).coerceAtMost(1f)
                m.lo = follow(m.lo, a); m.hi = follow(m.hi, b)
                m.pb += dt * (0.25f + 1.5f * m.lo * k)
                m.ps += dt * (0.45f + 2.4f * m.hi * k)
                m.t = clock
            }
        }
    }
    return m
}

/** The visualisation itself (full screen or a preview). */
@Composable
private fun VisCanvas(v: VisSettings, modifier: Modifier, live: Boolean) {
    val m = rememberVisMotion(v, live)
    val spots = visColours(v)
    val bg = visBackground(v)
    val plasma = remember { android.graphics.Bitmap.createBitmap(18, 32, android.graphics.Bitmap.Config.ARGB_8888) }
    val px = remember { IntArray(18 * 32) }
    Canvas(modifier) {
        val t = m.t
        val a = v.brightness.coerceIn(0.2f, 1f)
        drawBackground(v.effect, bg, t, m.lo, a, plasma, px)
        drawSpots(spots, m, a, v.mix.coerceIn(0f, 1f), v.effect)
    }
}

private fun DrawScope.spot(c: Color, alpha: Float, center: Offset, r: Float) {
    val rr = r.coerceAtLeast(1f)
    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.4f), Color.Transparent), center, rr), rr, center, blendMode = BlendMode.Plus)
}

/** Big spots follow the low part, small spots the high part; both travel across the whole screen. */
private fun DrawScope.drawSpots(cols: List<Color>, m: VisMotion, a: Float, mix: Float, effect: String) {
    val w = size.width
    val h = size.height
    val mn = size.minDimension
    val bigA = (if (effect == "clouds") 0.75f else 0.5f) * (0.55f + 0.6f * mix) * a
    for (i in 0 until 4) {
        val p = m.pb * (0.45f + i * 0.13f) + i * 1.9f
        val q = m.pb * (0.37f + i * 0.11f) + i * 2.7f
        val r = mn * (0.5f + 0.35f * m.lo) * (0.8f + 0.5f * mix) * (0.85f + 0.2f * sin(p * 1.3f))
        spot(cols[i % cols.size], bigA, Offset(w * (0.5f + 0.48f * sin(p)), h * (0.5f + 0.46f * cos(q))), r)
    }
    for (i in 0 until 7) {
        val p = m.ps * (0.7f + i * 0.17f) + i * 1.3f
        val q = m.ps * (0.55f + i * 0.13f) + i * 2.1f
        val r = mn * (0.11f + 0.11f * m.hi) * (0.8f + 0.5f * mix)
        spot(cols[(i + 1) % cols.size], (0.35f + 0.35f * m.hi) * (0.6f + 0.5f * mix) * a, Offset(w * (0.5f + 0.47f * sin(p)), h * (0.5f + 0.47f * cos(q))), r)
    }
}

/** Each pattern's background: always fills the whole screen (ambient light). */
private fun DrawScope.drawBackground(effect: String, bg: List<Color>, t: Float, lo: Float, a: Float, plasma: android.graphics.Bitmap, px: IntArray) {
    val w = size.width
    val h = size.height
    fun dim(c: Color) = Color(c.red * a, c.green * a, c.blue * a)
    when (effect) {
        "tides" -> {
            drawRect(dim(bg[0]))
            listOf(bg[1], lerpC(bg[1], Color.White, 0.25f), lerpC(bg[1], Color.White, 0.45f)).forEachIndexed { k, col ->
                val path = Path()
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w + 8f) { path.lineTo(x, h * (0.35f + 0.2f * k) + h * (0.03f + 0.07f * lo) * sin(x / w * 7f + t * (1.3f + k * 0.4f) + k)); x += 8f }
                path.lineTo(w, h); path.close()
                drawPath(path, dim(col).copy(alpha = 0.75f))
            }
        }
        "plasma" -> {
            for (j in 0 until 32) for (i in 0 until 18) {
                val v = sin(i * 0.35f + t * 1.4f) + sin(j * 0.25f + t) + sin((i + j) * 0.2f + t * 0.7f) +
                    sin(hypot(i - 9f - 6f * sin(t * 0.5f), j - 16f - 10f * cos(t * 0.4f)) * 0.35f - t * (1.5f + 2f * lo))
                val hue = (((v * 45f + t * 30f) % 360f) + 360f) % 360f
                px[j * 18 + i] = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, (0.45f + 0.2f * lo) * a))
            }
            plasma.setPixels(px, 0, 18, 0, 0, 18, 32)
            drawImage(plasma.asImageBitmap(), dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.Low)
        }
        "silk" -> {
            drawRect(dim(bg[1]))
            for (k in 0 until 4) {
                val y0 = h * (0.15f + 0.22f * k)
                val path = Path().apply {
                    moveTo(-10f, y0)
                    cubicTo(w * 0.3f, y0 - h * 0.08f + 20f * sin(t * 0.6f + k), w * 0.6f, y0 + h * 0.12f + 20f * cos(t * 0.5f + k), w + 10f, y0 + 10f * sin(t + k))
                    lineTo(w + 10f, y0 + h * 0.06f * (1f + lo))
                    cubicTo(w * 0.6f, y0 + h * 0.16f + 20f * cos(t * 0.5f + k), w * 0.3f, y0 - h * 0.02f + 20f * sin(t * 0.6f + k), -10f, y0 + h * 0.06f)
                    close()
                }
                drawPath(path, dim(lerpC(bg[1], Color.White, 0.3f + 0.15f * k)).copy(alpha = 0.35f + 0.2f * lo))
            }
        }
        "sunset", "ocean" -> {
            val s = 0.1f * sin(t * 0.3f)
            drawRect(Brush.verticalGradient(0f to dim(bg[0]), (0.5f + s) to dim(bg[1]), 1f to dim(bg[2])))
        }
        "nebula" -> drawRect(Brush.radialGradient(listOf(dim(bg[1]), dim(bg[0])), Offset(w * (0.6f + 0.1f * sin(t * 0.2f)), h * 0.4f), h * 0.8f))
        "lava", "embers" -> {
            drawRect(Brush.verticalGradient(listOf(dim(bg[0]), dim(bg[1]), dim(bg[2]))))
            drawRect(Brush.radialGradient(listOf(dim(bg[2]).copy(alpha = 0.6f + 0.3f * lo), Color.Transparent), Offset(w / 2, h * 1.05f), h * (0.55f + 0.25f * lo)))
        }
        else -> drawRect(dim(bg[0]))
    }
}

// ---------------------------------------------------------------- full screen

/**
 * Full screen: the screen stays on, Android's navigation bar stays usable. Double tap anywhere: back.
 * Slide along the long side: next / previous colour combination; along the short side: screen brightness.
 */
@Composable
fun VisualisationScreen(nav: Navigator) {
    val v = Alarms.state.collectAsState().value.vis
    val act = LocalContext.current.findActivity()
    var bright by remember { mutableFloatStateOf(act?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var showB by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var noteAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        val w = act?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { w?.insetsController?.hide(WindowInsets.Type.statusBars()) } catch (_: Exception) {}   // bottom navigation bar stays usable
        onDispose {
            try { w?.insetsController?.show(WindowInsets.Type.statusBars()) } catch (_: Exception) {}
            w?.attributes = w?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    fun setBright(b: Float) {
        bright = b.coerceIn(0.01f, 1f); shownAt = System.currentTimeMillis(); showB = true
        act?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = bright } }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { nav.back() }) }
        .pointerInput(Unit) {
            var axis = 0
            var acc = 0f
            var sx = 0f
            var sy = 0f
            detectDragGestures(onDragStart = { axis = 0; acc = 0f; sx = 0f; sy = 0f }) { ch, d ->
                ch.consume()
                val portrait = size.height >= size.width
                sx += d.x; sy += d.y
                if (axis == 0 && (abs(sx) > 24f || abs(sy) > 24f)) axis = if ((abs(sy) > abs(sx)) == portrait) 1 else 2
                if (axis == 1) {
                    acc += if (portrait) d.y else d.x
                    if (abs(acc) > 140.dp.toPx()) {
                        val dir = if (acc > 0) 1 else -1
                        acc = 0f
                        val keys = PALETTES.map { it.first }
                        val cur = Alarms.current.vis
                        val next = keys[(keys.indexOf(cur.palette).coerceAtLeast(0) + dir + keys.size) % keys.size]
                        Alarms.setVis(cur.copy(palette = next))
                        note = PALETTES.first { it.first == next }.second; noteAt = System.currentTimeMillis()
                    }
                } else if (axis == 2) setBright(bright + (if (portrait) d.x else -d.y) / 700f)
            }
        }) {
        VisCanvas(v, Modifier.fillMaxSize(), live = true)
        if (showB) Text("☀ ${(bright * 100).toInt()} %", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        if (note.isNotEmpty()) Text(note, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, modifier = Modifier.align(Alignment.Center).padding(top = 60.dp))
        LaunchedEffect(shownAt) { kotlinx.coroutines.delay(1300); showB = false }
        LaunchedEffect(noteAt) { kotlinx.coroutines.delay(1300); note = "" }
    }
}

// ---------------------------------------------------------------- settings

private const val F_MIN = 20f
private const val F_MAX = 10_000f
private fun toPos(f: Float) = (ln(f / F_MIN) / ln(F_MAX / F_MIN)).coerceIn(0f, 1f)
private fun toHz(p: Float) = F_MIN * (F_MAX / F_MIN).pow(p)
private fun hzText(f: Float) = if (f >= 1000f) "%.1f kHz".format(f / 1000f) else "${f.toInt()} Hz"

/** Settings → Visualisation: pattern (with a 3 s preview above the buttons), colours, sliders, frequency range. */
@Composable
fun VisualisationSettings() {
    val v = Alarms.state.collectAsState().value.vis
    fun set(n: VisSettings) = Alarms.setVis(n)
    var preview by remember { mutableStateOf<String?>(null) }
    var previewAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(previewAt) { if (previewAt > 0) { kotlinx.coroutines.delay(3000); preview = null } }
    val dens = LocalDensity.current
    CardBox {
        Text("Pattern", color = C.text, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
        Box {
            // Preview: above the pattern buttons, closes by itself after 3 s (another pick shows that one).
            preview?.let { k ->
                Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, with(dens) { -(196).dp.roundToPx() })) {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(C.panel).border(1.dp, accent(), RoundedCornerShape(12.dp)).padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        VisCanvas(v.copy(effect = k), Modifier.size(96.dp, 170.dp).clip(RoundedCornerShape(8.dp)), live = false)
                        Text(EFFECT_NAMES.first { it.first == k }.second, color = C.text, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EFFECT_NAMES.forEach { (k, l) -> Pill(l, selected = v.effect == k) { set(v.copy(effect = k)); preview = k; previewAt = System.currentTimeMillis() } }
            }
        }
        Divider()
        Text("Colours", color = C.text, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PALETTES.forEach { (key, label) ->
                val cs = visColours(v.copy(palette = key))
                val on = v.palette == key
                Column(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, if (on) accent() else C.line, RoundedCornerShape(10.dp))
                    .clickable { set(v.copy(palette = key)) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row { cs.forEach { Box(Modifier.size(14.dp).clip(CircleShape).background(it)) } }
                    Text(label, color = if (on) accent() else C.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (v.palette == "custom") Column(Modifier.padding(horizontal = 16.dp)) {
            listOf(v.hue1, v.hue2, v.hue3).forEachIndexed { i, hue ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour ${i + 1}", color = C.text, fontSize = 13.sp, modifier = Modifier.width(78.dp))
                    GradientBar(hue / 360f, Brush.horizontalGradient(listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f).map { Color.hsl(it, 0.9f, 0.55f) }),
                        Modifier.weight(1f)) { f -> val h = f * 360f; set(when (i) { 0 -> v.copy(hue1 = h); 1 -> v.copy(hue2 = h); else -> v.copy(hue3 = h) }) }
                    Box(Modifier.padding(start = 10.dp).size(26.dp).clip(CircleShape).background(Color.hsl(hue.coerceIn(0f, 359.9f), 0.9f, 0.55f)))
                }
            }
        }
        Divider()
        VisSlider("Movement", v.move, 0.2f..2f, "×%.1f".format(v.move)) { set(v.copy(move = it)) }
        VisSlider("Colour mixing", v.mix, 0f..1f, "${(v.mix * 100).toInt()} %") { set(v.copy(mix = it)) }
        VisSlider("Brightness", v.brightness, 0.2f..1f, "${(v.brightness * 100).toInt()} %") { set(v.copy(brightness = it)) }
        Divider()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Frequencies followed", color = C.text, fontSize = 15.sp)
                Text(hzText(v.fLo) + " – " + hzText(v.fHi) + if (v.fHi / v.fLo >= 4f) " · big spots: lower part, small spots: upper part" else " · all spots together",
                    color = C.muted, fontSize = 12.sp)
            }
            Pill("Reset") { set(v.copy(fLo = 30f, fHi = 150f)) }
        }
        RangeSlider(toPos(v.fLo)..toPos(v.fHi), { r ->
            val lo = toHz(r.start)
            val hi = toHz(r.endInclusive).coerceAtLeast(lo * 1.2f).coerceAtMost(F_MAX)
            set(v.copy(fLo = lo, fHi = hi))
        }, Modifier.padding(horizontal = 16.dp), colors = SliderDefaults.colors(thumbColor = accent(), activeTrackColor = accent()))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("20 Hz", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("450 Hz", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("10 kHz", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Text("On the visualisation: double tap to leave · slide along the long side for the colours, along the short side for the brightness. " +
            "Movements follow only the chosen frequencies and stay gentle (no flashing).", color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp))
        Box(Modifier.height(2.dp))
    }
}

@Composable
private fun VisSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, colors = SliderDefaults.colors(thumbColor = accent(), activeTrackColor = accent()))
        Text(shown, color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
    }
}
