package app.vlcinterface.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.player.OutputKind

object C {
    val bg = Color(0xFF0F1115)
    val card = Color(0xFF181B21)
    val card2 = Color(0xFF1F232A)
    val panel = Color(0xFF14171C)
    val line = Color(0xFF2C3038)
    val text = Color(0xFFECEDEF)
    val muted = Color(0xFFA3A8B1)
    val ok = Color(0xFF7BD88F)
    val bad = Color(0xFFFF8F8F)
}

val LocalAccent = staticCompositionLocalOf { Color(0xFFFF8A3D) }

fun scheme(accent: Color): ColorScheme = darkColorScheme(
    primary = accent, onPrimary = Color(0xFF1A0E05), background = C.bg, surface = C.panel,
    onBackground = C.text, onSurface = C.text, surfaceVariant = C.card, onSurfaceVariant = C.muted,
    outline = C.line,
)

@Composable
fun accent() = LocalAccent.current

/** Always-visible thin scrollbar for a verticalScroll Column while content overflows. */
fun Modifier.scrollbar(state: ScrollState, color: Color = Color(0xFF6B717C)): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max > 0 && max != Int.MAX_VALUE) {
        val total = size.height + max
        val thumbH = (size.height * size.height / total).coerceAtLeast(24.dp.toPx())
        val y = (size.height - thumbH) * state.value / max
        drawRoundRect(color, Offset(size.width - 4.dp.toPx(), y), Size(3.dp.toPx(), thumbH), CornerRadius(2.dp.toPx()))
    }
}

/** Same for lazy lists (approximation based on item positions). */
fun Modifier.scrollbar(state: LazyListState, color: Color = Color(0xFF6B717C)): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total > 0 && visible.isNotEmpty() && (visible.size < total || state.canScrollForward || state.canScrollBackward)) {
        val frac = visible.size.toFloat() / total
        val thumbH = (size.height * frac).coerceAtLeast(24.dp.toPx())
        val first = state.firstVisibleItemIndex.toFloat() / (total - visible.size).coerceAtLeast(1)
        val y = (size.height - thumbH) * first.coerceIn(0f, 1f)
        drawRoundRect(color, Offset(size.width - 4.dp.toPx(), y), Size(3.dp.toPx(), thumbH), CornerRadius(2.dp.toPx()))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(top = 6.dp, bottom = 4.dp), color = C.muted,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card), content = content)
}

@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val a = accent()
    val bg = when { filled -> a; selected -> a.copy(alpha = 0.14f); else -> Color.Transparent }
    val fg = if (filled) Color(0xFF1A0E05) else C.text
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(1.dp, if (selected || filled) a else C.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = if (selected || filled) FontWeight.SemiBold else FontWeight.Medium) }
}

@Composable
fun ToggleRow(label: String, hint: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = accent()))
    }
}

@Composable
fun ValueRow(label: String, hint: String = "", value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
        }
        Text(value, color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF22262D)))

/** Header pill showing the current sound output; tap opens the output sheet. */
@Composable
fun OutputPill(onClick: () -> Unit) {
    val devices by App.instance.output.devices.collectAsState()
    val active = devices.firstOrNull { it.active }
    val name = active?.name ?: "Sound output"
    Row(
        Modifier.heightIn(min = 40.dp).widthIn(max = 180.dp).clip(RoundedCornerShape(20.dp)).background(C.card2)
            .border(1.dp, C.line, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            when (active?.kind) { OutputKind.BLUETOOTH -> "ᛒ"; OutputKind.SPEAKER -> "🔈"; else -> "🎧" },
            color = accent(), fontSize = 14.sp
        )
        Text(name, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = C.muted)
    }
}

@Composable
fun Badge(text: String, good: Boolean) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(if (good) Color(0xFF1E3A27) else Color(0xFF3A2222)), contentAlignment = Alignment.Center) {
        Text(text, color = if (good) C.ok else C.bad, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun ago(t: Long): String {
    if (t <= 0) return "never scanned"
    val d = (System.currentTimeMillis() - t) / 1000
    return when {
        d < 3600 -> "scanned just now"
        d < 86400 -> "scanned today"
        d < 86400 * 7 -> "scanned ${d / 86400} d ago"
        else -> "scanned ${d / (86400 * 7)} wk ago"
    }
}

@Composable
fun Typo() = MaterialTheme.typography


// ---------- button size (Settings → Look → Button size) ----------

/** Scale for every symbol button and its tap area (0.75 = 25 % smaller than standard). */
val LocalBtnScale = androidx.compose.runtime.staticCompositionLocalOf { 0.75f }

@Composable
fun btnScale(): Float = LocalBtnScale.current

/** Symbol button (replaces IconButton): button, tap area and the symbol inside follow the Button size setting. */
@Composable
fun SymButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val k = btnScale()
    val d = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.size((48 * k).dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        // Text symbols (✕ ▶ ❚❚ …) shrink with the button too.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * k),
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
        ) { content() }
    }
}

/** Material symbol at the Button size setting. */
@Composable
fun SymIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
            tint: Color = androidx.compose.material3.LocalContentColor.current) {
    androidx.compose.material3.Icon(imageVector, contentDescription, modifier.size((24 * btnScale()).dp), tint = tint)
}


/**
 * A row of buttons that never overlaps or runs off screen: measured at the chosen Button size first;
 * if it doesn't fit the space it gets, the buttons inside are drawn smaller until it does.
 */
@Composable
fun AutoFitRow(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = 6.dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val k = btnScale()
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { c ->
        fun row(f: Float, slot: Any) = subcompose(slot) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBtnScale provides f) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
        var f = k
        var placeables = row(f, 0).map { it.measure(androidx.compose.ui.unit.Constraints()) }
        if (c.hasBoundedWidth) {
            // Shrink in up to 4 steps (text that doesn't scale makes it not exactly proportional).
            for (step in 1..4) {
                val w = placeables.maxOf { it.width }
                if (w <= c.maxWidth) break
                f = (f * c.maxWidth / w * 0.97f).coerceAtLeast(0.3f)
                placeables = row(f, step).map { it.measure(androidx.compose.ui.unit.Constraints()) }
            }
        }
        val w = placeables.maxOf { it.width }.coerceAtMost(if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE)
        val h = placeables.maxOf { it.height }
        layout(w, h) { placeables.forEach { it.placeRelative(0, (h - it.height) / 2) } }
    }
}
