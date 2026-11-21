@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.vlcinterface.ui

import kotlinx.coroutines.flow.first
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.relocation.bringIntoViewRequester
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.data.PlEntry
import app.vlcinterface.data.Playlist
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Source
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem
import app.vlcinterface.player.PlayerState
import app.vlcinterface.sources.Browse
import app.vlcinterface.sources.Cue
import app.vlcinterface.sources.CueResult
import app.vlcinterface.sources.Entry
import app.vlcinterface.sources.MusicLibrary
import app.vlcinterface.sources.NasWriter
import app.vlcinterface.sources.PlaylistFiles
import app.vlcinterface.sources.SmbProbe
import app.vlcinterface.sources.Urls
import app.vlcinterface.sources.ext
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.vlcinterface.player.NativeAudio
import kotlin.math.log10

// ---------------- Music player ----------------

/** Music now-playing screen (the video player keeps its own interface). Never shows lyrics. */
@Composable
fun MusicScreen(nav: Navigator, openOutput: () -> Unit) {
    val player = App.instance.player
    // The play position changes several times a second. The page itself only follows it every 15 s (enough for the
    // sleep badge); the progress bar and the time left read the exact position in their own small parts, so the
    // whole page isn't rebuilt on every tick (less work for the phone while music plays).
    val full = player.state.collectAsState()
    val p by remember { derivedStateOf { full.value.copy(time = full.value.time / 15_000 * 15_000) } }
    val st by App.instance.store.state.collectAsState()
    val item = p.item
    if (item == null) { LaunchedEffect(Unit) { nav.back() }; return }
    LaunchedEffect(item.uri) { if (!item.isMusic && !item.isRadio) nav.replace(Screen.Player) }
    // Double tap on the Treble slider: Settings → Audio scrolled to the Treble band; Back returns here (21:50).
    LaunchedEffect(TrebleBandNav.want) { if (TrebleBandNav.want) { TrebleBandNav.want = false; SettingsAnchor.want = "trebleband"; nav.go(Screen.SettingsGroup("audio")) } }
    var sleepMenu by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    var playNextPick by remember { mutableStateOf(false) }
    var upMenu by remember { mutableStateOf(-1) }
    val scroll = rememberScrollState()
    val meters = NativeAudio.active
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = conf.screenWidthDp > conf.screenHeightDp
    // VU meters sideways: narrower in proportion to the shorter screen height.
    // Sideways: song name and artist · album smaller in the same proportion as the picture (190 / 280).
    val tk = if (landscape) 190f / 280f else 1f
    // Sideways the halves are measured so the page fits the screen without scrolling where possible:
    // the picture takes what the left half has left over (never below 110 dp), and "Up next" shows as many songs
    // as fit under the controls (at least one; the full queue is one tap away). Scrolling stays as the fallback.
    var leftHpx by remember { mutableIntStateOf(0) }
    var rightHpx by remember { mutableIntStateOf(0) }
    // Upright: the title block sticks under the header once the cover has scrolled away (portrait only).
    var titleTopPx by remember { mutableIntStateOf(0) }
    var titleHpx by remember { mutableIntStateOf(0) }
    // Floating play / pause: shown when the player's buttons are scrolled out of view.
    var ctlBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var viewTop by remember { mutableFloatStateOf(0f) }
    // Where the scrolling page and the tone sliders are on screen (for the sine button: scroll to the sliders).
    // Live layout handles (read at the moment of the tap, never an old position).
    val viewBox = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val toneBox = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    var upNextYpx by remember { mutableIntStateOf(-1) }
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val vuK = if (landscape) (conf.screenHeightDp.toFloat() / conf.screenWidthDp).coerceIn(0.35f, 1f) else 1f
    androidx.compose.runtime.CompositionLocalProvider(LocalInPlayer provides true) {
    // Upright: the clock (or the album picture) at the top of the page scrolls away normally with it; once it's fully
    // out of view it's taken away (the page doesn't move) and stays away while you act — any scroll or touch restarts
    // Look → "Clock hidden for" / "Cover hidden for" — and never comes back while a menu, pop-up or settings window is open.
    val setsP = App.instance.store.state.collectAsState().value.settings
    val clockTop = app.vlcinterface.alarm.Alarms.state.collectAsState().value.showClock
    val topSec = if (clockTop) setsP.clockHideSec else setsP.coverHideSec
    // Look switches "Hide the clock / album picture while scrolling": off = the clock stays pinned above the page,
    // the picture simply scrolls with the page; neither is ever taken away.
    // (The album picture only when it's the first row of the page.)
    val topAuto = if (clockTop) setsP.clockAutoHide else setsP.coverAutoHide && playerRowOrder(setsP, item.isRadio).firstOrNull() == "picture"
    var lastAct by remember { mutableLongStateOf(0L) }
    var topHidden by remember { mutableStateOf(false) }
    var topHpx by remember { mutableIntStateOf(0) }
    var ignoreScrollUntil by remember { mutableLongStateOf(0L) }
    // Look → "Player rows spacing": the gap between the player's rows (and around the title block's bottom).
    val rowGap = setsP.playerGap.coerceIn(0, 16).dp
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowGap.toPx() }.toInt()
    val winFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(clockTop, landscape, topAuto) { topHidden = false }
    LaunchedEffect(scroll, landscape, topAuto) {
        androidx.compose.runtime.snapshotFlow { scroll.value }.collect { v ->
            if (landscape || !topAuto || System.currentTimeMillis() < ignoreScrollUntil) return@collect
            if (topHidden) {
                lastAct = System.currentTimeMillis()
                return@collect
            }
            if (topHpx > 0 && v > topHpx + gapPx) {
                // Fully scrolled out: take it away and move the page by the same amount, so nothing on screen moves.
                ignoreScrollUntil = Long.MAX_VALUE
                topHidden = true
                scroll.scrollTo(v - topHpx)            // it stays in the page with no height (its gap stays too)
                ignoreScrollUntil = System.currentTimeMillis() + 300
                lastAct = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(lastAct, winFocused, topSec, topHidden) {
        if (!topHidden || !winFocused) return@LaunchedEffect   // a window is open: stays away; counts again once it's closed
        kotlinx.coroutines.delay((lastAct + topSec * 1000L - System.currentTimeMillis()).coerceAtLeast(0L))
        // Back into view: put back at the top of the page and the whole page glides back up to show it.
        ignoreScrollUntil = Long.MAX_VALUE
        topHidden = false
        kotlinx.coroutines.yield()
        try { scroll.animateScrollTo(0) } finally { ignoreScrollUntil = System.currentTimeMillis() + 300 }
    }
    LaunchedEffect(winFocused) { if (winFocused && topHidden) lastAct = System.currentTimeMillis() }
    Box(Modifier.fillMaxSize().background(PLAYER_BG).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (topHidden && e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) lastAct = System.currentTimeMillis()
            }
        }
    }) {   // players: 90 % black
    Column(Modifier.fillMaxSize().padding(end = if (meters) (21 * vuK).dp else 0.dp)) {
        // No page title: the song name is already shown in the middle of the player.
        // Header spread evenly over the bar, nothing touching: upright on two rows (symbols, then the player switches
        // and the output), sideways on one row.
        val headSyms: @Composable () -> Unit = {
            SymButton(onClick = { nav.back() }) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            HomeButton(nav)
            // Audio player → Audio settings; radio player → Radio settings.
            SymButton({ nav.go(Screen.SettingsGroup(if (item.isRadio) "radio" else "audio")) }) { SymIcon(Icons.Filled.Settings, "Settings", tint = C.text) }
            // Jump to a song: search the queue and the Library; plays it (added after the current song if new).
            SymButton({ jump = true }) { SymIcon(Icons.Filled.Search, "Jump to song", tint = C.text) }
            ShazamButton(); BackgroundWorkButton(); ScreenOnSign(C.text); ExitButton()
        }
        Scaled {
            // Every header row fits the width (15:56): shrunk evenly when its items don't fit, never past the edge.
            if (landscape) FitRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
                headSyms(); Row(verticalAlignment = Alignment.CenterVertically) { PlayerSwitchButtons(nav, if (item.isRadio) "radio" else "songs") }; OutputPill(openOutput)
            } else Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FitRow(Modifier.fillMaxWidth()) { headSyms() }
                FitRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    // The other two players (each resumes what it last played), then the output on the right.
                    Row(verticalAlignment = Alignment.CenterVertically) { PlayerSwitchButtons(nav, if (item.isRadio) "radio" else "songs") }
                    OutputPill(openOutput)
                }
            }
        }
        SaveSongBar()
        if (jump) JumpDialog(nav = nav) { jump = false }
        if (playNextPick) JumpDialog(playNext = true, nav = nav) { playNextPick = false }
        // Upright: one scrolling page. Sideways: the same page split in two under the track / ReplayGain line —
        // picture and titles on the left, controls and the rest on the right, each scrolling on its own.
        // Clock shown: pinned at the top (upright, the page scrolls under it) or alone on the left half (sideways).
        val clockShown = app.vlcinterface.alarm.Alarms.state.collectAsState().value.showClock
        val pinnedClock: @Composable (Modifier) -> Unit = { mod ->
            val asp = app.vlcinterface.alarm.Alarms.state.collectAsState().value.aspect
            androidx.compose.foundation.layout.BoxWithConstraints(mod, contentAlignment = Alignment.Center) {
                ClockWithGear(nav) { ClockFace(asp, Modifier.size(minOf(maxWidth, maxHeight))) }
            }
        }
        // The page's rows, in the order chosen in Look → "Order of the player rows" (songs and radio apart).
        val rowsOf: @Composable ColumnScope.(List<String>) -> Unit = { ids ->
            var coverNote by remember(item.uri) { mutableStateOf("") }
            // Long-press the picture: look for it online again (also when it wasn't found before).
            var coverRetry by remember(item.uri) { mutableIntStateOf(0) }
            var coverRefresh by remember(item.uri) { mutableIntStateOf(0) }
            // Long-press: all fitting album pictures online; several → choose one from the list.
            var coverPick by remember(item.uri) { mutableStateOf(false) }
            val coverItem = item.copy(artist = p.metaArtist.ifEmpty { item.artist }, album = p.metaAlbum.ifEmpty { item.album })
            val coverSize = if (!landscape) 280 else if (leftHpx <= 0) 190 else (with(dens) { leftHpx.toDp().value }.toInt() - 128).coerceIn(110, 240)
            // Radio: the announced song's album picture (temporary radio pictures), else the station's logo.
            // Clock button pressed: the clock and the alarms in the picture's place, whatever the player does.
            val alarmData = app.vlcinterface.alarm.Alarms.state.collectAsState().value
            val clockOn = alarmData.showClock
            // Settings → Visualisation: always the visualisation in the picture's place (songs / radio / both).
            val visAlways = VisCover.on || setsP.visInsteadCover.let { it == "both" || (it == "radio" && item.isRadio) || (it == "songs" && !item.isRadio) }
            val pic: @Composable (Int) -> Unit = { sz ->
                if (clockOn) Box(Modifier.padding(top = 8.dp)) { ClockWithGear(nav) { ClockFace(alarmData.aspect, Modifier.size(sz.dp)) } } else
                if (visAlways) Box(Modifier.padding(top = 8.dp).size(sz.dp).background(Color.Black)) {
                    VisArea(Modifier.fillMaxSize().combinedClickable(onClick = {}, onDoubleClick = { nav.go(Screen.Visualisation) }, onLongClick = { nav.go(Screen.SettingsGroup("vis")) }))
                } else
                // No picture found (song or station): the visualisation with its current settings fills that place;
                // a long press on it opens the Visualisation settings.
                if (item.isRadio) Cover(p.artworkUrl.ifEmpty { item.artwork }, sz, Modifier.padding(top = 8.dp).combinedClickable(onClick = {}, onDoubleClick = { nav.go(Screen.Visualisation) }, onLongClick = {}), noCover = { VisArea(Modifier.fillMaxSize().combinedClickable(onClick = {}, onDoubleClick = { nav.go(Screen.Visualisation) }, onLongClick = { nav.go(Screen.SettingsGroup("vis")) })) }) else
                // The picture goes as soon as the song ends (the next song brings its own).
                Cover(if (p.songOver) "" else item.artwork, sz, Modifier.padding(top = 8.dp).combinedClickable(onClick = {}, onDoubleClick = { nav.go(Screen.Visualisation) }, onLongClick = { coverNote = ""; coverPick = true }), retry = coverRetry,
                    item = if (p.songOver) null else coverItem, download = !p.songOver, refresh = coverRefresh,
                    onNote = { coverNote = it }, onFound = { player.useDownloadedArtwork(item, "mem:" + item.uri) },
                    noCover = if (p.songOver) null else ({ VisArea(Modifier.fillMaxSize().combinedClickable(onClick = {}, onDoubleClick = { nav.go(Screen.Visualisation) }, onLongClick = { nav.go(Screen.SettingsGroup("vis")) })) }))
            }
            val rowPicture: @Composable () -> Unit = {
            // Sideways: the picture / clock takes the height left by the rest of the left half (no scrolling).
            // Upright: the clock takes the whole width; the album picture keeps its size.
            if (clockOn) Unit   // the clock is pinned outside the scrolling page
            else if (landscape) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                pic((minOf(maxWidth, maxHeight).value.toInt() - 8).coerceAtLeast(48))
            } else Box(Modifier.keepWhenHidden(topHidden).onSizeChanged { topHpx = it.height }) { pic(coverSize) }
            // Alarm system off (Settings): the clock stays, its alarms are hidden.
            if (clockOn && alarmData.global.systemOn) AlarmMiniList(nav, compact = false)
            if (coverPick) CoverPicker(coverItem,
                onNone = { coverPick = false; coverNote = "Looking for the picture online…"; coverRetry++ },
                onDone = { note, changed ->
                    coverPick = false
                    if (note.isNotEmpty()) coverNote = note
                    if (changed) { coverRefresh++; player.useDownloadedArtwork(item, "mem:" + item.uri + "#v" + System.currentTimeMillis()) }
                }, artPath = p.artworkUrl.ifEmpty { item.artwork },
                onBan = { clear ->
                    coverPick = false
                    coverNote = if (clear) "Allowing the pictures again…" else "Looking for another picture…"
                    player.replacePicture(coverItem, clear) { note -> coverNote = note; coverRefresh++ }
                })
            if (coverNote.isNotEmpty() && !clockOn) Text(coverNote, color = C.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            val rowTitle: @Composable () -> Unit = {
            // Upright: stays at the top (under the header) once scrolled up to it; the rest scrolls underneath.
            val stickMod = if (landscape) Modifier else Modifier.fillMaxWidth()
                .onGloballyPositioned { titleTopPx = it.positionInParent().y.toInt(); titleHpx = it.size.height }
                .zIndex(2f).graphicsLayer { translationY = (scroll.value - titleTopPx).coerceAtLeast(0).toFloat() }
                .background(PLAYER_BG).padding(bottom = rowGap / 2)
            Box(stickMod, contentAlignment = Alignment.TopCenter) {
            if (item.isRadio) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RadioHeader(item, p, tk)
                // Upright: "Live · listening …" stays with the station in the frozen block.
                if (!landscape) RadioListening()
            } else
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Tap the song name: its folder in Libraries.
                // Long-press: the song's menu (ReplayGain scan, YouTube / Spotify links, file path).
                var songMenu by remember { mutableStateOf(false) }
                Box {
                    // One line; a title too long glides continuously one way (Look → Player titles autoscroll).
                    MarqueeText(item.title, C.text, Modifier.fillMaxWidth().combinedClickable(onClick = { openItemFolder(nav, item) }, onLongClick = { songMenu = true }),
                        fontSize = (22 * tk).sp, fontWeight = FontWeight.Bold, center = true, list = "player")
                    SongTitleMenu(item.copy(album = p.metaAlbum.ifEmpty { item.album }), p.metaArtist.ifEmpty { item.artist }, p.length, songMenu) { songMenu = false }
                }
                // Long-press the album: ReplayGain scan of the whole album (its songs in the current folder / queue).
                var rgAlbum by remember { mutableStateOf(false) }
                val albumName = p.metaAlbum.ifEmpty { item.album }
                FitText(listOfNotNull(p.metaArtist.ifEmpty { null }, p.metaAlbum.ifEmpty { null }).joinToString(" · "), color = C.muted, fontSize = (15 * tk).sp, textAlign = TextAlign.Center, minSize = 12.sp,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { if (albumName.isNotBlank()) rgAlbum = true }))
                // Why a song didn't play (shown here too, not only on the video player); the queue goes on by itself.
                if (p.error.isNotEmpty()) Text(p.error, color = C.bad, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                UseNasButton()
                if (rgAlbum) {
                    val songs = p.queue.filter { it.isMusic && it.album.equals(albumName, true) && (it.folderKey == item.folderKey || item.folderKey.isEmpty()) }
                        .ifEmpty { p.queue.filter { it.isMusic && it.album.equals(albumName, true) } }.ifEmpty { listOf(item) }
                    RgScanDialog(nav, { songs }, RgModeSingle, "ReplayGain: $albumName") { rgAlbum = false }
                }
                val badges = listOfNotNull(if (item.trackNo > 0) "Track ${item.trackNo}" else null, p.rgInfo.ifEmpty { null },
                    if (p.sleepUntil > 0) "Sleep ${((p.sleepUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(0)} min"
                    else if (p.sleepSongs == 1) "Sleep after this song" else if (p.sleepSongs > 1) "Sleep after ${p.sleepSongs} songs" else null)
                // Tap the badges (or the title area) for the song's tags and ReplayGain values.
                var showInfo by remember { mutableStateOf(false) }
                FitText(if (badges.isNotEmpty()) badges.joinToString(" · ") + "  ℹ️" else "Tags & ReplayGain ℹ️", color = accent(), fontSize = 12.sp, maxLines = 1, minSize = 10.sp,
                    modifier = Modifier.clickable { showInfo = true }.padding(4.dp))
                if (showInfo) TagInfoDialog(item) { showInfo = false }
            }
            }
            }
            val radio = item.isRadio
            // Skip buttons say where they skip in: the playlist or the folder playback started from.
            val within = if (radio) "station" else when (p.origin.kind) { "playlist" -> "playlist"; "folder", "local" -> "folder"; else -> "queue" }
            var sleepAsk by remember { mutableStateOf("") }
            var sleepNote by remember { mutableStateOf("") }
            val sleepScope = rememberCoroutineScope()
            val rowPosition: @Composable () -> Unit = {
            // Radio: no position to move to — only how long this station has been on.
            if (radio) { if (landscape) RadioListening() } else SeekBlock(item)
            }
            val rowButtons: @Composable () -> Unit = {
            // Sideways radio: how long the station has been on, above its buttons.
            if (radio && landscape) RadioListening()
            // Never overlapping: shrinks the controls if they don't fit the width (large Button size, narrow phone).
            // Clock button on the left in its own column, an equal empty column on the right: the player buttons stay
            // centred, and they get only the width in between (shrunk if needed), so nothing overlaps on any screen.
            Row(Modifier.fillMaxWidth().onGloballyPositioned { ctlBottom = it.boundsInRoot().bottom }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { ClockToggleButton(30); VisButton(nav, 30) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AutoFitRow(spacing = 12.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(maxOf(62 * btnScale(), 46f).dp)) {
                    CtlBtn("⏮", 52) { player.prev() }
                    Text(if (radio) "Prev\nstation" else "Prev in\n$within", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (!radio) Box(Modifier.padding(top = 4.dp)) { CtlBtn("−${st.settings.doubleTapSeekSec}", 44) { player.seekBy(-st.settings.doubleTapSeekSec * 1000L) } }
                Box(Modifier.size((76 * btnScale()).dp).liteShadow(CircleShape).clip(CircleShape).background(accent()).clickable { player.togglePause() }, contentAlignment = Alignment.Center) {
                    Text(if (p.isPlaying) "❚❚" else "▶", color = Color(0xFF1A0E05), fontSize = (26 * btnScale()).sp, fontWeight = FontWeight.Bold)
                }
                if (!radio) Box(Modifier.padding(top = 4.dp)) { CtlBtn("+${st.settings.doubleTapSeekSec}", 44) { player.seekBy(st.settings.doubleTapSeekSec * 1000L) } }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(maxOf(62 * btnScale(), 46f).dp)) {
                    CtlBtn("⏭", 52) { player.next() }
                    Text(if (radio) "Next\nstation" else "Next in\n$within", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            }
            // Sleep: on the right of the player buttons (mirroring the clock / visualisation buttons); what's left under it.
            // Above it, the sine button: scrolls the page to the tone sliders (centred in the free space; as far as
            // the page goes when they're near its end). Shown only when the tone sliders are on.
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (st.settings.toneSliders && "tone" in playerRowOrder(setsP, radio)) Box(Modifier.size(30.dp).liteShadow(CircleShape).clip(CircleShape)
                    .background(Color(0x24FFFFFF)).clickable {
                        val vc = viewBox[0]?.takeIf { it.isAttached }; val tc = toneBox[0]?.takeIf { it.isAttached }
                        if (vc == null || tc == null) ShortNote.show("The tone sliders aren't on this page (Look → player rows)")
                        else {
                            // Positions measured now, inside the page's visible area.
                            val tTop = vc.localPositionOf(tc, androidx.compose.ui.geometry.Offset.Zero).y
                            val tMid = tTop + tc.size.height / 2f
                            // Free space: the visible area below the frozen title block (upright).
                            val top = if (landscape) 0f else titleHpx.toFloat()
                            val delta = tMid - (top + vc.size.height) / 2f
                            val target = (scroll.value + delta).toInt().coerceIn(0, scroll.maxValue)
                            if (kotlin.math.abs(target - scroll.value) < 4) ShortNote.show("Tone sliders are in view")
                            // The cover / clock hiding must not take part in this scroll (it used to stop it half-way
                            // and later scroll the page back up).
                            else sleepScope.launch {
                                ignoreScrollUntil = Long.MAX_VALUE
                                try { scroll.animateScrollTo(target) } finally { ignoreScrollUntil = System.currentTimeMillis() + 300 }
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                    val wave = accent()
                    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                        val path = androidx.compose.ui.graphics.Path()
                        val w = size.width; val h = size.height
                        for (i in 0..40) {
                            val x = w * i / 40f
                            val y = h / 2f - kotlin.math.sin(i / 40f * 2f * Math.PI.toFloat()) * h * 0.32f
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, wave, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                }
                Box {
                    val on = p.sleepUntil > 0 || p.sleepSongs > 0
                    // Same round button as the clock / visualisation ones on the left (symmetry), with the word "Sleep" inside;
                    // the word shrinks to fit the circle so the buttons keep their order, size and spacing. Ringed in the accent when set.
                    val ac = accent()
                    Box(Modifier.size(30.dp).liteShadow(CircleShape).clip(CircleShape).background(if (on) ac.copy(alpha = 0.25f) else Color(0x24FFFFFF))
                        .border(1.dp, if (on) ac else Color.Transparent, CircleShape).clickable { sleepMenu = true }, contentAlignment = Alignment.Center) {
                        FitText("Sleep", color = if (on) ac else C.text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            textAlign = TextAlign.Center, minSize = 5.sp, modifier = Modifier.padding(horizontal = 5.dp))   // clear of the circle's edge
                    }
                    SDropdownMenu(sleepMenu, { sleepMenu = false }) {
                        // Radio: time only (a station has no songs to count).
                        if (!radio) {
                        SMenuItem({ Text("After 1 song (this one)") }, { player.setSleepSongs(1); sleepMenu = false })
                        SMenuItem({ Text("After this album") }, { sleepMenu = false; sleepScope.launch { val n = player.sleepAfterAlbum(); if (n <= 1) sleepNote = "No more songs of this album after this one: stops after this song" } })
                        SMenuItem({ Text("After … songs") }, { sleepMenu = false; sleepAsk = "songs" })
                        }
                        SMenuItem({ Text("30 minutes") }, { player.setSleep(30); sleepMenu = false })
                        SMenuItem({ Text("Custom minutes…") }, { sleepMenu = false; sleepAsk = "minutes" })
                        if (p.sleepUntil > 0 || p.sleepSongs > 0) SMenuItem({ Text("Off") }, { player.setSleep(0); sleepMenu = false })
                    }
                }
                val left = if (p.sleepUntil > 0) "${((p.sleepUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(0)} min"
                    else if (p.sleepSongs > 0) "${p.sleepSongs} song" + (if (p.sleepSongs == 1) "" else "s") else ""
                if (left.isNotEmpty()) Text(left, color = C.muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
            }
            }
            val rowTone: @Composable () -> Unit = {
            // Loudness, treble and preamp, right under the player buttons (unless switched off in Settings).
            if (st.settings.toneSliders) Box(Modifier.fillMaxWidth().onGloballyPositioned { toneBox[0] = it }) { ToneSliders() }
            }
            val rowFrom: @Composable () -> Unit = {
            // Prominent shortcut: where the music is playing from (playlist or Library folder).
            val fromLabel = when {
                radio && p.origin.kind == "radio" -> "Favourites: ${p.queue.size} station" + if (p.queue.size == 1) "" else "s"
                else -> when (p.origin.kind) {
                "playlist" -> "Playlist: " + (st.playlists.firstOrNull { it.id == p.origin.playlistId }?.name ?: "")
                "folder", "local" -> "Folder: " + p.origin.title
                else -> "Open song folder"
            } }
            // Tap: open where it plays from, or load another saved playlist instead.
            var originPick by remember { mutableStateOf(false) }
            Pill("☰  $fromLabel", filled = true, modifier = Modifier.fillMaxWidth()) { originPick = true }
            if (originPick) OriginPickDialog(fromLabel, onOpen = { originPick = false; openOrigin(nav) }, onDismiss = { originPick = false })
            }
            val rowPlayNext: @Composable () -> Unit = {
            // Search all songs; the one picked plays next (inserted after the current song), window closes by itself.
            if (!radio) Pill("♪  Play next…", modifier = Modifier.fillMaxWidth()) { playNextPick = true }
            }
            val rowExtra: @Composable () -> Unit = {
            // Sleep: number input (up to 3 digits) for "After … songs" and "Custom minutes…".
            if (sleepAsk.isNotEmpty()) {
                var num by remember(sleepAsk) { mutableStateOf("") }
                AlertDialog(onDismissRequest = { sleepAsk = "" }, containerColor = C.panel,
                    title = { Text(if (sleepAsk == "songs") "Sleep after how many songs?" else "Sleep after how many minutes?", color = C.text) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DigitField(num, if (sleepAsk == "songs") "Songs (1–999)" else "Minutes (1–999)", 3, Modifier.fillMaxWidth(), openAtStart = true) { num = it }
                            if (sleepAsk == "songs") Text("The playing song counts as 1. If the queue or playlist has fewer songs left, it goes on from its beginning.", color = C.muted, fontSize = 12.sp)
                        }
                    },
                    confirmButton = {
                        STextButton({
                            val n = num.toIntOrNull() ?: 0
                            if (n > 0) { if (sleepAsk == "songs") player.setSleepSongs(n) else player.setSleep(n) }
                            sleepAsk = ""
                        }, enabled = (num.toIntOrNull() ?: 0) > 0) { Text("Set", color = accent()) }
                    },
                    dismissButton = { STextButton({ sleepAsk = "" }) { Text("Cancel") } },
                )
            }
            if (sleepNote.isNotEmpty()) { Text(sleepNote, color = C.muted, fontSize = 12.sp); LaunchedEffect(sleepNote) { kotlinx.coroutines.delay(4000); sleepNote = "" } }
            // RG button state and its question live outside the auto-fitting row (that row is laid out more than once).
            var rgAsk by remember { mutableStateOf<app.vlcinterface.sources.Rg?>(null) }
            val rgScope = rememberCoroutineScope()
            val ctxRg = LocalContext.current
            rgAsk?.let { rg ->
                AlertDialog(onDismissRequest = { rgAsk = null }, containerColor = C.panel,
                    title = { Text("Which ReplayGain?", color = C.text) },
                    text = {
                        Text("This song has both values. Album gain keeps the album's own loud and quiet songs as they are; " +
                            "track gain makes every song equally loud. Your choice is kept for all songs of \"${item.album.ifEmpty { "this album" }}\" until you change it.",
                            color = C.muted, fontSize = 13.sp)
                    },
                    confirmButton = {
                        STextButton({ player.setAlbumRgChoice(item, "album"); rgAsk = null; player.toggleReplayGain() }) {
                            Text("Album  %+.1f dB".format(java.util.Locale.US, rg.albumGain), color = accent()) }
                    },
                    dismissButton = {
                        STextButton({ player.setAlbumRgChoice(item, "track"); rgAsk = null; player.toggleReplayGain() }) {
                            Text("Track  %+.1f dB".format(java.util.Locale.US, rg.trackGain), color = accent()) }
                    },
                )
            }
            AutoFitRow(spacing = 8.dp) {
                if (!radio) Pill(if (st.settings.shuffle) "Random" else "Sequential", selected = st.settings.shuffle) { player.setShuffle(!st.settings.shuffle) }
                if (!radio) Pill("Repeat: " + st.settings.repeat.replaceFirstChar { it.uppercase() }, selected = st.settings.repeat != "off") {
                    val next = when (st.settings.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
                    App.instance.store.update { it.copy(settings = it.settings.copy(repeat = next)) }
                }
                // ReplayGain on / off, applied at once (the song continues from where it is).
                // Mixed list (songs from several albums): track gain, never asked. One album / folder: asked
                // (when the song has both values) or album gain.
                val mixed = remember(p.queue) { player.listIsMixed(p) }
                // Radio: no ReplayGain (stations use the Preamp slider instead) — no RGain button.
                if (!radio) Pill(if (st.settings.rgMode == "off") "RGain: Off" else "RGain: On" + (if (mixed) " · track"
                    else player.albumRgChoice(item)?.let { if (it == "album") " · album" else " · track" } ?: ""),
                    selected = st.settings.rgMode != "off") {
                    if (st.settings.rgMode != "off" || mixed) player.toggleReplayGain()
                    else rgScope.launch {
                        // Both values in the song's tags: ask which one (album gain is then kept for the whole album).
                        val rg = app.vlcinterface.sources.ReplayGain.read(ctxRg, item.uri, item.user, item.password, item.domain, item.smbVersion)
                        if (rg?.trackGain != null && rg.albumGain != null) rgAsk = rg else player.toggleReplayGain()
                    }
                }
            }
            }
            val rowUpNext: @Composable () -> Unit = {
            // Radio: favourite stations and the station search instead of "Up next".
            if (radio) RadioPanel(nav)
            val next0 = if (radio) emptyList() else if (p.index >= 0) p.queue.drop(p.index + 1).take(st.settings.upNextCount) else emptyList()
            // Same number of songs upright and sideways (the side column scrolls if they don't fit).
            val next = next0
            if (next.isNotEmpty()) Column(Modifier.fillMaxWidth().onGloballyPositioned { upNextYpx = it.positionInParent().y.toInt() }) {
                Row(verticalAlignment = Alignment.CenterVertically) { SectionTitle("Up next", Modifier.weight(1f)); 

                    SaveQueueButton(); QueueTimeLeft(item) { nav.go(Screen.Queue) } }
                val upDrag = remember { QueueDrag() }
                next.forEachIndexed { i, n -> androidx.compose.runtime.key(n.uri) {
                    Row(Modifier.then(upDrag.row(n.uri)).fillMaxWidth().combinedClickable(onClick = { player.jumpTo(p.index + 1 + i) }, onLongClick = { upMenu = i })
                        .padding(vertical = if (landscape) 5.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Order number: fixed, outside the gliding text (which is clipped a little after it).
                        Text("${i + 1}.", color = C.muted, maxLines = 1, softWrap = false, modifier = Modifier.width(30.dp))
                        Box(Modifier.width(6.dp))
                        // Artist - Album - Year - Song from the file's tags, gliding continuously in one direction
                        // (speed from the Songs list autoscroll setting).
                        val ctxN = LocalContext.current
                        var line by remember(n.uri) { mutableStateOf(listOf(n.artist, n.album, n.title).filter { it.isNotBlank() }.joinToString(" - ")) }
                        LaunchedEffect(n.uri) { app.vlcinterface.sources.MediaInfo.upNextLine(ctxN.applicationContext, n)?.let { line = it } }
                        MarqueeText(line, C.text, Modifier.weight(1f).padding(end = 4.dp), fontSize = 15.sp)
                        // ≡ on the right, before the length, away from the screen edge (21:04): hold it a moment, then drag.
                        Text("≡", color = C.muted, fontSize = 18.sp, modifier = Modifier.then(upDrag.handle(n.uri,
                            { player.state.value.queue.indexOfFirst { it.uri == n.uri } }, { player.state.value.queue.size }) { a, d ->
                                if (a + d > player.state.value.index) player.moveInQueue(a, d) }).padding(horizontal = 10.dp, vertical = 4.dp))
                        if (n.durationMs > 0) Text(fmtTime(n.durationMs), color = C.muted, fontSize = 13.sp)
                        Box { if (upMenu == i) FileMenu(nav, n, true, { upMenu = -1 }, FileMenuExtras(
                            onMoveUp = if (i > 0) ({ player.moveInQueue(p.index + 1 + i, -1) }) else null,
                            onMoveDown = if (p.index + 2 + i < p.queue.size) ({ player.moveInQueue(p.index + 1 + i, 1) }) else null,
                            removeLabel = "Remove from queue", onRemove = { player.removeFromQueue(p.index + 1 + i) })) }
                    }
                } }
            }
            }
            for (id in ids) when (id) {
                "picture" -> rowPicture()
                "title" -> rowTitle()
                "position" -> rowPosition()
                "buttons" -> rowButtons()
                "tone" -> rowTone()
                "from" -> rowFrom()
                "playnext" -> rowPlayNext()
                "extra" -> rowExtra()
                "upnext" -> rowUpNext()
            }
            if (ids.any { it !in PLAYER_LEFT_ROWS }) Box(Modifier.height(if (landscape) 0.dp else 16.dp))
        }
        val rowOrder = playerRowOrder(setsP, item.isRadio)
        val partA: @Composable ColumnScope.() -> Unit = { rowsOf(rowOrder.filter { it in PLAYER_LEFT_ROWS }) }
        val partB: @Composable ColumnScope.() -> Unit = { rowsOf(rowOrder.filter { it !in PLAYER_LEFT_ROWS }) }
        val allRows: @Composable ColumnScope.() -> Unit = { rowsOf(rowOrder) }
        if (landscape) Row(Modifier.weight(1f).fillMaxWidth()) {
            if (clockShown) {
                // Clock alone on the left, as big as possible; titles, alarms and buttons on the right.
                pinnedClock(Modifier.weight(1f).fillMaxHeight().padding(8.dp))
                Column(Modifier.weight(1f).fillMaxHeight().onSizeChanged { rightHpx = it.height }.onGloballyPositioned { viewTop = it.boundsInRoot().top; viewBox[0] = it }
                    .verticalScroll(scroll).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { allRows() }
            } else {
                // Left half: everything fits without scrolling — the picture shrinks to the room left.
                Column(Modifier.weight(1f).fillMaxHeight().onSizeChanged { leftHpx = it.height }.padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { partA() }
                Column(Modifier.weight(1f).fillMaxHeight().onSizeChanged { rightHpx = it.height }.onGloballyPositioned { viewTop = it.boundsInRoot().top; viewBox[0] = it }
                    .verticalScroll(scroll).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { partB() }
            }
        } else {
            // Upright, clock pinned (Look switch off): it stays above the page; only the page scrolls.
            if (clockShown && !setsP.clockAutoHide) pinnedClock(Modifier.fillMaxWidth().heightIn(max = (conf.screenHeightDp * 0.46f).dp).padding(horizontal = 16.dp, vertical = 4.dp))
            // Otherwise the clock is the top of the page and scrolls away with it (taken away once out of view).
            Column(Modifier.weight(1f).onGloballyPositioned { viewTop = it.boundsInRoot().top; viewBox[0] = it }.verticalScroll(scroll).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(rowGap)) {
                if (clockShown && setsP.clockAutoHide) Box(Modifier.fillMaxWidth().keepWhenHidden(topHidden).onSizeChanged { topHpx = it.height }) {
                    pinnedClock(Modifier.fillMaxWidth().heightIn(max = (conf.screenHeightDp * 0.46f).dp).padding(vertical = 4.dp))
                }
                allRows()
            }
        }
    }
    // Stereo level meters: right edge, full height of the player.
    if (meters) StereoVu(Modifier.align(Alignment.CenterEnd).padding(end = (4 * vuK).dp).fillMaxHeight().width((15 * vuK).dp))
    // Floating play / pause: only while the player's buttons are scrolled out of view (under the title block / off the top).
    val ctlHidden = scroll.value > 0 && ctlBottom < viewTop + (if (landscape) 0 else titleHpx) + 2f
    // Look → "Floating play / pause button": always (default), only when the buttons scroll away, or off.
    val fMode = setsP.floatBtnMode
    if (fMode == "always" || (fMode == "scroll" && ctlHidden)) FloatingPlayButton(p.isPlaying) { player.togglePause() }
    }
    }
}

/**
 * Two vertical meters (left, right): 6 dp wide, 3 dp apart. Fast rise, slow fall;
 * each has a square peak marker that holds ~1 s, then falls slowly.
 */
/** Progress bar + elapsed / remaining time: follows the exact play position on its own. */
@Composable
private fun SeekBlock(item: PlayItem) {
    val player = App.instance.player
    val p by player.state.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        var drag by remember { mutableStateOf<Float?>(null) }
        val len = if (item.endMs > 0) item.endMs - item.startMs else p.length
        val base = if (item.startMs > 0) item.startMs else 0
        val pos = (p.time - base).coerceAtLeast(0)
        // Touch / slide: a bubble over the thumb shows the time it points at, following the finger; the song keeps
        // playing and only jumps there when the finger is lifted.
        // A short tap (no sliding, quickly released) does not jump: it opens the "Jump to m:ss" window instead.
        var moved by remember { mutableStateOf(false) }
        var held by remember { mutableStateOf(false) }
        var tapped by remember { mutableStateOf(false) }
        var askTime by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                moved = false; held = false; tapped = false
                val t0 = down.uptimeMillis
                while (true) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (c.uptimeMillis - t0 > 300) held = true
                    if (!c.pressed) { tapped = !moved && c.uptimeMillis - t0 < 300; break }
                }
            }
        }) {
            val bw = maxWidth
            // Slim: thin track and small knob (like the settings sliders); still dragged anywhere on its row.
            val sCols = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = C.line)
            Slider(drag ?: if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f, { drag = it },
                modifier = Modifier.height(26.dp),
                onValueChangeFinished = {
                    if (tapped) { if (len > 0) askTime = true } else drag?.let { player.seekFraction(it) }
                    drag = null
                }, enabled = len > 0 || p.item != null,
                colors = sCols,
                thumb = { KnobThumb(Color.White) },
                track = { st -> SliderDefaults.Track(sliderState = st, modifier = Modifier.height(4.dp), colors = sCols, enabled = len > 0 || p.item != null,
                    drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) })
            // The bubble shows once the finger slides or stays down (not for a short tap).
            drag?.takeIf { moved || held }?.let { f ->
                val target = (f * len).toLong()
                val bubbleW = 190.dp
                val thumbX = 10.dp + (bw - 20.dp) * f
                androidx.compose.ui.window.Popup(alignment = Alignment.TopStart,
                    offset = with(androidx.compose.ui.platform.LocalDensity.current) {
                        androidx.compose.ui.unit.IntOffset((thumbX - bubbleW / 2).coerceIn(0.dp, (bw - bubbleW).coerceAtLeast(0.dp)).roundToPx(), (-96).dp.roundToPx())
                    }) {
                    Column(Modifier.width(bubbleW).clip(RoundedCornerShape(14.dp)).background(C.popup).border(2.dp, accent(), RoundedCornerShape(14.dp))
                        .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Always the time from the start of the song; big, well above the finger.
                        Text(fmtTime(target), color = C.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(Modifier.offset(y = (-3).dp)) { Text(fmtTime(pos), color = C.muted, fontSize = 12.sp, lineHeight = 13.sp); Grow(); Text("-" + fmtTime((len - pos).coerceAtLeast(0)), color = C.muted, fontSize = 12.sp, lineHeight = 13.sp) }
        if (askTime) JumpToTimeDialog(len, onDismiss = { askTime = false }) { ms -> askTime = false; player.seekFraction(ms.toFloat() / len) }
    }
}

/**
 * "Jump to" within the song: two small boxes, minutes and seconds. Digits only, no leading 0 on the minutes;
 * a space / . / , / - / : or the keyboard's Next moves to the seconds box. The 2nd seconds digit jumps at once;
 * a time outside the song just closes the window (no error shown).
 */
private object LastJump { var min = ""; var sec = "" }

@Composable
private fun JumpToTimeDialog(len: Long, onDismiss: () -> Unit, onJump0: (Long) -> Unit) {
    // Opens with the last time jumped to (▶ jumps there again); typing anything clears it first.
    var min by remember { mutableStateOf(LastJump.min) }
    var sec by remember { mutableStateOf(LastJump.sec) }
    var fresh by remember { mutableStateOf(LastJump.min.isNotEmpty() || LastJump.sec.isNotEmpty()) }
    fun go() {
        val m = min.toIntOrNull() ?: 0; val sc = sec.toIntOrNull() ?: 0
        val target = (m * 60L + sc) * 1000
        if ((min.isEmpty() && sec.isEmpty()) || sc > 59 || target > len) { onDismiss(); return }
        LastJump.min = min; LastJump.sec = sec
        onJump0(target)
    }
    /** First keystroke on a prefilled window: the old time goes, only the new character stays. */
    fun typed(old: String, t: String): String {
        if (!fresh) return t
        fresh = false; min = ""; sec = ""
        return if (t.length > old.length) t.last().toString() else ""
    }
    // Typed with the app's own digit pad (17:27): min (up to 3 digits) : sec (2); Android's keyboard stays hidden.
    var atSec by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.panel,
        title = { Text("Jump to", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable
                    fun cell(v: String, ph: String, on: Boolean, pick: () -> Unit) =
                        Box(Modifier.width(76.dp).clip(RoundedCornerShape(8.dp)).border(if (on) 2.dp else 1.dp, if (on) accent() else C.line, RoundedCornerShape(8.dp))
                            .clickable(onClick = pick).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            if (v.isEmpty()) Text(ph, color = C.muted, fontSize = 20.sp) else Text(v, color = C.text, fontSize = 26.sp)
                        }
                    cell(min, "min", !atSec) { atSec = false }
                    Text(":", color = C.text, fontSize = 26.sp)
                    cell(sec, "sec", atSec) { atSec = true }
                    // Jump to the time shown (e.g. the same place again).
                    val acc = accent()
                    androidx.compose.foundation.Canvas(Modifier.padding(start = 6.dp).size(44.dp).clip(RoundedCornerShape(8.dp)).clickable { go() }) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.23f, size.height * 0.14f)
                            lineTo(size.width * 0.86f, size.height * 0.5f)
                            lineTo(size.width * 0.23f, size.height * 0.86f)
                            close()
                        }
                        drawPath(path, acc)
                    }
                }
                DigitPad({ k ->
                    when (k) {
                        "⌫" -> { fresh = false; if (atSec) sec = sec.dropLast(1) else min = min.dropLast(1) }
                        "→" -> atSec = !atSec
                        else -> if (!atSec) {
                            val t = typed(min, min + k)
                            min = t.filter { it.isDigit() }.trimStart('0').take(3)
                            if (min.length == 3) atSec = true
                        } else {
                            val t = typed(sec, sec + k)
                            sec = t.filter { it.isDigit() }.take(2)
                            if (sec.length == 2) go()
                        }
                    }
                }, lastKey = "→", modifier = Modifier.padding(top = 4.dp))
                Text("0:00 – ${fmtTime(len)}", color = C.muted, fontSize = 12.sp)
            }
        },
        confirmButton = { STextButton(onDismiss) { Text("Cancel") } },
    )
}

/** "Queue › 1h 23m 45s": time still to play in what's playing now (rest of this song + all after it), every second. */
@Composable
internal fun QueueTimeLeft(item: PlayItem, onClick: () -> Unit) {
    val full = App.instance.player.state.collectAsState()
    val p by remember { derivedStateOf { full.value } }
    val after = remember(p.queue, p.index) { p.queue.drop(p.index + 1).sumOf { q -> (if (q.endMs > 0) q.endMs - q.startMs else q.durationMs).coerceAtLeast(0) } }
    val curLen = if (item.endMs > 0) item.endMs - item.startMs else p.length
    val curLeft = (curLen - (p.time - maxOf(item.startMs, 0L))).coerceAtLeast(0)
    val tl = (curLeft + after) / 1000
    // e.g. 1h 23m 45s · 23m 05s · 45s
    val leftTxt = when {
        tl >= 3600 -> "%dh %02dm %02ds".format(tl / 3600, tl / 60 % 60, tl % 60)
        tl >= 60 -> "%dm %02ds".format(tl / 60, tl % 60)
        else -> "${tl}s"
    }
    Text("Queue ›  $leftTxt", color = accent(), fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClick).padding(8.dp))
}

@Composable
fun StereoVu(modifier: Modifier) {
    var lvL by remember { mutableFloatStateOf(0f) }
    var lvR by remember { mutableFloatStateOf(0f) }
    var pkL by remember { mutableFloatStateOf(0f) }
    var pkR by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val buf = FloatArray(2)
        var last = 0L; var holdL = 0L; var holdR = 0L
        fun meter(p: Float): Float = if (p <= 0.00001f) 0f else ((20f * log10(p) + 48f) / 48f).coerceIn(0f, 1f) // −48…0 dBFS
        while (true) {
            // Paused / silent and the bars have fallen: check 4 times a second instead of every screen frame.
            if (!App.instance.player.state.value.isPlaying && lvL == 0f && lvR == 0f && pkL == 0f && pkR == 0f) { kotlinx.coroutines.delay(250); last = 0L; continue }
            withFrameNanos { t ->
            val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.1f); last = t
            NativeAudio.peaks(buf)
            val nl = meter(buf[0]); val nr = meter(buf[1])
            lvL = if (nl > lvL) nl else maxOf(nl, lvL - dt * 1.2f)
            lvR = if (nr > lvR) nr else maxOf(nr, lvR - dt * 1.2f)
            if (lvL >= pkL) { pkL = lvL; holdL = t } else if (t - holdL > 1_000_000_000L) pkL = maxOf(lvL, pkL - dt * 0.35f)
            if (lvR >= pkR) { pkR = lvR; holdR = t } else if (t - holdR > 1_000_000_000L) pkR = maxOf(lvR, pkR - dt * 0.35f)
            }
        }
    }
    Canvas(modifier) {
        // Bars and gap follow the meter's width (15 dp upright; narrower sideways), so they shrink in proportion.
        val w = size.width * 6f / 15f; val gap = size.width * 3f / 15f; val h = size.height
        // Own fixed colours (not changed by the low-battery red).
        val zones = listOf(0f to 0.7f to Color(0xFF4CC26A), 0.7f to 0.9f to Color(0xFFE8B23A), 0.9f to 1f to Color(0xFFE5534B))
        listOf(lvL to pkL, lvR to pkR).forEachIndexed { i, (lv, pk) ->
            val x = size.width - (2 - i) * w - (1 - i) * gap
            drawRect(Color(0xFF1A1D23), Offset(x, 0f), Size(w, h))
            zones.forEach { (range, col) ->
                val top = minOf(lv, range.second)
                if (top > range.first) drawRect(col, Offset(x, h * (1f - top)), Size(w, h * (top - range.first)))
            }
            if (pk > 0.01f) drawRect(if (pk >= 0.9f) Color(0xFFE5534B) else Color.White, Offset(x, (h * (1f - pk)).coerceAtMost(h - w)), Size(w, w))
        }
    }
}

@Composable
private fun CtlBtn(t: String, size: Int, onClick: () -> Unit) {
    val k = btnScale()
    Box(Modifier.size((size * k).dp).liteShadow(CircleShape).clip(CircleShape).background(if (LocalInPlayer.current) Color(0xFF303035) else C.card2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(t, color = C.text, fontSize = (16 * k).sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- Queue ----------------

@Composable
fun QueueScreen(nav: Navigator) {
    val player = App.instance.player
    val p by player.state.collectAsState()
    var saveName by remember { mutableStateOf<String?>(null) }
    var qMenu by remember { mutableStateOf(-1) }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Queue", nav, "${p.queue.size} items") {
            if (p.queue.isNotEmpty()) STextButton({ saveName = "" }) { Text("Save as playlist", color = accent()) }
        }
        val st by App.instance.store.state.collectAsState()
        OrderSwitch(st.settings.shuffle, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { player.setShuffle(it) }
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (p.index - 2).coerceAtLeast(0))
        val qDrag = remember { QueueDrag() }
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 16.dp), state = state) {
            if (p.queue.size > 20) stickyHeader { JumpToBox(state, p.queue.map { listOf(it.title, it.artist).joinToString(" ") }, offset = 1) }
            itemsIndexed(p.queue, key = { _, q -> q.uri }) { i, q ->
                Row(Modifier.then(qDrag.row(q.uri)).fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(C.bg)
                    .background(if (i == p.index) accent().copy(alpha = 0.12f) else Color.Transparent)
                    .combinedClickable(onClick = { player.jumpTo(i) }, onLongClick = { qMenu = i }).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = if (i == p.index) accent() else C.muted, modifier = Modifier.width(36.dp), fontSize = 13.sp)
                    Column(Modifier.weight(1f)) {
                        MarqueeText(q.title, if (i == p.index) accent() else C.text, Modifier.fillMaxWidth(), fontSize = 16.sp, list = "upnext")
                        if (q.artist.isNotEmpty()) Text(q.artist, color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    // ≡ on the right, before the length, away from the screen edge (21:04): hold it a moment, then drag,
                    // one place per half row.
                    Text("≡", color = C.muted, fontSize = 20.sp, modifier = Modifier.then(qDrag.handle(q.uri,
                        { player.state.value.queue.indexOfFirst { it.uri == q.uri } }, { player.state.value.queue.size }) { a, d -> player.moveInQueue(a, d) })
                        .padding(horizontal = 10.dp, vertical = 10.dp))
                    if (q.durationMs > 0) Text(fmtTime(q.durationMs), color = C.muted, fontSize = 13.sp)
                    if (i != p.index) SymButton({ player.removeFromQueue(i) }) { Text("✕", color = C.muted) }
                    androidx.compose.foundation.layout.Box {
                        FileMenu(nav, q, qMenu == i, { qMenu = -1 }, FileMenuExtras(
                            onMoveUp = if (i > 0) ({ player.moveInQueue(i, -1) }) else null,
                            onMoveDown = if (i < p.queue.size - 1) ({ player.moveInQueue(i, 1) }) else null,
                            removeLabel = "Remove from queue",
                            onRemove = if (i != p.index) ({ player.removeFromQueue(i) }) else null))
                    }
                }
            }
        }
        BottomBar(nav, current = 1)
    }
    saveName?.let { name ->
        AlertDialog(onDismissRequest = { saveName = null }, title = { Text("Save queue as playlist") },
            text = { OutlinedTextField(name, { saveName = it }, singleLine = true, placeholder = { Text("Playlist name") }) },
            confirmButton = { STextButton({
                val items = p.queue.map { MusicLibrary.toPlEntry(it) }
                App.instance.store.update { s -> s.copy(playlists = s.playlists + Playlist(name = name.ifBlank { "Queue" }, items = items)) }
                saveName = null
            }) { Text("Save") } },
            dismissButton = { STextButton({ saveName = null }) { Text("Cancel") } })
    }
}

// ---------------- Playlists ----------------

@Composable
fun PlaylistsScreen(nav: Navigator, openOutput: () -> Unit) {
    val ctx = LocalContext.current
    val store = App.instance.store
    val st by store.state.collectAsState()
    val p by App.instance.player.state.collectAsState()
    var newName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var plMenu by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { ctx.contentResolver.openInputStream(uri)?.use { Cue.decode(it.readBytes()) } ?: "" } catch (_: Exception) { "" }
        val entries = PlaylistFiles.parse(text).map { (t, u) -> PlEntry(u, t) }
        if (entries.isEmpty()) status = "No playable links in that file (relative paths need the file to be on a saved server)"
        else {
            store.update { s -> s.copy(playlists = s.playlists + Playlist(name = Urls.nameOf(uri.toString()).substringBeforeLast('.'), items = entries)) }
            status = "Imported ${entries.size} items"
        }
    }
    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Playlists", openOutput, nav) { HomeButton(nav) }
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("New playlist", filled = true) { newName = "" }
                Pill("Import M3U/PLS") { importer.launch(arrayOf("*/*")) }
            }
            if (status.isNotEmpty()) Text(status, color = C.muted, fontSize = 13.sp)
            if (p.queue.isNotEmpty()) {
                SectionTitle("Now playing")
                CardBox(Modifier.clickable { nav.go(Screen.Queue) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Queue", color = C.text, fontWeight = FontWeight.SemiBold)
                            Text("${p.queue.size} items · playing ${p.index + 1}", color = C.muted, fontSize = 13.sp)
                        }
                        Text("›", color = C.muted, fontSize = 20.sp)
                    }
                }
            }
            SectionTitle("Saved playlists")
            if (st.playlists.isEmpty()) Text("No playlists yet. Long-press a song or folder in the Library → Add to playlist, or create one here.", color = C.muted, fontSize = 14.sp)
            st.playlists.forEach { pl ->
                // Tap opens it; long-press: Play, Random, Load (ready in the queue, not started), Add to queue, Open.
                CardBox(Modifier.combinedClickable(onClick = { nav.go(Screen.PlaylistDetail(pl.id)) }, onLongClick = { plMenu = pl.id })) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            val total = pl.items.sumOf { it.durationMs }
                            Text("${pl.items.size} items" + if (total > 0) " · ${fmtTime(total)}" else "", color = C.muted, fontSize = 13.sp)
                        }
                        Pill("Play", filled = true) { playPlaylist(nav, pl, 0, random = false) }
                        Pill("Random", modifier = Modifier.padding(start = 6.dp)) { playPlaylist(nav, pl, pl.items.indices.random(), random = true) }
                        Box {
                            SDropdownMenu(plMenu == pl.id, { plMenu = null }) {
                                SMenuItem({ Text("▶  Play") }, { plMenu = null; playPlaylist(nav, pl, 0, random = false) })
                                SMenuItem({ Text("Play in random order") }, { plMenu = null; if (pl.items.isNotEmpty()) playPlaylist(nav, pl, pl.items.indices.random(), random = true) })
                                SMenuItem({ Text("Load playlist (ready, not playing)") }, {
                                    plMenu = null
                                    if (pl.items.isNotEmpty()) {
                                        App.instance.player.loadQueue(pl.items.map { MusicLibrary.fromPlEntry(it) }, Origin("playlist", playlistId = pl.id, title = pl.name))
                                        nav.go(Screen.Music)
                                    }
                                })
                                SMenuItem({ Text("Add to queue") }, {
                                    plMenu = null
                                    val n = App.instance.player.addToQueue(pl.items.map { MusicLibrary.fromPlEntry(it) })
                                    android.widget.Toast.makeText(ctx, "$n added to the queue", android.widget.Toast.LENGTH_SHORT).show()
                                })
                                SMenuItem({ Text("Open") }, { plMenu = null; nav.go(Screen.PlaylistDetail(pl.id)) })
                            }
                        }
                    }
                }
            }
            Box(Modifier.height(16.dp))
        }
        BottomBar(nav, current = 1)
    }
    newName?.let { name ->
        AlertDialog(onDismissRequest = { newName = null }, title = { Text("New playlist") },
            text = { OutlinedTextField(name, { newName = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { STextButton({ store.update { s -> s.copy(playlists = s.playlists + Playlist(name = name.ifBlank { "Playlist" })) }; newName = null }) { Text("Create") } },
            dismissButton = { STextButton({ newName = null }) { Text("Cancel") } })
    }
}

/** [random]: true = random order, false = in order, null = keep the current mode (tap on a song). */
fun playPlaylist(nav: Navigator, pl: Playlist, index: Int, random: Boolean? = null) {
    if (pl.items.isEmpty()) return
    val items = pl.items.map { MusicLibrary.fromPlEntry(it) }
    App.instance.player.playQueue(items, index, Origin("playlist", playlistId = pl.id, title = pl.name), random = random)
    nav.go(items[index.coerceIn(0, items.size - 1)].let { if (it.isMusic || it.isRadio) Screen.Music else Screen.Player })
}

/** "In order" / "Random" switch for the queue. */
@Composable
fun OrderSwitch(random: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("In order", selected = !random) { onChange(false) }
        Pill("Random", selected = random) { onChange(true) }
    }
}

/** "Play in order" / "Play random" buttons for a list (playlist or Library folder). */
@Composable
fun PlayListButtons(modifier: Modifier = Modifier, onPlay: (random: Boolean) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("▶  Play in order", filled = true) { onPlay(false) }
        Pill("🔀  Play random") { onPlay(true) }
    }
}

@Composable
fun PlaylistDetailScreen(nav: Navigator, d: Screen.PlaylistDetail) {
    val ctx = LocalContext.current
    val store = App.instance.store
    val st by store.state.collectAsState()
    val pl = st.playlists.firstOrNull { it.id == d.id }
    val playing by App.instance.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    if (pl == null) { LaunchedEffect(Unit) { nav.back() }; return }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sendNas by remember { mutableStateOf(false) }
    var cueChoice by remember { mutableStateOf(false) }
    var cueResult by remember { mutableStateOf<CueResult?>(null) }
    // Multi-select (by position): long-press → Select…, then taps tick/untick.
    var selected by remember(d.id) { mutableStateOf(setOf<Int>()) }
    var rgItems by remember { mutableStateOf<List<PlayItem>?>(null) }
    var infoPl by remember { mutableStateOf<PlayItem?>(null) }
    var itemMenu by remember { mutableIntStateOf(-1) }
    fun update(f: (Playlist) -> Playlist) = store.update { s -> s.copy(playlists = s.playlists.map { if (it.id == pl.id) f(it) else it }) }
    val phoneCue = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { cueResult = Cue.fromPhone(ctx, uri) }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader(pl.name, nav, "${pl.items.size} items") {
            Pill("+ Add") { PickBasket.reset(pl.id); nav.go(Screen.PickMedia(pl.id)) }
            Box {
                SymButton({ menu = true }) { SymIcon(Icons.Filled.MoreVert, "Playlist options", tint = C.text) }
                SDropdownMenu(menu, { menu = false }) {
                    SMenuItem({ Text("Add files or folders…") }, { PickBasket.reset(pl.id); nav.go(Screen.PickMedia(pl.id)); menu = false })
                    SMenuItem({ Text("Add from .cue file…") }, { cueChoice = true; menu = false })
                    // No questions: per-song gain, written into the files, only for songs with no ReplayGain record at all.
                    // Runs in the background; a pop-up confirms what was done when it ends.
                    SMenuItem({ Text("ReplayGain scan — songs without RG") }, {
                        menu = false
                        val its = pl.items.map { MusicLibrary.fromPlEntry(it) }.filter { it.isMusic }.distinctBy { it.uri }
                        if (its.isEmpty())
                            android.widget.Toast.makeText(ctx, "No songs in this playlist", android.widget.Toast.LENGTH_SHORT).show()
                        else {
                            val busy = app.vlcinterface.sources.RgScanner.state.value.running
                            app.vlcinterface.sources.RgScanner.start(ctx.applicationContext, its, app.vlcinterface.sources.RgMode.TRACK,
                                app.vlcinterface.sources.RgTarget.FILES, rescanTagged = false, onlyWithoutRg = true, confirmTitle = pl.name)
                            android.widget.Toast.makeText(ctx, if (busy) "Queued: ReplayGain scan of ${its.size} songs" else "ReplayGain scan started: ${its.size} songs, those without RG get track gain",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    })
                    SMenuItem({ Text("Send to NAS…") }, { sendNas = true; menu = false })
                    SMenuItem({ Text("Share") }, {
                        val text = PlaylistFiles.toM3u8(pl.items, "")
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/x-mpegurl")
                            .putExtra(Intent.EXTRA_SUBJECT, "${pl.name}.m3u8").putExtra(Intent.EXTRA_TEXT, text), "Share playlist"))
                        menu = false
                    })
                    SMenuItem({ Text("Rename") }, { rename = pl.name; menu = false })
                    SMenuItem({ Text("Delete playlist", color = C.bad) }, { confirmDelete = true; menu = false })
                }
            }
        }
        if (selected.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.card2)
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${selected.size} selected", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Pill("All") { selected = pl.items.indices.toSet() }
            Pill("RG scan", filled = true) { rgItems = selected.sorted().mapNotNull { pl.items.getOrNull(it) }.map { MusicLibrary.fromPlEntry(it) } }
            Pill("Remove") { val drop = selected; update { p -> p.copy(items = p.items.filterIndexed { j, _ -> j !in drop }) }; selected = emptySet() }
            Text("✕", color = C.muted, fontSize = 18.sp, modifier = Modifier.clickable { selected = emptySet() }.padding(6.dp))
        } else if (pl.items.isNotEmpty()) PlayListButtons(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { random ->
            playPlaylist(nav, pl, if (random) pl.items.indices.random() else 0, random = random)
        }
        val state = rememberLazyListState()
        LaunchedEffect(d.highlight) {
            val i = pl.items.indexOfFirst { it.uri == d.highlight }
            if (i >= 0) state.scrollToItem((i - 2).coerceAtLeast(0))
        }
        if (pl.items.isEmpty()) Text("Empty. Long-press songs or folders in the Library → Add to playlist, or add a .cue sheet from the menu.",
            color = C.muted, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 16.dp), state = state) {
            if (pl.items.size > 20) stickyHeader { JumpToBox(state, pl.items.map { listOf(it.title, it.artist).joinToString(" ") }, offset = 1) }
            itemsIndexed(pl.items) { i, e ->
                val cur = playing.origin.playlistId == pl.id && playing.index == i
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (i in selected) accent().copy(alpha = 0.18f) else if (cur || e.uri == d.highlight) accent().copy(alpha = 0.12f) else Color.Transparent)
                    .combinedClickable(onClick = { if (selected.isNotEmpty()) selected = if (i in selected) selected - i else selected + i else playPlaylist(nav, pl, i) },
                        onLongClick = { if (selected.isNotEmpty()) selected = if (i in selected) selected - i else selected + i else itemMenu = i }).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i in selected) "✓" else "${i + 1}", color = if (cur || i in selected) accent() else C.muted, modifier = Modifier.width(36.dp), fontSize = 13.sp)
                    Column(Modifier.weight(1f)) {
                        MarqueeText(e.title, if (cur) accent() else C.text, Modifier.fillMaxWidth(), fontSize = 16.sp, list = "songs")
                        val sub = listOfNotNull(e.artist.ifEmpty { null }, e.album.ifEmpty { null }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(if (e.durationMs > 0) fmtTime(e.durationMs) else "–:––", color = C.muted, fontSize = 13.sp)
                    Box {
                        SymButton({ itemMenu = i }) { SymIcon(Icons.Filled.MoreVert, "Item options", tint = C.muted) }
                        // Same file menu as everywhere; here also Select, Move up / down and Remove from playlist.
                        FileMenu(nav, MusicLibrary.fromPlEntry(e), itemMenu == i, { itemMenu = -1 }, FileMenuExtras(
                            onSelect = { selected = setOf(i) },
                            onMoveUp = if (i > 0) ({ update { p -> p.copy(items = p.items.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) } }) else null,
                            onMoveDown = if (i < pl.items.size - 1) ({ update { p -> p.copy(items = p.items.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) } }) else null,
                            removeLabel = "Remove from playlist",
                            onRemove = { update { p -> p.copy(items = p.items.filterIndexed { j, _ -> j != i }) } }))
                    }
                }
            }
        }
        BottomBar(nav, current = 1)
    }

    rgItems?.let { its -> RgScanDialog(nav, { its }) { rgItems = null; selected = emptySet() } }
    infoPl?.let { it0 -> TagInfoDialog(it0) { infoPl = null } }
    rename?.let { n ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename playlist") },
            text = { OutlinedTextField(n, { rename = it }, singleLine = true) },
            confirmButton = { STextButton({ update { it.copy(name = n.ifBlank { it.name }) }; rename = null }) { Text("Save") } },
            dismissButton = { STextButton({ rename = null }) { Text("Cancel") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete \"${pl.name}\"?") },
        text = { Text("Only the playlist is deleted; the music and video files stay where they are.") },
        confirmButton = { STextButton({ store.update { s -> s.copy(playlists = s.playlists.filterNot { it.id == pl.id }) }; confirmDelete = false; nav.back() }) { Text("Delete", color = C.bad) } },
        dismissButton = { STextButton({ confirmDelete = false }) { Text("Cancel") } })
    if (cueChoice) AlertDialog(onDismissRequest = { cueChoice = false }, title = { Text("Add from .cue file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Where is the .cue file?", color = C.muted)
                Text("On this phone", color = C.text, modifier = Modifier.fillMaxWidth().clickable { cueChoice = false; phoneCue.launch(arrayOf("*/*")) }.padding(10.dp))
                st.sources.filter { it.proto in setOf("smb", "webdav", "http", "sftp", "ftp", "ftps", "nfs") }.forEach { s ->
                    Text(s.name.ifEmpty { s.host }, color = C.text, modifier = Modifier.fillMaxWidth().clickable {
                        cueChoice = false; nav.go(Screen.PickCue(pl.id, s.id, Urls.forSource(s)))
                    }.padding(10.dp))
                }
            }
        },
        confirmButton = {}, dismissButton = { STextButton({ cueChoice = false }) { Text("Cancel") } })
    cueResult?.let { r -> CueSummaryDialog(r, pl) { cueResult = null } }
    if (sendNas) SaveToNasDialog("Send playlist to NAS", "${pl.name}.m3u8", "playlist", { folder -> PlaylistFiles.toM3u8(pl.items, folder).toByteArray() }) { sendNas = false }
}

@Composable
fun CueSummaryDialog(r: CueResult, pl: Playlist, onDone: () -> Unit) {
    val store = App.instance.store
    AlertDialog(onDismissRequest = onDone, title = { Text("Add from .cue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.album.isNotEmpty()) Text(r.album, color = C.text, fontWeight = FontWeight.SemiBold)
                Text("${r.items.size} track${if (r.items.size == 1) "" else "s"} with a valid file", color = C.ok)
                if (r.ignored.isNotEmpty()) Text("${r.ignored.size} ignored — file not found: " + r.ignored.joinToString(", ") { "'$it'" }, color = C.bad, fontSize = 13.sp)
            }
        },
        confirmButton = {
            STextButton({
                store.update { s -> s.copy(playlists = s.playlists.map { p ->
                    if (p.id != pl.id) p else {
                        val have = p.items.map { it.key }.toHashSet()
                        p.copy(items = p.items + r.items.map { MusicLibrary.toPlEntry(it) }.filter { have.add(it.key) })
                    }
                }) }
                onDone()
            }, enabled = r.items.isNotEmpty()) { Text("Add ${r.items.size}") }
        },
        dismissButton = { STextButton(onDone) { Text("Cancel") } })
}

/** Browse a saved source to pick a .cue file (the Library itself never shows .cue files). */
@Composable
fun PickCueScreen(nav: Navigator, p: Screen.PickCue) {
    val st by App.instance.store.state.collectAsState()
    val src = st.sources.firstOrNull { it.id == p.sourceId }
    val pl = st.playlists.firstOrNull { it.id == p.playlistId }
    var entries by remember(p.uri) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(p.uri) { mutableStateOf("") }
    var result by remember { mutableStateOf<CueResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(p.uri) {
        if (src == null) { error = "Source not found"; return@LaunchedEffect }
        Browse.listAll(src, p.uri).onSuccess { l -> entries = l.filter { it.isDir || it.name.ext() == "cue" } }.onFailure { error = it.message ?: "Could not open" }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Pick a .cue file", nav, p.uri)
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp)) {
            if (error.isNotEmpty()) Text(error, color = C.bad)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = accent(), strokeWidth = 2.dp); Text("  Checking the files in the .cue…", color = C.muted) }
            entries?.forEach { e ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                    if (e.isDir) nav.go(Screen.PickCue(p.playlistId, p.sourceId, e.uri))
                    else if (src != null) scope.launch {
                        busy = true
                        val bytes = readBytes(src, e.uri)
                        result = if (bytes != null) Cue.fromSource(src, e.uri, bytes) else CueResult(emptyList(), listOf(e.name), "")
                        ServerLog.log(src.host, "cue", src.proto.uppercase(), src.user, result!!.items.isNotEmpty(),
                            "${e.name}: ${result!!.items.size} tracks ok, ${result!!.ignored.size} ignored", result!!.ignored.joinToString(", "))
                        busy = false
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.isDir) "▤" else "≡", color = accent(), modifier = Modifier.padding(end = 14.dp))
                    Text(e.name, color = C.text)
                }
                Divider()
            }
        }
    }
    val r = result
    if (r != null && pl != null) CueSummaryDialog(r, pl) { result = null; nav.back() }
}

private suspend fun readBytes(src: Source, uri: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        when {
            uri.startsWith("smb://") -> SmbFile(app.vlcinterface.sources.VpnLink.route(android.net.Uri.decode(uri)), SmbProbe.fileContext(src.smbVersion, src.user, src.password, src.domain)).openInputStream().use { it.readBytes() }
            uri.startsWith("http") -> {
                val req = okhttp3.Request.Builder().url(uri).apply { if (src.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(src.user, src.password)) }.build()
                app.vlcinterface.sources.Net.http.newCall(req).execute().use { it.body?.bytes() }
            }
            else -> null
        }
    } catch (_: Exception) { null }
}

/** Pick a writable saved source + folder, then write a small file there (asks before overwriting). */
@Composable
fun SaveToNasDialog(title: String, defaultName: String, what: String, content: (String) -> ByteArray, onDone: () -> Unit) {
    val st by App.instance.store.state.collectAsState()
    val writable = st.sources.filter { NasWriter.canWrite(it) }
    var src by remember { mutableStateOf(writable.firstOrNull()) }
    var folder by remember(src) { mutableStateOf(src?.path ?: "") }
    var name by remember { mutableStateOf(defaultName) }
    var status by remember { mutableStateOf("") }
    var askReplace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun doWrite(finalName: String) {
        val s = src ?: return
        scope.launch {
            status = "Saving…"
            val folderUrl = Urls.forSource(s.copy(path = ""), folder)
            NasWriter.write(s, folderUrl, finalName, content(folderUrl), what)
                .onSuccess { status = "Saved $finalName"; onDone() }.onFailure { status = it.message ?: "Could not save" }
        }
    }
    AlertDialog(onDismissRequest = onDone, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (writable.isEmpty()) Text("No saved SMB or WebDAV source to write to. Add your NAS in Library → LAN first.", color = C.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    writable.forEach { s -> Pill(s.name.ifEmpty { s.host }, selected = src?.id == s.id) { src = s } }
                }
                Field("Folder on the server", folder) { folder = it }
                Field("File name", name) { name = it }
                if (status.isNotEmpty()) Text(status, color = C.muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            STextButton({
                val s = src ?: return@STextButton
                scope.launch {
                    if (NasWriter.exists(s, Urls.forSource(s.copy(path = ""), folder), name)) askReplace = true else doWrite(name)
                }
            }, enabled = src != null && name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { STextButton(onDone) { Text("Cancel") } })
    if (askReplace) AlertDialog(onDismissRequest = { askReplace = false }, title = { Text("$name already exists") },
        text = { Text("Replace it, or keep both?") },
        confirmButton = { STextButton({ askReplace = false; doWrite(name) }) { Text("Replace") } },
        dismissButton = { STextButton({
            askReplace = false
            val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
            doWrite("$base (2)" + if (ext.isNotEmpty()) ".$ext" else "")
        }) { Text("Keep both") } })
}

// ---------------- Network logs ----------------

@Composable
fun LogsScreen(nav: Navigator) {
    val v by ServerLog.version.collectAsState()
    val saved by App.instance.store.state.collectAsState()
    // Servers only: the app's own logs (deleted files, ReplayGain, sound engine, player) have their own pages in Settings.
    val servers = remember(v, saved.sources) { ServerLog.servers(saved.sources.filter { it.proto != "upnp" }.map { it.host to it.proto })
        .filter { it.key !in setOf(ServerLog.DELETED, ServerLog.REPLAYGAIN, "sound-engine", "player") } }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Network logs", nav, "One page per server")
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (servers.isEmpty()) Text("No network activity logged yet.", color = C.muted)
            servers.forEach { s ->
                CardBox(Modifier.clickable { nav.go(Screen.LogPage(s.key)) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge(when (s.last?.ok) { true -> "✓"; false -> "✕"; null -> "–" }, s.last?.ok == true)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(s.host, color = C.text, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(s.protocols.joinToString("/").ifEmpty { null }, "${s.count} entries",
                                s.last?.let { fmt.format(Date(it.at)) }).joinToString(" · "), color = C.muted, fontSize = 12.sp)
                            Text(s.last?.message ?: "Saved server — nothing logged yet", color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogPageScreen(nav: Navigator, key: String) {
    val ctx = LocalContext.current
    val v by ServerLog.version.collectAsState()
    val entries = remember(v, key) { ServerLog.entries(key) }
    var errorsOnly by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var saveNas by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val shown = if (errorsOnly) entries.filter { !it.ok } else entries
    Column(Modifier.fillMaxSize()) {
        BackHeader(key, nav, "${entries.size} entries")
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Errors only", selected = errorsOnly) { errorsOnly = !errorsOnly }
            Pill("Empty log") { confirmEmpty = true }
            Pill("Share") {
                val (name, text) = ServerLog.exportText(key, App.VERSION)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, text), "Share log"))
            }
            Pill("Save to NAS") { saveNas = true }
        }
        val state = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 20.dp), state = state) {
            itemsIndexed(shown) { _, e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Badge(if (e.ok) "✓" else "✕", e.ok)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("${fmt.format(Date(e.at))} · ${e.action} · ${e.protocol}" + (if (e.user.isNotEmpty()) " · ${e.user}" else " · guest") + if (e.ms > 0) " · ${e.ms} ms" else "",
                            color = C.muted, fontSize = 12.sp)
                        Text(e.message, color = C.text, fontSize = 14.sp)
                        if (e.detail.isNotEmpty()) Text(e.detail, color = C.muted, fontSize = 12.sp)
                    }
                }
                Divider()
            }
        }
    }
    if (confirmEmpty) AlertDialog(onDismissRequest = { confirmEmpty = false }, title = { Text("Empty the log for $key?") },
        text = { Text("Only this server's log is cleared.") },
        confirmButton = { STextButton({ ServerLog.clear(key); confirmEmpty = false }) { Text("Empty", color = C.bad) } },
        dismissButton = { STextButton({ confirmEmpty = false }) { Text("Cancel") } })
    if (saveNas) {
        val (name, text) = remember { ServerLog.exportText(key, App.VERSION) }
        SaveToNasDialog("Save log to NAS", name, "log", { text.toByteArray() }) { saveNas = false }
    }
}


// ---------------- Pick files / folders for a playlist ----------------

/** What's ticked while browsing (kept across folder pages). Folders are added with all their subfolders. */
object PickBasket {
    data class Pick(val key: String, val label: String, val isDir: Boolean, val sourceId: String, val uri: String,
                    val local: app.vlcinterface.sources.LocalFolder? = null, val entry: Entry? = null)
    var playlistId = ""
    val picks = androidx.compose.runtime.mutableStateListOf<Pick>()
    fun reset(id: String) { playlistId = id; picks.clear() }
    fun toggle(p: Pick) { val i = picks.indexOfFirst { it.key == p.key }; if (i >= 0) picks.removeAt(i) else picks.add(p) }
    fun has(key: String) = picks.any { it.key == key }
}

@Composable
fun PickMediaScreen(nav: Navigator, p: Screen.PickMedia) {
    val ctx = LocalContext.current
    val st by App.instance.store.state.collectAsState()
    val pl = st.playlists.firstOrNull { it.id == p.playlistId }
    val scope = rememberCoroutineScope()
    var localFolders by remember { mutableStateOf<List<app.vlcinterface.sources.LocalFolder>?>(null) }
    var entries by remember(p.uri) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(p.uri) { mutableStateOf("") }
    var adding by remember { mutableStateOf("") }
    var done by remember { mutableStateOf("") }
    val src = st.sources.firstOrNull { it.id == p.sourceId }
    LaunchedEffect(p.sourceId, p.uri) {
        when {
            p.sourceId == "phone" && p.uri.isEmpty() -> localFolders = app.vlcinterface.sources.InternalSource.folders(ctx)
            p.sourceId == "phone" -> {
                val f = localFolders?.firstOrNull { it.id == p.uri } ?: app.vlcinterface.sources.InternalSource.folders(ctx).firstOrNull { it.id == p.uri }
                entries = if (f != null) MusicLibrary.playOrder(app.vlcinterface.sources.InternalSource.files(ctx, f), f.name) else emptyList()
            }
            src != null -> Browse.list(src, p.uri).onSuccess { l ->
                val tagged = MusicLibrary.withCachedTags(l)
                entries = tagged.filter { it.isDir } + MusicLibrary.playOrder(tagged, Urls.nameOf(p.uri))
            }.onFailure { error = app.vlcinterface.sources.SmbErrors.explain(it, src.user.isEmpty()) }
        }
    }
    fun addAll() {
        val target = pl ?: return
        scope.launch {
            adding = "Collecting files…"
            val items = ArrayList<PlayItem>()
            for (k in PickBasket.picks.toList()) {
                val s = st.sources.firstOrNull { it.id == k.sourceId }
                when {
                    k.local != null -> items += MusicLibrary.collectLocal(ctx, k.local, true)
                    k.isDir && s != null -> items += MusicLibrary.collectRecursive(s, k.uri) { n -> adding = "Collecting files… ${items.size + n}" }
                    k.entry != null -> items += MusicLibrary.toPlayItem(k.entry, s, Urls.parent(k.uri))
                }
            }
            var added = 0
            App.instance.store.update { s ->
                s.copy(playlists = s.playlists.map { pp ->
                    if (pp.id != target.id) pp else {
                        val have = pp.items.map { it.key }.toHashSet()
                        val add = items.map { MusicLibrary.toPlEntry(it) }.filter { have.add(it.key) }
                        added = add.size
                        pp.copy(items = pp.items + add)
                    }
                })
            }
            adding = ""
            done = "Added $added of ${items.size} files to ${target.name}" + if (items.size > added) " (${items.size - added} were already in it)" else ""
            PickBasket.picks.clear()
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader(if (p.sourceId.isEmpty()) "Add to ${pl?.name ?: "playlist"}" else p.title.ifEmpty { "Choose" }, nav,
            "Tick files and folders · folders include their subfolders")
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 20.dp)) {
            if (error.isNotEmpty()) Text(error, color = C.bad)
            when {
                p.sourceId.isEmpty() -> {
                    SectionTitle("Where from")
                    PickRow("▤", "On this phone", null, false) { nav.go(Screen.PickMedia(p.playlistId, "phone", "", "On this phone")) }
                    st.sources.filter { it.proto != "m3u" }.forEach { s ->
                        PickRow("▤", s.name.ifEmpty { s.host }, null, false) { nav.go(Screen.PickMedia(p.playlistId, s.id, Urls.forSource(s), s.name)) }
                    }
                }
                p.sourceId == "phone" && p.uri.isEmpty() -> localFolders?.forEach { f ->
                    val key = "phone:${f.id}"
                    PickRow("▤", f.name, PickBasket.has(key), true,
                        onCheck = { PickBasket.toggle(PickBasket.Pick(key, f.name, true, "phone", f.relativePath, local = f)) }) {
                        nav.go(Screen.PickMedia(p.playlistId, "phone", f.id, f.name))
                    }
                } ?: CircularProgressIndicator(color = accent())
                else -> entries?.forEach { e ->
                    val key = "${p.sourceId}:${e.uri}"
                    if (e.isDir) PickRow("▤", e.name, PickBasket.has(key), true,
                        onCheck = { PickBasket.toggle(PickBasket.Pick(key, e.name, true, p.sourceId, e.uri)) }) {
                        nav.go(Screen.PickMedia(p.playlistId, p.sourceId, e.uri, e.name))
                    } else PickRow(if (e.isAudio) "♪" else "▶", (if (e.track > 0) "${e.track}. " else "") + e.display +
                        (if (e.durationMs > 0) "  ·  ${fmtTime(e.durationMs)}" else ""), PickBasket.has(key), true,
                        onCheck = { PickBasket.toggle(PickBasket.Pick(key, e.display, false, p.sourceId, e.uri, entry = e)) }) {
                        PickBasket.toggle(PickBasket.Pick(key, e.display, false, p.sourceId, e.uri, entry = e))
                    }
                } ?: if (error.isEmpty()) CircularProgressIndicator(color = accent()) else Unit
            }
            Box(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().background(C.panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (adding.isNotEmpty()) adding else if (done.isNotEmpty()) done else "${PickBasket.picks.size} selected",
                color = C.text, modifier = Modifier.weight(1f), fontSize = 14.sp)
            if (done.isNotEmpty()) Pill("Back to playlist", filled = true) {
                while (nav.top is Screen.PickMedia) nav.back()
            } else Pill("Add selected", filled = true) { if (PickBasket.picks.isNotEmpty() && adding.isEmpty()) addAll() }
        }
    }
}

@Composable
private fun PickRow(icon: String, label: String, checked: Boolean?, checkable: Boolean, onCheck: () -> Unit = {}, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        if (checkable && checked != null) androidx.compose.material3.Checkbox(checked, { onCheck() },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = accent()))
        Text(icon, color = accent(), modifier = Modifier.padding(start = 4.dp, end = 12.dp))
        Text(label, color = C.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (icon == "▤") Text("›", color = C.muted, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
    Divider()
}


/**
 * Entries gathered from every log, newest first: "errors" = everything that failed (any log), "indexing" = LAN
 * indexing runs. Each line says which log it comes from; tap it to open that log.
 */
@Composable
fun LogViewScreen(nav: Navigator, kind: String) {
    val v by ServerLog.version.collectAsState()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val rows = remember(v, kind) {
        ServerLog.allKeys().flatMap { k -> ServerLog.entries(k).map { k to it } }
            .filter { (_, e) -> when (kind) { "errors" -> !e.ok; "indexing" -> e.action == "index"; else -> true } }
            .sortedByDescending { it.second.at }.take(1000)
    }
    Column(Modifier.fillMaxSize()) {
        BackHeader(when (kind) { "errors" -> "All errors"; "indexing" -> "Indexing"; else -> "Logs" }, nav, "${rows.size} entries")
        val state = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).scrollbar(state).padding(horizontal = 20.dp), state = state) {
            if (rows.isEmpty()) item { Text("Nothing logged.", color = C.muted, modifier = Modifier.padding(vertical = 12.dp)) }
            itemsIndexed(rows) { _, (k, e) ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(Screen.LogPage(k)) }.padding(vertical = 8.dp)) {
                    Badge(if (e.ok) "✓" else "✕", e.ok)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("${fmt.format(Date(e.at))} · $k · ${e.action}", color = C.muted, fontSize = 12.sp)
                        Text(e.message, color = C.text, fontSize = 14.sp)
                        if (e.detail.isNotEmpty()) Text(e.detail, color = C.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Divider()
            }
        }
    }
}


/**
 * From the player's "Playlist / Folder" button: open the current source, or load another saved playlist
 * (play it now, in random order, or have it ready without starting).
 */
@Composable
fun OriginPickDialog(fromLabel: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val st by App.instance.store.state.collectAsState()
    val player = App.instance.player
    val curId = player.state.value.origin.let { if (it.kind == "playlist") it.playlistId else null }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.panel,
        title = { Text("Playing from", color = C.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("☰  Open $fromLabel", filled = true, modifier = Modifier.fillMaxWidth()) { onOpen() }
                Text("Load another playlist", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                val lists = st.playlists.filter { it.items.isNotEmpty() }
                if (lists.isEmpty()) Text("No saved playlists yet.", color = C.muted, fontSize = 13.sp)
                lists.forEach { pl ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile).padding(10.dp)) {
                        val total = pl.items.sumOf { it.durationMs }
                        Text(pl.name + if (pl.id == curId) "  (playing)" else "", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${pl.items.size} items" + if (total > 0) " · ${fmtTime(total)}" else "", color = C.muted, fontSize = 12.sp)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val items = { pl.items.map { MusicLibrary.fromPlEntry(it) } }
                            val origin = Origin("playlist", playlistId = pl.id, title = pl.name)
                            Pill("▶ Play", filled = true) { onDismiss(); player.playQueue(items(), 0, origin, random = false) }
                            Pill("Random") { onDismiss(); player.playQueue(items(), pl.items.indices.random(), origin, random = true) }
                            Pill("Load") { onDismiss(); player.loadQueue(items(), origin) }
                        }
                    }
                }
            }
        },
        confirmButton = { STextButton(onDismiss) { Text("Close") } },
    )
}

// ---------------- Radio mode of the audio player ----------------

/**
 * Radio header: RADIO STATION, the station (your name for it, and the name it announces when different), its link,
 * then the announced song shown like a song's tags (title big, artist under it), the station's details and,
 * on mobile data, the data this station has used so far.
 */
@Composable
private fun RadioHeader(item: PlayItem, p: PlayerState, tk: Float) {
    val st by App.instance.store.state.collectAsState()
    val fav = st.stations.firstOrNull { it.url == item.uri }
    val name = fav?.name ?: item.title
    val (artist, title) = app.vlcinterface.player.splitNowPlaying(p.nowPlaying)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Station rows (label, name, address, details): the station's menu. Song rows (title, artist): the song's menu.
        var stMenu by remember { mutableStateOf(false) }
        var songMenu by remember { mutableStateOf(false) }
        val station = Modifier.combinedClickable(onClick = { stMenu = true }, onLongClick = { stMenu = true })
        val song = Modifier.combinedClickable(onClick = { songMenu = true }, onLongClick = { songMenu = true })
        Text("RADIO STATION", color = accent(), fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 1.sp, modifier = station)
        Box {
            MarqueeText(name + if (p.stationName.isNotBlank() && !p.stationName.equals(name, true)) " · ${p.stationName}" else "",
                C.text, Modifier.fillMaxWidth().then(station), fontSize = (16 * tk).sp, lineHeight = (19 * tk).sp, fontWeight = FontWeight.SemiBold,
                center = true, list = "player")
            StationMenu(item, stMenu, "station") { stMenu = false }
        }
        Text(item.uri.substringAfter("://").take(60), color = C.muted, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = station)
        Box {
            MarqueeText(title.ifEmpty { "Live" }, C.text, Modifier.fillMaxWidth().padding(top = 6.dp).then(song), fontSize = (22 * tk).sp,
                lineHeight = (25 * tk).sp, fontWeight = FontWeight.Bold, center = true, list = "player")
            StationMenu(item, songMenu, "song") { songMenu = false }
        }
        if (artist.isNotEmpty()) FitText(artist, color = C.muted, fontSize = (15 * tk).sp, minSize = 12.sp, lineHeight = (18 * tk).sp, textAlign = TextAlign.Center, modifier = song)
        val details = listOfNotNull(stationMetaShort(fav?.meta ?: LastStationMeta.of(item.uri)).ifEmpty { null }, p.rgInfo.ifEmpty { null },
            if (p.sleepUntil > 0) "Sleep ${((p.sleepUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(0)} min" else null)
        if (details.isNotEmpty()) FitText(details.joinToString(" · "), color = accent(), fontSize = 12.sp, minSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, modifier = station.padding(horizontal = 4.dp, vertical = 3.dp))
        if (p.error.isNotEmpty()) Text(p.error, color = C.bad, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // No VPN-NAS button on the radio player: radio doesn't use the NAS.
        // Mobile data only: what this station has used so far, live.
        val cell by app.vlcinterface.player.NetGuard.cellular.collectAsState()
        if (cell) {
            var used by remember { mutableStateOf(0L) }
            var total by remember { mutableStateOf(0L) }
            LaunchedEffect(item.uri) { while (true) { used = app.vlcinterface.player.NetGuard.stationBytes().coerceAtLeast(0)
                total = app.vlcinterface.player.NetGuard.radioMobileTotal(); kotlinx.coroutines.delay(1000) } }
            Text("On mobile data · this station ${app.vlcinterface.player.NetGuard.fmt(used)} · radio total ${app.vlcinterface.player.NetGuard.fmt(total)}" +
                if (fav?.meta?.contains("flac", true) == true) " · FLAC uses a lot of data" else "",
                color = C.bad, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

/** Radio: how long this station has been playing (no progress bar, nothing to seek). */
@Composable
private fun RadioListening() {
    val p by App.instance.player.state.collectAsState()
    // Counted by the app from when this station started (the stream's own clock can count from the station's
    // start, days ago — Ogg streams do); paused time is not counted.
    var shown by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { shown = RadioClock.listened(App.instance.player.state.value); kotlinx.coroutines.delay(1000) } }
    Text("Live · listening ${fmtTime(shown)}", color = C.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

/** How long the current station has been listened to (playing time only), per station start. */
object RadioClock {
    private var uri = ""; private var acc = 0L; private var since = 0L
    @Synchronized fun listened(s: app.vlcinterface.player.PlayerState): Long {
        val now = System.currentTimeMillis()
        val u = s.item?.takeIf { it.isRadio }?.uri ?: ""
        if (u != uri) { uri = u; acc = 0; since = 0 }
        val playing = s.isPlaying && u.isNotEmpty()
        if (playing && since == 0L) since = now
        if (!playing && since != 0L) { acc += now - since; since = 0 }
        return acc + if (since != 0L) now - since else 0
    }
}

/** Rename a favourite station (the name shown everywhere in the app). */
@Composable
fun StationRenameDialog(station: app.vlcinterface.data.Station, onDone: () -> Unit) {
    var name by remember { mutableStateOf(station.name) }
    AlertDialog(onDismissRequest = onDone, containerColor = C.panel,
        title = { Text("Rename station", color = C.text) },
        text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            STextButton({
                val n = name.trim()
                if (n.isNotEmpty()) App.instance.store.update { x -> x.copy(stations = x.stations.map { if (it.id == station.id) it.copy(name = n) else it }) }
                onDone()
            }) { Text("Save", color = accent()) }
        },
        dismissButton = { STextButton(onDone) { Text("Cancel") } },
    )
}

/** Radio, lower part of the player: all favourite stations (scrollable) and the station search with filters. */
@Composable
private fun RadioPanel(nav: Navigator) {
    val st by App.instance.store.state.collectAsState()
    val player = App.instance.player
    val cur = player.state.collectAsState().value.item?.uri
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<app.vlcinterface.data.Station?>(null) }
    rename?.let { s -> StationRenameDialog(s) { rename = null } }
    var details by remember { mutableStateOf<app.vlcinterface.data.Station?>(null) }
    details?.let { s -> StationDetailsDialog(s) { details = null } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Favourite stations")
        if (st.stations.isEmpty()) Text("No favourites yet: find stations below and tap ☆.", color = C.muted, fontSize = 13.sp)
        else {
            // Compact one-line rows, no frame: name, then bitrate and codec; tap plays, ⋮ or long-press for the menu.
            val ls = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(Unit) { st.stations.indexOfFirst { it.url == cur }.takeIf { it > 2 }?.let { ls.scrollToItem(it - 2) } }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp).scrollbar(ls), state = ls) {
                items(st.stations.size) { i ->
                    val s = st.stations[i]
                    val on = s.url == cur
                    // Same tight rows as the search results (2026-11-21 02:58): flag (or globe) in front, no extra gap.
                    Row(Modifier.fillMaxWidth().background(if (on) accent().copy(alpha = 0.14f) else Color.Transparent)
                        .combinedClickable(onClick = { player.playStation(s.name, s.url) }, onLongClick = { menu = s.id })
                        .padding(start = 6.dp, top = (3 * sg()).dp, bottom = (3 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                        val rs = remember(s.url, s.meta) { app.vlcinterface.sources.RadioStation("", s.name, s.url, "", 0, stationCountry(s.meta), "", s.favicon) }
                        StationFlag(rs)
                        MarqueeText((if (on) "▶ " else "") + s.name, if (on) accent() else C.text, Modifier.weight(1f), fontSize = 15.sp,
                            lineHeight = 17.sp, list = "radioFav")
                        Text("  " + stationQuality(s.meta), color = C.muted, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false)
                        Box {
                            Text("⋮", color = C.text, fontSize = 20.sp, lineHeight = 20.sp, modifier = Modifier.clickable { menu = s.id }.padding(horizontal = 10.dp))
                            SDropdownMenu(menu == s.id, { menu = null }) {
                                SMenuItem({ Text("▶  Play") }, { menu = null; player.playStation(s.name, s.url) })
                                SMenuItem({ Text("Rename…") }, { menu = null; rename = s })
                                SMenuItem({ Text("Copy link") }, {
                                    menu = null
                                    ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("station", s.url))
                                    android.widget.Toast.makeText(ctx, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                                })
                                SMenuItem({ Text("Share link…") }, {
                                    menu = null
                                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, s.name).putExtra(android.content.Intent.EXTRA_TEXT, "${s.name}\n${s.url}"), "Share station"))
                                })
                                SMenuItem({ Text("Add to playlist…") }, { menu = null; FileDialogs.addTo = listOf(PlayItem(s.url, s.name, isRadio = true)) })
                                SMenuItem({ Text("Details…") }, { menu = null; details = s })
                                SMenuItem({ Text("Remove from favourites", color = C.bad) }, { menu = null; App.instance.store.update { x -> x.copy(stations = x.stations.filterNot { it.id == s.id }) } })
                            }
                        }
                    }
                }
            }
        }
        StationSearch()
    }
}

/** Station search (radio-browser.info): name, plus Style, Bitrate, Country, Language, Codec and Sort filters. */
@Composable
fun StationSearch() {
    val st by App.instance.store.state.collectAsState()
    val player = App.instance.player
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var bitrate by remember { mutableStateOf(0) }       // 0 any, -1 = up to 128 (mobile data), else minimum
    var country by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var codec by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("clickcount") }
    var results by remember { mutableStateOf<List<app.vlcinterface.sources.RadioStation>?>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var open by remember { mutableStateOf("") }
    val ctxS = LocalContext.current
    fun run() {
        scope.launch {
            results = null; error = ""
            app.vlcinterface.sources.RadioBrowser.search(q, style, country, 40,
                bitrateMin = if (bitrate > 0) bitrate else 0, bitrateMax = if (bitrate < 0) 128 else 0,
                language = language, codec = codec, order = order)
                .onSuccess { results = it }.onFailure { results = emptyList(); error = it.message ?: "Search failed" }
        }
    }
    @Composable
    fun Filter(key: String, label: String, value: String, options: List<Pair<String, () -> Unit>>) {
        Box {
            Pill("$label: ${value.ifEmpty { "any" }} ▾", selected = value.isNotEmpty()) { open = key }
            SDropdownMenu(open == key, { open = "" }) {
                options.forEach { (l, f) -> SMenuItem({ Text(l) }, { open = ""; f(); run() }) }
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, C.line, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Find stations", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val reqs = remember(results) { List(results?.size ?: 0) { androidx.compose.foundation.relocation.BringIntoViewRequester() } }
        val at = -1
        val focusM = androidx.compose.ui.platform.LocalFocusManager.current
        // ✕ closes the search: name and results cleared, the box shrinks back, the keyboard goes away.
        SmallSearchBar(q, { q = it }, "Station name", results?.size ?: 0, onSearch = { run() },
            onClose = { q = ""; results = emptyList(); error = ""; focusM.clearFocus() })
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Filter("style", "Style", style, (listOf("any" to "") + listOf("Rock", "Pop", "Jazz", "Classical", "Blues", "Metal", "Electronic", "Chill", "Ambient",
                "Hip hop", "Country", "Folk", "Reggae", "Soul", "Oldies", "80s", "90s", "News", "Talk").map { it to it.lowercase() })
                .map { (l, v) -> l to { style = v } })
            Filter("bitrate", "Bitrate", when { bitrate < 0 -> "≤ 128"; bitrate > 0 -> "≥ $bitrate"; else -> "" },
                listOf("any" to 0, "≤ 128 kbps (mobile data)" to -1, "≥ 128 kbps" to 128, "≥ 192 kbps" to 192, "≥ 320 kbps / lossless" to 320).map { (l, v) -> l to { bitrate = v } })
            Filter("country", "Country", country, (listOf("any" to "", "Near me (${java.util.Locale.getDefault().country})" to java.util.Locale.getDefault().country) +
                listOf("US", "GB", "DE", "FR", "ES", "IT", "NL", "RO", "CH", "AT", "BE", "CA", "AU", "BR", "JP").map { it to it }).map { (l, v) -> l to { country = v } })
            Filter("language", "Language", language, (listOf("any" to "") + listOf("English", "Spanish", "German", "French", "Italian", "Romanian", "Portuguese", "Dutch")
                .map { it to it.lowercase() }).map { (l, v) -> l to { language = v } })
            Filter("codec", "Codec", codec, listOf("any" to "", "MP3" to "MP3", "AAC" to "AAC", "AAC+" to "AAC+", "OGG" to "OGG", "FLAC" to "FLAC").map { (l, v) -> l to { codec = v } })
            Filter("sort", "Sort", when (order) { "votes" -> "votes"; "bitrate" -> "bitrate"; "name" -> "name"; else -> "popular" },
                listOf("popular" to "clickcount", "votes" to "votes", "bitrate" to "bitrate", "name" to "name").map { (l, v) -> l to { order = v } })
        }
        when {
            results == null -> Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent()) }
            error.isNotEmpty() -> Text(error, color = C.bad, fontSize = 13.sp)
            // Results in their own column: no extra gaps between rows, only the thin grey line.
            else -> Column(Modifier.fillMaxWidth()) { results!!.forEachIndexed { ri, r ->
                if (ri > 0) Divider()
              Box(Modifier.then(if (ri < reqs.size) Modifier.bringIntoViewRequester(reqs[ri]) else Modifier)
                  .background(if (ri == at) accent().copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(8.dp))) {
                val fav = st.stations.any { it.url == r.url }
                LastStationMeta.put(r.url, r.meta)
                var rMenu by remember(r.url) { mutableStateOf(false) }
                var rDetails by remember(r.url) { mutableStateOf(false) }
                if (rDetails) StationDetailsDialog(app.vlcinterface.data.Station(name = r.name, url = r.url, meta = r.meta, favicon = r.favicon)) { rDetails = false }
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { player.playStation(r.name, r.url) }, onLongClick = { rMenu = true })
                    .padding(vertical = (3 * sg()).dp), verticalAlignment = Alignment.CenterVertically) {
                    // The station's country flag; when the country isn't known, a globe turned to where the station is
                    // (Europe while looking it up and when nothing is found).
                    StationFlag(r)
                    Column(Modifier.weight(1f)) {
                        MarqueeText(r.name, C.text, Modifier.fillMaxWidth(), fontSize = 14.sp, lineHeight = 16.sp, list = "radioFind")
                        if (r.meta.isNotEmpty()) Text(stationMetaShort(r.meta), color = if (r.isFlac) C.bad.copy(alpha = 0.8f) else C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (fav) "★" else "☆", color = accent(), fontSize = 20.sp, lineHeight = 20.sp, modifier = Modifier.clickable {
                        toggleFavourite(r.name, r.url, r.meta, r.favicon)
                    }.padding(horizontal = 8.dp))
                    Box {
                        Text("⋮", color = C.text, fontSize = 20.sp, lineHeight = 20.sp, modifier = Modifier.clickable { rMenu = true }.padding(horizontal = 10.dp))
                        SDropdownMenu(rMenu, { rMenu = false }) {
                            SMenuItem({ Text("▶  Play") }, { rMenu = false; player.playStation(r.name, r.url) })
                            SMenuItem({ Text(if (fav) "Remove from favourites" else "Add to favourites") }, { rMenu = false; toggleFavourite(r.name, r.url, r.meta, r.favicon) })
                            SMenuItem({ Text("Add to playlist…") }, { rMenu = false; FileDialogs.addTo = listOf(PlayItem(r.url, r.name, isRadio = true)) })
                            SMenuItem({ Text("Details…") }, { rMenu = false; rDetails = true })
                            SMenuItem({ Text("Copy link") }, { rMenu = false; copyStationLink(ctxS, r.url) })
                            SMenuItem({ Text("Share link…") }, { rMenu = false; shareStation(ctxS, r.name, r.url) })
                        }
                    }
                }
              }
            } }
        }
    }
}


/**
 * Loudness and treble: reset to 0 (no processing) on the left, recall (this song's own saved values) on the right.
 * Values changed during a song are kept with it; songs without their own values use the last ones.
 */
/** A double tap on the Treble slider asks the player screen to open Settings → Audio at the Treble band. */
object TrebleBandNav {
    var want by mutableStateOf(false)
}

@Composable
private fun ToneSliders() {
    val player = App.instance.player
    val p by player.state.collectAsState()
    // One lock for all the sliders: unlocking one unlocks them all. Locked against accidental touches; long-press
    // to unlock; locks again 10 s after the last change, or at once when the screen is locked / the app goes away.
    var open by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(0L) }
    LaunchedEffect(open, touched) { if (open) { kotlinx.coroutines.delay(10_000); open = false } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) open = false }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    @Composable
    fun row(label: String, v: Float, range: ClosedFloatingPointRange<Float>, recall: Boolean = true, onDoubleTap: (() -> Unit)? = null, onSet: (Float, Boolean) -> Unit) {
        fun set(x: Float, done: Boolean) { touched = System.nanoTime(); onSet(x, done) }
        // Short tap on anything locked in this row: "Long press to unlock" for 3 s.
        val hintNow = remember { mutableStateOf(0L) }
        // Label above; the buttons sit on the slider's own line, centred on it.
        Column(Modifier.fillMaxWidth()) {
            Text((if (open) "" else "🔒 ") + "$label " + (if (v == 0f) "off" else "%+.1f".format(java.util.Locale.US, v)),
                color = C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 40.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Reset: 0 = no processing (only while unlocked).
                // 0: reset (unlocked); long-press also unlocks all the sliders.
                Text("0", color = if (v == 0f || !open) C.muted else C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.size(28.dp).clip(CircleShape).border(1.dp, C.line, CircleShape)
                        .combinedClickable(onClick = { if (open) set(0f, true) else hintNow.value = System.nanoTime() },
                            onLongClick = { open = true; touched = System.nanoTime() }).wrapContentHeight())
                // The slider only draws; touches are handled here so that one gesture can unlock and move:
                // hold until it unlocks (long-press), then slide without lifting the finger. Unlocked: tap or slide.
                val dens = androidx.compose.ui.platform.LocalDensity.current
                val inset = with(dens) { 14.dp.toPx() }       // padding 6 dp + half the thumb (8 dp)
                val vNow by rememberUpdatedState(v)
                val openNow by rememberUpdatedState(open)
                if (hintNow.value != 0L) {
                    LaunchedEffect(hintNow.value) { kotlinx.coroutines.delay(3000); hintNow.value = 0L }
                    androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter,
                        // Well above the slider, so the finger that tapped doesn't cover it.
                        offset = androidx.compose.ui.unit.IntOffset(0, with(dens) { (-110).dp.roundToPx() })) {
                        Text("Long press to unlock", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.popup).border(1.dp, accent(), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                val setNow by rememberUpdatedState<(Float, Boolean) -> Unit> { x, d -> set(x, d) }
                // Double tap (21:50, Treble: its band settings): the time of the last short tap and the value before it.
                val lastTap = remember { longArrayOf(0L) }
                val beforeTap = remember { floatArrayOf(0f) }
                val dbl by rememberUpdatedState(onDoubleTap)
                fun valueAt(x: Float, w: Int): Float {
                    val f = ((x - inset) / (w - 2 * inset)).coerceIn(0f, 1f)
                    return Math.round((range.start + f * (range.endInclusive - range.start)) * 2) / 2f
                }
                Box(Modifier.weight(1f).height(28.dp).pointerInput(range) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!openNow) {
                            // Locked: wait for the long-press (moving or lifting first cancels).
                            // Returns true for a short tap (lifted without moving), false when the finger moved away.
                            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var tap = false
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                    if (!c.pressed) { tap = true; break }
                                }
                                tap
                            }
                            if (ended != null) {
                                if (ended) {
                                    val now = System.currentTimeMillis()
                                    if (dbl != null && now - lastTap[0] < 350) { lastTap[0] = 0L; hintNow.value = 0L; dbl?.invoke() }
                                    else { lastTap[0] = now; hintNow.value = System.nanoTime() }
                                }
                                return@awaitEachGesture
                            }
                            open = true
                        }
                        touched = System.nanoTime()
                        val before = vNow
                        val t0 = System.currentTimeMillis()
                        var moved = false
                        var last = valueAt(down.position.x, size.width)
                        setNow(last, false)
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            val nv = valueAt(c.position.x, size.width)
                            if (nv != last) { last = nv; setNow(nv, false) }
                        }
                        val now = System.currentTimeMillis()
                        val tap = !moved && now - t0 < 300
                        if (tap && dbl != null && now - lastTap[0] < 350) {
                            // Second tap: the value goes back to what it was before the first tap, then the band settings.
                            lastTap[0] = 0L
                            setNow(beforeTap[0], true)
                            dbl?.invoke()
                        } else {
                            if (tap) { lastTap[0] = now; beforeTap[0] = before } else lastTap[0] = 0L
                            setNow(last, true)
                        }
                    }
                }) {
                    // Thin slider drawn here: 4 dp track, 16 dp round knob (the standard slider is much thicker).
                    val active = if (open) accent() else accent().copy(alpha = 0.45f)
                    val knob = if (open) Color.White else C.muted
                    val line = C.line
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val y = size.height / 2
                        val x0 = inset; val x1 = size.width - inset
                        val f = ((vNow - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                        val xv = x0 + (x1 - x0) * f
                        // For ranges around 0 (Treble, Preamp) the coloured part runs from 0 to the value.
                        val zero = if (range.start < 0f) x0 + (x1 - x0) * ((0f - range.start) / (range.endInclusive - range.start)) else x0
                        val th = 4.dp.toPx()
                        drawLine(line, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), th, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        if (xv != zero) drawLine(active, androidx.compose.ui.geometry.Offset(minOf(zero, xv), y), androidx.compose.ui.geometry.Offset(maxOf(zero, xv), y), th, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        if (range.start < 0f) drawCircle(line, 2.5.dp.toPx(), androidx.compose.ui.geometry.Offset(zero, y))
                        if (UiPrefs.controlShadow) {
                            // Faint light-grey shadow 2 dp down-right, and a 1 dp grey ring round the knob.
                            val o = 2.dp.toPx()
                            drawCircle(Color(0x29CDCDD2), 8.dp.toPx() + 0.5f, androidx.compose.ui.geometry.Offset(xv + o + 0.5f, y + o + 0.5f))
                            drawCircle(Color(0x3DCDCDD2), 8.dp.toPx(), androidx.compose.ui.geometry.Offset(xv + o, y + o))
                        }
                        drawCircle(knob, 8.dp.toPx(), androidx.compose.ui.geometry.Offset(xv, y))
                        if (UiPrefs.controlShadow) drawCircle(Color(0xFF8A8A8F), 8.dp.toPx(), androidx.compose.ui.geometry.Offset(xv, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                }
                // Recall: this song's own values (dimmed when it has none).
                if (!recall) Box(Modifier.size(28.dp)) else
                Text("↺", color = if (p.toneSaved) accent() else C.muted.copy(alpha = 0.5f), fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.size(28.dp).clip(CircleShape).border(1.dp, C.line, CircleShape)
                        .combinedClickable(onClick = { if (p.toneSaved) player.recallTone() },
                            onLongClick = { open = true; touched = System.nanoTime() }).wrapContentHeight())
            }
        }
    }
    // One slider per row, centred, almost the full width (longer sliders, finer control).
    Column(Modifier.fillMaxWidth(0.94f)) {
        // Auto loudness on: the label also shows how much is used at the phone's current volume.
        row(if (p.loudEffDb != p.loudDb && p.loudDb > 0f) "Loudness (auto %.1f)".format(java.util.Locale.US, p.loudEffDb) else "Loudness",
            p.loudDb, 0f..15f) { x, done -> player.setTone(x, p.trebleDb, done) }
        // Double tap: Settings → Audio → Treble band (the filter this slider drives), 21:50.
        row("Treble", p.trebleDb, -5f..5f, onDoubleTap = { TrebleBandNav.want = true }) { x, done -> player.setTone(p.loudDb, x, done) }
        // Pre-amp per song / station (−12…+12 dB): for ones too loud or too quiet (on top of ReplayGain for songs).
        row("Preamp", p.radioPreDb, -12f..12f, recall = false) { x, done -> player.setRadioPre(x, done) }
    }
}


/** "128 kbps MP3" out of a station's details (bitrate and codec only), or "" if unknown. */
/**
 * The station's country flag; when the country isn't known, a globe turned to where the station is (Europe while
 * looking it up and when nothing is found). Used by the search results and the favourites.
 */
@Composable
fun StationFlag(r: app.vlcinterface.sources.RadioStation) {
    val flag = flagOf(r.country)
    if (flag.isNotEmpty()) Text(flag, fontSize = 18.sp, lineHeight = 20.sp, modifier = Modifier.padding(end = 8.dp))
    else {
        val globe by produceState(app.vlcinterface.sources.Globe.known(r) ?: app.vlcinterface.sources.Globe.EUROPE, r.url) {
            if (app.vlcinterface.sources.Globe.known(r) == null) value = app.vlcinterface.sources.Globe.lookup(r)
        }
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(globe), "Station location",
            Modifier.padding(end = 8.dp).size(20.dp))
    }
}

/** Country code kept in a favourite's details ("Blues · 1500 kbps OGG · ES" → "ES"); "" when it has none. */
fun stationCountry(meta: String): String =
    meta.split(" · ").map { it.trim() }.lastOrNull { Regex("^[A-Z]{2}$").matches(it) } ?: ""

fun stationQuality(meta: String): String =
    // Only the bitrate value followed by k ("1500k"), no codec name (2026-11-21 11:50).
    Regex("""(\d{2,4})\s*kbps""", RegexOption.IGNORE_CASE).find(meta)?.let { it.groupValues[1] + "k" } ?: ""

/**
 * A station's details as shown in the radio lists and the radio player: "1500 kbps OGG" → "1500k" (no codec names,
 * 11:50); other parts (style, country) stay.
 */
fun stationMetaShort(meta: String): String =
    meta.split(" · ").map { part ->
        Regex("""(\d{2,4})\s*kbps(\s+[A-Za-z0-9+]+)?""", RegexOption.IGNORE_CASE).replace(part) { it.groupValues[1] + "k" }.trim()
    }.filter { it.isNotEmpty() && !Regex("""^(MP3|AAC\+?|AACP|HE-AAC|OGG|OPUS|FLAC|VORBIS|WMA)$""", RegexOption.IGNORE_CASE).matches(it) }
        .joinToString(" · ")


/**
 * Everything known about a station, in three parts — saved in the app, reported live by the stream (while it
 * plays), and listed in the radio directory. Tap a line to copy its value; text can also be selected; Copy all
 * copies the whole report.
 */
@Composable
fun StationDetailsDialog(station: app.vlcinterface.data.Station, onDone: () -> Unit) {
    val p by App.instance.player.state.collectAsState()
    val ctx = LocalContext.current
    val playing = p.item?.uri == station.url
    val saved = buildList {
        add("Name" to station.name)
        add("Link" to station.url)
        station.meta.takeIf { it.isNotBlank() }?.let { add("Saved details" to it) }
        stationQuality(station.meta).takeIf { it.isNotEmpty() }?.let { add("Bitrate / codec" to it) }
        station.favicon.takeIf { it.isNotBlank() }?.let { add("Logo" to it) }
        add("Preamp" to ((app.vlcinterface.player.ToneStore.getPre(station.url) ?: 0f).let { if (it == 0f) "0 dB (none)" else "%.1f dB".format(java.util.Locale.US, it) }))
        app.vlcinterface.player.ToneStore.get(app.vlcinterface.player.PlayItem(station.url, station.name, isRadio = true))?.let { (l, t) ->
            add("Loudness / treble" to "%+.1f / %+.1f dB".format(java.util.Locale.US, l, t)) }
        if (playing) add("Listening for" to fmtTime(p.time))
    }
    // Live: refreshed every 2 s while open.
    var live by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(playing) { while (playing) { live = App.instance.player.streamInfo(); kotlinx.coroutines.delay(2000) } }
    var dir by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(station.url) { dir = app.vlcinterface.sources.RadioBrowser.details(station.url) }
    // Same style as every details pop-up (DetailsDialog).
    val secs = buildList {
        add("In the app" to saved)
        if (playing) add("Live stream" to live.ifEmpty { listOf("Stream" to "waiting for the stream to report…") })
        add("Radio directory" to (dir?.ifEmpty { listOf("Directory" to "not listed in radio-browser.info") } ?: listOf("Directory" to "looking up…")))
    }
    DetailsDialog("Station details · ${station.name}", secs, onDone = onDone)
}


/** Copy / share a station's link. */
fun copyStationLink(ctx: android.content.Context, url: String) {
    ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("station", url))
    android.widget.Toast.makeText(ctx, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
}
fun shareStation(ctx: android.content.Context, name: String, url: String) {
    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, name).putExtra(android.content.Intent.EXTRA_TEXT, "$name\n$url"), "Share station")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
fun toggleFavourite(name: String, url: String, meta: String, favicon: String) {
    App.instance.store.update { x ->
        if (x.stations.any { it.url == url }) x.copy(stations = x.stations.filterNot { it.url == url })
        else x.copy(stations = x.stations + app.vlcinterface.data.Station(name = name, url = url, meta = meta, favicon = favicon))
    }
}

/** The playing station's menu (from its name or the song title): everything for it, favourite or not. */
@Composable
private fun StationMenu(item: PlayItem, open: Boolean, part: String = "all", onClose: () -> Unit) {
    val st by App.instance.store.state.collectAsState()
    val ctx = LocalContext.current
    val fav = st.stations.firstOrNull { it.url == item.uri }
    val name = fav?.name ?: item.title
    var details by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    if (details) StationDetailsDialog(fav ?: app.vlcinterface.data.Station(name = item.title, url = item.uri, meta = LastStationMeta.of(item.uri), favicon = item.artwork)) { details = false }
    if (rename && fav != null) StationRenameDialog(fav) { rename = false }
    // The song now playing: save it, its links, Shazam.
    val p by App.instance.player.state.collectAsState()
    val (npArtist, npTitle) = app.vlcinterface.player.splitNowPlaying(p.nowPlaying)
    var saveSong by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf(false) }
    if (saveSong) SaveSongDialog { saveSong = false }
    var savedList by remember { mutableStateOf(false) }
    if (savedList) SavedSongsDialog { savedList = false }
    if (links) SongLinksDialog(npArtist, npTitle, 0, "", null) { links = false }
    val cap by app.vlcinterface.sources.RadioCapture.status.collectAsState()
    SDropdownMenu(open, onClose) {
      // "song": the song now playing · "station": the station · "all": both.
      if (part != "station") {
        SMenuItem({ Text(if (cap.pending != null) "Saving… (stop / extend)" else "Save this song…") }, { onClose(); saveSong = true })
        if (npTitle.isNotBlank()) SMenuItem({ Text(if (st.settings.spotifyId.isNotBlank()) "YouTube / Spotify links…" else "YouTube link…") }, { onClose(); links = true })
        SMenuItem({ Text("Saved songs…") }, { onClose(); savedList = true })
        SMenuItem({ Text("Open Shazam") }, { onClose(); openShazam(ctx) })
      }
      if (part != "song") {
        SMenuItem({ Text(if (fav != null) "Remove from favourites" else "Add to favourites") }, {
            onClose(); toggleFavourite(item.title, item.uri, LastStationMeta.of(item.uri), item.artwork) })
        if (fav != null) SMenuItem({ Text("Rename…") }, { onClose(); rename = true })
        SMenuItem({ Text("Add to playlist…") }, { onClose(); FileDialogs.addTo = listOf(PlayItem(item.uri, name, isRadio = true)) })
        SMenuItem({ Text("Details…") }, { onClose(); details = true })
        SMenuItem({ Text("Copy link") }, { onClose(); copyStationLink(ctx, item.uri) })
        SMenuItem({ Text("Share link…") }, { onClose(); shareStation(ctx, name, item.uri) })
      }
    }
}

/** Details the search listed for stations (bitrate, codec, style, country), so a played search result keeps them. */
object LastStationMeta {
    private val m = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun put(url: String, meta: String) { if (meta.isNotBlank()) m[url] = meta }
    fun of(url: String) = m[url] ?: ""
}


/** Slider knob of the players: with the shadow setting, a 1 dp grey ring and a faint shadow 2 dp down-right. */
@Composable
fun KnobThumb(color: Color) {
    val shadow = UiPrefs.controlShadow
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).then(if (shadow) Modifier.liteShadow(CircleShape, 2f) else Modifier).clip(CircleShape).background(color)
            .then(if (shadow) Modifier.border(1.dp, Color(0xFF8A8A8F), CircleShape) else Modifier))
    }
}


/** The clock's square with a very small settings symbol in its top-right corner (outside the round face) → Alarm clock settings. */
@Composable
fun ClockWithGear(nav: Navigator, clock: @Composable () -> Unit) {
    Box {
        clock()
        Text("⚙", color = C.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape)
            .clickable { nav.go(Screen.AlarmSettings) }.padding(3.dp))
    }
}


/** Round play / pause button floating over the player; drag it anywhere (its place is kept). Size and transparency in Look. */
@Composable
fun BoxScope.FloatingPlayButton(playing: Boolean, onToggle: () -> Unit) {
    val store = App.instance.store
    val s = store.state.collectAsState().value.settings
    val dens = LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val sizePx = with(dens) { s.floatBtnSize.dp.toPx() }
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        var x by remember { mutableFloatStateOf(if (s.floatBtnX >= 0f) s.floatBtnX * wPx else wPx - sizePx - with(dens) { 28.dp.toPx() }) }
        var y by remember { mutableFloatStateOf(if (s.floatBtnY >= 0f) s.floatBtnY * hPx else hPx - sizePx - with(dens) { 96.dp.toPx() }) }
        x = x.coerceIn(0f, (wPx - sizePx).coerceAtLeast(0f)); y = y.coerceIn(0f, (hPx - sizePx).coerceAtLeast(0f))
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }.size(s.floatBtnSize.dp)
            .graphicsLayer { alpha = s.floatBtnAlpha / 100f }
            .liteShadow(CircleShape).clip(CircleShape).background(if (s.floatBtnColor != 0L) Color(s.floatBtnColor) else accent())
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = {
                    store.update { it.copy(settings = it.settings.copy(floatBtnX = x / wPx, floatBtnY = y / hPx)) }
                }) { ch, d -> ch.consume(); x = (x + d.x).coerceIn(0f, (wPx - sizePx).coerceAtLeast(0f)); y = (y + d.y).coerceIn(0f, (hPx - sizePx).coerceAtLeast(0f)) }
            }
            .clickable { onToggle() }, contentAlignment = Alignment.Center) {
            Text(if (playing) "❚❚" else "▶", color = floatSymbolColor(s.floatBtnColor), fontSize = (s.floatBtnSize * 0.36f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Symbol colour readable on the floating button's colour: dark on light colours, white on dark ones. */
fun floatSymbolColor(c: Long): Color = if (c == 0L || Color(c).luminance() > 0.4f) Color(0xFF1A0E05) else Color.White

/** The floating button as drawn on the player (for previews). */
@Composable
fun FloatingButtonSample(color: Long, playing: Boolean, size: Int = 56) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(if (color != 0L) Color(color) else accent()), contentAlignment = Alignment.Center) {
        Text(if (playing) "❚❚" else "▶", color = floatSymbolColor(color), fontSize = (size * 0.36f).sp, fontWeight = FontWeight.Bold)
    }
}


/** Hidden but kept: no height and not drawn, while its picture / state stays loaded (no empty cover when it comes back). */
fun Modifier.keepWhenHidden(hidden: Boolean): Modifier = this.layout { m, c ->
    val pl = m.measure(c)
    layout(pl.width, if (hidden) 0 else pl.height) { if (!hidden) pl.place(0, 0) }
}


/** Player rows (Look → "Order of the player rows"); sideways the picture and title stay on the left side. */
val PLAYER_SONG_ROWS = listOf("picture", "title", "position", "buttons", "tone", "from", "playnext", "extra", "upnext")
val PLAYER_RADIO_ROWS = listOf("picture", "title", "buttons", "tone", "from", "extra", "upnext")
val PLAYER_LEFT_ROWS = setOf("picture", "title")
val PLAYER_ROW_NAMES = mapOf("picture" to "Picture / clock", "title" to "Title", "position" to "Position bar",
    "buttons" to "Buttons", "tone" to "Tone sliders", "from" to "Playing from", "playnext" to "Play next…",
    "extra" to "Extra buttons", "upnext" to "Up next / stations")

/** The rows shown on the player, in their order. */
fun playerRowOrder(s: app.vlcinterface.data.Settings, radio: Boolean): List<String> = playerRowOrderAll(s, radio)

/** Every row of that player in its order, removed ones included (for Look). */
fun playerRowOrderAll(s: app.vlcinterface.data.Settings, radio: Boolean): List<String> {
    val def = if (radio) PLAYER_RADIO_ROWS else PLAYER_SONG_ROWS
    val saved = (if (radio) s.rowOrderRadio else s.rowOrderSong).filter { it in def }
    return saved + def.filter { it !in saved }
}

/** Video player upright: the rows under the picture (2026-11-21 15:42), in Look → "Order of the player rows" → Video. */
val PLAYER_VIDEO_ROWS = listOf("title", "playing", "subs", "picture", "files", "format", "upnext")
val VIDEO_ROW_NAMES = mapOf("title" to "Title", "playing" to "Play buttons (⏮ −10 ▶ +10 ⏭)", "subs" to "Subs/Change, Subs/Sync, Audio/Sync", "picture" to "Hue/Sat, Subs/Format, speed",
    "files" to "Browse", "format" to "Format, Rotate", "upnext" to "Queue box (Up next)")

fun videoRowOrder(s: app.vlcinterface.data.Settings): List<String> {
    // The old single "sound" row (before 16:46) is now two lines, kept in its place.
    val saved = s.rowOrderVideo.flatMap { if (it == "sound") listOf("subs", "picture") else listOf(it) }.filter { it in PLAYER_VIDEO_ROWS }
    return saved + PLAYER_VIDEO_ROWS.filter { it !in saved }
}

/** Rows that can't be removed: without them the player can't be used. */
val PLAYER_ROWS_KEPT = setOf("buttons")


/** Flag emoji of a two-letter country code ("FR" → 🇫🇷); 🌐 when unknown. */
fun flagOf(code: String): String {
    val c = code.trim().uppercase()
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""   // unknown: the caller shows a globe instead
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}


/**
 * "Save" next to Up next (2026-11-21 16:48): the whole queue as a new playlist, with a name proposed — the series for
 * episodes ("The Office"), else where it was played from — that can be changed.
 */
@Composable
fun SaveQueueButton() {
    val p by App.instance.player.state.collectAsState()
    var name by remember { mutableStateOf<String?>(null) }
    if (p.queue.size < 2) return
    Text("Save", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
            val cur = p.item
            val ser = cur?.let { app.vlcinterface.player.Episodes.parse(it.fileName.ifEmpty { android.net.Uri.decode(it.uri.substringAfterLast('/')) }) }?.series
            name = ser?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }?.takeIf { it.isNotBlank() }
                ?: p.origin.title.ifEmpty { "Queue" }
        }.padding(horizontal = 8.dp, vertical = 4.dp))
    name?.let { n ->
        AlertDialog(onDismissRequest = { name = null }, containerColor = C.panel,
            title = { Text("Save the queue as a playlist", color = C.text) },
            text = { OutlinedTextField(n, { name = it }, singleLine = true, placeholder = { Text("Playlist name") }) },
            confirmButton = { STextButton({
                val items = p.queue.map { MusicLibrary.toPlEntry(it) }
                App.instance.store.update { s -> s.copy(playlists = s.playlists + Playlist(name = n.ifBlank { "Queue" }, items = items)) }
                ShortNote.show("Playlist \"${n.ifBlank { "Queue" }}\" saved · ${items.size} items")
                name = null
            }) { Text("Save", color = accent()) } },
            dismissButton = { STextButton({ name = null }) { Text("Cancel") } })
    }
}
