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
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

private val PALETTES: List<Pair<String, String>> = listOf("pattern" to "Pattern colours", "sunset" to "Sunset", "ocean" to "Ocean",
    "forest" to "Forest", "neon" to "Neon", "fire" to "Fire", "aurora" to "Aurora", "custom" to "Custom")

/** The visualisations offered (23:06: only the chosen ones — centred spectrum and the four analysers; projectM follows). */
private val EFFECT_NAMES = listOf("mirror" to "Spectrum (centred)",
    "am_led" to "Analyser: LED bars", "am_lumi" to "Analyser: light bars", "am_area" to "Analyser: wave area", "am_radial" to "Analyser: radial")

/** Mathematical patterns: drawn as a colour field (cosine palettes, or the chosen colour combination). */
private val FIELD_FX = setOf("ink", "reaction", "phase", "chladni", "mandel", "epicycles")
private fun cosOffsets(effect: String): FloatArray = when (effect) {
    "reaction" -> floatArrayOf(0.8f, 0.9f, 0.3f)
    "chladni" -> floatArrayOf(0f, 0.1f, 0.2f)
    "epicycles" -> floatArrayOf(0.5f, 0.2f, 0.25f)
    else -> floatArrayOf(0f, 0.33f, 0.67f)
}
private fun cosColour(f: Float, o: FloatArray) = Color(0.5f + 0.5f * cos(6.2832f * (f + o[0])), 0.5f + 0.5f * cos(6.2832f * (f + o[1])), 0.5f + 0.5f * cos(6.2832f * (f + o[2])))

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
    in FIELD_FX -> cosOffsets(effect).let { o -> listOf(0f, 0.25f, 0.5f, 0.75f).map { cosColour(it, o) } to listOf(0f, 0.33f, 0.66f).map { cosColour(it, o) } }
    else -> c(0xFFFF6B35, 0xFFF72585, 0xFF7209B7, 0xFFFFD166) to c(0xFF050505, 0xFF050505, 0xFF050505)
}

/** Colour [f] along a combination, wrapping round (smooth blend between its colours). */
private fun cyc(cols: List<Color>, f: Float): Color {
    val n = cols.size
    val x = ((f % 1f) + 1f) % 1f * n
    val i = x.toInt().coerceIn(0, n - 1)
    return lerpC(cols[i], cols[(i + 1) % n], x - i)
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
    /** Colour time: runs with the colour speed (a little faster on the beat). */
    var tc = 0f
    // Beat following: fast envelope, short average, and a pulse fired on each detected beat (per band).
    var envLo = 0f; var avgLo = 0.001f; var kickLo = 0f; var lastLo = -1f
    var envHi = 0f; var avgHi = 0.001f; var kickHi = 0f; var lastHi = -1f
    /** Beat engine (low band): onset strength history, recent onsets, tempo and the next expected beat. */
    val odf = FloatArray(96); var odfI = 0; var prevLo = 0f
    val onsets = ArrayDeque<Float>()
    var period = 0f; var lastBeat = -10f; var confidence = 0f
    /** Tempo found (beats per minute; 0 = not yet). */
    var bpm = 0f
    /** Spectrum analyser: band levels (0…1), their slow references and the falling peak caps. */
    val spec = FloatArray(NativeAudio.SPEC_N); val specRaw = FloatArray(NativeAudio.SPEC_N)
    val specRef = FloatArray(NativeAudio.SPEC_N) { 0.01f }; val specPeak = FloatArray(NativeAudio.SPEC_N)
}

/** Spectrum pictures (bars from the 20-band analyser). */
val SPEC_FX = setOf("bars", "mirror", "radial", "am_led", "am_lumi", "am_area", "am_radial")
/** Patterns whose bars follow the "Bar thickness" setting. */
val BAR_FX = setOf("mirror", "am_led", "am_lumi", "am_radial")

/**
 * Beat engine: onsets of the low band are found against their own recent statistics (mean + 1.6 × spread), the
 * tempo from the spacing of the recent onsets (folded into 70–180 BPM), and beats keep coming on time between
 * clear onsets (phase-locked: a clear onset near the expected beat moves the beat onto it). Returns true on a beat.
 */
private fun beatStep(m: VisMotion, x: Float, clock: Float): Boolean {
    // Onset strength: rise of the (normalised) level since the last frame.
    val d = max(0f, x - m.prevLo); m.prevLo = x
    m.odf[m.odfI % m.odf.size] = d; m.odfI++
    val n = minOf(m.odfI, m.odf.size)
    var mean = 0f; for (i in 0 until n) mean += m.odf[i]; mean /= n
    var vr = 0f; for (i in 0 until n) { val e = m.odf[i] - mean; vr += e * e }; val sd = kotlin.math.sqrt(vr / n)
    val onset = n > 20 && d > mean + 1.6f * sd && d > 0.04f && clock - (m.onsets.lastOrNull() ?: -10f) > 0.22f
    if (onset) {
        m.onsets.addLast(clock); while (m.onsets.size > 16) m.onsets.removeFirst()
        // Tempo: histogram of the spacings between recent onsets, folded into 0.33 … 0.86 s.
        if (m.onsets.size >= 4) {
            val hist = FloatArray(54)          // 10 ms bins from 0.33 s
            val list = m.onsets.toList()
            for (i in list.indices) for (j in i + 1 until list.size) {
                var ioi = list[j] - list[i]
                if (ioi > 2.6f) continue
                while (ioi > 0.86f) ioi /= 2f
                while (ioi < 0.33f) ioi *= 2f
                val bin = ((ioi - 0.33f) / 0.01f).toInt().coerceIn(0, 53)
                for (k in -2..2) { val b = bin + k; if (b in 0..53) hist[b] += exp(-(k * k) / 2f) }
            }
            var best = 0; for (i in hist.indices) if (hist[i] > hist[best]) best = i
            val p = 0.33f + best * 0.01f + 0.005f
            m.period = if (m.period == 0f) p else m.period + (p - m.period) * 0.3f
            m.confidence = (hist[best] / (list.size * 1.5f)).coerceIn(0f, 1f)
            m.bpm = 60f / m.period
        }
    }
    // Beat: a clear onset near where the beat is expected (or any clear onset before the tempo is known) …
    val expected = m.lastBeat + m.period
    if (onset && (m.period == 0f || kotlin.math.abs(clock - expected) < m.period * 0.3f || clock - m.lastBeat > m.period * 1.5f)) {
        m.lastBeat = clock; return true
    }
    // … or, with a steady tempo, the expected time itself (keeps time through quiet bars).
    if (m.period > 0f && m.confidence > 0.35f && clock >= expected && clock - expected < 0.05f) { m.lastBeat = expected; return true }
    if (m.period > 0f && clock - m.lastBeat > m.period * 4f) { m.period = 0f; m.bpm = 0f; m.confidence = 0f }   // lost: start over
    return false
}

/**
 * Frame loop: band levels from the app's sound output (or a gentle simulated beat in previews / other engines).
 * Levels are normalised to the music (slow automatic gain) and smoothed with limited speed: no flashing.
 */
@Composable
private fun rememberVisMotion(v: VisSettings, live: Boolean, shown: () -> Boolean = { true }): VisMotion {
    val m = remember { VisMotion() }
    val vs by rememberUpdatedState(v)
    val shownNow by rememberUpdatedState(shown)
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(live) {
        val buf = FloatArray(2)
        var last = 0L
        var clock = 0f
        // Works only while the screen is on and the app is in the foreground (resumed); stopped otherwise.
        // Coming back on screen: carry on from where it was (same picture, tempo, levels) — nothing is rebuilt; only the
        // sound measured while it was away is dropped, so the first frame doesn't jump to a level from seconds ago.
        val skip = FloatArray(NativeAudio.SPEC_N)
        fun resync() { last = 0L; if (live && NativeAudio.active) { NativeAudio.bands(buf); NativeAudio.spectrum(skip) } }
        life.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
        resync()
        var wasShown = true
        while (true) {
            // Not on screen (cover place hidden or scrolled away, zero size): nothing is computed, checked 4× a second.
            if (!shownNow()) { kotlinx.coroutines.delay(250); wasShown = false; continue }
            if (!wasShown) { resync(); wasShown = true }
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1e9f).coerceIn(0f, 0.1f)
                last = n; clock += dt
                val playing = try { app.vlcinterface.App.instance.player.state.value.isPlaying } catch (_: Throwable) { false }
                var a: Float
                var b: Float
                if (live && NativeAudio.active) {
                    NativeAudio.bandRange(vs.fLo, vs.fHi)
                    NativeAudio.bands(buf)
                    // Automatic gain: follows the loudest recent level, letting go within a few seconds.
                    m.refLo = max(m.refLo * (1f - dt * 0.5f), max(buf[0], 0.004f))
                    m.refHi = max(m.refHi * (1f - dt * 0.5f), max(buf[1], 0.004f))
                    // Rhythm: a beat is a jump of the band's level above its own short average (≈ 0.4 s). Each beat
                    // fires a pulse that drives the movement, so the picture moves with the music's rhythm, not just
                    // with how loud it is. Low band: kick / bass (at most ~5 beats a second); high band: hats, snares.
                    fun beat(x: Float, env: Float, avg: Float, last: Float, minGap: Float): Triple<Float, Float, Boolean> {
                        val e = max(x, env * exp(-dt * 14f))
                        val av = avg + (x - avg) * (dt * 2.5f).coerceAtMost(1f)
                        val hit = x > av * 1.35f + 0.0005f && x > env * 0.9f && clock - last > minGap
                        return Triple(e, av, hit)
                    }
                    // Low band: the beat engine (onsets + tempo, keeps time between clear onsets).
                    if (beatStep(m, (buf[0] / m.refLo).coerceIn(0f, 1f), clock)) { m.kickLo = 1f; m.lastLo = clock; m.pb += 0.35f }
                    beat(buf[1], m.envHi, m.avgHi, m.lastHi, 0.1f).let { (e, av, hit) ->
                        m.envHi = e; m.avgHi = av
                        if (hit) { m.kickHi = 1f; m.lastHi = clock; m.ps += 0.25f }
                    }
                    m.kickLo *= exp(-dt / 0.16f); m.kickHi *= exp(-dt / 0.09f)
                    val lvLo = (buf[0] / m.refLo).coerceIn(0f, 1f); val lvHi = (buf[1] / m.refHi).coerceIn(0f, 1f)
                    a = (0.35f * lvLo + 0.85f * m.kickLo).coerceIn(0f, 1f)
                    b = (0.35f * lvHi + 0.85f * m.kickHi).coerceIn(0f, 1f)
                } else if (!live || playing) {
                    // Simulated: kick on the beat (120 BPM) for the low part, off-beat hats for the high part.
                    a = exp(-(clock % 0.5f) / 0.13f) + 0.25f * exp(-((clock + 0.25f) % 2f) / 0.3f)
                    b = 0.7f * exp(-((clock + 0.125f) % 0.25f) / 0.05f) * (0.6f + 0.4f * sin(clock * 1.3f))
                    a = (a * 0.7f).coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
                } else { a = 0f; b = 0f }
                // Spectrum analyser: levels on a 60 dB scale against each band's own slow reference; bars rise at once
                // and fall smoothly, peak caps hold and drop slowly.
                if (vs.effect in SPEC_FX) {
                    if (live && NativeAudio.active) NativeAudio.spectrum(m.specRaw)
                    else for (i in m.specRaw.indices) m.specRaw[i] = if (!live || playing)
                        (0.05f + 0.6f * m.lo * exp(-i / 6f) + 0.3f * m.hi * exp(-(m.specRaw.size - 1 - i) / 9f) + 0.08f * (0.5f + 0.5f * sin(clock * 3f + i))) else 0f
                    for (i in m.spec.indices) {
                        val raw = m.specRaw[i]
                        m.specRef[i] = max(m.specRef[i] * (1f - dt * 0.2f), max(raw, 1e-4f))
                        // Sensitivity: higher values lift the bars so loud parts reach the edges of the frame.
                        val db = if (raw <= 1e-6f) 0f else (((20f * kotlin.math.log10(raw / m.specRef[i]) + 42f) / 42f) * vs.specGain.coerceIn(0.5f, 3f)).coerceIn(0f, 1f)
                        m.spec[i] = if (db > m.spec[i]) db else max(db, m.spec[i] - dt * 1.6f)
                        m.specPeak[i] = if (m.spec[i] >= m.specPeak[i]) m.spec[i] else max(m.spec[i], m.specPeak[i] - dt * 0.5f)
                    }
                }
                val k = vs.move.coerceIn(0.2f, 2f)
                a = (a * k).coerceIn(0f, 1f); b = (b * k).coerceIn(0f, 1f)
                // Rises at once on a beat, falls back within a fraction of a second (follows the rhythm, still no flashing).
                fun follow(cur: Float, target: Float) = cur + (target - cur) * ((if (target > cur) 30f else 6f) * dt).coerceAtMost(1f)
                m.lo = follow(m.lo, a); m.hi = follow(m.hi, b)
                // Movement speed comes mostly from the music: a calm drift between beats, quick moves on them.
                m.pb += dt * (0.15f + 4.0f * m.lo * k)
                m.ps += dt * (0.25f + 5.0f * m.hi * k)
                m.tc += dt * vs.colourSpeed.coerceIn(0.2f, 3f) * (0.6f + 0.8f * m.hi)
                m.t = clock
            }
        }
        }
    }
    return m
}

/** The visualisation with the current settings, live, in any area (the player's picture place when there's no cover). */
@Composable
fun VisArea(modifier: Modifier) {
    val v = Alarms.state.collectAsState().value.vis
    VisCanvas(v, modifier, live = true)
}

/** The visualisation itself (full screen or a preview). */
@Composable
private fun VisCanvas(v: VisSettings, modifier: Modifier, live: Boolean) {
    // On screen with a real size? (the player's cover place can be hidden or scrolled out of view)
    val shown = remember { booleanArrayOf(true) }
    val m = rememberVisMotion(v, live) { shown[0] }
    val spots = visColours(v)
    val bg = visBackground(v)
    val plasma = remember { android.graphics.Bitmap.createBitmap(18, 32, android.graphics.Bitmap.Config.ARGB_8888) }
    val px = remember { IntArray(18 * 32) }
    val field = remember(v.effect) { arrayOfNulls<VisField>(1) }
    // Field patterns: as fine a grid as the phone draws smoothly. Starts at 1.6× the old grid and adjusts itself
    // (finer while frames are cheap, coarser if they get slow), up to 3× (≈ 216 × 384 cells).
    // scale, summed ms, frames, last draw (ms), locked (1 = settled: never grows again after having to shrink)
    val res = remember(v.effect) { floatArrayOf(1.6f, 0f, 0f, 0f, 0f) }
    // Gone from the screen for good (cover found, clock switched on, another page): its picture memory is freed at once.
    // (Its frame loop and the spectrum measuring stop with it.)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { try { field[0]?.bmp?.recycle(); field[0] = null; plasma.recycle() } catch (_: Exception) {} } }
    // The analyser is measured only while a live spectrum picture is shown (saves battery otherwise).
    // Measured only while the screen is on and the app is in the foreground.
    if (live && v.effect in SPEC_FX) {
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(owner) {
            var on = false
            fun set(want: Boolean) { if (want != on) { on = want; NativeAudio.spectrumOn(want) } }
            val obs = androidx.lifecycle.LifecycleEventObserver { _, _ -> set(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs); set(false) }
        }
    }
    Canvas(modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow(); shown[0] = b.width > 1f && b.height > 1f
    }) {
        val t = m.t
        val a = v.brightness.coerceIn(0.2f, 1f)
        if (v.effect in SPEC_FX) {
            when (v.effect) {
                "am_led", "am_lumi", "am_area", "am_radial" -> drawAnalyser(v.effect, m, a, visColours(v), bg, v.specBar.dp.toPx())
                else -> drawSpectrum(m, a, visColours(v), bg, v.specBar.dp.toPx())
            }
            return@Canvas
        }
        if (v.effect in FIELD_FX) {
            val land = size.width > size.height
            val shortS = (72 * res[0]).toInt(); val longS = (128 * res[0]).toInt()
            val fw = if (land) longS else shortS; val fh = if (land) shortS else longS
            val f = field[0]?.takeIf { it.w == fw && it.h == fh } ?: VisField(fw, fh).also { field[0] = it }
            val t0 = System.nanoTime()
            drawField(v.effect, f, m, a, paletteColours(v), cosOffsets(v.effect), v)
            val nowMs = (t0 / 1_000_000L % 10_000_000L).toFloat()
            // Back on screen after a pause: the first frames are slow while the phone wakes up — they don't count,
            // so the picture isn't rebuilt (restarted) because of them.
            if (nowMs - res[3] > 500f || nowMs < res[3]) { res[1] = 0f; res[2] = -30f }
            res[3] = nowMs
            if (res[2] >= 0f) res[1] += (System.nanoTime() - t0) / 1e6f
            res[2] += 1f
            if (res[2] >= 90f) {       // every ~1.5 s of steady drawing
                val avg = res[1] / res[2]
                // Changing the grid restarts the pattern, so it settles: once it had to go coarser it never grows again.
                if (avg > 9f && res[0] > 0.8f) { res[0] = (res[0] - 0.2f).coerceAtLeast(0.8f); res[4] = 1f }
                else if (avg < 4f && res[0] < 3f && res[4] == 0f) res[0] = (res[0] + 0.2f).coerceAtMost(3f)
                res[1] = 0f; res[2] = 0f
            }
            return@Canvas
        }
        drawBackground(v.effect, bg, t, m.lo, a, plasma, px, m.tc)
        drawSpots(spots, m, a, v.mix.coerceIn(0f, 1f), v.effect, v.sharp)
    }
}

/**
 * Centred spectrum: bars growing up and down from the middle line, with peak marks. The bar thickness is set by the
 * user ([barPx]); the number of bars is what fits across the width at that thickness (thicker → fewer bars), each
 * bar taking its share of the 32 measured bands. Colours run across the bars from the chosen colour combination.
 */
private fun DrawScope.drawSpectrum(m: VisMotion, a: Float, cols: List<Color>, bg: List<Color>, barPx: Float) {
    drawRect(lerpC(Color.Black, bg.firstOrNull() ?: Color.Black, 0.25f + 0.25f * m.kickLo))
    val gap = (barPx * 0.35f).coerceAtLeast(2f)
    val bars = ((size.width + gap) / (barPx + gap)).toInt().coerceIn(3, m.spec.size)
    val bw = (size.width - gap * (bars - 1)) / bars        // fills the width exactly
    val left = 0f
    fun colAt(i: Int): Color {
        if (cols.size < 2) return (cols.firstOrNull() ?: Color.White).copy(alpha = a)
        val f = i / (bars - 1f).coerceAtLeast(1f) * (cols.size - 1)
        val j = f.toInt().coerceIn(0, cols.size - 2)
        return lerpC(cols[j], cols[j + 1], f - j).copy(alpha = a)
    }
    // Each bar: the loudest of its bands (log-spaced bands shared out evenly across the bars).
    fun level(arr: FloatArray, i: Int): Float {
        val n = arr.size
        val from = i * n / bars; val to = ((i + 1) * n / bars).coerceAtLeast(from + 1)
        var v = 0f; for (k in from until to.coerceAtMost(n)) v = max(v, arr[k]); return v
    }
    val mid = size.height / 2; val full = mid * 0.92f
    for (i in 0 until bars) {
        val x = left + i * (bw + gap)
        val h = full * level(m.spec, i).coerceAtLeast(0.01f)
        val ph = full * level(m.specPeak, i)
        val col = colAt(i)
        drawRect(col, Offset(x, mid - h), androidx.compose.ui.geometry.Size(bw, h * 2))
        drawRect(col.copy(alpha = col.alpha * 0.9f), Offset(x, mid - ph - 3f), androidx.compose.ui.geometry.Size(bw, 3f))
        drawRect(col.copy(alpha = col.alpha * 0.9f), Offset(x, mid + ph), androidx.compose.ui.geometry.Size(bw, 3f))
    }
}

/**
 * Analyser pictures in the style of hi-fi spectrum analysers (the app's own drawing, from the 32 measured bands):
 * - "am_led": rows of LED segments growing left and right from a vertical middle line, peak LEDs both sides;
 * - "am_lumi": rows glowing from the vertical middle line, their light and reach following each band;
 * - "am_area": a smooth curve over the bands, filled below, with a bright edge;
 * - "am_radial": bars all round a circle that swells on the beat, set on the vertical axis (mirrored left / right).
 * Bars fill the width at the chosen thickness (thicker → fewer); colours from the chosen combination.
 */
private fun DrawScope.drawAnalyser(effect: String, m: VisMotion, a: Float, cols: List<Color>, bg: List<Color>, barPx: Float) {
    drawRect(lerpC(Color.Black, bg.firstOrNull() ?: Color.Black, 0.2f + 0.2f * m.kickLo))
    fun colAt(f: Float): Color {
        if (cols.size < 2) return (cols.firstOrNull() ?: Color.White).copy(alpha = a)
        val p = f.coerceIn(0f, 1f) * (cols.size - 1)
        val j = p.toInt().coerceIn(0, cols.size - 2)
        return lerpC(cols[j], cols[j + 1], p - j).copy(alpha = a)
    }
    fun level(arr: FloatArray, i: Int, bars: Int): Float {
        val n = arr.size
        val from = i * n / bars; val to = ((i + 1) * n / bars).coerceAtLeast(from + 1)
        var v = 0f; for (k in from until to.coerceAtMost(n)) v = max(v, arr[k]); return v
    }
    val gap = (barPx * 0.3f).coerceAtLeast(2f)
    when (effect) {
        "am_led" -> {
            // Turned 90°: the origin is a vertical line in the middle; each band is a row of LED segments growing left
            // and right from it (bass at the top, treble at the bottom), colour changing away from the middle,
            // peak LEDs on both sides. Bar thickness = row height (thicker → fewer rows).
            val bars = ((size.height + gap) / (barPx + gap)).toInt().coerceIn(3, m.spec.size)
            val bh = (size.height - gap * (bars - 1)) / bars
            val mid = size.width / 2; val half = mid * 0.94f
            val seg = (bh * 0.45f).coerceIn(4f, 18f); val sgap = seg * 0.35f
            val nSeg = (half / (seg + sgap)).toInt().coerceAtLeast(3)
            for (i in 0 until bars) {
                val y = i * (bh + gap)
                val lit = (level(m.spec, i, bars) * nSeg).toInt()
                val pk = (level(m.specPeak, i, bars) * nSeg).toInt().coerceAtMost(nSeg - 1)
                for (sIdx in 0 until nSeg) {
                    val on = sIdx < lit || sIdx == pk
                    val c = colAt(sIdx / (nSeg - 1f).coerceAtLeast(1f))
                    val col = if (on) c else c.copy(alpha = 0.08f * a)
                    val off = sgap / 2 + sIdx * (seg + sgap)
                    drawRect(col, Offset(mid - off - seg, y), androidx.compose.ui.geometry.Size(seg, bh))
                    drawRect(col, Offset(mid + off, y), androidx.compose.ui.geometry.Size(seg, bh))
                }
            }
        }
        "am_lumi" -> {
            // Turned 90°: rows glowing from the vertical middle line, brightest at the middle, fading left and right;
            // the glow and its reach follow each band (bass at the top). Bar thickness = row height.
            val bars = ((size.height + gap) / (barPx + gap)).toInt().coerceIn(3, m.spec.size)
            val bh = (size.height - gap * (bars - 1)) / bars
            val mid = size.width / 2
            for (i in 0 until bars) {
                val v = level(m.spec, i, bars)
                val c = colAt(i / (bars - 1f).coerceAtLeast(1f))
                val reach = mid * (0.35f + 0.65f * v)
                val core = c.copy(alpha = (0.15f + 0.85f * v) * a); val edge = c.copy(alpha = 0.03f * a)
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(edge, core, edge), startX = mid - reach, endX = mid + reach),
                    Offset(mid - reach, i * (bh + gap)), androidx.compose.ui.geometry.Size(reach * 2, bh))
            }
        }
        "am_area" -> {
            val n = m.spec.size
            val path = androidx.compose.ui.graphics.Path(); val edge = androidx.compose.ui.graphics.Path()
            val base = size.height * 0.92f; val hMax = size.height * 0.85f
            fun px(i: Int) = size.width * i / (n - 1f)
            fun py(i: Int) = base - hMax * m.spec[i]
            path.moveTo(0f, base); path.lineTo(px(0), py(0)); edge.moveTo(px(0), py(0))
            for (i in 1 until n) {       // smooth: through the middles of the segments
                val mx = (px(i - 1) + px(i)) / 2; val my = (py(i - 1) + py(i)) / 2
                path.quadraticBezierTo(px(i - 1), py(i - 1), mx, my); edge.quadraticBezierTo(px(i - 1), py(i - 1), mx, my)
            }
            path.lineTo(px(n - 1), py(n - 1)); edge.lineTo(px(n - 1), py(n - 1))
            path.lineTo(size.width, base); path.close()
            val stops = (0 until maxOf(cols.size, 2)).map { colAt(it / (maxOf(cols.size, 2) - 1f)) }
            drawPath(path, androidx.compose.ui.graphics.Brush.horizontalGradient(stops.map { it.copy(alpha = it.alpha * 0.45f) }))
            drawPath(edge, androidx.compose.ui.graphics.Brush.horizontalGradient(stops), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() * (1f + m.kickLo)))
        }
        else -> {   // am_radial
            val c = Offset(size.width / 2, size.height / 2)
            val r0 = minOf(size.width, size.height) * (0.2f + 0.04f * m.kickLo)
            val len = minOf(size.width, size.height) * 0.28f
            val circ = 2f * Math.PI.toFloat() * r0
            val bars = ((circ / 2f + gap) / (barPx + gap)).toInt().coerceIn(3, m.spec.size)   // half circle, mirrored
            val bw = (circ / (bars * 2)) - gap
            // Centre: the bass colour (the same as the bass bars at the top), glowing with the bass and the beat.
            val bassCol = colAt(0f)
            val bassLv = level(m.spec, 0, bars)
            drawCircle(bassCol.copy(alpha = (0.45f + 0.4f * bassLv + 0.15f * m.kickLo).coerceAtMost(1f) * a), r0 * 0.9f, c)
            // Centred and set on the vertical axis: bass at the top, treble at the bottom, the left half the mirror
            // of the right half (no turning).
            for (i in 0 until bars * 2) {
                val b = if (i < bars) i else 2 * bars - 1 - i
                val step = Math.PI.toFloat() / bars
                val ang = -Math.PI.toFloat() / 2 + (if (i < bars) (b + 0.5f) * step else -(b + 0.5f) * step)
                val r2 = r0 + len * level(m.spec, b, bars).coerceAtLeast(0.02f)
                drawLine(colAt(b / (bars - 1f).coerceAtLeast(1f)), Offset(c.x + cos(ang) * r0, c.y + sin(ang) * r0),
                    Offset(c.x + cos(ang) * r2, c.y + sin(ang) * r2), bw.coerceAtLeast(2f), androidx.compose.ui.graphics.StrokeCap.Butt)
            }
        }
    }
}

/** [sharp]: 0 = very diffused (soft glow) … 1 = very sharp (crisp edge). */
private fun DrawScope.spot(c: Color, alpha: Float, center: Offset, r: Float, sharp: Float) {
    val rr = r.coerceAtLeast(1f)
    val s = sharp.coerceIn(0f, 1f)
    val edge = 0.35f + 0.62f * s
    drawCircle(Brush.radialGradient(0f to c.copy(alpha = alpha), edge to c.copy(alpha = alpha * (0.4f + 0.55f * s)), 1f to Color.Transparent,
        center = center, radius = rr), rr, center, blendMode = BlendMode.Plus)
}

/** Big spots follow the low part, small spots the high part; both travel across the whole screen. */
private fun DrawScope.drawSpots(cols: List<Color>, m: VisMotion, a: Float, mix: Float, effect: String, sharp: Float) {
    val w = size.width
    val h = size.height
    val mn = size.minDimension
    val bigA = (if (effect == "clouds") 0.75f else 0.5f) * (0.55f + 0.6f * mix) * a
    for (i in 0 until 4) {
        val p = m.pb * (0.45f + i * 0.13f) + i * 1.9f
        val q = m.pb * (0.37f + i * 0.11f) + i * 2.7f
        val r = mn * (0.5f + 0.35f * m.lo) * (0.8f + 0.5f * mix) * (0.85f + 0.2f * sin(p * 1.3f))
        spot(cyc(cols, i * 0.25f + m.tc * 0.15f), bigA, Offset(w * (0.5f + 0.48f * sin(p)), h * (0.5f + 0.46f * cos(q))), r, sharp)
    }
    for (i in 0 until 7) {
        val p = m.ps * (0.7f + i * 0.17f) + i * 1.3f
        val q = m.ps * (0.55f + i * 0.13f) + i * 2.1f
        val r = mn * (0.11f + 0.11f * m.hi) * (0.8f + 0.5f * mix)
        spot(cyc(cols, (i + 1) * 0.25f + m.tc * 0.2f), (0.35f + 0.35f * m.hi) * (0.6f + 0.5f * mix) * a, Offset(w * (0.5f + 0.47f * sin(p)), h * (0.5f + 0.47f * cos(q))), r, sharp)
    }
}

/** Each pattern's background: always fills the whole screen (ambient light). */
private fun DrawScope.drawBackground(effect: String, bg0: List<Color>, t: Float, lo: Float, a: Float, plasma: android.graphics.Bitmap, px: IntArray, tc: Float) {
    val w = size.width
    val h = size.height
    // The background's colours slide slowly along their own set (colour speed).
    val bg = bg0.indices.map { i -> cyc(bg0, i / bg0.size.toFloat() + tc * 0.05f) }
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
                val hue = (((v * 45f + tc * 90f) % 360f) + 360f) % 360f
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
    // Settings → Visualisation → Full-screen brightness (or the phone's own); a swipe changes it and is kept.
    var bright by remember { mutableFloatStateOf(v.fsBright.takeIf { it > 0f } ?: act?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var showB by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var noteAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        val w = act?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (v.fsBright > 0f) w?.let { it.attributes = it.attributes.apply { screenBrightness = v.fsBright.coerceIn(0.01f, 1f) } }
        // Bars hidden like a full-screen app; Android's usual swipe from the edge brings them back for a moment.
        try { w?.insetsController?.let { it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; it.hide(WindowInsets.Type.systemBars()) } } catch (_: Exception) {}
        onDispose {
            try { w?.insetsController?.show(WindowInsets.Type.systemBars()) } catch (_: Exception) {}
            w?.attributes = w?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    fun setBright(b: Float) {
        bright = b.coerceIn(0.01f, 1f); shownAt = System.currentTimeMillis(); showB = true
        act?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = bright } }
    }
    // A swipe's brightness is kept as the setting (saved once the finger has rested a moment).
    LaunchedEffect(shownAt) { if (shownAt > 0) { kotlinx.coroutines.delay(700); Alarms.setVis(Alarms.current.vis.copy(fsBright = bright)) } }
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
    // Songs / Radio / Both / Off: the visualisation always in the album picture's place.
    run {
        val store = app.vlcinterface.App.instance.store
        val s = store.state.collectAsState().value.settings
        var visHelp by remember { mutableStateOf(false) }
        if (visHelp) HelperDialog("Visualisation instead of album pictures", SettingHelp.text("Visualisation instead of album pictures", "")) { visHelp = false }
        CardBox {
            Column(Modifier.padding(horizontal = 16.dp, vertical = (10 * sg()).dp)) {
                Text("Visualisation instead of album pictures", color = C.text, fontSize = 15.sp, modifier = Modifier.explain("Visualisation instead of album pictures"))
                Text("Always in the picture's place on the players", color = C.muted, fontSize = 13.sp)
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp),
                    verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                    listOf("off" to "Off", "songs" to "Songs", "radio" to "Radio", "both" to "Both").forEach { (m, l) ->
                        Pill(l, selected = s.visInsteadCover == m, filled = s.visInsteadCover == m) {
                            store.update { it.copy(settings = it.settings.copy(visInsteadCover = m)) }
                            if (Alarms.current.global.helpers) visHelp = true
                        }
                    }
                }
            }
        }
    }
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
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = (8 * sg()).dp), horizontalArrangement = Arrangement.spacedBy((6 * sg()).dp), verticalArrangement = Arrangement.spacedBy((6 * sg()).dp)) {
                EFFECT_NAMES.forEach { (k, l) -> Pill(l, selected = v.effect == k) { set(v.copy(effect = k)); preview = k; previewAt = System.currentTimeMillis() } }
            }
        }
        Divider()
        Text("Colours", color = C.text, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = (8 * sg()).dp), horizontalArrangement = Arrangement.spacedBy((8 * sg()).dp), verticalArrangement = Arrangement.spacedBy((8 * sg()).dp)) {
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
        VisSlider("Colour speed", v.colourSpeed, 0.2f..3f, "×%.1f".format(v.colourSpeed)) { set(v.copy(colourSpeed = it)) }
        VisSlider("Definition", v.sharp, 0f..1f, if (v.sharp < 0.15f) "soft" else if (v.sharp > 0.85f) "sharp" else "${(v.sharp * 100).toInt()} %") { set(v.copy(sharp = it)) }
        VisSlider("Brightness", v.brightness, 0.2f..1f, "${(v.brightness * 100).toInt()} %") { set(v.copy(brightness = it)) }
        // Full-screen brightness: the phone's own, or a level of its own (also set by swiping on the full screen).
        run {
            var fbHelp by remember { mutableStateOf(false) }
            if (fbHelp) HelperDialog("Full-screen brightness", SettingHelp.text("Full-screen brightness", "")) { fbHelp = false }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Full-screen brightness", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f).explain("Full-screen brightness"))
                Pill("Phone's own", selected = v.fsBright <= 0f, filled = v.fsBright <= 0f) {
                    set(v.copy(fsBright = if (v.fsBright <= 0f) 0.6f else -1f)); if (Alarms.current.global.helpers) fbHelp = true }
            }
            if (v.fsBright > 0f) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(110.dp))
                ThinSlider(v.fsBright, { set(v.copy(fsBright = (Math.round(it * 100f) / 100f).coerceIn(0.01f, 1f))) }, Modifier.weight(1f), valueRange = 0.01f..1f,
                    onHoldStill = { if (Alarms.current.global.helpers) fbHelp = true })
                Text("${(v.fsBright * 100).toInt()} %", color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
            }
        }
        // Centred spectrum: bar thickness (thicker → fewer bars). Helper with a live preview on a still hold.
        if (v.effect in SPEC_FX) {
            var gainHelp by remember { mutableStateOf(false) }
            if (gainHelp) HelperDialog("Sensitivity", SettingHelp.text("Sensitivity", ""), extra = {
                VisCanvas(v, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)), live = true)
            }) { gainHelp = false }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sensitivity", color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp).explain("Sensitivity"))
                ThinSlider(v.specGain, { set(v.copy(specGain = (Math.round(it * 10f) / 10f))) }, Modifier.weight(1f), valueRange = 0.5f..3f,
                    onHoldStill = { if (Alarms.current.global.helpers) gainHelp = true })
                Text("×%.1f".format(v.specGain), color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
            }
        }
        if (v.effect in BAR_FX) {
            var barHelp by remember { mutableStateOf(false) }
            if (barHelp) HelperDialog("Bar thickness", "How thick each bar of the spectrum and analyser pictures is. The bars always fill the whole width, " +
                "so thicker bars means fewer of them (each covering a wider slice of the sound): thin bars show more detail, thick bars a bolder beat. " +
                "From 3 to 40 dp; default 10 dp.", extra = {
                VisCanvas(v, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(8.dp)), live = true)
            }) { barHelp = false }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bar thickness", color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp).explain("Bar thickness"))
                ThinSlider(v.specBar, { set(v.copy(specBar = Math.round(it).toFloat())) }, Modifier.weight(1f), valueRange = 3f..40f,
                    onHoldStill = { if (Alarms.current.global.helpers) barHelp = true })
                Text("${v.specBar.toInt()} dp", color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
            }
        }
        Divider()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Frequencies followed", color = C.text, fontSize = 15.sp)
                Text(hzText(v.fLo) + " – " + hzText(v.fHi) + if (v.fHi / v.fLo >= 4f) " · big spots: lower part, small spots: upper part" else " · all spots together",
                    color = C.muted, fontSize = 13.sp)
            }
            Pill("Reset") { set(v.copy(fLo = 30f, fHi = 150f)) }
        }
        ThinRangeSlider(toPos(v.fLo)..toPos(v.fHi), { r ->
            val lo = toHz(r.start)
            val hi = toHz(r.endInclusive).coerceAtLeast(lo * 1.2f).coerceAtMost(F_MAX)
            set(v.copy(fLo = lo, fHi = hi))
        }, Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("20 Hz", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("450 Hz", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("10 kHz", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Text("On the visualisation: double tap to leave · slide along the long side for the colours, along the short side for the brightness. " +
            "Movements follow only the chosen frequencies and stay gentle (no flashing).", color = C.muted, fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp))
        Box(Modifier.height(2.dp))
    }
}

@Composable
private fun VisSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 14.sp, modifier = Modifier.width(110.dp).explain(label))
        ThinSlider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text(shown, color = accent(), fontSize = 13.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
    }
}


// ---------------------------------------------------------------- mathematical patterns (colour fields)

/** State of a field pattern: a small grid drawn stretched and smoothed over the screen. */
private class VisField(val w: Int, val h: Int) {
    val px = IntArray(w * h)
    val bmp: android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    var r = FloatArray(w * h); var g = FloatArray(w * h); var b = FloatArray(w * h)
    var u = FloatArray(w * h) { 1f }; var v = FloatArray(w * h)
    var inkReady = false; var rdReady = false
    var lastT = -1f
    fun seedRd() {
        val rnd = java.util.Random()
        repeat(14) {
            val cx = 4 + rnd.nextInt(w - 8); val cy = 4 + rnd.nextInt(h - 8)
            for (j in -3..3) for (i in -3..3) { val k = (cy + j) * w + cx + i; v[k] = 0.5f; u[k] = 0.5f }
        }
        rdReady = true
    }
}

private fun argb(c: Color, a: Float, l: Float = 1f): Int {
    val k = a * l
    return (0xFF shl 24) or ((c.red * k * 255).toInt().coerceIn(0, 255) shl 16) or ((c.green * k * 255).toInt().coerceIn(0, 255) shl 8) or (c.blue * k * 255).toInt().coerceIn(0, 255)
}

private fun DrawScope.drawField(effect: String, f: VisField, m: VisMotion, a: Float, cols: List<Color>?, o: FloatArray, v: VisSettings) {
    val w = f.w; val h = f.h
    val t = m.t; val tc = m.tc; val lo = m.lo; val hi = m.hi
    val dt = if (f.lastT < 0f) 0.016f else (t - f.lastT).coerceIn(0f, 0.1f)
    f.lastT = t
    fun col(x: Float): Color = if (cols != null) cyc(cols, x) else cosColour(x, o)
    val px = f.px
    when (effect) {
        "ink" -> {
            if (!f.inkReady) { for (k in 0 until w * h) { val c = col(k / (w * h).toFloat()); f.r[k] = c.red; f.g[k] = c.green; f.b[k] = c.blue }; f.inkReady = true }
            val sp = (10f + 28f * lo) * dt * v.move.coerceIn(0.2f, 2f)
            val r2 = FloatArray(w * h); val g2 = FloatArray(w * h); val b2 = FloatArray(w * h)
            fun sm(arr: FloatArray, x0: Float, y0: Float): Float {
                val x = x0.coerceIn(0f, w - 1.001f); val y = y0.coerceIn(0f, h - 1.001f)
                val i = x.toInt(); val j = y.toInt(); val fx = x - i; val fy = y - j; val k = j * w + i
                return arr[k] * (1 - fx) * (1 - fy) + arr[k + 1] * fx * (1 - fy) + arr[k + w] * (1 - fx) * fy + arr[k + w + 1] * fx * fy
            }
            for (j in 0 until h) for (i in 0 until w) {
                // Stream function ψ = sin(x+0.3t)·cos(y−0.21t) + 0.6·sin(1.7y+0.12t)·cos(1.3x); velocity = (∂ψ/∂y, −∂ψ/∂x).
                val x = i / w.toFloat() * 4f; val y = j / h.toFloat() * 7f
                val uu = -sin(x + t * 0.3f) * sin(y - t * 0.21f) + 1.02f * cos(1.7f * y + t * 0.12f) * cos(1.3f * x)
                val vv = -(cos(x + t * 0.3f) * cos(y - t * 0.21f) - 0.78f * sin(1.7f * y + t * 0.12f) * sin(1.3f * x))
                val sx = i - uu * sp; val sy = j - vv * sp * 1.2f; val k = j * w + i
                r2[k] = sm(f.r, sx, sy); g2[k] = sm(f.g, sx, sy); b2[k] = sm(f.b, sx, sy)
            }
            f.r = r2; f.g = g2; f.b = b2
            for (e in 0 until 3) {
                val ex = w * (0.5f + 0.4f * sin(t * 0.17f * (1 + e * 0.3f) + e * 2)); val ey = h * (0.5f + 0.42f * cos(t * 0.13f * (1 + e * 0.2f) + e))
                val c = col(tc * 0.12f + e / 3f); val rad = 4f + 5f * hi
                for (j in -9..9) for (i in -9..9) {
                    val d = hypot(i.toFloat(), j.toFloat()); if (d > rad) continue
                    val X = (ex + i).toInt(); val Y = (ey + j).toInt(); if (X < 0 || Y < 0 || X >= w || Y >= h) continue
                    val k = Y * w + X; val q = 0.25f * (1 - d / rad)
                    f.r[k] += (c.red - f.r[k]) * q; f.g[k] += (c.green - f.g[k]) * q; f.b[k] += (c.blue - f.b[k]) * q
                }
            }
            for (k in 0 until w * h) px[k] = (0xFF shl 24) or ((f.r[k] * a * 255).toInt().coerceIn(0, 255) shl 16) or
                ((f.g[k] * a * 255).toInt().coerceIn(0, 255) shl 8) or (f.b[k] * a * 255).toInt().coerceIn(0, 255)
        }
        "reaction" -> {
            if (!f.rdReady) f.seedRd()
            val F = 0.026f + 0.03f * (0.5f + 0.5f * sin(t * 0.011f)); val K = 0.054f + 0.009f * sin(t * 0.0137f) + 0.002f * lo
            repeat(6) {
                val u2 = FloatArray(w * h); val v2 = FloatArray(w * h); val u = f.u; val vv = f.v
                for (j in 0 until h) {
                    val jm = ((j - 1 + h) % h) * w; val jp = ((j + 1) % h) * w; val jj = j * w
                    for (i in 0 until w) {
                        val im = (i - 1 + w) % w; val ip = (i + 1) % w; val id = jj + i
                        val lu = u[jj + im] + u[jj + ip] + u[jm + i] + u[jp + i] - 4 * u[id]
                        val lv = vv[jj + im] + vv[jj + ip] + vv[jm + i] + vv[jp + i] - 4 * vv[id]
                        val uvv = u[id] * vv[id] * vv[id]
                        u2[id] = u[id] + 0.2f * lu - uvv + F * (1 - u[id]); v2[id] = vv[id] + 0.1f * lv + uvv - (F + K) * vv[id]
                    }
                }
                f.u = u2; f.v = v2
            }
            if (f.v.sum() < 5f) f.seedRd()
            for (k in 0 until w * h) px[k] = argb(col(f.v[k] * 2.2f + f.u[k] * 0.35f + tc * 0.1f), a)
        }
        "phase" -> {
            val roots = arrayOf(floatArrayOf(0.6f * cos(t * 0.2f), 0.9f * sin(t * 0.17f)), floatArrayOf(-0.5f + 0.3f * sin(t * 0.11f), 0.4f * cos(t * 0.23f)),
                floatArrayOf(0.2f * cos(t * 0.31f), -0.8f + 0.2f * sin(t * 0.13f)))
            val poles = arrayOf(floatArrayOf(-0.4f * cos(t * 0.15f), 0.6f * sin(t * 0.19f)), floatArrayOf(0.5f * sin(t * 0.09f), -0.3f + 0.4f * cos(t * 0.14f)))
            val sx = if (w > h) 3.9f else 2.2f; val sy = if (w > h) 2.2f else 3.9f
            for (j in 0 until h) for (i in 0 until w) {
                val x = (i / w.toFloat() - 0.5f) * sx; val y = (j / h.toFloat() - 0.5f) * sy
                var fr = 1f; var fi = 0f
                for (q in roots) { val ar = x - q[0]; val ai = y - q[1]; val nr = fr * ar - fi * ai; fi = fr * ai + fi * ar; fr = nr }
                var gr = 1f; var gi = 0f
                for (q in poles) { val ar = x - q[0]; val ai = y - q[1]; val nr = gr * ar - gi * ai; gi = gr * ai + gi * ar; gr = nr }
                val d = gr * gr + gi * gi + 1e-9f; val qr = (fr * gr + fi * gi) / d; val qi = (fi * gr - fr * gi) / d
                val arg = kotlin.math.atan2(qi, qr) / 6.2832f
                val mag = (ln(hypot(qr, qi) + 1e-9f) / ln(2f)); val band = mag - kotlin.math.floor(mag)
                px[j * w + i] = argb(col(arg + tc * 0.1f), a, 0.55f + 0.45f * band.pow(0.6f + 0.8f * lo))
            }
        }
        "chladni" -> {
            val modes = arrayOf(intArrayOf(1, 2), intArrayOf(2, 5), intArrayOf(3, 4), intArrayOf(1, 6), intArrayOf(4, 7))
            val wt = FloatArray(5) { k -> sin(t * (0.07f + k * 0.031f) + k * 1.7f) * (if (k == 0) 1f + 0.4f * lo else 1f) }
            val pi = 3.14159f
            for (j in 0 until h) for (i in 0 until w) {
                val uu = i / w.toFloat(); val vv = j / h.toFloat()
                var z = 0f
                for (k in 0 until 5) { val n = modes[k][0]; val mm = modes[k][1]; z += wt[k] * (cos(n * pi * uu) * cos(mm * pi * vv) - cos(mm * pi * uu) * cos(n * pi * vv)) }
                px[j * w + i] = argb(col(z * 0.35f + tc * 0.1f), a, 0.45f + 0.55f * exp(-abs(z) * 1.5f))
            }
        }
        "mandel" -> {
            val ph = (t * 0.02f) % 2f
            val zz = exp(-(if (ph < 1f) ph else 2f - ph) * 6.5f) * 1.6
            val it = 50 + ((1 - zz / 1.6) * 60).toInt()
            val cr0 = -0.743643887; val ci0 = 0.131825904
            val ax = if (w > h) 2.0 else 1.15; val ay = if (w > h) 1.15 else 2.0
            for (j in 0 until h) for (i in 0 until w) {
                val x0 = cr0 + (i / w.toDouble() - 0.5) * zz * ax; val y0 = ci0 + (j / h.toDouble() - 0.5) * zz * ay
                var x = 0.0; var y = 0.0; var n = 0
                while (n < it && x * x + y * y < 64) { val tx = x * x - y * y + x0; y = 2 * x * y + y0; x = tx; n++ }
                val smv = if (n < it) n + 1 - kotlin.math.log2(ln(kotlin.math.sqrt(x * x + y * y))) else it.toDouble()
                px[j * w + i] = argb(col((smv * 0.03).toFloat() + tc * 0.1f), a, if (n < it) 1f else 0.3f)
            }
        }
        "epicycles" -> {
            // Background: soft diffused waves, only in the chosen colours.
            for (j in 0 until h) for (i in 0 until w) {
                val x = i / w.toFloat(); val y = j / h.toFloat()
                val wv = sin(x * 3f + t * 0.4f) + sin(y * 2.2f - t * 0.3f) + sin((x + y) * 2.5f + t * 0.2f) + 0.6f * sin(hypot(x - 0.5f, y - 0.5f) * 6f - t * (0.5f + lo))
                px[j * w + i] = argb(col(wv * 0.12f + tc * 0.08f), a, 0.5f)
            }
        }
    }
    f.bmp.setPixels(px, 0, w, 0, 0, w, h)
    drawImage(f.bmp.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.High)
    if (effect == "epicycles") drawEpicycles(m, a, ::col)
}

/** Fourier epicycles: circles on circles; the tip leaves thick glowing trails that fade. */
private fun DrawScope.drawEpicycles(m: VisMotion, a: Float, col: (Float) -> Color) {
    val terms = arrayOf(floatArrayOf(1f, 0.30f), floatArrayOf(-2f, 0.13f), floatArrayOf(3f, 0.09f), floatArrayOf(-5f, 0.055f), floatArrayOf(7f, 0.035f))
    val mn = size.minDimension
    val cx = size.width / 2; val cy = size.height / 2
    val ky = size.height / size.width.coerceAtLeast(1f)
    fun tip(s: Float): Offset {
        var x = cx; var y = cy
        for (q in terms) { val r = q[1] * mn * (1f + 0.25f * m.lo); x += r * cos(q[0] * s); y += r * sin(q[0] * s) * ky.coerceIn(0.6f, 1.8f) }
        return Offset(x, y)
    }
    val now = m.pb * 0.9f
    val n = 160
    var prev = tip(now - 5f)
    for (k in 1..n) {
        val s = now - 5f + 5f * k / n
        val p = tip(s)
        val age = k / n.toFloat()
        val c = col(m.tc * 0.1f + age * 0.5f)
        drawLine(c.copy(alpha = 0.18f * age * a), prev, p, mn * (0.06f + 0.03f * m.hi), androidx.compose.ui.graphics.StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(c.copy(alpha = 0.7f * age * a), prev, p, mn * (0.018f + 0.012f * m.hi), androidx.compose.ui.graphics.StrokeCap.Round, blendMode = BlendMode.Plus)
        prev = p
    }
    // The circles themselves, faint.
    var x = cx; var y = cy
    for (q in terms) {
        val r = q[1] * mn * (1f + 0.25f * m.lo)
        drawCircle(Color.White.copy(alpha = 0.10f * a), r, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        x += r * cos(q[0] * now); y += r * sin(q[0] * now) * ky.coerceIn(0.6f, 1.8f)
    }
}
