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

fun visColours(v: VisSettings): List<Color> = when (v.palette) {
    "ocean" -> listOf(0xFF0077B6, 0xFF00B4D8, 0xFF90E0EF, 0xFF3A0CA3)
    "forest" -> listOf(0xFF2D6A4F, 0xFF52B788, 0xFFB7E4C7, 0xFFFFD60A)
    "neon" -> listOf(0xFFFF00FF, 0xFF00FFFF, 0xFF39FF14, 0xFFFFFF00)
    "fire" -> listOf(0xFFD00000, 0xFFFF5400, 0xFFFFBD00, 0xFFFF0054)
    "aurora" -> listOf(0xFF00F5D4, 0xFF9B5DE5, 0xFFF15BB5, 0xFF00BBF9)
    "custom" -> return listOf(v.hue1, v.hue2, v.hue3).map { Color.hsl(it.coerceIn(0f, 359.9f), 0.9f, 0.55f) }
    else -> listOf(0xFFFF6B35, 0xFFF72585, 0xFF7209B7, 0xFFFFD166)
}.map { Color(it) }

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
 * The screen stays on. Double tap anywhere: back. Slide in any direction: screen brightness (up / right brighter).
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
        try { w?.insetsController?.hide(WindowInsets.Type.systemBars()) } catch (_: Exception) {}
        onDispose {
            try { w?.insetsController?.show(WindowInsets.Type.systemBars()) } catch (_: Exception) {}
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
    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { nav.back() }) }
        .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); setBright(bright + (d.x - d.y) / 900f) } }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val m = size.minDimension
            val a = v.brightness.coerceIn(0.2f, 1f)
            for (i in 0 until 7) {
                val c = cols[i % cols.size]
                val fx = 0.37f + i * 0.11f; val fy = 0.29f + i * 0.07f
                val x = w * (0.5f + 0.36f * sin(t * fx + i * 1.7f))
                val y = h * (0.5f + 0.36f * cos(t * fy + i * 2.3f))
                val r = m * (0.22f + 0.3f * level + 0.35f * beat) * (0.8f + 0.25f * sin(t * 0.9f + i))
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), c.copy(alpha = a * 0.35f), Color.Transparent), Offset(x, y), r.coerceAtLeast(1f)),
                    r.coerceAtLeast(1f), Offset(x, y), blendMode = BlendMode.Plus)
            }
        }
        if (showB) Text("☀ ${(bright * 100).toInt()} %", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp,
            modifier = Modifier.align(Alignment.Center))
        LaunchedEffect(shownAt) { kotlinx.coroutines.delay(1300); showB = false }
    }
}

/** Settings → Visualisation: colour combination and brightness. */
@Composable
fun VisualisationSettings() {
    val v = Alarms.state.collectAsState().value.vis
    fun set(n: VisSettings) = Alarms.setVis(n)
    CardBox {
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
        Text("On the visualisation: double tap to leave, slide in any direction for the screen brightness", color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        Box(Modifier.height(2.dp))
    }
}
