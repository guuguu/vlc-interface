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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.alarm.Alarms
import app.vlcinterface.alarm.VisSettings
import app.vlcinterface.player.NativeAudio
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val PALETTES: List<Pair<String, String>> = listOf("sunset" to "Sunset", "ocean" to "Ocean", "forest" to "Forest",
    "neon" to "Neon", "fire" to "Fire", "aurora" to "Aurora", "custom" to "Custom")

fun visColours(v: VisSettings): List<Color> {
    if (v.palette == "custom") return listOf(v.hue1, v.hue2, v.hue3).map { Color.hsl(it.coerceIn(0f, 359.9f), 0.9f, 0.55f) }
    return when (v.palette) {
    "ocean" -> listOf(0xFF0077B6, 0xFF00B4D8, 0xFF90E0EF, 0xFF3A0CA3)
    "forest" -> listOf(0xFF2D6A4F, 0xFF52B788, 0xFFB7E4C7, 0xFFFFD60A)
    "neon" -> listOf(0xFFFF00FF, 0xFF00FFFF, 0xFF39FF14, 0xFFFFFF00)
    "fire" -> listOf(0xFFD00000, 0xFFFF5400, 0xFFFFBD00, 0xFFFF0054)
    "aurora" -> listOf(0xFF00F5D4, 0xFF9B5DE5, 0xFFF15BB5, 0xFF00BBF9)
    else -> listOf(0xFFFF6B35, 0xFFF72585, 0xFF7209B7, 0xFFFFD166)
    }.map { Color(it) }
}

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

/**
 * Full screen: only colours dancing and blending to the music (levels of the app's own sound output).
 * The screen stays on. Double tap anywhere: back. Slide along the long side: colours; along the short side: brightness.
 */
@Composable
fun VisualisationScreen(nav: Navigator) {
    val v = Alarms.state.collectAsState().value.vis
    val cols = visColours(v)
    val act = LocalContext.current.findActivity()
    var bright by remember { mutableFloatStateOf(act?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var showB by remember { androidx.compose.runtime.mutableStateOf(false) }
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
    // Music levels → a fast and a slow follower; their difference is the beat.
    var t by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val buf = FloatArray(2)
        var last = 0L; var slow = 0f; var fast = 0f
        while (true) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1e9f).coerceIn(0f, 0.1f)
                last = n
                try { NativeAudio.peaks(buf) } catch (_: Throwable) { buf[0] = 0f; buf[1] = 0f }
                var p = max(buf[0], buf[1]).coerceIn(0f, 1f)
                // No level from the sound output (other engine): a gentle pulse while music plays.
                if (!NativeAudio.active && app.vlcinterface.App.instance.player.state.value.isPlaying) p = 0.35f + 0.25f * sin(t * 6f).coerceAtLeast(0f)
                fast += (p - fast) * (dt * 18f).coerceAtMost(1f)
                slow += (p - slow) * (dt * 1.5f).coerceAtMost(1f)
                level = fast
                beat = (fast - slow).coerceAtLeast(0f)
                t += dt * (0.25f + 1.6f * fast + 2.5f * beat)
            }
        }
    }
    // Slides: along the screen's long side → next / previous colour combination; along the short side → brightness.
    var paletteNote by remember { androidx.compose.runtime.mutableStateOf("") }
    var noteAt by remember { mutableLongStateOf(0L) }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { nav.back() }) }
        .pointerInput(Unit) {
            var axis = 0          // 0 undecided, 1 long, 2 short
            var acc = 0f
            var sx = 0f; var sy = 0f
            detectDragGestures(onDragStart = { axis = 0; acc = 0f; sx = 0f; sy = 0f }) { ch, d ->
                ch.consume()
                val portrait = size.height >= size.width
                sx += d.x; sy += d.y
                if (axis == 0 && (kotlin.math.abs(sx) > 24f || kotlin.math.abs(sy) > 24f)) {
                    val vertical = kotlin.math.abs(sy) > kotlin.math.abs(sx)
                    axis = if (vertical == portrait) 1 else 2
                }
                if (axis == 1) {
                    acc += if (portrait) d.y else d.x
                    val stepPx = 140.dp.toPx()
                    if (kotlin.math.abs(acc) > stepPx) {
                        val dir = if (acc > 0) 1 else -1; acc = 0f
                        val keys = PALETTES.map { it.first }
                        val cur = Alarms.current.vis
                        val next = keys[(keys.indexOf(cur.palette).coerceAtLeast(0) + dir + keys.size) % keys.size]
                        Alarms.setVis(cur.copy(palette = next))
                        paletteNote = PALETTES.first { it.first == next }.second; noteAt = System.currentTimeMillis()
                    }
                } else if (axis == 2) setBright(bright + (if (portrait) d.x else -d.y) / 700f)
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val a = v.brightness.coerceIn(0.2f, 1f)
            when (v.effect) {
                "bars" -> visBars(t, level, beat, cols, a)
                "waves" -> visWaves(t, level, beat, cols, a)
                "rings" -> visRings(t, level, beat, cols, a)
                "particles" -> visParticles(t, level, beat, cols, a)
                "kaleido" -> visKaleido(t, level, beat, cols, a)
                else -> visBlobs(t, level, beat, cols, a)
            }
        }
        if (showB) Text("☀ ${(bright * 100).toInt()} %", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp,
            modifier = Modifier.align(Alignment.Center))
        if (paletteNote.isNotEmpty()) Text(paletteNote, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp,
            modifier = Modifier.align(Alignment.Center).padding(top = 60.dp))
        LaunchedEffect(shownAt) { kotlinx.coroutines.delay(1300); showB = false }
        LaunchedEffect(noteAt) { kotlinx.coroutines.delay(1300); paletteNote = "" }
    }
}

// ---------------------------------------------------------------- effects

private fun DrawScope.blob(c: Color, a: Float, center: Offset, r: Float) {
    val rr = r.coerceAtLeast(1f)
    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), c.copy(alpha = a * 0.35f), Color.Transparent), center, rr), rr, center, blendMode = BlendMode.Plus)
}

private fun DrawScope.visBlobs(t: Float, level: Float, beat: Float, cols: List<Color>, a: Float) {
    val w = size.width; val h = size.height; val m = size.minDimension
    for (i in 0 until 7) {
        val x = w * (0.5f + 0.36f * sin(t * (0.37f + i * 0.11f) + i * 1.7f))
        val y = h * (0.5f + 0.36f * cos(t * (0.29f + i * 0.07f) + i * 2.3f))
        blob(cols[i % cols.size], a, Offset(x, y), m * (0.22f + 0.3f * level + 0.35f * beat) * (0.8f + 0.25f * sin(t * 0.9f + i)))
    }
}

private fun DrawScope.visBars(t: Float, level: Float, beat: Float, cols: List<Color>, a: Float) {
    val n = 28
    val bw = size.width / n
    val mid = size.height / 2
    for (i in 0 until n) {
        val wob = 0.5f + 0.5f * sin(t * 3.1f + i * 0.73f) * cos(t * 1.7f + i * 0.31f)
        val hh = size.height * (0.03f + level * 0.38f * wob + beat * 0.22f * (0.5f + 0.5f * sin(i * 1.9f + t * 5f)))
        val c = cols[(i * cols.size / n).coerceAtMost(cols.size - 1)]
        drawRect(Brush.verticalGradient(listOf(c.copy(alpha = a), c.copy(alpha = a * 0.25f)), mid - hh, mid), Offset(i * bw + bw * 0.12f, mid - hh),
            androidx.compose.ui.geometry.Size(bw * 0.76f, hh), blendMode = BlendMode.Plus)
        drawRect(Brush.verticalGradient(listOf(c.copy(alpha = a * 0.25f), c.copy(alpha = a * 0.7f)), mid, mid + hh * 0.7f), Offset(i * bw + bw * 0.12f, mid),
            androidx.compose.ui.geometry.Size(bw * 0.76f, hh * 0.7f), blendMode = BlendMode.Plus)
    }
}

private fun DrawScope.visWaves(t: Float, level: Float, beat: Float, cols: List<Color>, a: Float) {
    val w = size.width; val h = size.height
    for (k in 0 until 5) {
        val path = androidx.compose.ui.graphics.Path()
        val amp = h * (0.04f + 0.18f * level + 0.15f * beat) * (1f - k * 0.12f)
        var x = 0f
        while (x <= w) {
            val y = h / 2 + (k - 2) * h * 0.06f + amp * sin(x / w * 6.28f * (1.2f + k * 0.35f) + t * (1.5f + k * 0.4f)) * cos(t * 0.6f + k)
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 8f
        }
        drawPath(path, cols[k % cols.size].copy(alpha = a), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f + 18f * beat + 6f * level,
            cap = androidx.compose.ui.graphics.StrokeCap.Round), blendMode = BlendMode.Plus)
    }
}

private fun DrawScope.visRings(t: Float, level: Float, beat: Float, cols: List<Color>, a: Float) {
    val c = Offset(size.width / 2, size.height / 2)
    val maxR = size.maxDimension * 0.65f
    for (i in 0 until 8) {
        val f = ((t * 0.35f + i / 8f) % 1f)
        drawCircle(cols[i % cols.size].copy(alpha = a * (1f - f)), maxR * f * (0.85f + 0.3f * level), c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f + 40f * beat + 14f * level), blendMode = BlendMode.Plus)
    }
    blob(cols[0], a, c, size.minDimension * (0.12f + 0.25f * beat + 0.1f * level))
}

private fun DrawScope.visParticles(t: Float, level: Float, beat: Float, cols: List<Color>, a: Float) {
    val c = Offset(size.width / 2, size.height / 2)
    val maxR = size.maxDimension * 0.6f
    for (i in 0 until 90) {
        val ang = i * 2.39996f + t * 0.15f
        val f = ((t * 0.22f * (0.6f + (i % 5) * 0.2f) + i * 0.137f) % 1f)
        val d = maxR * f * (0.7f + 0.5f * level)
        val p = Offset(c.x + cos(ang) * d, c.y + sin(ang) * d)
        val r = (2f + 10f * beat + 6f * level) * (0.5f + f)
        blob(cols[i % cols.size], a * (1f - f * 0.6f), p, r * 3f)
    }
}

private fun DrawScope.visKaleido(t: Float, level: Float, beat: Float, cols: List<Color>, a: Float) {
    val c = Offset(size.width / 2, size.height / 2)
    val m = size.minDimension
    for (k in 0 until 6) for (j in 0 until 3) {
        val base = k * 1.0472f
        val d = m * (0.12f + 0.12f * j + 0.12f * level) * (1f + 0.25f * sin(t * (0.7f + j * 0.3f) + j))
        val off = 0.35f * sin(t * (0.5f + j * 0.2f) + j * 2f)
        for (mirror in listOf(1f, -1f)) {
            val ang = base + mirror * off
            blob(cols[(j + k) % cols.size], a * 0.8f, Offset(c.x + cos(ang) * d, c.y + sin(ang) * d), m * (0.07f + 0.08f * level + 0.12f * beat))
        }
    }
}

/** Settings → Visualisation: colour combination and brightness. */
@Composable
fun VisualisationSettings() {
    val v = Alarms.state.collectAsState().value.vis
    fun set(n: VisSettings) = Alarms.setVis(n)
    CardBox {
        Text("Effect", color = C.text, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("blobs" to "Colour clouds", "bars" to "Bars", "waves" to "Waves", "rings" to "Rings", "particles" to "Particles", "kaleido" to "Kaleidoscope")
                .forEach { (k, l) -> Pill(l, selected = v.effect == k) { set(v.copy(effect = k)) } }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp))
            Slider(v.brightness, { set(v.copy(brightness = it)) }, Modifier.weight(1f), valueRange = 0.2f..1f,
                colors = SliderDefaults.colors(thumbColor = accent(), activeTrackColor = accent()))
            Text("${(v.brightness * 100).toInt()} %", color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
        }
        Text("On the visualisation: double tap to leave · slide along the long side for the colours, along the short side for the brightness", color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        Box(Modifier.height(2.dp))
    }
}
