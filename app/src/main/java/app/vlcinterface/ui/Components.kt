package app.vlcinterface.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.drawOutline

import androidx.compose.ui.graphics.drawscope.translate

import androidx.compose.ui.draw.drawBehind

import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
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
    val ok: Color get() = if (UiPrefs.lowBattery) LOW_BATTERY_RED else Color(0xFF7BD88F)
    val bad = Color(0xFFFF8F8F)
    /** Pop-up windows, search lists and menus: opacity from Settings, never a solid block. */
    val popup: Color get() = Color(0xFF14171C).copy(alpha = UiPrefs.popupAlpha)
    /** Long-press / ⋮ menus: their own opacity from Settings. */
    val menu: Color get() = Color(0xFF14171C).copy(alpha = UiPrefs.menuAlpha)
    /** Rows / tiles inside pop-ups: a light wash, so the pop-up's own transparency is kept. */
    val tile = Color.White.copy(alpha = 0.06f)
}

/** Look settings read while drawing (kept in step with Settings by the app's root). */
object UiPrefs {
    /** Players: shadow on buttons / knobs (Settings → look). */
    var controlShadow by androidx.compose.runtime.mutableStateOf(true)
    var popupAlpha by androidx.compose.runtime.mutableFloatStateOf(0.9f)
    var menuAlpha by androidx.compose.runtime.mutableFloatStateOf(0.9f)
    /** Space above and below each menu row (dp), from Settings. */
    var menuGapDp by androidx.compose.runtime.mutableIntStateOf(6)
    /** Look → "List row spacing" (%): the height of every list / queue row. */
    var listRowPct by androidx.compose.runtime.mutableIntStateOf(70)
    /** Battery 15 % or less (not charging): coloured items turn bright red. */
    var lowBattery by androidx.compose.runtime.mutableStateOf(false)
    var menuFont by androidx.compose.runtime.mutableStateOf("medium")
    /** 4 menu text sizes; the biggest still fits a menu (max 340 dp wide, long entries wrap). */
    val menuSizes = listOf("small" to "S", "medium" to "M", "large" to "L", "xlarge" to "XL")
    fun spOf(k: String) = when (k) { "small" -> 13; "large" -> 18; "xlarge" -> 21; else -> 15 }
    val menuFontSp get() = spOf(menuFont)
    /** Long-press on any menu entry: the size choice shows at the top of that menu. */
    var sizePick by androidx.compose.runtime.mutableStateOf(false)
}

val LOW_BATTERY_RED = Color(0xFFFF1A1A)

val LocalAccent = staticCompositionLocalOf { Color(0xFFFF8A3D) }

fun scheme(accent: Color): ColorScheme = darkColorScheme(
    primary = accent, onPrimary = Color(0xFF1A0E05), background = C.bg, surface = C.panel,
    onBackground = C.text, onSurface = C.text, surfaceVariant = C.card, onSurfaceVariant = C.muted,
    outline = C.line,
)

@Composable
fun accent() = LocalAccent.current

/** Always-visible thin scrollbar for a verticalScroll Column while content overflows. */
fun Modifier.scrollbar(state: ScrollState, color: Color = Color(0xFF6B717C)): Modifier = composed {
    val scope = rememberCoroutineScope()
    fastScroll(color, { state.maxValue > 0 && state.maxValue != Int.MAX_VALUE }, { h ->
        val total = h + state.maxValue
        (h * h / total) to (state.value.toFloat() / state.maxValue)
    }) { f -> scope.launch { state.scrollTo((f * state.maxValue).toInt()) } }
}

/** Same for lazy lists (approximation based on item positions). */
fun Modifier.scrollbar(state: LazyListState, color: Color = Color(0xFF6B717C)): Modifier = composed {
    val scope = rememberCoroutineScope()
    fastScroll(color, {
        val info = state.layoutInfo
        info.totalItemsCount > 0 && info.visibleItemsInfo.isNotEmpty() &&
            (info.visibleItemsInfo.size < info.totalItemsCount || state.canScrollForward || state.canScrollBackward)
    }, { h ->
        val info = state.layoutInfo
        val total = info.totalItemsCount; val vis = info.visibleItemsInfo.size
        (h * vis / total) to (state.firstVisibleItemIndex.toFloat() / (total - vis).coerceAtLeast(1)).coerceIn(0f, 1f)
    }) { f ->
        val info = state.layoutInfo
        val target = (f * (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)).toInt()
        scope.launch { state.scrollToItem(target.coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))) }
    }
}

/**
 * The lists' scroll bar: its width set in Settings (4–9 px); drag it (or touch the right edge) to jump straight to
 * any place — 8000 items in one move. [thumb] gives (thumb height, position 0…1) for a list height.
 */
private fun Modifier.fastScroll(color: Color, shown: () -> Boolean, thumb: (Float) -> Pair<Float, Float>, jump: (Float) -> Unit): Modifier = composed {
    var dragging by remember { mutableStateOf(false) }
    val widthPx = app.vlcinterface.App.instance.store.state.collectAsState().value.settings.scrollbarPx.toFloat()
    val accentCol = accent()
    this.pointerInput(Unit) {
        val grab = 24.dp.toPx()
        awaitEachGesture {
            // Only touches on the right edge, and only when there is something to scroll; the list keeps the rest.
            val down = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull() ?: return@awaitEachGesture
            if (!down.pressed || down.position.x < size.width - grab || !shown()) return@awaitEachGesture
            fun to(y: Float) {
                val (th, _) = thumb(size.height.toFloat()); val tH = th.coerceAtLeast(24.dp.toPx())
                jump(((y - tH / 2) / (size.height - tH).coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            // A tap on the edge (a ⋮ button there) goes through; only a drag along the edge becomes fast scroll.
            val slop = viewConfiguration.touchSlop
            while (true) {
                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val c = ev.changes.firstOrNull() ?: return@awaitEachGesture
                if (!c.pressed) return@awaitEachGesture
                if (kotlin.math.abs(c.position.y - down.position.y) > slop) { c.consume(); dragging = true; to(c.position.y); break }
            }
            while (true) {
                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val c = ev.changes.firstOrNull() ?: break
                if (!c.pressed) { c.consume(); break }
                c.consume(); to(c.position.y)
            }
            dragging = false
        }
    }.drawWithContent {
        drawContent()
        if (!shown()) return@drawWithContent
        val (th, pos) = thumb(size.height)
        val thumbH = th.coerceAtLeast(24.dp.toPx())
        val y = (size.height - thumbH) * pos
        // Dragging: a bit wider and in the accent colour, so you see what you hold.
        val w = if (dragging) widthPx * 1.6f else widthPx
        drawRoundRect(if (dragging) accentCol else color, Offset(size.width - w - 1.dp.toPx(), y), Size(w, thumbH), CornerRadius(w / 2))
    }
}

/** Lower case, accents taken off (é → e). */
fun normText(x: String): String = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/**
 * Search rule for the lists: the typed characters exactly, in their order — spaces typed before / after included
 * (" rock " = the word rock only). Capitals and accents don't matter; punctuation next to a word counts as a space.
 * [hay] must already be [normText]-ed; [q] is the text as typed (not trimmed).
 */
fun textMatch(hay: String, q: String): Boolean {
    val n = normText(q); if (n.isBlank()) return false
    val padded = " $hay "
    return padded.contains(n) || padded.replace(Regex("[^\\p{L}\\p{N}]+"), " ").contains(n)
}

/**
 * "Jump to…" box pinned at the top of long lists: each letter typed moves the list to the first item that starts
 * with the text (then: a word in it starting with it, then: containing it). [offset] = list rows before the items.
 */
@Composable
fun JumpToBox(state: LazyListState, labels: List<String>, offset: Int = 0) {
    var q by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keys = remember(labels) { labels.map { normText(it) } }
    // Every item holding exactly what's typed (spaces typed included, see textMatch); ‹ › step through them.
    // Searched 2 s after the last letter typed (11:40), like every library search.
    val dq = rememberDebounced(q)
    val found = remember(dq, keys) { if (dq.isBlank()) emptyList() else keys.indices.filter { textMatch(keys[it], dq) } }
    var at by remember(dq) { mutableStateOf(0) }
    fun go(k: Int) { if (found.isEmpty()) return; at = (k + found.size) % found.size; scope.launch { state.scrollToItem(found[at] + offset) } }
    fun jump(t: String) { q = t; at = 0 }
    androidx.compose.runtime.LaunchedEffect(found) { if (found.isNotEmpty()) state.scrollToItem(found[0] + offset) }
    SmallSearchBar(q, { jump(it) }, "Jump to…", found.size, at, { go(at - 1) }, { go(at + 1) }, modifier = Modifier.background(C.bg))
}

/**
 * The app's search bar: small and simple — the text, then "n/total ▲ ▼ ✕" once something is typed. [onSearch]: a
 * search sent somewhere (Enter / the 🔍), for online searches. [count] / [at]: results and the one shown.
 */
@Composable
fun SmallSearchBar(value: String, onChange: (String) -> Unit, placeholder: String, count: Int = 0, at: Int = 0,
                   prev: () -> Unit = {}, next: () -> Unit = {}, onSearch: (() -> Unit)? = null, modifier: Modifier = Modifier,
                   onClose: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp)).background(C.card2).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = C.muted, fontSize = 13.sp, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = C.text, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent()), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }))
        }
        if (onSearch != null) Text("🔍", fontSize = 16.sp, modifier = Modifier.clickable { onSearch() }.padding(horizontal = 8.dp))
        // With onClose: the result count and ✕ (no arrows); ✕ which closes the search (clears it and shrinks the box back).
        if (onClose != null) { if (value.isNotEmpty() || count > 0) {
            Text(if (count == 0) "0" else "$count", color = if (count == 0) C.bad else C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            Text("✕", color = C.text, fontSize = SYM, modifier = Modifier.clickable { onClose() }.padding(horizontal = 10.dp)) } }
        else if (value.isNotEmpty() || count > 0) SearchSteps(count, at, prev, next) { onChange("") }
    }
}

/** Small "n/total ▲ ▼ ✕" controls of the lists' search bars. */
@Composable
fun SearchSteps(count: Int, at: Int, prev: () -> Unit, next: () -> Unit, clear: () -> Unit) {
    Text(if (count == 0) "0" else "${at + 1}/$count", color = if (count == 0) C.bad else C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    // ▲ previous match (up the list), ▼ next match (down the list).
    Text("▲", color = if (count > 1) C.text else C.muted, fontSize = 15.sp, modifier = Modifier.clickable(enabled = count > 1) { prev() }.padding(horizontal = 8.dp))
    Text("▼", color = if (count > 1) C.text else C.muted, fontSize = 15.sp, modifier = Modifier.clickable(enabled = count > 1) { next() }.padding(horizontal = 8.dp))
    Text("✕", color = C.muted, fontSize = 16.sp, modifier = Modifier.clickable { clear() }.padding(horizontal = 8.dp))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.anchor(text).padding(top = (6 * sg()).dp, bottom = (4 * sg()).dp), color = C.muted,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // One box per category: rounded, a lighter shade of the page, a faint accent-tinted border.
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card)
        .border(1.dp, accent().copy(alpha = 0.3f), RoundedCornerShape(16.dp)), content = content)
}

/** Players' background: 90 % black. */
val PLAYER_BG = Color(0xFF1A1A1A)
/** Inside a player: buttons and pills get the faint shadow. */
val LocalInPlayer = androidx.compose.runtime.staticCompositionLocalOf { false }
/** Pills inside it use the touch guard (the player's own Random / Repeat / RGain). */
val LocalGuard = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Touch guard on the players' main buttons (2026-11-28 request 39): the action happens only when the finger stayed on
 * the button at least Playback & gestures → "Touch guard" (default 120 ms) and didn't slide; a brush, a palm or a touch
 * that turns into a scroll does nothing. The same button pressed again within 0.4 s is ignored (no double actions).
 * Off (0 ms): a plain tap, as before.
 */
object TouchGuard {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun ms(): Int = try { App.instance.store.current.settings.touchGuardMs } catch (_: Exception) { 60 }
    fun allow(key: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val prev = last[key] ?: 0L
        if (now - prev < 400) return false
        last[key] = now; return true
    }
}

fun Modifier.guardClick(key: String, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val ms = TouchGuard.ms()
    val cb = androidx.compose.runtime.rememberUpdatedState(onClick)
    if (!enabled) this
    else if (ms <= 0) this.clickable { if (TouchGuard.allow(key)) cb.value() }
    else this.semantics { this.onClick(label = null) { cb.value(); true } }.pointerInput(key, ms) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved = false
            var upAt = -1L
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (!c.pressed) { upAt = c.uptimeMillis; if (!moved && c.position.x in 0f..size.width.toFloat() && c.position.y in 0f..size.height.toFloat()) c.consume(); break }
            }
            if (upAt >= 0 && !moved && upAt - down.uptimeMillis >= ms && TouchGuard.allow(key)) cb.value()
        }
    }
}

/**
 * Faint light-grey shadow 45° down-right ([offset] dp) drawn behind a control of [shape] — put it before clip().
 * Only in the players, and only when switched on in Settings.
 */
@Composable
fun Modifier.liteShadow(shape: androidx.compose.ui.graphics.Shape, offset: Float = 1f): Modifier {
    if (!UiPrefs.controlShadow || !LocalInPlayer.current) return this
    return this.drawBehind {
        val o = offset.dp.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        // Two passes: a soft edge (1 px blur look) around the offset shape.
        translate(o + 0.5f, o + 0.5f) { drawOutline(outline, Color(0x29CDCDD2)) }
        translate(o, o) { drawOutline(outline, Color(0x3DCDCDD2)) }
    }
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
            .liteShadow(RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(1.dp, if (selected || filled) a else C.line, RoundedCornerShape(18.dp))
            .then(if (LocalGuard.current) Modifier.guardClick("pill:$text", onClick = onClick) else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = if (selected || filled) FontWeight.SemiBold else FontWeight.Medium) }
}

@Composable
fun ToggleRow(label: String, hint: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    val ex = LocalExplain.current
    Row(
        Modifier.anchor(label).fillMaxWidth().heightIn(min = (48 * sg()).dp).combinedClickable(onClick = { onChange(!checked) }, onLongClick = { ex(label, hint) })
            .padding(horizontal = 16.dp, vertical = (5 * sg()).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
        }
        AppSwitch(checked, onChange)
    }
}

/**
 * A setting that is a value (2026-11-28 10:04: "all settings as values to be sliders"): its name, line and current value
 * on top, a slider under it across the row. Long press on the name: its explanation. [shown] writes the value
 * ("10 s", "Off", "No limit"); [onChange] gets whole steps; [onFirst] runs on the first change of each visit (helpers).
 */
@Composable
fun ValueSlider(label: String, hint: String = "", shown: String, value: Int, range: IntRange, step: Int = 1,
                onFirst: () -> Unit = {}, onChange: (Int) -> Unit) {
    val ex = LocalExplain.current
    var first by remember { mutableStateOf(true) }
    Column(Modifier.anchor(label).fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { ex(label, hint) })
        .padding(horizontal = 16.dp, vertical = (6 * sg()).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = C.text, fontSize = 15.sp)
                if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
            }
            Text(shown, color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        val n = ((range.last - range.first) / step).coerceAtLeast(1)
        ThinSlider(value.coerceIn(range.first, range.last).toFloat(), { v ->
            val k = (Math.round((v - range.first) / step) * step + range.first).coerceIn(range.first, range.last)
            if (k != value) { onChange(k); if (first) { first = false; onFirst() } }
        }, Modifier.fillMaxWidth(), valueRange = range.first.toFloat()..range.last.toFloat(), steps = (n - 1).coerceAtLeast(0))
    }
}

@Composable
fun ValueRow(label: String, hint: String = "", value: String, onClick: () -> Unit) {
    val ex = LocalExplain.current
    Row(
        Modifier.anchor(label).fillMaxWidth().heightIn(min = (48 * sg()).dp).combinedClickable(onClick = onClick, onLongClick = { ex(label, hint) })
            .padding(horizontal = 16.dp, vertical = (5 * sg()).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.text, fontSize = 15.sp)
            if (hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp)
            if (LocalShowNow.current && value.isNotBlank()) NowLine(value)
        }
        if (LocalShowNow.current && value.endsWith("▶")) Text(value, color = WIZ_GREEN, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        else Text(value, color = accent(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- text that fits (players)

/** Text that shrinks by itself to fit its lines (down to [minSize]) and uses its full size when there's room. */
@Composable
fun FitText(text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier,
            fontWeight: FontWeight? = null, textAlign: androidx.compose.ui.text.style.TextAlign? = null, maxLines: Int = 2,
            lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
            overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
            minSize: androidx.compose.ui.unit.TextUnit = 12.sp) {
    var k by remember(text, fontSize, maxLines) { mutableStateOf(1f) }
    var ready by remember(text, fontSize, maxLines) { mutableStateOf(false) }
    val minK = (minSize.value / fontSize.value).coerceIn(0.3f, 1f)
    Text(text, color = color, fontSize = fontSize * k, lineHeight = if (lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified) lineHeight * k else lineHeight,
        fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines, overflow = overflow, letterSpacing = letterSpacing,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r -> if (r.hasVisualOverflow && k > minK) k = (k * 0.9f).coerceAtLeast(minK) else ready = true })
}

// ---------------------------------------------------------------- settings text size

/** Settings, wizard, help and helper pop-ups: all their text scaled together (main Settings page → "Text size"). */
@Composable
fun SettingsText(content: @Composable () -> Unit) {
    val pct = app.vlcinterface.App.instance.store.state.collectAsState().value.settings.settingsTextPct.coerceIn(70, 160)
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * pct / 100f)) { content() }
}

// ---------------------------------------------------------------- symbols on the settings pages

/** Settings pages' vertical spacing (main Settings page → "Vertical spacing"), 1 = 100 %. */
fun sg(): Float = app.vlcinterface.App.instance.store.current.settings.settingsGapPct.coerceIn(40, 160) / 100f

/** One size for every symbol / arrow on the settings pages; usable = bright, not usable = dimmer but still clear. */
val SYM = 18.sp
val SYM_OFF = Color(0xFF8A8F98)

// ---------------------------------------------------------------- switches

/** Switch of the app: its own colours for on and off (Look); greyed only when it can't be used. */
@Composable
fun AppSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val s = app.vlcinterface.App.instance.store.state.collectAsState().value.settings
    val on = if (s.switchOn != 0L) Color(s.switchOn) else accent(); val off = Color(s.switchOff)
    // Sized with the text next to it: follows the settings Text size (and the phone's font size), so a switch is never
    // tiny beside big text or huge beside small text. Its room on the row grows / shrinks with it.
    val k = switchK()
    Box(modifier.size((52 * k).dp, (32 * k).dp), contentAlignment = Alignment.Center) {
    androidx.compose.material3.Switch(checked, onChange, Modifier.wrapContentSize(unbounded = true).graphicsLayer { scaleX = k; scaleY = k }, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = on, uncheckedTrackColor = off, checkedBorderColor = on, uncheckedBorderColor = off,
            checkedThumbColor = Color(0xFF1A0E05), uncheckedThumbColor = Color(0xFFE8E8E8),
            disabledCheckedTrackColor = C.line, disabledUncheckedTrackColor = C.line, disabledCheckedBorderColor = C.line,
            disabledUncheckedBorderColor = C.line, disabledCheckedThumbColor = C.muted.copy(alpha = 0.6f), disabledUncheckedThumbColor = C.muted.copy(alpha = 0.6f)))
}
}

/** How big the switches are drawn: the text's size factor (settings Text size × phone font size), within limits. */
@Composable
fun switchK(): Float = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(0.7f, 1.8f) * SWITCH_BASE

/** Switches are drawn at 2/3 of Android's standard size (one third smaller), before the text-size factor. */
const val SWITCH_BASE = 2f / 3f

/** Wide, horizontally elongated switch (alarm rows): easier to hit; same colours as the other switches. */
@Composable
fun WideSwitch(checked: Boolean, height0: androidx.compose.ui.unit.Dp = 26.dp, holdMs: Long = 0L, tapNote: String = "", onChange: (Boolean) -> Unit) {
    // Follows the text size too (see AppSwitch).
    val height = height0 * switchK()
    val s = app.vlcinterface.App.instance.store.state.collectAsState().value.settings
    val on = if (s.switchOn != 0L) Color(s.switchOn) else accent(); val off = Color(s.switchOff)
    val knob = height - 6.dp
    // With [holdMs]: switches only after the finger stays that long (it fills up meanwhile); a short tap shows [tapNote].
    val fill = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cur by androidx.compose.runtime.rememberUpdatedState(checked)
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val gesture = if (holdMs <= 0L) Modifier.clickable { onChange(!checked) } else Modifier.pointerInput(holdMs) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val job = scope.launch {
                fill.snapTo(0f)
                fill.animateTo(1f, androidx.compose.animation.core.tween(holdMs.toInt(), easing = androidx.compose.animation.core.LinearEasing))
                change(!cur)
            }
            val up = waitForUpOrCancellation()
            if (job.isActive) {
                job.cancel()
                if (up != null && tapNote.isNotEmpty()) ShortNote.show(tapNote)
            }
            scope.launch { fill.snapTo(0f) }
        }
    }
    Box(Modifier.width(height * 2.8f).height(height).clip(androidx.compose.foundation.shape.RoundedCornerShape(height / 2))
        .background(if (checked) on else off).then(gesture)) {
        if (fill.value > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fill.value).background((if (checked) off else on).copy(alpha = 0.75f)))
        Box(Modifier.fillMaxSize().padding(3.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(knob).clip(androidx.compose.foundation.shape.CircleShape).background(if (checked) Color(0xFF1A0E05) else Color(0xFFE8E8E8)))
        }
    }
}

/** Short, self-closing note at the bottom of the screen (how long: Look → Short pop-ups stay for). */
object ShortNote {
    var text by androidx.compose.runtime.mutableStateOf("")
    var stamp by androidx.compose.runtime.mutableLongStateOf(0L)
    /** A hint instead of a done-note: "ℹ" and the accent-free light text instead of the green ✓. */
    var hint by androidx.compose.runtime.mutableStateOf(false)
    fun show(t: String) { text = t; hint = false; stamp = System.currentTimeMillis() }
    fun hint(t: String) { text = t; hint = true; stamp = System.currentTimeMillis() }
}

/**
 * ⏭ on the players (2026-11-22 19:06): with a single item in the queue there is no next one — the button is greyed and a
 * tap shows "Add more items to the queue". Radio keeps its next station.
 */
object NextBtn {
    fun none(): Boolean { val s = app.vlcinterface.App.instance.player.state.value; return s.item?.isRadio != true && s.queue.size <= 1 }
    fun tap(): Boolean { if (none()) { ShortNote.hint("Add more items to the queue"); return false }; app.vlcinterface.App.instance.player.next(); return true }
    /** ⏮ the same way (19:09: both buttons). */
    fun tapPrev(): Boolean { if (none()) { ShortNote.hint("Add more items to the queue"); return false }; app.vlcinterface.App.instance.player.prev(); return true }
}

@Composable
fun ShortNoteHost(modifier: Modifier) {
    val sec = app.vlcinterface.App.instance.store.state.collectAsState().value.settings.shortPopupSec
    val t = ShortNote.text; val st = ShortNote.stamp
    androidx.compose.runtime.LaunchedEffect(st) { if (t.isNotEmpty()) { kotlinx.coroutines.delay(sec * 1000L); if (ShortNote.stamp == st) ShortNote.text = "" } }
    if (t.isNotEmpty()) Box(modifier.padding(bottom = 90.dp, start = 24.dp, end = 24.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        .background(Color(0xF02A2E35)).border(1.dp, if (ShortNote.hint) Color(0xFF8A8F98) else WIZ_GREEN, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        .clickable { ShortNote.text = "" }.padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(if (ShortNote.hint) "ℹ $t" else "✓ $t", color = if (ShortNote.hint) Color(0xFFE8EAED) else WIZ_GREEN, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- current value (setup wizard)

/** True on the setup wizard's pages: every setting shows its current value on a row underneath. */
val LocalShowNow = androidx.compose.runtime.staticCompositionLocalOf { false }

/** "Now: …" row under a setting (red when something isn't granted / set). */
@Composable
fun NowLine(text: String, bad: Boolean = false) {
    Text("Now: $text", color = if (bad) C.bad else if (text.endsWith("✓")) WIZ_GREEN else Color(0xFF8FC3E8), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

/** Light green of the setup wizard's ticks and forward arrows. */
val WIZ_GREEN = Color(0xFF7CFC9A)

// ---------------------------------------------------------------- explanations (long-press any setting)

/** Long-press on a setting: (title, its own short description) → an explanation pop-up (see [ExplainHost]). */
val LocalExplain = androidx.compose.runtime.staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

/** Long-press on this item: its explanation. For rows that aren't ToggleRow / ValueRow (sliders, choices…). */
@Composable
fun Modifier.explain(title: String, hint: String = ""): Modifier {
    val ex = LocalExplain.current
    return this.anchor(title).combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = { ex(title, hint) })
}

/** Around the whole app: shows the explanation of a long-pressed setting. */
@Composable
fun ExplainHost(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(LocalExplain provides { t, h -> shown = t to h }) { content() }
    shown?.let { (t, h) ->
        HelperDialog(t, SettingHelp.text(t, h)) { shown = null }
    }
}

/** Helper pop-up: title and OK in a top bar that never scrolls; the explanation scrolls under it when long. */
@Composable
fun HelperDialog(title: String, text: String, extra: (@Composable () -> Unit)? = null, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) { SettingsText {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)
            .background(C.panel, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))) {
            Row(Modifier.fillMaxWidth().background(C.card, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(accent())
                    .clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 7.dp)) {
                    Text("OK", color = Color(0xFF1A0E05), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            val sc = androidx.compose.foundation.rememberScrollState()
            androidx.compose.foundation.layout.Column(Modifier.weight(1f, fill = false).verticalScroll(sc).padding(18.dp)) {
                Text(text, color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
                extra?.invoke()
            }
        }
    } }
}

// ---------------------------------------------------------------- thin sliders (like the players')

/** Slider of the settings pages: thin track and the players' small knob. */
@Composable
fun ThinSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
               steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null, enabled: Boolean = true, onHoldStill: (() -> Unit)? = null) {
    val a = accent()
    val colors = SliderDefaults.colors(thumbColor = a, activeTrackColor = a, inactiveTrackColor = C.line, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    // Helper pop-up only when the finger stays down without moving (never while the slider is being moved), and only
    // once per slider until the app is restarted (2026-11-21 11:39): after that, holding it still just holds it.
    val helpKey = androidx.compose.runtime.currentCompositeKeyHash
    val hold by androidx.compose.runtime.rememberUpdatedState(onHoldStill?.let { h -> { if (SliderHelpOnce.shown.add(helpKey)) h() } })
    val holdMod = if (onHoldStill == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val still = withTimeoutOrNull(650L) {
                while (true) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (still == null) hold?.invoke()   // held 650 ms, no movement
        }
    }
    // Less empty space around it (the touch row follows "Vertical spacing").
    androidx.compose.material3.Slider(value, onValueChange, modifier.height((30 * sg()).dp).then(holdMod), enabled = enabled, onValueChangeFinished = onValueChangeFinished, colors = colors, steps = steps,
        thumb = { KnobThumb(a) },
        track = { st -> SliderDefaults.Track(sliderState = st, modifier = Modifier.height(3.dp), colors = colors, enabled = enabled,
            drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
        valueRange = valueRange)
}

/** Sliders whose helper has already popped up in this run of the app (cleared only by restarting the app). */
object SliderHelpOnce { val shown: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet()) }

/** Two-handle version (frequency range…). */
@Composable
fun ThinRangeSlider(value: ClosedFloatingPointRange<Float>, onValueChange: (ClosedFloatingPointRange<Float>) -> Unit, modifier: Modifier = Modifier,
                    valueRange: ClosedFloatingPointRange<Float> = 0f..1f) {
    val a = accent()
    val colors = SliderDefaults.colors(thumbColor = a, activeTrackColor = a, inactiveTrackColor = C.line)
    androidx.compose.material3.RangeSlider(value, onValueChange, modifier, valueRange = valueRange, colors = colors,
        startThumb = { KnobThumb(a) }, endThumb = { KnobThumb(a) },
        track = { st -> SliderDefaults.Track(rangeSliderState = st, modifier = Modifier.height(3.dp), colors = colors,
            drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) })
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
fun SymButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, size: androidx.compose.ui.unit.Dp = 48.dp, content: @Composable () -> Unit) = Scaled {
    Box(modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) { content() }
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
    // Own row (Android's menu rows are at least 48 dp high): rows as close as set in Settings (menu row spacing).
    val gap = UiPrefs.menuGapDp
    Row(modifier.fillMaxWidth().widthIn(min = 112.dp).clickable(enabled = enabled, onClick = onClick).pointerInput(Unit) {
        // Long-press (no click afterwards): show the menu text size choice in this menu.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var done = false
                while (!done) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    done = c == null || !c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
            val long = ended == null
            if (long) {
                UiPrefs.sizePick = true
                // Swallow the rest of the touch so the entry is not also chosen.
                while (true) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
            }
        }
    }.padding(horizontal = 16.dp, vertical = gap.dp), verticalAlignment = Alignment.CenterVertically) {
        leadingIcon?.let { it(); androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp)) }
        Box(Modifier.weight(1f)) {
            androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.copy(fontSize = sp.sp, lineHeight = (sp + 4).sp,
                color = if (enabled) C.text else C.muted)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides (if (enabled) C.text else C.muted)) { text() }
            }
        }
        trailingIcon?.let { androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp)); it() }
    }
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

/**
 * A horizontal row that always fits the screen width (2026-11-21 15:56): laid out with [arrangement] when its items fit;
 * when they don't, the whole row is shrunk evenly (items, text and tap areas together) to exactly the width, so items
 * never overlap and never go past the edge of the display.
 */
@Composable
fun FitRow(modifier: Modifier = Modifier, arrangement: androidx.compose.foundation.layout.Arrangement.Horizontal = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
           minGap: androidx.compose.ui.unit.Dp = 4.dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { c ->
        val natural = subcompose("measure") {
            androidx.compose.runtime.CompositionLocalProvider(LocalMeasureOnly provides true) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(minGap),
                    verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }.map { it.measure(androidx.compose.ui.unit.Constraints()) }
        val natW = natural.maxOfOrNull { it.width } ?: 0
        if (!c.hasBoundedWidth || natW <= c.maxWidth) {
            val shown = subcompose("shown") {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement,
                    verticalAlignment = Alignment.CenterVertically, content = content)
            }.map { it.measure(c.copy(minWidth = if (c.hasBoundedWidth) c.maxWidth else 0, minHeight = 0)) }
            val w = shown.maxOfOrNull { it.width } ?: 0; val h = shown.maxOfOrNull { it.height } ?: 0
            layout(w, h) { shown.forEach { it.place(0, 0) } }
        } else {
            val sc = c.maxWidth.toFloat() / natW
            val shown = subcompose("shown") {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(minGap),
                    verticalAlignment = Alignment.CenterVertically, content = content)
            }.map { it.measure(androidx.compose.ui.unit.Constraints()) }
            val h = ((shown.maxOfOrNull { it.height } ?: 0) * sc).toInt()
            layout(c.maxWidth, h) {
                shown.forEach { it.placeWithLayer(0, 0) { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) } }
            }
        }
    }
}

/** Every menu and submenu starts with "‹ Back", which closes it and returns to the page. */
@Composable
fun SDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
                  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (LocalMeasureOnly.current) return
    // A menu never sits over the keyboard: the keyboard is put away when it opens.
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (expanded) androidx.compose.runtime.LaunchedEffect(Unit) { kb?.hide() }
    // Never over the status bar or the navigation / gesture bar: at most the space between them (the menu scrolls),
    // and Android's menu keeps 48 dp from the screen's top and bottom edges, beyond those bars.
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val bars = androidx.compose.foundation.layout.WindowInsets.Companion.systemBars
    val freeH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp -
        with(dens) { (bars.getTop(dens) + bars.getBottom(dens)).toDp() } - 104.dp
    androidx.compose.material3.DropdownMenu(expanded, onDismissRequest, modifier.widthIn(max = 340.dp).heightIn(max = freeH.coerceAtLeast(160.dp)),
        containerColor = C.menu, tonalElevation = 0.dp, shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, C.line)) {
        if (!expanded) UiPrefs.sizePick = false
        if (UiPrefs.sizePick) {
            // Quick menu text size check (same setting as in Settings); the menu stays open to see the result.
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                UiPrefs.menuSizes.forEach { (k, l) ->
                    Pill(l, selected = UiPrefs.menuFont == k, filled = UiPrefs.menuFont == k) {
                        App.instance.store.update { it.copy(settings = it.settings.copy(menuFont = k)) }
                    }
                }
                Text("✓", color = C.text, fontSize = 20.sp, modifier = Modifier.clickable { UiPrefs.sizePick = false }.padding(horizontal = 8.dp))
            }
        }
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
    // Songs being saved from the radio: finished first (a pop-up shows them), then the app closes.
    val rc = app.vlcinterface.sources.RadioCapture
    if (rc.hasWork() && !rc.exiting.value) { rc.finishThen { exitApp(restart, keepNotice) }; return }
    val a = app.vlcinterface.App.instance
    // Exit: the volume now is kept for a start within the "Reset volume after" time. Restart the app (2026-11-22 18:41):
    // a fresh start — the Start volume is set (it was kept, so the volume never changed on a restart).
    a.exiting = true
    try { a.getSharedPreferences("volkeep", android.content.Context.MODE_PRIVATE).edit().putLong("alive", if (restart) 0L else System.currentTimeMillis())
        .putInt("pct", a.output.volumePercent()).putBoolean("cleanExit", !restart).commit() } catch (_: Exception) {}
    try { a.store.flush() } catch (_: Exception) {}
    try { app.vlcinterface.sources.RgScanner.cancel() } catch (_: Exception) {}
    // The radio buffer and any working copy: deleted.
    try { app.vlcinterface.sources.RadioCapture.wipe() } catch (_: Exception) {}
    try { app.vlcinterface.sources.Prefetch.clear() } catch (_: Exception) {}
    try { app.vlcinterface.sources.ParallelFetch.report() } catch (_: Exception) {}
    // The queue and the position: kept for the next start.
    try { app.vlcinterface.player.QueueKeeper.save(a.player.state.value, a.player.originalOrder()) } catch (_: Exception) {}
    // The process is ended by a plain thread in 1.5 s whatever happens next — even if stop() hangs on a network read
    // stuck in libVLC (the screen's thread then can't run the usual end below).
    Thread({ try { Thread.sleep(1500) } catch (_: InterruptedException) {}; android.os.Process.killProcess(android.os.Process.myPid()) }, "exit-guard").start()
    try { a.player.stop() } catch (_: Exception) {}
    // Sleep closing the app keeps its "Player closed by sleep timer" notification; everything else is removed.
    // 2026-11-28 01:10: Exit closes everything — no notification left but the alarms' (next alarm, ringing, snoozed,
    // missed). The player's media session is released too (its card on Android 13+ went on showing), the services are
    // stopped first, and the clean-up is done again just before the process ends (a service could post once more).
    try { a.media.cancelAll(); a.media.session.release() } catch (_: Exception) {}
    try { a.stopService(android.content.Intent(a, app.vlcinterface.player.PlaybackService::class.java)) } catch (_: Exception) {}
    try { a.stopService(android.content.Intent(a, app.vlcinterface.sources.RgScanService::class.java)) } catch (_: Exception) {}
    // The alarm service is kept only while an alarm rings / is snoozed or watched (Android would restart it, "sticky").
    try { if (app.vlcinterface.alarm.AlarmEngine.ui.value.phase == "idle") a.stopService(android.content.Intent(a, app.vlcinterface.alarm.AlarmService::class.java)) } catch (_: Exception) {}
    try { a.getSharedPreferences("volkeep", android.content.Context.MODE_PRIVATE).edit().putLong("exitAt", System.currentTimeMillis()).commit() } catch (_: Exception) {}
    fun clearNotes() = try {
        val nm = a.getSystemService(android.app.NotificationManager::class.java)
        val keep = setOf("alarm_next_quiet", "alarm_ring_quiet", "alarm_ring_led_quiet", "alarm_watch_quiet") + if (keepNotice) setOf("sleep_quiet") else emptySet()
        nm.activeNotifications.filter { it.notification.channelId !in keep && !it.notification.channelId.orEmpty().startsWith("alarm") }.forEach { nm.cancel(it.tag, it.id) }
    } catch (_: Exception) {}
    clearNotes()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ clearNotes() }, 250)
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
    // 2026-11-28 03:09 (process still there after Exit): the end no longer depends on the screen's thread being free
    // (a player or network call stuck on it kept the delayed end from ever running) — a plain thread ends the process
    // after 1.2 s whatever happens.
    Thread({ try { Thread.sleep(1200) } catch (_: InterruptedException) {}; android.os.Process.killProcess(android.os.Process.myPid()) }, "exit").apply { isDaemon = false }.start()
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
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                // Work still going on: what it is and about how long it needs (refreshed every second).
                var work by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(BackgroundWorkInfo.lines()) }
                androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); work = BackgroundWorkInfo.lines() } }
                if (work.isNotEmpty()) {
                    Text("Still in progress", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    work.forEach { Text("• $it", color = C.text, fontSize = 13.sp) }
                    Text("Exit or restart now stops it: an unfinished song save is lost, a ReplayGain scan keeps what it already wrote.",
                        color = C.bad, fontSize = 12.sp)
                }
                Text("Exit closes the app completely (playback stops, notifications go away).\n" +
                    "Restart closes it and opens it again fresh: the player, sound engine, connections and all settings are loaded anew.",
                    color = C.muted, fontSize = 13.sp)
            }
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


/** What runs in the background, with the estimated time left — for the Exit question. */
object BackgroundWorkInfo {
    private fun eta(ms: Long): String = when {
        ms < 60_000 -> "less than a minute left"
        ms < 3_600_000 -> "about ${(ms + 30_000) / 60_000} min left"
        else -> "about ${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min left"
    }
    private fun left(startedAt: Long, done: Float): Long? =
        if (startedAt <= 0 || done < 0.02f) null else ((System.currentTimeMillis() - startedAt) * (1 - done) / done).toLong()

    fun lines(): List<String> = buildList {
        val paused = app.vlcinterface.player.BackgroundWork.paused.value
        val rg = app.vlcinterface.sources.RgScanner.state.value
        if (rg.running) add("ReplayGain scan: ${rg.done}/${rg.total} files · " + (left(rg.startedAt, rg.progress)?.let { eta(it) } ?: "estimating time left…"))
        val rgQ = app.vlcinterface.sources.RgScanner.queued.value
        if (rgQ.isNotEmpty()) add("${rgQ.size} more ReplayGain scan${if (rgQ.size == 1) "" else "s"} queued: " + rgQ.joinToString(", "))
        val rc = app.vlcinterface.sources.RadioCapture
        val q = rc.saveQueue.value
        if (q.isNotEmpty()) {
            val pr = rc.saveProgress.value
            val cur = left(rc.saveStartedAt, pr)
            add("Saving “${q.first()}”: ${(pr * 100).toInt()} % · " + (cur?.let { eta(it) } ?: "estimating time left…"))
            if (q.size > 1) {
                // Each song takes about as long as the one being saved now.
                val per = if (pr >= 0.02f) ((System.currentTimeMillis() - rc.saveStartedAt) / pr).toLong() else null
                add("${q.size - 1} more song${if (q.size == 2) "" else "s"} to save" + (if (per != null && cur != null) " · all done in " + eta(cur + per * (q.size - 1)).removeSuffix(" left") else ""))
            }
        }
        rc.status.value.pending?.let { add("“${it.title}”: saved after it ends (still playing)") }
        app.vlcinterface.sources.NetIndex.busy.value.takeIf { it.isNotEmpty() }?.let { add("$it · time not known") }
        if (paused && isNotEmpty()) add(0, "Background work is paused (red button)")
    }
}

/** Scroll positions of the Library lists, kept while the app runs (going into a folder and back keeps the place). */
object ScrollMemory { val pos = java.util.concurrent.ConcurrentHashMap<String, Int>() }

/**
 * A scroll state that remembers its place under [key]: restored as soon as the list's content is there ([ready]),
 * saved while scrolling and when leaving.
 */
@Composable
fun rememberKeptScroll(key: String, ready: Boolean = true): androidx.compose.foundation.ScrollState {
    val sc = androidx.compose.foundation.rememberScrollState()
    var restored by androidx.compose.runtime.remember(key) { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(key, ready, sc.maxValue) {
        if (ready && !restored) {
            val want = ScrollMemory.pos[key] ?: 0
            if (want <= 0) { restored = true; return@LaunchedEffect }
            if (sc.maxValue >= want) { sc.scrollTo(want); restored = true }
        }
    }
    // The list got shorter meanwhile: go as far as it reaches.
    androidx.compose.runtime.LaunchedEffect(key, ready) {
        if (ready) { kotlinx.coroutines.delay(700); if (!restored) { sc.scrollTo(minOf(ScrollMemory.pos[key] ?: 0, sc.maxValue)); restored = true } }
    }
    androidx.compose.runtime.LaunchedEffect(key) {
        androidx.compose.runtime.snapshotFlow { sc.value }.collect { v -> if (restored) ScrollMemory.pos[key] = v }
    }
    return sc
}


/**
 * A pop-up that only informs (no choice to make): it closes by itself after 3 s; touched, it stays until its OK
 * button is pressed or the screen is touched outside it. Same arguments as AlertDialog.
 */
@Composable
fun AutoInfoDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = C.panel,
) {
    var pinned by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val sec = app.vlcinterface.App.instance.store.state.collectAsState().value.settings.shortPopupSec
    androidx.compose.runtime.LaunchedEffect(pinned) { if (!pinned) { kotlinx.coroutines.delay(sec * 1000L); if (!pinned) onDismissRequest() } }
    // A touch outside it always closes it (kept open or not). Title and buttons in a top bar; the text scrolls under it.
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) { SettingsText {
        androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().heightIn(max = 560.dp)
            .background(containerColor, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); pinned = true } }) {
            Row(Modifier.fillMaxWidth().background(C.card, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides C.text,
                        androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)) { title?.invoke() }
                }
                dismissButton?.invoke(); confirmButton()
            }
            val sc = androidx.compose.foundation.rememberScrollState()
            androidx.compose.foundation.layout.Column(Modifier.weight(1f, fill = false).verticalScroll(sc).padding(18.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides C.muted) { text?.invoke() }
                if (!pinned) Text("Closes in $sec s · touch it to keep it open", color = C.muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    } }
}

// ---------------------------------------------------------------- choices kept between visits

/**
 * A page's own choice buttons (Songs / Radio / Both and the like) remembered where they were left: coming back to
 * the page — later, or after restarting the app — shows the same choice again.
 */
object KeptChoice {
    private val prefs get() = app.vlcinterface.App.instance.getSharedPreferences("uiChoices", android.content.Context.MODE_PRIVATE)
    fun get(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun put(key: String, v: String) = prefs.edit().putString(key, v).apply()
}

/** A remembered choice for a page: reads the last value, saves every change. */
@Composable
fun rememberKept(key: String, def: String): androidx.compose.runtime.MutableState<String> {
    val st = androidx.compose.runtime.remember(key) { androidx.compose.runtime.mutableStateOf(KeptChoice.get(key, def)) }
    androidx.compose.runtime.LaunchedEffect(key) {
        androidx.compose.runtime.snapshotFlow { st.value }.collect { KeptChoice.put(key, it) }
    }
    return st
}

/**
 * A list put in order by dragging (requested 2026-11-21 11:28): hold the ≡ handle (or long-press the row) and drag the
 * row up or down — the others make room as it passes; let go to keep the new order. ✕ removes an item (when
 * [onRemove] is given and [canRemove] allows it); removed items are listed under it with "+" to bring them back.
 */
@Composable
fun DragOrderList(
    items: List<String>,
    name: (String) -> String,
    onOrder: (List<String>) -> Unit,
    removed: List<String> = emptyList(),
    onRemove: ((String) -> Unit)? = null,
    onRestore: ((String) -> Unit)? = null,
    canRemove: (String) -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    // Local copy while dragging, so the rows move live; taken from [items] again when not dragging.
    val ord = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var offset by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val heights = androidx.compose.runtime.remember { HashMap<String, Int>() }
    if (dragging == null && ord.toList() != items) { ord.clear(); ord.addAll(items) }
    val currentItems by androidx.compose.runtime.rememberUpdatedState(items)
    val currentOnOrder by androidx.compose.runtime.rememberUpdatedState(onOrder)

    fun dragBy(dy: Float) {
        val id = dragging ?: return
        offset += dy
        val i = ord.indexOf(id); if (i < 0) return
        // Passed half of the neighbour's height: swap with it and carry the rest of the offset over.
        if (offset > 0 && i < ord.lastIndex) {
            val h = heights[ord[i + 1]] ?: return
            if (offset > h / 2f) { ord.removeAt(i); ord.add(i + 1, id); offset -= h }
        } else if (offset < 0 && i > 0) {
            val h = heights[ord[i - 1]] ?: return
            if (-offset > h / 2f) { ord.removeAt(i); ord.add(i - 1, id); offset += h }
        }
    }
    fun start(id: String) { dragging = id; offset = 0f }
    fun end() {
        val changed = ord.toList() != currentItems
        val newOrder = ord.toList()
        dragging = null; offset = 0f
        if (changed) currentOnOrder(newOrder)
    }

    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF))) {
        ord.forEach { id ->
            androidx.compose.runtime.key(id) {
                val isDrag = dragging == id
                Row(
                    Modifier.fillMaxWidth()
                        .zIndex(if (isDrag) 1f else 0f)
                        .graphicsLayer { translationY = if (isDrag) offset else 0f; shadowElevation = if (isDrag) 8f else 0f }
                        .background(if (isDrag) Color(0xFF2A3038) else Color.Transparent)
                        .onGloballyPositioned { heights[id] = it.size.height }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start(id) }, onDragEnd = { end() }, onDragCancel = { end() },
                                onDrag = { ch, d -> ch.consume(); dragBy(d.y) })
                        }
                        .padding(vertical = (2 * sg()).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ≡ handle: drags at once (no long press needed).
                    Text("≡", color = if (isDrag) accent() else C.muted, fontSize = 22.sp, lineHeight = 22.sp,
                        modifier = Modifier.pointerInput(id) {
                            detectDragGestures(
                                onDragStart = { start(id) }, onDragEnd = { end() }, onDragCancel = { end() },
                                onDrag = { ch, d -> ch.consume(); dragBy(d.y) })
                        }.padding(horizontal = 14.dp, vertical = 8.dp))
                    Text(name(id), color = C.text, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (onRemove != null && canRemove(id))
                        Text("✕", color = C.bad, fontSize = SYM, modifier = Modifier.clickable { onRemove(id) }.padding(horizontal = 14.dp, vertical = 8.dp))
                    else if (onRemove != null) Spacer(Modifier.width(44.dp))
                }
            }
        }
        if (removed.isNotEmpty() && onRestore != null) {
            Text("Removed — tap to bring back", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
            removed.forEach { id ->
                Row(Modifier.fillMaxWidth().clickable { onRestore(id) }.padding(vertical = (2 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+", color = WIZ_GREEN, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    Text(name(id), color = C.muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The one style of every details pop-up (2026-11-21 11:51) — songs, videos and stations in every player and list:
 * title, "Tap a line to copy it.", sections in the accent colour, each line label on the left and value on the right
 * with thin dividers, tap a line to copy its value, "Share" (Android's share sheet), "Copy all" for everything, "Close". [sections]: title → lines;
 * [note]: a short line at the end (or the state while loading).
 */
@Composable
fun DetailsDialog(title: String, sections: List<Pair<String, List<Pair<String, String>>>>, note: String = "",
                  emptyText: String = "Nothing to show.", youtube: app.vlcinterface.player.PlayItem? = null, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun copy(text: String, what: String) {
        ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
        android.widget.Toast.makeText(ctx, "$what copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun report() = detailsText(title, sections, note)
    androidx.compose.material3.AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text(title, color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Tap a line to copy it.", color = C.muted, fontSize = 12.sp)
                if (sections.all { it.second.isEmpty() }) Text(emptyText, color = C.text, modifier = Modifier.padding(top = 8.dp))
                sections.filter { it.second.isNotEmpty() }.forEach { (t, rows) ->
                    Text(t, color = accent(), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    rows.forEachIndexed { i, (k, v) ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().clickable { copy(v, k) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Text(k, color = C.muted, fontSize = 13.sp, modifier = Modifier.width(118.dp).padding(end = 8.dp))
                            Text(v, color = if (v == "—" || v.endsWith("…")) C.muted else C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (note.isNotEmpty()) Text(note, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { STextButton(onDone) { Text("Close", color = accent()) } },
        dismissButton = {
            Row {
                // Share (2026-11-21 11:53): Android's share sheet with everything as text (message, mail, notes…).
                // Videos: search the same name on YouTube (2026-11-21 21:04).
                youtube?.let { v -> STextButton({ YouTube.open(ctx, v) }) { Text("YouTube") } }
                STextButton({ shareDetails(ctx, title, report()) }) { Text("Share") }
                STextButton({ copy(report(), "Details") }) { Text("Copy all") }
            }
        },
    )
}

/** Details as plain text (title, sections with "  label: value" lines, note): Copy all and Share. */
fun detailsText(title: String, sections: List<Pair<String, List<Pair<String, String>>>>, note: String = ""): String = buildString {
    append(title).append("\n\n")
    sections.forEach { (t, rows) -> if (rows.isNotEmpty()) { append(t).append('\n'); rows.forEach { (k, v) -> append("  ").append(k).append(": ").append(v).append('\n') }; append('\n') } }
    if (note.isNotEmpty()) append(note).append('\n')
}

/** Android's share sheet with [text] (subject [title]); copied instead if nothing can share. */
/**
 * Shares a text as a .txt file (2026-11-21 23:38: a long log put inside the share message went over Android's size limit
 * and nothing happened). Android's app picker opens; the text itself also goes in the message when it's short.
 */
fun shareTextFile(ctx: android.content.Context, fileName: String, text: String, chooserTitle: String) {
    // Saved in Download/vlc_interface (the app's own file there, like "Collect error logs") and shared from it — no file
    // provider in the app (1.73). Android's app picker opens; short texts also go in the message.
    try {
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/vlc_interface")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("Download folder not available")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw Exception("can't write the file")
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text.length <= 60_000) send.putExtra(android.content.Intent.EXTRA_TEXT, text)
        send.clipData = android.content.ClipData.newRawUri(fileName, uri)
        ctx.startActivity(android.content.Intent.createChooser(send, chooserTitle).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Couldn't share: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * What to share from a log (2026-11-21 23:38): all entries or errors only (the current filter chosen at first), then
 * Android's app picker. [counts]: all, errors.
 */
@Composable
fun LogShareDialog(title: String, all: Int, errors: Int, errorsFirst: Boolean, onPick: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var errOnly by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(errorsFirst && errors > 0) }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, containerColor = C.panel,
        title = { Text(title, color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What to share:", color = C.muted, fontSize = 13.sp)
                listOf(false to "All entries ($all)", true to "Errors only ($errors)").forEach { (k, label) ->
                    val on = errOnly == k
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .then(if (on) Modifier.background(accent().copy(alpha = 0.18f)) else Modifier)
                        .clickable(enabled = !(k && errors == 0)) { errOnly = k }.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (on) "●" else "○", color = if (on) accent() else C.muted, modifier = Modifier.width(26.dp))
                        Text(label, color = if (k && errors == 0) C.muted else C.text, fontSize = 15.sp)
                    }
                }
                Text("Sent as a .txt file — then pick the app (mail, messages, Drive…).", color = C.muted, fontSize = 12.sp)
            }
        },
        confirmButton = { STextButton({ onPick(errOnly) }) { Text("Share…", color = accent()) } },
        dismissButton = { STextButton(onDismiss) { Text("Cancel") } })
}

fun shareDetails(ctx: android.content.Context, title: String, text: String) {
    try {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, title).putExtra(android.content.Intent.EXTRA_TEXT, text)
        ctx.startActivity(android.content.Intent.createChooser(send, "Share $title").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
        android.widget.Toast.makeText(ctx, "Details copied", android.widget.Toast.LENGTH_SHORT).show()
    }
}



/**
 * Drag to move a queue entry one place at a time (2026-11-21 16:51): hold the ≡ handle and drag; each half row
 * passed moves it one position up or down in the queue, the row following the finger.
 */
class QueueDrag {
    var key by androidx.compose.runtime.mutableStateOf<String?>(null)
    var dy by androidx.compose.runtime.mutableFloatStateOf(0f)
    var rowH by androidx.compose.runtime.mutableIntStateOf(1)

    /** The row: lifted and following the finger while it's the one dragged. */
    fun row(k: String): Modifier = Modifier.zIndex(if (key == k) 1f else 0f)
        .graphicsLayer { if (key == k) { translationY = dy; shadowElevation = 12f } }
        .onGloballyPositioned { if (key == k) rowH = it.size.height }
        .background(if (key == k) C.card else Color.Transparent)

    /**
     * The ≡ handle of the row with queue key [k]; [indexOf] finds its place now, [move] moves it (+1 / -1).
     * Hold first (2026-11-21 21:04): the drag starts only after the finger rests on ≡ for [HOLD_MS]; a finger that moves
     * before that is a scroll and is left to the list (scrolling that began on ≡ used to move the row).
     */
    fun handle(k: String, indexOf: () -> Int, size: () -> Int, move: (Int, Int) -> Unit): Modifier = Modifier.pointerInput(k) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // The hold: ends early (no drag) when the finger lifts, moves past the touch slop, or the list took it.
            val held = withTimeoutOrNull(HOLD_MS) {
                var still = true
                while (still) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed || c.isConsumed || (c.position - down.position).getDistance() > slop) still = false
                }
                false                                          // the hold was broken before the time
            } ?: true                                          // time up with the finger still: drag
            if (!held) return@awaitEachGesture
            key = k; dy = 0f
            try {
                drag(down.id) { ch ->
                    val d = ch.positionChange().y
                    ch.consume()
                    dy += d
                    val h = rowH.coerceAtLeast(1).toFloat()
                    var i = indexOf()
                    while (dy > h / 2 && i in 0 until size() - 1) { move(i, 1); i++; dy -= h }
                    while (dy < -h / 2 && i > 0) { move(i, -1); i--; dy += h }
                }
            } finally { key = null; dy = 0f }
        }
    }

    companion object { const val HOLD_MS = 200L }
}


/**
 * The app's own small digit pad (2026-11-21 17:26 / 17:27): every number input in the app is typed with it, and
 * Android's keyboard stays hidden. Keys 1–9, ⌫, 0 and [lastKey] (→ next box, or ✓ done).
 */
@Composable
fun DigitPad(onKey: (String) -> Unit, lastKey: String = "✓", modifier: Modifier = Modifier) {
    // Keys shrink when the phone is sideways (short screen), so the pad and its pop-up stay on screen (17:53).
    // Sizes worked out from the screen (17:55): its pop-up keeps ~220 dp for the title, the field and the buttons; the
    // rest of the height (and the pop-up's width) is shared by the 3 × 4 keys, their text scaling with them.
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val keyH = ((conf.screenHeightDp - 64f - 220f - 18f) / 4f).coerceIn(26f, 56f)
    val keyW = ((minOf(conf.screenWidthDp.toFloat(), 420f) - 96f - 12f) / 3f).coerceIn(44f, 88f)
    val keyFont = (minOf(keyW, keyH) * 0.42f).coerceIn(12f, 24f)
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", lastKey)).forEach { row ->
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Box(Modifier.size(width = keyW.dp, height = keyH.dp).clip(RoundedCornerShape(10.dp)).background(C.card).clickable { onKey(k) },
                        contentAlignment = Alignment.Center) {
                        Text(k, color = if (k == lastKey) accent() else C.text, fontSize = keyFont.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Which number field has its digit pad open (one at a time). */
object DigitFieldUi {
    var open by androidx.compose.runtime.mutableStateOf<String?>(null)
}

/**
 * A number field typed with [DigitPad] (no Android keyboard): looks like the other input fields; a tap opens the pad
 * under it, the first digit replaces what was there, ✓ closes it. [maxLen] digits at most.
 */
@Composable
fun DigitField(value: String, label: String, maxLen: Int, modifier: Modifier = Modifier, openAtStart: Boolean = false, onChange: (String) -> Unit) {
    val key = androidx.compose.runtime.remember { java.util.UUID.randomUUID().toString() }
    var fresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (openAtStart) DigitFieldUi.open = key }
    val isOpen = DigitFieldUi.open == key
    Column(modifier) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(if (isOpen) 2.dp else 1.dp, if (isOpen) accent() else C.line, RoundedCornerShape(6.dp))
            .clickable { DigitFieldUi.open = if (isOpen) null else key; fresh = true }.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(label, color = if (isOpen) accent() else C.muted, fontSize = 12.sp)
            Text(value.ifEmpty { " " }, color = C.text, fontSize = 18.sp)
        }
        if (isOpen) DigitPad({ k ->
            when (k) {
                "⌫" -> { onChange(value.dropLast(1)); fresh = false }
                "✓" -> DigitFieldUi.open = null
                else -> { onChange(((if (fresh) "" else value) + k).take(maxLen)); fresh = false }
            }
        }, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
    }
}


/**
 * Page title (2026-11-21 18:04): one size everywhere — the same on every page and header, whatever the Button size,
 * the settings Text size or a page's own scaling (22 dp letters, bold × Settings → "Page titles size").
 */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val real = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.density
    // Same size on every page; set with Settings → "Page titles size" (18:05).
    val pct = app.vlcinterface.App.instance.store.state.collectAsState().value.settings.pageTitlePct / 100f
    val sp = 22f * pct * real / (d.density * d.fontScale)
    Text(text, color = C.text, fontSize = sp.sp, lineHeight = (sp * 1.2f).sp, fontWeight = FontWeight.Bold, maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = modifier)
}

// ---------------------------------------------------------------- Settings search: open a page at a setting

/**
 * Where a settings page should scroll when it opens (Settings search, the players' shortcuts): a setting's name
 * ([want]) and its section ([section]). The rows that carry that name report their place; the page scrolls there and
 * lights the row for 2 s ([flash]). No row found: its section; neither: the page stays at its top.
 */
object SettingsAnchor {
    var page by mutableStateOf<String?>(null)
    var want by mutableStateOf<String?>(null)
    var section by mutableStateOf<String?>(null)
    var flash by mutableStateOf<String?>(null)
    internal var wantY = Float.NaN
    internal var sectionY = Float.NaN
    fun go(page: String, setting: String?, section: String?) {
        wantY = Float.NaN; sectionY = Float.NaN; this.page = page
        want = setting?.takeIf { it.isNotEmpty() }; this.section = section?.takeIf { it.isNotEmpty() }
    }
}

/** This row is the setting / section named [key]: it reports its place when a page is asked to open there, and lights up. */
@Composable
fun Modifier.anchor(key: String): Modifier {
    val a = SettingsAnchor
    var m = this
    if (a.want == key || a.section == key) m = m.onGloballyPositioned { c ->
        val y = c.positionInRoot().y
        if (a.want == key) a.wantY = y
        if (a.section == key && a.sectionY.isNaN()) a.sectionY = y     // the first one: the section's own title
    }
    if (a.flash == key) m = m.background(accent().copy(alpha = 0.25f), RoundedCornerShape(8.dp))
    return m
}

/** On a settings page's scrolling column (before verticalScroll): scrolls to the asked setting once it's laid out. */
@Composable
fun Modifier.anchorViewport(scroll: androidx.compose.foundation.ScrollState, page: String): Modifier {
    val a = SettingsAnchor
    val top = remember { floatArrayOf(Float.NaN) }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(a.page, a.want, a.section) {
        val key = a.want; val sec = a.section
        if (a.page != page || (key == null && sec == null)) return@LaunchedEffect
        // Rows report while the page is laid out; give it a moment (the page may still be opening).
        var waited = 0
        while (waited < 1200 && (top[0].isNaN() || (key != null && a.wantY.isNaN()) || (key == null && a.sectionY.isNaN()))) {
            kotlinx.coroutines.delay(40); waited += 40
        }
        kotlinx.coroutines.delay(60)
        val ownY = a.wantY; val secY = a.sectionY
        val (y, lit) = when {
            key != null && !ownY.isNaN() -> ownY to key
            sec != null && !secY.isNaN() -> secY to sec
            else -> Float.NaN to null
        }
        a.want = null; a.section = null; a.page = null
        if (lit == null || top[0].isNaN()) { if (key != null) SettingsSearch.notFound(key); return@LaunchedEffect }
        scroll.animateScrollTo((scroll.value + y - top[0] - margin).toInt().coerceIn(0, scroll.maxValue))
        a.flash = lit
        kotlinx.coroutines.delay(2000)
        if (a.flash == lit) a.flash = null
    }
    return this.onGloballyPositioned { top[0] = it.positionInRoot().y }
}


/** A list row's height / spacing scaled by Look → "List row spacing" (one value for every list and queue). */
fun lrDp(base: Int): androidx.compose.ui.unit.Dp = (base * UiPrefs.listRowPct / 100f).dp

/** A row's small buttons (✕, ⋮) follow List row spacing too (12:01), never smaller than 22 dp (still tappable). */
fun lrBtn(): androidx.compose.ui.unit.Dp = lrDp(48).coerceIn(22.dp, 48.dp)

/** Text line height in list rows: the usual at 100 % and more, tighter below it (down to the letters' own height). */
fun lrLine(fontSp: Float): androidx.compose.ui.unit.TextUnit =
    androidx.compose.ui.unit.TextUnit(fontSp * (1.02f + 0.23f * (UiPrefs.listRowPct / 100f).coerceIn(0f, 1f)), androidx.compose.ui.unit.TextUnitType.Sp)


/**
 * Library searches (2026-11-22 11:40): the text typed is searched 2 s after the last letter — each new letter starts
 * the wait again, so the list isn't filtered (and the phone doesn't work) on every key. Emptying the box applies at once.
 */
@Composable
fun rememberDebounced(value: String, ms: Long = 2000): String {
    var out by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value.isBlank()) out = value else { kotlinx.coroutines.delay(ms); out = value } }
    return out
}


/**
 * Helpers popping up by themselves (2026-11-28 01:15): only with Helpers on (Alarm clock settings), only on the pages
 * whose switch is on (Helpers per settings page), and each helper once per session — a long press still shows it any time.
 */
object AutoHelp {
    val PAGES = listOf("alarm" to "Alarm page", "audio" to "Audio", "playback" to "Playback & gestures", "radio" to "Radio", "video" to "Video", "look" to "Look", "library" to "Library & files",
        "vis" to "Visualisation", "clock" to "Clock aspect", "assoc" to "File associations")
    private val shown = java.util.Collections.synchronizedSet(HashSet<String>())
    fun pageOn(page: String) = app.vlcinterface.alarm.Alarms.current.global.helpers &&
        page !in app.vlcinterface.App.instance.store.current.settings.helpersOffPages
    fun ok(page: String, key: String): Boolean = pageOn(page) && shown.add("$page|$key")
}
