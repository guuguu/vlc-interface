package app.vlcinterface.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.alarm.ClockAspect
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.TextUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Main colour: saturation 0 = white, 1 = the pure hue. */
fun clockMain(h: Float, s: Float): Color = Color.hsl(h.coerceIn(0f, 359.9f), s.coerceIn(0f, 1f), 0.5f + 0.42f * (1f - s.coerceIn(0f, 1f)))
/** Disk (inside the circle only): dark, tinted by hue / saturation. */
fun clockDisk(h: Float, s: Float): Color = Color.hsl(h.coerceIn(0f, 359.9f), s.coerceIn(0f, 1f), 0.11f)

private class Spec(val disk: Float, val ring: Float, val ringW: Float, val inner: Float, val hourTick: FloatArray?, val hourTickW: Float,
                   val minTick: FloatArray?, val minTickW: Float, val numR: Float, val numSize: Float, val numBold: Boolean,
                   val hourW: Float, val minW: Float, val secW: Float, val fadedTicks: Boolean)

private fun spec(d: Int): Spec = when (d) {
    2 -> Spec(54f, 0f, 0f, 0f, null, 0f, null, 0f, 44f, 11f, false, 4f, 2.5f, 0.8f, false)
    4 -> Spec(53f, 53f, 1f, 46f, floatArrayOf(46f, 50.5f), 1.8f, floatArrayOf(48f, 50.5f), 0.6f, 38f, 7.5f, false, 3.2f, 2f, 0.8f, false)
    5 -> Spec(53f, 0f, 0f, 0f, floatArrayOf(42f, 52f), 4.5f, null, 0f, 33f, 8f, true, 5f, 4f, 1.2f, false)
    8 -> Spec(54f, 0f, 0f, 0f, floatArrayOf(49f, 51.5f), 0.8f, null, 0f, 43f, 8.5f, false, 1.4f, 1f, 0.6f, true)
    else -> Spec(52f, 52f, 1.2f, 0f, floatArrayOf(46f, 50f), 2.2f, null, 0f, 38f, 8f, false, 2.4f, 1.8f, 0.8f, false)
}

private val DAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * The clock that replaces the album picture: analog designs 1, 2, 4, 5, 8 (hour numbers and a second hand on all,
 * the date without the month, placed where the hands aren't) or digital. [now] = fixed time (previews), 0 = live.
 */
@Composable
fun ClockFace(a: ClockAspect, modifier: Modifier, now: Long = 0L) {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (now == 0L) LaunchedEffect(Unit) { while (true) { t = System.currentTimeMillis(); delay(1000 - t % 1000 + 5) } }
    val tm = rememberTextMeasurer()
    val aspect by rememberUpdatedState(a)
    val g = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global
    val w by app.vlcinterface.alarm.Weather.now.collectAsState()
    // Weather for the chosen city: checked every 10 min while the clock is shown (refreshed at most every 30 min).
    LaunchedEffect(g.cityLat, g.cityLon, g.showWeather, g.weatherNoon) { while (true) { app.vlcinterface.alarm.Weather.refresh(); delay(10 * 60_000L) } }
    val zone = app.vlcinterface.alarm.Alarms.zone()
    // The line under the city (06:44 / 06:47): the time zone, the temperature now > in 6 hours, or both alternating every 5 s.
    val tzText = tzLabel(zone, if (now != 0L) now else t)
    val sameCity = w.key == "${g.cityLat},${g.cityLon},${if (g.weatherNoon) "noon" else "now"}"
    val tempText = if (sameCity && !w.nowC.isNaN() && !w.in6C.isNaN()) "${Math.round(w.nowC)}°>${Math.round(w.in6C)}°" else ""
    val line2 = when {
        tempText.isEmpty() || g.wxLine == "tz" -> tzText
        g.wxLine == "temp" -> tempText
        else -> if (((if (now != 0L) now else t) / 5000L) % 2L == 1L) tempText else tzText
    }
    val widest = if (g.wxLine == "both" && tempText.isNotEmpty()) (if (tempText.length > tzText.length) tempText else tzText) else line2
    val wx = if (g.cityName.isEmpty() || !g.showWeather) null
        else WxInfo(if (sameCity) w.stage else -1, g.cityShort, line2, widest)
    Canvas(modifier) { drawClock(aspect, if (now != 0L) now else t, tm, zone, wx) }
}

private fun DrawScope.drawClock(a: ClockAspect, ms: Long, tm: TextMeasurer, zone: TimeZone, wx: WxInfo?) {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = ms }
    val h = cal.get(Calendar.HOUR_OF_DAY); val m = cal.get(Calendar.MINUTE); val s = cal.get(Calendar.SECOND)
    val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val dayNo = cal.get(Calendar.DAY_OF_MONTH)
    val dim = if (a.nightDim > 0 && (h >= 22 || h < 7)) 1f - a.nightDim / 100f else 1f
    // Thickness of lines and fonts, set per design.
    val thk = a.thickFor().coerceIn(0.4f, 3f)
    val weightK = FontWeight((400 * thk).toInt().coerceIn(100, 900))
    fun Color.d() = copy(alpha = alpha * dim)
    val main = clockMain(a.mainHue, a.mainSat).d()
    val minuteC = lerp(clockMain(a.mainHue, a.mainSat), Color.White, 0.5f).d()
    val secC = Color.hsl(a.secHue.coerceIn(0f, 359.9f), 0.9f, 0.62f).d()
    val dateC = clockMain(a.dateHue, a.dateSat).d()
    val c = Offset(size.width / 2, size.height / 2)
    val u = minOf(size.width, size.height) / 2f / 60f * a.size.coerceIn(0.5f, 1f)
    fun px(v: Float) = v * u
    fun spOf(pxv: Float) = (pxv / density / fontScale).sp
    fun pt(angDeg: Float, r: Float) = Offset(c.x + px(r) * sin(angDeg * PI.toFloat() / 180f), c.y - px(r) * cos(angDeg * PI.toFloat() / 180f))
    // Readable floor for the date and weather: about 14 sp (never more than a share of a small clock's face).
    val floorPx = minOf(14f * density * fontScale, minOf(size.width, size.height) * 0.1f)
    fun text(str: String, center: Offset, sizeU: Float, color: Color, bold: Boolean = false, floor: Float = 0f) {
        val l = tm.measure(str, TextStyle(color = color, fontSize = spOf(maxOf(px(sizeU), floor)), fontWeight = if (bold) FontWeight((700 * thk).toInt().coerceIn(100, 900)) else weightK))
        drawText(l, topLeft = Offset(center.x - l.size.width / 2f, center.y - l.size.height / 2f))
    }

    if (!a.analog) {
        drawCircle(clockDisk(a.diskHue, a.diskSat).d(), px(56f), c)
        val hh = if (a.h24) h else (h % 12).let { if (it == 0) 12 else it }
        val main0 = "%02d:%02d".format(hh, m)
        val big = 22f * a.digitalSize.coerceIn(0.5f, 1.4f)
        val l = tm.measure(main0, TextStyle(color = main, fontSize = spOf(px(big)), fontWeight = FontWeight(((if (a.digitalBold) 700 else 300) * thk).toInt().coerceIn(100, 900))))
        val extra = listOfNotNull(if (a.seconds) ":%02d".format(s) else null, if (!a.h24) (if (h < 12) " AM" else " PM") else null).joinToString("")
        val l2 = if (extra.isEmpty()) null else tm.measure(extra, TextStyle(color = main, fontSize = spOf(px(big * 0.42f))))
        var w = l.size.width + (l2?.size?.width ?: 0)
        // Never wider than the disk.
        val maxW = px(100f)
        val k = if (w > maxW) maxW / w else 1f
        val la = if (k < 1f) tm.measure(main0, TextStyle(color = main, fontSize = spOf(px(big * k)), fontWeight = FontWeight(((if (a.digitalBold) 700 else 300) * thk).toInt().coerceIn(100, 900)))) else l
        val lb = if (k < 1f && extra.isNotEmpty()) tm.measure(extra, TextStyle(color = main, fontSize = spOf(px(big * 0.42f * k)))) else l2
        w = la.size.width + (lb?.size?.width ?: 0)
        val y0 = c.y - la.size.height / 2f - if (a.digitalDate) px(4f) else 0f
        drawText(la, topLeft = Offset(c.x - w / 2f, y0))
        lb?.let { drawText(it, topLeft = Offset(c.x - w / 2f + la.size.width, y0 + la.size.height * 0.62f - it.size.height / 2f)) }
        if (a.digitalDate) text(DAY_LONG[dow] + " " + dayNo, Offset(c.x, y0 + la.size.height + px(5f * a.dateSize)), 6.5f * a.dateSize.coerceIn(0.5f, 2f), dateC)
        if (wx != null) {
            val wh = wxSize(wx, 1f, tm, u, ::spOf).second
            drawWeather(wx, Offset(c.x, (y0 - px(wh / 2 + 4f)).coerceAtLeast(c.y - px(48f))), 1f, main, tm, u, ::spOf)
        }
        return
    }

    val sp = spec(a.design)
    drawCircle(clockDisk(a.diskHue, a.diskSat).d(), px(sp.disk), c)
    if (sp.ring > 0) drawCircle(main, px(sp.ring), c, style = Stroke(px(sp.ringW * a.ringW * thk)))
    if (sp.inner > 0) drawCircle(main.copy(alpha = main.alpha * 0.4f), px(sp.inner), c, style = Stroke(px(0.6f * a.ringW * thk)))
    sp.minTick?.let { r -> for (i in 0 until 60) if (i % 5 != 0) drawLine(main.copy(alpha = main.alpha * 0.7f), pt(i * 6f, r[0]), pt(i * 6f, r[1]), px(sp.minTickW * a.tickW * thk)) }
    sp.hourTick?.let { r -> for (i in 0 until 12) drawLine(if (sp.fadedTicks) main.copy(alpha = main.alpha * 0.4f) else main,
        pt(i * 30f, r[0]), pt(i * 30f, r[1]), px(sp.hourTickW * a.tickW * thk)) }
    for (i in 0 until 12) text(if (i == 0) "12" else "$i", pt(i * 30f, sp.numR), sp.numSize, main, sp.numBold)

    val hourAng = ((h % 12) + m / 60f) * 30f
    val minAng = m * 6f + s / 10f
    val secAng = s * 6f

    // Date (and weather): in free parts of the face, never crossed by the hour / minute hands, never over the
    // numbers, never over each other.
    val design4 = a.design == 4
    val ds = a.dateSize.coerceIn(0.5f, 2f)
    val dateStr = when (a.design) { 1 -> DAY_LONG[dow] + " " + dayNo; 2 -> DAY_LONG[dow].take(3).uppercase(Locale.getDefault()) + " " + dayNo
        else -> DAY_LONG[dow].take(3) + " " + dayNo }
    val limit = sp.numR - sp.numSize * 0.75f          // units: inside the hour numbers
    fun dateBox(k: Float): Pair<Float, Float> = if (design4) { val fs = 6.5f * ds * k; (fs * 2.4f) to (fs * 2.9f) }
        else { val l = tm.measure(dateStr, TextStyle(fontSize = spOf(px(6.5f * ds * k)))); (l.size.width / u) to (l.size.height / u) }
    val placedDate = placeBox(::dateBox, limit, hourAng, minAng, emptyList())
    val bc = Offset(c.x + px(placedDate.cx), c.y + px(placedDate.cy))
    if (design4) {
        val fs = 6.5f * ds * placedDate.k
        val ww = fs * 2.4f; val wh = fs * 1.35f; val gap = fs * 0.2f
        listOf("$dayNo" to (bc.y - wh / 2 * u - gap / 2 * u), DAY_LONG[dow].take(3).uppercase(Locale.getDefault()) to (bc.y + wh / 2 * u + gap / 2 * u)).forEachIndexed { i, (str, cy) ->
            val tl = Offset(bc.x - px(ww) / 2, cy - px(wh) / 2)
            drawRoundRect(Color(0xFF111111).d(), tl, androidx.compose.ui.geometry.Size(px(ww), px(wh)), androidx.compose.ui.geometry.CornerRadius(px(1.5f)))
            drawRoundRect(main.copy(alpha = main.alpha * 0.7f), tl, androidx.compose.ui.geometry.Size(px(ww), px(wh)), androidx.compose.ui.geometry.CornerRadius(px(1.5f)), style = Stroke(px(0.6f)))
            text(str, Offset(bc.x, cy), if (i == 0) fs else fs * 0.86f, dateC, floor = floorPx)
        }
    } else {
        // At the readable floor a long weekday is shortened rather than made smaller.
        val atFloor = px(6.5f * ds * placedDate.k) < floorPx
        text(if (atFloor && a.design == 1) DAY_LONG[dow].take(3) + " " + dayNo else dateStr, bc, 6.5f * ds * placedDate.k, dateC, floor = floorPx)
    }
    if (wx != null) {
        fun wxBox(k: Float): Pair<Float, Float> = wxSize(wx, k, tm, u, ::spOf, floorPx)
        val pw = placeBox(::wxBox, limit, hourAng, minAng, listOf(placedDate.rect))
        drawWeather(wx, Offset(c.x + px(pw.cx), c.y + px(pw.cy)), pw.k, main, tm, u, ::spOf, floorPx)
    }
    // Hands.
    val hw = (a.handW * thk).coerceIn(0.3f, 6f)
    drawLine(main, c, pt(hourAng, 24f), px(sp.hourW * hw), StrokeCap.Round)
    drawLine(minuteC, c, pt(minAng, 36f), px(sp.minW * hw), StrokeCap.Round)
    if (a.seconds) drawLine(secC, pt(secAng + 180f, 8f), pt(secAng, 40f), px(sp.secW * hw), StrokeCap.Round)
    drawCircle(if (a.seconds) secC else main, px(2f * hw.coerceAtMost(1.5f)), c)
}

private fun angDist(a: Float, b: Float): Float { val d = abs(((a - b) % 360f + 360f) % 360f); return minOf(d, 360f - d) }

// ---------------------------------------------------------------- hue / saturation bars

private val HUES = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsl(it.coerceAtMost(359.9f), 1f, 0.5f) }

/** A bar with a gradient and a round thumb: tap or drag to set [value] (0…1). */
@Composable
fun GradientBar(value: Float, brush: Brush, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier.height(28.dp)) {
        val w = constraints.maxWidth.toFloat()
        Box(Modifier.fillMaxWidth().height(12.dp).align(Alignment.Center).clip(RoundedCornerShape(6.dp)).background(brush)
            .pointerInput(w) { detectTapGestures { cb((it.x / w).coerceIn(0f, 1f)) } }
            .pointerInput(w) { detectHorizontalDragGestures { ch, _ -> cb((ch.position.x / w).coerceIn(0f, 1f)) } })
        val x = (value.coerceIn(0f, 1f) * (maxWidth - 20.dp).value).dp
        Box(Modifier.padding(start = x).size(20.dp).align(Alignment.CenterStart).clip(CircleShape).background(Color.White).border(1.dp, Color(0xFF888888), CircleShape))
    }
}

/** Hue bar + saturation bar, each with a small preview swatch. */
@Composable
fun HueSatBars(title: String, hue: Float, sat: Float, swatch: (Float, Float) -> Color, round: Boolean = false, onChange: (Float, Float) -> Unit) {
    Text(title, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hue", color = C.text, fontSize = 13.sp, modifier = Modifier.width(78.dp))
        GradientBar(hue / 360f, Brush.horizontalGradient(HUES), Modifier.weight(1f)) { onChange(it * 360f, sat) }
        Box(Modifier.padding(start = 10.dp).size(26.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp)).background(Color.hsl(hue.coerceAtMost(359.9f), 1f, 0.5f)))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Saturation", color = C.text, fontSize = 13.sp, modifier = Modifier.width(78.dp))
        GradientBar(sat, Brush.horizontalGradient(listOf(swatch(hue, 0f), swatch(hue, 1f))), Modifier.weight(1f)) { onChange(hue, it) }
        Box(Modifier.padding(start = 10.dp).size(26.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp)).background(swatch(hue, sat)).border(1.dp, C.line, if (round) CircleShape else RoundedCornerShape(6.dp)))
    }
}


// ---------------------------------------------------------------- placement

private class Placed(val cx: Float, val cy: Float, val k: Float, val rect: Rect)

/**
 * A box (size in clock units for a text scale k) put in the free part of the face: 12 directions tried; it must not
 * be crossed by the hour / minute hands nor touch [avoid]; it shrinks until it fits inside [limit].
 */
private fun placeBox(box: (Float) -> Pair<Float, Float>, limit: Float, hourAng: Float, minAng: Float, avoid: List<Rect>): Placed {
    data class P(val ang: Float, val p: Placed, val clear: Boolean)
    fun place(ang: Float): P {
        val dx = sin(ang * PI.toFloat() / 180f); val dy = -cos(ang * PI.toFloat() / 180f)
        var k = 1f; var cx = 0f; var cy = 0f; var bw = 0f; var bh = 0f
        for (iter in 0 until 10) {
            val (w, hh) = box(k); bw = w; bh = hh
            val d = 7f + abs(dx) * w / 2 + abs(dy) * hh / 2
            cx = dx * d; cy = dy * d
            val far = max(max(hypot(cx - w / 2, cy - hh / 2), hypot(cx + w / 2, cy - hh / 2)), max(hypot(cx - w / 2, cy + hh / 2), hypot(cx + w / 2, cy + hh / 2)))
            if (far <= limit) break
            k *= (limit / far).coerceIn(0.6f, 0.97f)
        }
        val pad = 2f
        val r = Rect(cx - bw / 2 - pad, cy - bh / 2 - pad, cx + bw / 2 + pad, cy + bh / 2 + pad)
        fun crosses(ang2: Float, len: Float): Boolean = (1..30).any { i ->
            val f = len * i / 30f
            r.contains(Offset(f * sin(ang2 * PI.toFloat() / 180f), -f * cos(ang2 * PI.toFloat() / 180f))) }
        val free = !crosses(hourAng, 24f) && !crosses(minAng, 36f) && avoid.none { it.overlaps(r) }
        return P(ang, Placed(cx, cy, k, r), free)
    }
    val cands = listOf(180f, 0f, 90f, 270f, 150f, 210f, 30f, 330f, 120f, 240f, 60f, 300f).map { place(it) }
    return (cands.filter { it.clear }.maxByOrNull { it.p.k }
        ?: cands.filter { c -> avoid.none { it.overlaps(c.p.rect) } }.maxByOrNull { minOf(angDist(it.ang, hourAng), angDist(it.ang, minAng)) }
        ?: cands.maxBy { minOf(angDist(it.ang, hourAng), angDist(it.ang, minAng)) }).p
}

// ---------------------------------------------------------------- weather

/** stage: -1 unknown, 0 sunny, 1 cloudy, 2 rainy, 3 storm. */
class WxInfo(val stage: Int, val city: String, val tz: String, val widest: String = tz)

fun tzLabel(z: TimeZone, ms: Long): String {
    val off = z.getOffset(ms) / 60_000
    if (off == 0) return "UTC"
    val sign = if (off > 0) "+" else "−"
    val a = kotlin.math.abs(off)
    return "UTC" + sign + (a / 60) + if (a % 60 != 0) ":%02d".format(a % 60) else ""
}

/** Width / height in clock units: icon on the left, city over the time zone on the right. */
private fun wxSize(w: WxInfo, k: Float, tm: TextMeasurer, u: Float, spOf: (Float) -> TextUnit, floor: Float = 0f): Pair<Float, Float> {
    val icon = if (w.stage >= 0) maxOf(11f * k, floor * 1.3f / u) else 0f
    val l1 = tm.measure(w.city, TextStyle(fontSize = spOf(maxOf(6f * k * u, floor)), fontWeight = FontWeight.Bold))
    // Measured for the longer of the two texts the line shows in turn, so the block doesn't move when it switches.
    val l2 = tm.measure(w.widest, TextStyle(fontSize = spOf(maxOf(4.5f * k * u, floor * 0.86f))))
    val tw = max(l1.size.width, l2.size.width) / u
    val th = (l1.size.height + l2.size.height) / u
    return (icon + (if (icon > 0) 2f * k else 0f) + tw) to max(icon, th)
}

private fun DrawScope.drawWeather(w: WxInfo, center: Offset, k: Float, color: Color, tm: TextMeasurer, u: Float, spOf: (Float) -> TextUnit, floor: Float = 0f) {
    val (bw, _) = wxSize(w, k, tm, u, spOf, floor)
    val left = center.x - bw * u / 2
    val icon = if (w.stage >= 0) maxOf(11f * k * u, floor * 1.3f) else 0f
    if (icon > 0) drawWxIcon(w.stage, Offset(left + icon / 2, center.y), icon, color)
    val l1 = tm.measure(w.city, TextStyle(color = color, fontSize = spOf(maxOf(6f * k * u, floor)), fontWeight = FontWeight.Bold))
    val l2 = tm.measure(w.tz, TextStyle(color = color.copy(alpha = color.alpha * 0.75f), fontSize = spOf(maxOf(4.5f * k * u, floor * 0.86f))))
    val tx = left + icon + (if (icon > 0) 2f * k * u else 0f)
    val top = center.y - (l1.size.height + l2.size.height) / 2f
    drawText(l1, topLeft = Offset(tx, top))
    drawText(l2, topLeft = Offset(tx, top + l1.size.height))
}

/** The 4 weather icons: sunny, cloudy, rainy, storm. */
private fun DrawScope.drawWxIcon(stage: Int, c: Offset, s: Float, color: Color) {
    val r = s / 2
    if (stage == 0) {
        val sun = Color(0xFFFFC83D).copy(alpha = color.alpha)
        drawCircle(sun, r * 0.45f, c)
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4
            drawLine(sun, Offset(c.x + cos(a) * r * 0.62f, c.y + sin(a) * r * 0.62f), Offset(c.x + cos(a) * r * 0.95f, c.y + sin(a) * r * 0.95f), r * 0.12f, StrokeCap.Round)
        }
        return
    }
    val rainC = Color(0xFF5AB0FF).copy(alpha = color.alpha)
    val snowC = Color(0xFFEAF4FF).copy(alpha = color.alpha)
    fun flake(p: Offset, rr: Float, w: Float) {
        for (k in 0 until 3) {
            val a = k * PI.toFloat() / 3
            drawLine(snowC, Offset(p.x - cos(a) * rr, p.y - sin(a) * rr), Offset(p.x + cos(a) * rr, p.y + sin(a) * rr), w, StrokeCap.Round)
        }
    }
    // Light rain: raindrops alone.
    if (stage == 4) {
        for ((dx, dy) in listOf(-0.5f to -0.55f, 0f to -0.3f, 0.5f to -0.55f, -0.25f to 0.25f, 0.25f to 0.25f))
            drawLine(rainC, Offset(c.x + dx * r, c.y + dy * r), Offset(c.x + dx * r - r * 0.14f, c.y + dy * r + r * 0.42f), r * 0.14f, StrokeCap.Round)
        return
    }
    // Light snow: snowflakes alone.
    if (stage == 5) {
        flake(Offset(c.x - r * 0.35f, c.y - r * 0.3f), r * 0.42f, r * 0.1f)
        flake(Offset(c.x + r * 0.42f, c.y + r * 0.2f), r * 0.42f, r * 0.1f)
        flake(Offset(c.x - r * 0.2f, c.y + r * 0.62f), r * 0.26f, r * 0.08f)
        return
    }
    val cloud = Color(0xFFD8DCE3).copy(alpha = color.alpha)
    val cy = if (stage == 1) c.y + r * 0.1f else c.y - r * 0.2f
    drawCircle(cloud, r * 0.32f, Offset(c.x - r * 0.38f, cy + r * 0.08f))
    drawCircle(cloud, r * 0.42f, Offset(c.x + r * 0.02f, cy - r * 0.12f))
    drawCircle(cloud, r * 0.3f, Offset(c.x + r * 0.45f, cy + r * 0.1f))
    drawRoundRect(cloud, Offset(c.x - r * 0.7f, cy + r * 0.05f), androidx.compose.ui.geometry.Size(r * 1.45f, r * 0.35f), androidx.compose.ui.geometry.CornerRadius(r * 0.17f))
    if (stage == 2) {
        val rain = Color(0xFF5AB0FF).copy(alpha = color.alpha)
        for (i in -1..1) drawLine(rain, Offset(c.x + i * r * 0.4f, cy + r * 0.55f), Offset(c.x + i * r * 0.4f - r * 0.12f, cy + r * 0.9f), r * 0.11f, StrokeCap.Round)
    }
    if (stage == 6) for (i in -1..1) flake(Offset(c.x + i * r * 0.42f, cy + r * (if (i == 0) 0.85f else 0.7f)), r * 0.2f, r * 0.07f)
    if (stage == 3) {
        val bolt = Path().apply {
            moveTo(c.x + r * 0.08f, cy + r * 0.35f); lineTo(c.x - r * 0.22f, cy + r * 0.72f); lineTo(c.x + r * 0.02f, cy + r * 0.72f)
            lineTo(c.x - r * 0.12f, cy + r * 1.0f); lineTo(c.x + r * 0.3f, cy + r * 0.58f); lineTo(c.x + r * 0.06f, cy + r * 0.58f); close()
        }
        drawPath(bolt, Color(0xFFFFD60A).copy(alpha = color.alpha))
    }
}
