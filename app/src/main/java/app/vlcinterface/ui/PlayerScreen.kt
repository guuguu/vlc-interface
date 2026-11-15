package app.vlcinterface.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.vlcinterface.App
import app.vlcinterface.MainActivity
import app.vlcinterface.data.SubStyle
import app.vlcinterface.player.PlayItem
import app.vlcinterface.player.PlayerState
import app.vlcinterface.sources.OpenSubtitles
import app.vlcinterface.sources.OsHash
import app.vlcinterface.sources.SubResult
import app.vlcinterface.sources.SubtitleFiles
import app.vlcinterface.sources.Urls
import app.vlcinterface.sources.guessTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import androidx.compose.ui.layout.onSizeChanged

private enum class Panel { NONE, TRACKS, DOWNLOAD, STYLE, SYNC_SUB, SYNC_AUDIO, PICTURE }

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private val scales = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Fit", MediaPlayer.ScaleType.SURFACE_FILL to "Fill",
    MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Crop", MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3", MediaPlayer.ScaleType.SURFACE_ORIGINAL to "1:1",
)

@Composable
fun PlayerScreen(nav: Navigator, openOutput: () -> Unit) {
    val player = App.instance.player
    val p by player.state.collectAsState()
    val st by App.instance.store.state.collectAsState()
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val videoPortrait = p.videoH > p.videoW && p.videoW > 0
    val inPip by MainActivity.inPip

    var panel by remember { mutableStateOf(Panel.NONE) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var scaleIdx by remember { mutableIntStateOf(scales.indexOfFirst { it.first == player.scale }.coerceAtLeast(0)) }
    // One video view for the whole player screen: it moves between the portrait and landscape layouts
    // instead of being recreated, and the picture is re-fitted every time its area changes size.
    val videoView = remember {
        VLCVideoLayout(ctx).also { v ->
            v.keepScreenOn = true
            // If Android ever takes the view off screen and puts it back (PiP, multi-window), give libVLC a fresh surface.
            var wasDetached = false
            v.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(view: android.view.View) { wasDetached = true }
                override fun onViewAttachedToWindow(view: android.view.View) { if (wasDetached) { wasDetached = false; player.reattach(v) } }
            })
        }
    }
    DisposableEffect(Unit) { onDispose { player.detach(videoView); player.resetPicture() } }
    LaunchedEffect(landscape, p.videoW, p.videoH) { kotlinx.coroutines.delay(150); player.refreshSurface() }
    // Entering/leaving picture-in-picture: Android swaps the window surface, so hand libVLC a fresh one
    // (the picture stayed black on the first minimise until going back and forth).
    LaunchedEffect(inPip) { kotlinx.coroutines.delay(250); player.reattach(videoView) }
    var toast by remember { mutableStateOf("") }
    val volDrag = remember { VolumeDrag(ctx) }
    var touchTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Immersive while the player is open.
    DisposableEffect(Unit) {
        val w = activity?.window
        val ctl = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(controls, touchTick, panel) {
        if (controls && panel == Panel.NONE) { delay(4000); if (p.isPlaying) controls = false }
    }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(900); toast = "" } }
    // Music never uses the video player: hand over to the music screen (also when a queue moves on to a song).
    LaunchedEffect(p.item?.uri, p.item?.startMs) { if (p.item?.isMusic == true) nav.replace(Screen.Music) }

    // Auto-download subtitles when nothing in the preferred languages is present.
    LaunchedEffect(p.item?.uri) {
        val item = p.item ?: return@LaunchedEffect
        if (item.isRadio || !st.settings.autoDownloadSubs || st.settings.osApiKey.isBlank()) return@LaunchedEffect
        delay(4000)
        val tracks = player.state.value.spuTracks.filter { it.id >= 0 }
        val langs = st.settings.subLangs.map { it.lowercase() }
        if (tracks.any { t -> langs.any { t.name.lowercase().contains(it) } }) return@LaunchedEffect
        if (tracks.isNotEmpty() && langs.isEmpty()) return@LaunchedEffect
        val os = OpenSubtitles(st.settings)
        val src = st.sources.firstOrNull { it.id == item.sourceId }
        val hash = OsHash.compute(ctx, item.uri, src)
        val (title, year) = guessTitle(item.title)
        os.search(hash?.first, if (hash == null) title else "", if (hash == null) year else "", langs).onSuccess { list ->
            val best = list.firstOrNull { it.hashMatch } ?: list.firstOrNull()
            if (best != null && (best.hashMatch || hash == null)) {
                os.download(best).onSuccess { (bytes, ext) ->
                    val name = SubtitleFiles.nameFor(item, ext)
                    // Never overwrite an existing subtitle without asking: skip the automatic download then.
                    if (!SubtitleFiles.existsNextToVideo(item, name)) {
                        val (ref, _) = SubtitleFiles.saveDownloaded(ctx, item, bytes, ext)
                        player.addSubtitleFile(Uri.fromFile(ref.local)); toast = "Subtitles added (${best.language})"
                    }
                }
            }
        }
    }

    val video: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black)) {
            AndroidView(factory = { _ ->
                    (videoView.parent as? android.view.ViewGroup)?.removeView(videoView)
                    videoView.also { player.attach(it) }
                },
                modifier = Modifier.fillMaxSize().onSizeChanged { player.refreshSurface() },
                onRelease = { /* the same view is reused by the next layout; detached when the screen closes */ })
            if (!inPip) GestureLayer(
                onTap = { controls = !controls; touchTick++ },
                onSeek = { fwd -> val s = st.settings.doubleTapSeekSec * 1000L; player.seekBy(if (fwd) s else -s); toast = if (fwd) "+${st.settings.doubleTapSeekSec} s" else "−${st.settings.doubleTapSeekSec} s" },
                onHold = { on -> if (st.settings.holdForSpeed) { player.setRate(if (on) 2f else 1f); toast = if (on) "2× speed" else "" } },
                onVolumeStart = { volDrag.start() },
                onVolume = { total -> if (st.settings.volumeSwipe) toast = "Volume ${volDrag.update(total)}%" },
                onBrightness = { d -> if (st.settings.brightnessSwipe) toast = "Brightness ${changeBrightness(activity, d)}%" },
                locked = locked,
            )
            if (p.buffering < 100f && !p.isPlaying && p.error.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center), color = accent())
            if (p.error.isNotEmpty()) Text(p.error, color = C.bad, modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000)).padding(12.dp))
            if (toast.isNotEmpty()) Text(toast, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }

    val syncPanelOpen = panel == Panel.SYNC_SUB || panel == Panel.SYNC_AUDIO
    val fullscreen = inPip || landscape || videoPortrait
    val syncBelowVideo = !fullscreen
    val ratio = (if (p.videoW > 0 && p.videoH > 0) p.videoW.toFloat() / p.videoH else 16f / 9f).coerceIn(1f, 3f)

    // The video is always drawn from this one place, so its view is never moved or recreated when the
    // phone rotates or PiP starts (moving it made the picture disappear): only its size and position change.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video(if (fullscreen) Modifier.fillMaxSize() else Modifier.safeDrawingPadding().fillMaxWidth().aspectRatio(ratio))
        if (inPip) {
            // Minimised (picture-in-picture): thin progress bar along the bottom (not for live radio/streams).
            if (p.length > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth((p.time.toFloat() / p.length).coerceIn(0f, 1f)).fillMaxHeight().background(accent()))
            }
            return@Box
        }
        if (fullscreen) {
            // Picture fills the screen; controls and panels sit on top of it.
            if (controls && panel == Panel.NONE) ControlsOverlay(p, nav, openOutput, locked, scales[scaleIdx].second,
                onLock = { locked = !locked }, onPanel = { panel = Panel.TRACKS }, onPicture = { panel = Panel.PICTURE },
                onScale = { scaleIdx = (scaleIdx + 1) % scales.size; player.setScale(scales[scaleIdx].first); toast = scales[scaleIdx].second },
                onRotate = { rotate(activity, landscape) }, onPip = { MainActivity.instance?.enterPip() }, onTouch = { touchTick++ })
            when (panel) {
                Panel.TRACKS, Panel.DOWNLOAD, Panel.STYLE, Panel.PICTURE -> SidePanel(landscape, onClose = { panel = Panel.NONE }) {
                    PanelContent(panel, p, onPanel = { panel = it })
                }
                Panel.SYNC_SUB, Panel.SYNC_AUDIO -> OverlaySync(panel == Panel.SYNC_SUB, p, st.settings.syncPanelOpacity) { panel = Panel.TRACKS }
                else -> {}
            }
        } else {
            // Phone upright with a widescreen video: video on top, everything else below it.
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                    if (controls && panel == Panel.NONE) ControlsOverlay(p, nav, openOutput, locked, scales[scaleIdx].second,
                        onLock = { locked = !locked }, onPanel = { panel = Panel.TRACKS }, onPicture = { panel = Panel.PICTURE },
                        onScale = { scaleIdx = (scaleIdx + 1) % scales.size; player.setScale(scales[scaleIdx].first) },
                        onRotate = { rotate(activity, landscape) }, onPip = { MainActivity.instance?.enterPip() }, onTouch = { touchTick++ }, compact = true)
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(C.bg)) {
                    Column(Modifier.fillMaxSize()) {
                        SeekRow(p, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                syncPanelOpen && syncBelowVideo -> PortraitSync(if (panel == Panel.SYNC_SUB) 0 else 1, p, onSwitch = { panel = if (it == 0) Panel.SYNC_SUB else Panel.SYNC_AUDIO }) { panel = Panel.TRACKS }
                                panel != Panel.NONE -> Column(Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(C.panel)) {
                                    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(when (panel) { Panel.DOWNLOAD -> "Download subtitles"; Panel.STYLE -> "Subtitle style"; Panel.PICTURE -> "Picture colour"; else -> "Audio & subtitles" },
                                            color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                        IconButton({ panel = if (panel == Panel.TRACKS) Panel.NONE else Panel.TRACKS }) { Text("✕", color = C.text) }
                                    }
                                    PanelContent(panel, p, onPanel = { panel = it })
                                }
                                else -> PortraitIdle(p, onPanel = { panel = Panel.TRACKS }, onOutput = openOutput)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun rotate(a: Activity?, landscape: Boolean) {
    a?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

/**
 * Volume swipe: remembers the volume when the finger lands and follows the total distance moved,
 * so slow drags count (each tiny move used to round down to nothing, then jump) and the level
 * tracks the finger. A swipe over ~80 % of the video height goes from silent to full.
 */
private class VolumeDrag(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var base = 0f
    fun start() { base = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() }
    fun update(total: Float): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val nv = kotlin.math.round(base + total * max * 1.25f).toInt().coerceIn(0, max)
        if (nv != am.getStreamVolume(AudioManager.STREAM_MUSIC)) am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
        return nv * 100 / max
    }
}

private fun changeBrightness(a: Activity?, delta: Float): Int {
    val w = a?.window ?: return 0
    val lp = w.attributes
    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
    lp.screenBrightness = (cur + delta).coerceIn(0.02f, 1f)
    w.attributes = lp
    return (lp.screenBrightness * 100).toInt()
}

@Composable
private fun GestureLayer(
    onTap: () -> Unit, onSeek: (Boolean) -> Unit, onHold: (Boolean) -> Unit,
    onVolumeStart: () -> Unit, onVolume: (Float) -> Unit, onBrightness: (Float) -> Unit, locked: Boolean,
) {
    val scope = rememberCoroutineScope()
    // A slow volume/brightness swipe must not also count as "hold for 2× speed".
    val dragging = remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()
        .pointerInput(locked) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { o -> if (!locked) onSeek(o.x > size.width / 2) },
                onPress = {
                    if (locked) return@detectTapGestures
                    var held = false
                    val job: Job = scope.launch { delay(600); if (!dragging.value) { held = true; onHold(true) } }
                    tryAwaitRelease()
                    job.cancel()
                    if (held) onHold(false)
                },
            )
        }
        .pointerInput(locked) {
            if (locked) return@pointerInput
            var right = false
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { o -> dragging.value = true; right = o.x > size.width / 2; total = 0f; if (right) onVolumeStart() },
                onDragEnd = { dragging.value = false },
                onDragCancel = { dragging.value = false },
                onVerticalDrag = { change, dy ->
                    change.consume()
                    val d = -dy / size.height
                    total += d
                    if (right) onVolume(total) else onBrightness(d)
                },
            )
        })
}

@Composable
private fun BoxScope.ControlsOverlay(
    p: PlayerState, nav: Navigator, openOutput: () -> Unit, locked: Boolean, scaleLabel: String,
    onLock: () -> Unit, onPanel: () -> Unit, onPicture: () -> Unit, onScale: () -> Unit, onRotate: () -> Unit, onPip: () -> Unit,
    onTouch: () -> Unit, compact: Boolean = false,
) {
    val player = App.instance.player
    Box(Modifier.matchParentSize().background(Color(0x66000000)))
    if (locked) {
        IconButton(onLock, Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Filled.Lock, "Unlock controls", tint = Color.White) }
        return
    }
    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton({ nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
        HomeButton(nav, Color.White)
        Text(p.item?.title ?: "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!compact) OutputPill(openOutput)
        OverlayChip("Audio & subs") { onTouch(); onPanel() }
        // Dedicated button: hue & saturation, straight to the sliders.
        OverlayChip(if (compact) "Colour" else "Hue & sat") { onTouch(); onPicture() }
        if (!compact) OverlayChip("${"%.2f".format(p.rate).trimEnd('0').trimEnd('.')}×") {
            val rates = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
            val i = rates.indexOfFirst { kotlin.math.abs(it - p.rate) < 0.01f }
            player.setRate(rates[(i + 1) % rates.size]); onTouch()
        }
        IconButton(onLock) { Icon(Icons.Filled.Lock, "Lock controls", tint = Color.White) }
    }
    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (compact) 28.dp else 56.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn("−10", 52) { player.seekBy(-10_000); onTouch() }
        Box(Modifier.size(if (compact) 60.dp else 76.dp).clip(CircleShape).background(accent()).clickable { player.togglePause(); onTouch() }, contentAlignment = Alignment.Center) {
            Text(if (p.isPlaying) "❚❚" else "▶", color = Color(0xFF1A0E05), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        RoundBtn("+10", 52) { player.seekBy(10_000); onTouch() }
    }
    if (!compact) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        SeekRow(p, Modifier)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlayChip(scaleLabel, onScale)
            Text("  ⟳", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable(onClick = onRotate).padding(10.dp))
            Grow()
            Text("⏮", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { player.prev() }.padding(10.dp))
            Text("⏭", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { player.next() }.padding(10.dp))
            Text("PiP", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onPip).padding(10.dp))
        }
    } else Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OverlayChip(scaleLabel, onScale)
        Text("  ⟳ ", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable(onClick = onRotate).padding(8.dp))
        Text("PiP", color = Color.White, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onPip).padding(8.dp))
    }
}

@Composable
private fun SeekRow(p: PlayerState, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = if (p.length > 0) (p.time.toFloat() / p.length).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fmtTime(if (dragging != null) (dragging!! * p.length).toLong() else p.time), color = Color.White, fontSize = 13.sp)
        Slider(
            value = dragging ?: frac, onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { App.instance.player.seekTo((it * p.length).toLong()) }; dragging = null },
            enabled = p.length > 0, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = Color(0x55FFFFFF)),
        )
        Text(if (p.length > 0) "-" + fmtTime(p.length - p.time) else "Live", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun OverlayChip(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x24FFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RoundBtn(text: String, sizeDp: Int, onClick: () -> Unit) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(0x24FFFFFF)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PortraitIdle(p: PlayerState, onPanel: () -> Unit, onOutput: () -> Unit) {
    val player = App.instance.player
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(p.item?.title ?: "", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (p.isPlaying) "Pause" else "Play", filled = true) { player.togglePause() }
            Pill("Audio & subs") { onPanel() }
            OutputPill(onOutput)
        }
    }
}

@Composable
private fun SidePanel(landscape: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x80000000)).clickable(onClick = onClose))
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (landscape) 0.6f else 1f)
            .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)).background(C.panel).safeDrawingPadding().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IconButton(onClose) { Text("✕", color = C.text, fontSize = 18.sp) } }
            content()
        }
    }
}

@Composable
private fun PanelContent(panel: Panel, p: PlayerState, onPanel: (Panel) -> Unit) {
    when (panel) {
        Panel.DOWNLOAD -> DownloadPanel(p) { onPanel(Panel.TRACKS) }
        Panel.STYLE -> StylePanel { onPanel(Panel.TRACKS) }
        Panel.PICTURE -> PicturePanel { onPanel(Panel.TRACKS) }
        else -> TracksPanel(p, onPanel)
    }
}

/** Hue and saturation sliders, applied to the picture while you drag. */
@Composable
private fun PicturePanel(onBack: () -> Unit) {
    val player = App.instance.player
    val st by App.instance.store.state.collectAsState()
    var hue by remember { mutableStateOf(st.settings.pictureHue) }
    var sat by remember { mutableStateOf(st.settings.pictureSat) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hue", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${if (hue > 0) "+" else ""}${hue.toInt()}°", color = accent(), fontWeight = FontWeight.SemiBold)
        }
        Slider(hue, { hue = (if (kotlin.math.abs(it) < 4f) 0f else it); player.setPicture(hue, sat, false) },
            onValueChangeFinished = { player.setPicture(hue, sat, true) }, valueRange = -180f..180f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = C.line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saturation", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${(sat * 100).toInt()} %", color = accent(), fontWeight = FontWeight.SemiBold)
        }
        Slider(sat, { sat = (if (kotlin.math.abs(it - 1f) < 0.03f) 1f else it); player.setPicture(hue, sat, false) },
            onValueChangeFinished = { player.setPicture(hue, sat, true) }, valueRange = 0f..3f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = C.line))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Reset") { hue = 0f; sat = 1f; player.setPicture(0f, 1f, true) }
            Pill("Back") { onBack() }
        }
        Text("0° and 100 % = original colours. The first change reopens the video at the same spot for a moment. " +
            "Back to the original colours when you close the video player. Music and radio are not affected.", color = C.muted, fontSize = 12.sp)
        Box(Modifier.height(12.dp))
    }
}

@Composable
private fun TracksPanel(p: PlayerState, onPanel: (Panel) -> Unit) {
    val player = App.instance.player
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) player.addSubtitleFile(uri) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("Audio")
        if (p.audioTracks.isEmpty()) Text("No audio tracks yet", color = C.muted, fontSize = 13.sp)
        p.audioTracks.forEach { t -> TrackRow(t.name, t.id == p.audioTrack) { player.selectAudio(t.id) } }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card2).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Audio offset  ", color = C.muted, fontSize = 13.sp)
            Text("${p.audioDelayMs} ms", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Pill("Sync sound") { onPanel(Panel.SYNC_AUDIO) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Subtitles", Modifier.weight(1f))
            Pill("Aa Style") { onPanel(Panel.STYLE) }
        }
        p.spuTracks.forEach { t -> TrackRow(if (t.id < 0) "Off" else t.name, t.id == p.spuTrack) { player.selectSpu(t.id) } }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Download", filled = true) { onPanel(Panel.DOWNLOAD) }
            Pill("From file") { pick.launch(arrayOf("*/*")) }
            Pill("Sync") { onPanel(Panel.SYNC_SUB) }
        }
        Text("Subtitle offset ${p.spuDelayMs} ms", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
    }
}

@Composable
private fun TrackRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val a = accent()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Color(0x14FFFFFF) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) a else Color(0xFF5A606B), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(a))
        }
        Text(name, color = C.text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Subtitle download ----------------

@Composable
private fun DownloadPanel(p: PlayerState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by App.instance.store.state.collectAsState()
    val item = p.item ?: return
    val scope = rememberCoroutineScope()
    val (title0, year0) = remember(item.uri) { guessTitle(item.title) }
    var query by remember { mutableStateOf(title0) }
    var langs by remember { mutableStateOf(st.settings.subLangs.toSet()) }
    var results by remember { mutableStateOf<List<SubResult>?>(null) }
    var status by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }
    val os = remember(st.settings) { OpenSubtitles(st.settings) }

    fun search() {
        scope.launch {
            results = null; status = "Searching…"
            if (hash == null) hash = OsHash.compute(ctx, item.uri, st.sources.firstOrNull { it.id == item.sourceId })?.first
            val r = os.search(hash, query, if (query == title0) year0 else "", langs.toList())
            r.onSuccess { results = it; status = if (it.isEmpty()) "No subtitles found" else "" }.onFailure { results = emptyList(); status = it.message ?: "Search failed" }
        }
    }
    LaunchedEffect(Unit) { if (os.configured) search() else status = "Add your OpenSubtitles API key, username and password in Settings to download subtitles." }

    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", color = accent(), modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            Text("Detected: $title0 ${if (year0.isNotEmpty()) "($year0)" else ""}" + if (hash != null) " · file hash" else "", color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search") })
            Pill("Search", filled = true, modifier = Modifier.padding(start = 8.dp)) { search() }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (st.settings.subLangs + listOf("en", "es", "fr", "de", "it", "pt", "ro")).distinct().forEach { l ->
                Pill(l.uppercase(), selected = l in langs) { langs = if (l in langs) langs - l else langs + l }
            }
        }
        if (status.isNotEmpty()) Text(status, color = C.muted, fontSize = 13.sp)
        results?.forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.match, color = if (r.hashMatch) C.ok else C.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (r.hashMatch) Color(0xFF1E3A27) else Color(0xFF2A2F38)).padding(horizontal = 6.dp, vertical = 3.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(r.release, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.language.uppercase()} · ${r.format} · ${r.downloads} downloads" + (if (r.rating > 0) " · rated ${"%.1f".format(r.rating)}" else "") +
                        (if (!r.hashMatch) " · may need sync" else "") + (if (r.hearingImpaired) " · HI" else ""), color = C.muted, fontSize = 12.sp)
                }
                Pill("Use", filled = true) {
                    scope.launch {
                        status = "Downloading…"
                        os.download(r).onSuccess { (bytes, ext) ->
                            val name = SubtitleFiles.nameFor(item, ext)
                            if (SubtitleFiles.existsNextToVideo(item, name)) { pending = Triple(bytes, ext, name); status = "" }
                            else {
                                val (ref, where) = SubtitleFiles.saveDownloaded(ctx, item, bytes, ext)
                                App.instance.player.addSubtitleFile(Uri.fromFile(ref.local)); status = where; onBack()
                            }
                        }.onFailure { status = it.message ?: "Download failed" }
                    }
                }
            }
        }
        Text("Saved with the video's name next to the video when possible; fine-tune timing with Sync.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
    pending?.let { (bytes, ext, name) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Replace the existing subtitle?") },
            text = { Text("$name already exists next to the video and will be overwritten.") },
            confirmButton = { androidx.compose.material3.TextButton({
                pending = null
                scope.launch {
                    val (ref, where) = SubtitleFiles.saveDownloaded(ctx, item, bytes, ext)
                    App.instance.player.addSubtitleFile(Uri.fromFile(ref.local)); status = where; onBack()
                }
            }) { Text("Replace") } },
            dismissButton = { androidx.compose.material3.TextButton({ pending = null; status = "Kept the existing subtitle" }) { Text("Cancel") } },
        )
    }
}

// ---------------- Subtitle style ----------------

private val fonts = listOf("sans-serif" to "Roboto", "sans-serif-condensed" to "Condensed", "sans-serif-medium" to "Medium", "serif" to "Noto Serif", "monospace" to "Mono")
private val colors = listOf(0xFFFFFF to "White", 0xFFE14D to "Yellow", 0xC9CCD2 to "Light grey", 0x5CE1E6 to "Cyan", 0x7BD88F to "Green")

@Composable
private fun StylePanel(onBack: () -> Unit) {
    val store = App.instance.store
    val st by store.state.collectAsState()
    val s = st.subStyle
    fun set(n: SubStyle) = store.update { it.copy(subStyle = n) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", color = accent(), modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            Grow()
            Pill("Reset") { set(SubStyle()) }
        }
        // Live preview of the chosen look.
        Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B3A4A)), contentAlignment = if (s.position == "top") Alignment.TopCenter else Alignment.BottomCenter) {
            Text("Subtitle preview line", color = Color(0xFF000000 or s.color.toLong()), fontSize = (18 * s.sizePct / 100f).sp,
                fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center,
                fontFamily = when (s.font) { "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; else -> FontFamily.SansSerif },
                modifier = Modifier.padding(8.dp).then(if (s.edges == "box") Modifier.background(Color(0xA0000000)).padding(horizontal = 6.dp) else Modifier))
        }
        Text("Font", color = C.muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fonts.forEach { (f, l) -> Pill(l, selected = s.font == f) { set(s.copy(font = f)) } }
        }
        Text("Size ${s.sizePct}%", color = C.muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(s.sizePct.toFloat(), { set(s.copy(sizePct = it.toInt())) }, valueRange = 50f..200f, steps = 14, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
            Pill("Bold", selected = s.bold, modifier = Modifier.padding(start = 8.dp)) { set(s.copy(bold = !s.bold)) }
        }
        Text("Colour", color = C.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            colors.forEach { (c, _) ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                    .border(if (s.color == c) 3.dp else 1.dp, if (s.color == c) accent() else C.line, CircleShape).clickable { set(s.copy(color = c)) })
            }
        }
        Text("Edges", color = C.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("outline" to "Outline", "shadow" to "Shadow", "box" to "Dark box", "none" to "None").forEach { (k, l) -> Pill(l, selected = s.edges == k) { set(s.copy(edges = k)) } }
        }
        Text("Position · ${s.edgePct}% from edge", color = C.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Bottom", selected = s.position == "bottom") { set(s.copy(position = "bottom")) }
            Pill("Top", selected = s.position == "top") { set(s.copy(position = "top")) }
            Slider(s.edgePct.toFloat(), { set(s.copy(edgePct = it.toInt())) }, valueRange = 0f..40f, modifier = Modifier.weight(1f).padding(start = 8.dp),
                colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
        }
        Pill("Apply to video", filled = true) { App.instance.player.applyStyle() }
        Text("Applies to all videos. The video reloads for a moment at the same position. Styled (ASS) subtitles keep their own look.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
}

// ---------------- Sync ----------------

private class QuickSync { var t1 = 0L }

@Composable
private fun SyncControls(subs: Boolean, p: PlayerState, compact: Boolean, onDone: () -> Unit) {
    val player = App.instance.player
    val value = if (subs) p.spuDelayMs else p.audioDelayMs
    fun set(v: Long) = if (subs) player.setSpuDelay(v) else player.setAudioDelay(v)
    var scope by remember(subs) { mutableStateOf(if (subs) "file" else "device") }
    val q = remember(subs) { QuickSync() }
    var step1 by remember(subs) { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val co = rememberCoroutineScope()
    val ctx = LocalContext.current
    val steps = listOf(-1000L to "−1 s", -100L to "−0.1 s", -50L to "−0.05 s", 50L to "+0.05 s", 100L to "+0.1 s", 1000L to "+1 s")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (value >= 0) "+$value ms" else "$value ms", color = accent(), fontSize = if (compact) 22.sp else 32.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(if (subs) "Text comes after the voice? Move it earlier (−)" else "Voice comes after the lips move? Move sound earlier (−)",
            color = Color(0xFFE2E4E8), fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            steps.forEach { (d, l) -> SyncBtn(l) { set(value + d) } }
            SyncBtn("Reset") { set(0) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SyncBtn(if (subs) "1 · Heard the line" else "1 · Saw it", highlight = step1) { q.t1 = System.currentTimeMillis(); step1 = true }
            SyncBtn(if (subs) "2 · Saw the text" else "2 · Heard it") {
                if (step1) { set(value - (System.currentTimeMillis() - q.t1)); step1 = false }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Apply to", color = Color(0xFFE2E4E8), fontSize = 12.sp)
            if (subs) {
                Pill("This file", selected = scope == "file") { scope = "file" }
                Pill("Whole folder", selected = scope == "folder") { scope = "folder" }
            } else {
                Pill("This file", selected = scope == "file") { scope = "file" }
                Pill(player.currentOutputName.ifEmpty { "This output" }, selected = scope == "device") { scope = "device" }
            }
            Pill("Done", filled = true) {
                val item = p.item
                if (subs && scope == "file" && item != null && value != 0L) {
                    // Write the correction into the subtitle file itself when it is a separate file.
                    co.launch {
                        val ref = SubtitleFiles.findSidecar(ctx, item)
                        if (ref != null) {
                            SubtitleFiles.applyShift(ctx, ref, value).onSuccess { msg ->
                                player.setSpuDelay(0); player.saveSpuDelay("file")
                                player.addSubtitleFile(Uri.fromFile(ref.local)); info = msg
                            }.onFailure { player.saveSpuDelay("file"); info = "Saved for this file in the app" }
                        } else { player.saveSpuDelay("file"); info = "Subtitle is inside the video: offset saved for this file" }
                        onDone()
                    }
                } else {
                    if (subs) player.saveSpuDelay(scope) else player.saveAudioDelay(scope)
                    onDone()
                }
            }
        }
        val item = p.item
        if (subs && item != null) {
            val ref = SubtitleFiles.current(item)
            if (ref != null && SubtitleFiles.canUndo(ctx, ref)) SyncBtn("Undo timing change") {
                co.launch { SubtitleFiles.undo(ctx, ref).onSuccess { info = it; player.addSubtitleFile(Uri.fromFile(ref.local)) } }
            }
        }
        if (info.isNotEmpty()) Text(info, color = Color(0xFFE2E4E8), fontSize = 12.sp)
    }
}

@Composable
private fun SyncBtn(text: String, highlight: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(if (highlight) accent().copy(alpha = 0.35f) else Color(0x24FFFFFF))
        .clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** See-through panel over the picture (landscape, or portrait-format video). Never scrolls; items wrap instead. */
@Composable
private fun BoxScope.OverlaySync(subs: Boolean, p: PlayerState, opacity: Int, onDone: () -> Unit) {
    val store = App.instance.store
    var top by remember(subs) { mutableStateOf(subs) }
    Column(Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter).safeDrawingPadding().padding(12.dp).widthIn(max = 720.dp).fillMaxWidth()
        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF101216).copy(alpha = opacity / 100f)).border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(16.dp))
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (subs) "Subtitle sync" else "Sound sync", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Panel $opacity%", color = Color(0xFFE2E4E8), fontSize = 12.sp)
            Slider(opacity.toFloat(), { v -> store.update { it.copy(settings = it.settings.copy(syncPanelOpacity = v.toInt())) } }, valueRange = 20f..80f,
                modifier = Modifier.widthIn(min = 120.dp, max = 160.dp), colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
            SyncBtn(if (top) "Move ↓" else "Move ↑") { top = !top }
        }
        SyncControls(subs, p, compact = true, onDone = onDone)
    }
}

/** Phone upright with a widescreen video: the sync card sits below the picture, so it is opaque. */
@Composable
private fun PortraitSync(which: Int, p: PlayerState, onSwitch: (Int) -> Unit, onDone: () -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(C.panel).scrollbar(scroll).verticalScroll(scroll).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.bg).padding(3.dp)) {
            listOf("Subtitle sync", "Sound sync").forEachIndexed { i, l ->
                Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(if (which == i) Color(0xFF2A2F38) else Color.Transparent).clickable { onSwitch(i) },
                    contentAlignment = Alignment.Center) { Text(l, color = if (which == i) C.text else C.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        SyncControls(which == 0, p, compact = false, onDone = onDone)
    }
}
