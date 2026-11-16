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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack

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
    /** Pop-up windows, search lists and menus: opacity from Settings, never a solid block. */
    val popup: Color get() = Color(0xFF14171C).copy(alpha = UiPrefs.popupAlpha)
}

/** Look settings read while drawing (kept in step with Settings by the app's root). */
object UiPrefs {
    var popupAlpha by androidx.compose.runtime.mutableFloatStateOf(0.9f)
    var menuFont by androidx.compose.runtime.mutableStateOf("medium")
    val menuFontSp get() = when (menuFont) { "small" -> 13; "large" -> 19; else -> 15 }
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
fun SectionTitle(text: String, modifier: Modifier = Modifier) = Scaled {
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
    Scaled { PillBody(text, selected, modifier, filled, onClick) }
}

@Composable
private fun PillBody(text: String, selected: Boolean, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
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
fun ToggleRow(label: String, hint: String = "", checked: Boolean, onChange: (Boolean) -> Unit) = Scaled {
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
fun ValueRow(label: String, hint: String = "", value: String, onClick: () -> Unit) = Scaled {
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
fun OutputPill(onClick: () -> Unit) = Scaled { OutputPillBody(onClick) }

@Composable
private fun OutputPillBody(onClick: () -> Unit) {
    val devices by App.instance.output.devices.collectAsState()
    val active = devices.firstOrNull { it.active }
    val name = active?.name ?: "Sound output"
    Row(
        Modifier.heightIn(min = 40.dp).widthIn(max = 180.dp).clip(RoundedCornerShape(20.dp)).background(C.card2)
            .border(1.dp, C.line, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            when (active?.kind) { OutputKind.BLUETOOTH -> "BT"; OutputKind.SPEAKER -> "🔈"; else -> "🎧" },
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



// ---------- button size (Settings → Look → Button size) ----------

/** Scale for every symbol button and its tap area (0.75 = 25 % smaller than standard). */
val LocalBtnScale = androidx.compose.runtime.staticCompositionLocalOf { 0.75f }

@Composable
fun btnScale(): Float = LocalBtnScale.current

private val LocalScaled = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Draws its content at the Button size setting: size, tap area, padding and the writing on it, all together,
 * so every button and menu item in the app looks the same. Nested buttons are scaled once only.
 */
@Composable
fun Scaled(content: @Composable () -> Unit) {
    if (LocalScaled.current) { content(); return }
    val k = btnScale()
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * k, d.fontScale),
        LocalScaled provides true,
    ) { content() }
}

/** Symbol button (replaces IconButton). */
@Composable
fun SymButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) = Scaled {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** Material symbol at the Button size setting. */
@Composable
fun SymIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
            tint: Color = androidx.compose.material3.LocalContentColor.current) = Scaled {
    androidx.compose.material3.Icon(imageVector, contentDescription, modifier.size(24.dp), tint = tint)
}

// Material buttons, menu items and ticks at the Button size setting (same look everywhere).
@Composable
fun STextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                colors: androidx.compose.material3.ButtonColors = androidx.compose.material3.ButtonDefaults.textButtonColors(),
                content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Scaled {
    androidx.compose.material3.TextButton(onClick, modifier, enabled, colors = colors, content = content)
}

@Composable
fun SButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
            colors: androidx.compose.material3.ButtonColors = androidx.compose.material3.ButtonDefaults.buttonColors(),
            content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Scaled {
    androidx.compose.material3.Button(onClick, modifier, enabled, colors = colors, content = content)
}

@Composable
fun SOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    colors: androidx.compose.material3.ButtonColors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Scaled {
    androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, colors = colors, content = content)
}

@Composable
fun SMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
              leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null) = Scaled {
    // Menu text size from Settings; long entries wrap onto more lines instead of being cut.
    val sp = UiPrefs.menuFontSp
    androidx.compose.material3.DropdownMenuItem({
        androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.copy(fontSize = sp.sp, lineHeight = (sp + 4).sp)) { text() }
    }, onClick, modifier.heightIn(min = (sp * 3).dp), leadingIcon, trailingIcon, enabled)
}

@Composable
fun SCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) = Scaled {
    androidx.compose.material3.Checkbox(checked, onCheckedChange, modifier, enabled)
}

@Composable
fun SRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) = Scaled {
    androidx.compose.material3.RadioButton(selected, onClick, modifier, enabled)
}

/**
 * A row of buttons that never overlaps or runs off screen: measured at the chosen Button size first;
 * if it doesn't fit the space it gets, the buttons inside are drawn smaller until it does.
 */
@Composable
fun AutoFitRow(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = 6.dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val k = btnScale()
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { c ->
        fun row(f: Float, slot: Any, measureOnly: Boolean = true) = subcompose(slot) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBtnScale provides f, LocalMeasureOnly provides measureOnly) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
                    verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
        var f = k
        var placeables = row(f, 0).map { it.measure(androidx.compose.ui.unit.Constraints()) }
        var shrunk = false
        if (c.hasBoundedWidth) {
            // Shrink in up to 4 steps (text that doesn't scale makes it not exactly proportional).
            for (step in 1..4) {
                val w = placeables.maxOf { it.width }
                if (w <= c.maxWidth) break
                f = (f * c.maxWidth / w * 0.97f).coerceAtLeast(0.3f)
                placeables = row(f, step).map { it.measure(androidx.compose.ui.unit.Constraints()) }
                shrunk = true
            }
        }
        // The row that is shown: menus and pop-ups inside it open only from this one (not from the trial layouts).
        placeables = row(f, "shown", measureOnly = false).map { it.measure(androidx.compose.ui.unit.Constraints()) }
        val w = placeables.maxOf { it.width }.coerceAtMost(if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE)
        val h = placeables.maxOf { it.height }
        layout(w, h) { placeables.forEach { it.placeRelative(0, (h - it.height) / 2) } }
    }
}


/** True inside the trial layouts of [AutoFitRow]: nothing pops up from there. */
val LocalMeasureOnly = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Every menu and submenu starts with "‹ Back", which closes it and returns to the page. */
@Composable
fun SDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
                  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (LocalMeasureOnly.current) return
    // A menu never sits over the keyboard: the keyboard is put away when it opens.
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (expanded) androidx.compose.runtime.LaunchedEffect(Unit) { kb?.hide() }
    androidx.compose.material3.DropdownMenu(expanded, onDismissRequest, modifier.widthIn(max = 340.dp), containerColor = C.popup) {
        SMenuItem(text = { Text("‹  Back", color = C.muted) }, onClick = onDismissRequest)
        content()
    }
}

/** Back to the previous page (shown on every page that has one). */
@Composable
fun BackButton(nav: Navigator, tint: Color = C.text) {
    SymButton({ nav.back() }) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the previous page", tint = tint) }
}


/** Exit: stops playback and any scan, removes the notifications and closes the app completely ([restart]: then starts it again fresh). */
fun exitApp(restart: Boolean = false, keepNotice: Boolean = false) {
    val a = app.vlcinterface.App.instance
    try { a.store.flush() } catch (_: Exception) {}
    try { app.vlcinterface.sources.RgScanner.cancel() } catch (_: Exception) {}
    try { a.player.stop() } catch (_: Exception) {}
    // Sleep closing the app keeps its "Player closed by sleep timer" notification; everything else is removed.
    try { val nm = a.getSystemService(android.app.NotificationManager::class.java); if (keepNotice) a.media.cancelAll() else nm.cancelAll() } catch (_: Exception) {}
    try { a.stopService(android.content.Intent(a, app.vlcinterface.player.PlaybackService::class.java)) } catch (_: Exception) {}
    try { a.stopService(android.content.Intent(a, app.vlcinterface.sources.RgScanService::class.java)) } catch (_: Exception) {}
    val act = app.vlcinterface.MainActivity.instance
    if (restart && act != null) {
        // A helper in its own process ends this one and opens the app again: everything is loaded fresh.
        act.startActivity(android.content.Intent(act, app.vlcinterface.RestartActivity::class.java)
            .putExtra(app.vlcinterface.RestartActivity.EXTRA_PID, android.os.Process.myPid())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    act?.finishAndRemoveTask()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(0) }, 300)
}

/** Power button: quits the app completely. */
@Composable
fun ExitButton(tint: Color = C.text) {
    // The question is shown by the app's root (ExitDialogHost), not by this button: on the video player the
    // controls hide after a few seconds and took the question away with them.
    SymButton({ ExitPrompt.visible = true }) {
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            val w = 2.2.dp.toPx()
            val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val inset = w
            drawArc(tint, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset + size.height * 0.06f),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset - size.height * 0.06f), style = st)
            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width / 2, inset * 0.5f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.5f),
                strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Exit / Restart question, kept open even when the page underneath changes (e.g. video controls hiding). */
object ExitPrompt { var visible by androidx.compose.runtime.mutableStateOf(false) }

@Composable
fun ExitDialogHost() {
    if (ExitPrompt.visible) AlertDialog(onDismissRequest = { ExitPrompt.visible = false }, containerColor = C.panel,
        title = { Text("Exit or restart?", color = C.text) },
        text = {
            Text("Exit closes the app completely (playback stops, notifications go away).\n" +
                "Restart closes it and opens it again fresh: the player, sound engine, connections and all settings are loaded anew.",
                color = C.muted, fontSize = 13.sp)
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                STextButton({ ExitPrompt.visible = false; exitApp(restart = true) }) { Text("Restart", color = accent()) }
                STextButton({ ExitPrompt.visible = false; exitApp() }) { Text("Exit", color = C.bad) }
            }
        },
        dismissButton = { STextButton({ ExitPrompt.visible = false }) { Text("Cancel") } },
    )
}
