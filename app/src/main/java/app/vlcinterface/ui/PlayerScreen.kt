@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.vlcinterface.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
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
import app.vlcinterface.player.Track
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

/** The "Audio sync" bar is open (from its button on the video player). */
private object AudioSyncUi {
    var open by androidx.compose.runtime.mutableStateOf(false)
}

/** The video player's queue panel is open ("Queue" button). */
private object VideoQueueUi {
    var open by androidx.compose.runtime.mutableStateOf(false)
}

/** Label of the Audio sync button: the value when one is set ("Audio sync 0.25 s"). */
private fun syncLabel(ms: Long): String {
    // Just the name, never the value (2026-11-21 15:48); the value is in the bar it opens.
    return "Audio/Sync"
}

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
    // Each video opens with the picture format kept for it.
    LaunchedEffect(p.item?.uri) { kotlinx.coroutines.delay(100); scaleIdx = scales.indexOfFirst { it.first == player.scale }.coerceAtLeast(0) }
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
    DisposableEffect(Unit) { player.syncPicture(); onDispose { player.detach(videoView); player.syncPicture() } }
    // Battery at 10 % or less (not charging): the video no longer keeps the screen on either.
    val lowBat by app.vlcinterface.player.BatteryGuard.low.collectAsState()
    LaunchedEffect(lowBat) { videoView.keepScreenOn = !lowBat }
    LaunchedEffect(landscape, p.videoW, p.videoH) { kotlinx.coroutines.delay(150); player.refreshSurface() }
    // Entering/leaving picture-in-picture: Android swaps the window surface, so hand libVLC a fresh one
    // (the picture stayed black on the first minimise until going back and forth).
    LaunchedEffect(inPip) { kotlinx.coroutines.delay(250); player.reattach(videoView) }
    var toast by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
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
            activity?.requestedOrientation = defaultOrientation()
        }
    }
    // Format analysis mode: every control stays on screen (also in the photos).
    val analysing by FormatCheck.mode.collectAsState()
    LaunchedEffect(analysing) { if (analysing) controls = true }
    LaunchedEffect(controls, touchTick, panel, analysing) {
        if (controls && panel == Panel.NONE && !analysing) { delay(4000); if (p.isPlaying) controls = false }
    }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(900); toast = "" } }
    // Music never uses the video player: hand over to the music screen (also when a queue moves on to a song).
    LaunchedEffect(p.item?.uri, p.item?.startMs) { if (p.item?.isMusic == true || p.item?.isRadio == true) nav.replace(Screen.Music) }

    // A video with subtitle tracks inside it (2026-11-21 16:14): a pop-up to choose which one to use, once per video
    // opened (not again when the same video reopens at the same spot); a tap on a choice sets it and closes the pop-up.
    var subAsk by remember { mutableStateOf(false) }
    LaunchedEffect(p.item?.uri) {
        val item = p.item ?: return@LaunchedEffect
        if (item.isRadio || item.isMusic || item.uri in SubAskOnce.asked) return@LaunchedEffect
        // The tracks arrive while the file opens: wait for them a few seconds.
        repeat(16) {
            if (player.state.value.item?.uri != item.uri) return@LaunchedEffect
            if (player.state.value.spuTracks.any { it.id >= 0 }) { delay(600); SubAskOnce.asked += item.uri; subAsk = true; return@LaunchedEffect }
            delay(500)
        }
    }
    if (subAsk) SubtitleChoiceDialog(p, onPick = { id -> player.selectSpu(id); subAsk = false }) { subAsk = false }

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
            // The picture is made as large as the chosen format allows: it grows (small videos too) until it reaches
            // the edges of the area — Fit / 1:1: the video's own shape; 16:9, 4:3: that shape; Fill: the whole area
            // (stretched); Crop: the whole area, zoomed in by libVLC.
            // The video view always covers the whole area; libVLC fits the picture in it by the chosen format
            // with the picture's real proportions (see PlayerController.vlcScale).
            val viewMod = Modifier.fillMaxSize()
            AndroidView(factory = { _ ->
                    (videoView.parent as? android.view.ViewGroup)?.removeView(videoView)
                    videoView.also { player.attach(it) }
                },
                modifier = viewMod.align(Alignment.Center).onSizeChanged { player.refreshSurface() },
                onRelease = { /* the same view is reused by the next layout; detached when the screen closes */ })
            if (!inPip) GestureLayer(
                onTap = { if (!analysing) controls = !controls; touchTick++ },
                onSeek = { fwd -> val s = st.settings.doubleTapSeekSec * 1000L; player.seekBy(if (fwd) s else -s); toast = if (fwd) "+${st.settings.doubleTapSeekSec} s" else "−${st.settings.doubleTapSeekSec} s" },
                // Long press on the picture: the video's details (replaces the old "hold for 2× speed").
                onHold = { on -> if (on) showDetails = true },
                onVolumeStart = { volDrag.start() },
                onVolume = { total -> if (st.settings.volumeSwipe) toast = "Volume ${volDrag.update(total)}%" },
                onBrightness = { d -> if (st.settings.brightnessSwipe) toast = "Brightness ${changeBrightness(activity, d)}%" },
                locked = locked,
                // Double tap in the middle third of the picture: picture-in-picture (2026-11-21 15:50, replaces the PiP button);
                // left / right thirds still seek back / forward.
                onCenterDouble = { MainActivity.instance?.enterPip() },
            )
            if (p.buffering < 100f && !p.isPlaying && p.error.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center), color = accent())
            if (p.error.isNotEmpty()) Text(p.error, color = C.bad, modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000)).padding(12.dp))
            if (toast.isNotEmpty()) Text(toast, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }

    val syncPanelOpen = panel == Panel.SYNC_SUB || panel == Panel.SYNC_AUDIO
    // Tall video upright: upper two thirds, buttons in the lower third — unless "Full screen" (double tap in the middle toggles).
    val tallFull = st.settings.videoTallFull
    // Upright (15:32): always the picture on top with everything else below it (a tall video's "Bigger" gives it more room).
    val fullscreen = inPip || landscape
    val syncBelowVideo = !fullscreen
    val ratio = (if (p.videoW > 0 && p.videoH > 0) p.videoW.toFloat() / p.videoH else 16f / 9f).coerceIn(1f, 3f)

    // The video is always drawn from this one place, so its view is never moved or recreated when the
    // phone rotates or PiP starts (moving it made the picture disappear): only its size and position change.
    // Video format check (from the details pop-up): every format, upright and sideways, photographed and measured.
    val checkNote by FormatCheck.running.collectAsState()
    val checkScope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    fun startCheck() {
        val a = activity ?: return
        if (checking) return
        checking = true; controls = true; panel = Panel.NONE
        checkScope.launch {
            val where = try {
                FormatCheck.run(a, scales, { i -> scaleIdx = i; player.setScale(scales[i].first) }, scaleIdx)
            } catch (_: Throwable) { null }
            checking = false
            ShortNote.show(if (where != null) "Format analysis saved: $where — upload that zip" else "Format analysis could not be saved")
        }
    }
    if (showDetails) VideoDetailsDialog { showDetails = false }
    // Upright (2026-11-21 15:30): a header bar above the picture like the song player's — symbols on the first row,
    // the other two players and the output on the second — and those buttons are no longer drawn on the picture.
    val headerOn = !landscape && !inPip
    val dens = androidx.compose.ui.platform.LocalDensity.current
    var headerHpx by remember { mutableIntStateOf(0) }
    val headerDp = if (headerOn) with(dens) { headerHpx.toDp() } else 0.dp
    // Upright: the picture's place under the header, measured from the layout below, so the video fills exactly it.
    var videoHpx by remember { mutableIntStateOf(0) }
    val videoDp = with(dens) { videoHpx.toDp() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video(if (fullscreen && !headerOn) Modifier.fillMaxSize()
            else if (fullscreen) Modifier.safeDrawingPadding().padding(top = headerDp).fillMaxSize()
            else Modifier.safeDrawingPadding().padding(top = headerDp).fillMaxWidth().height(videoDp))
        if (headerOn) VideoHeader(nav, locked, openOutput, onLock = { locked = !locked },
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().onSizeChanged { headerHpx = it.height })
        // Analysis mode, not running yet: the button that starts it (and ✕ to leave the mode). Kept off the photos.
        if (analysing && !checking && !inPip) Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xD0000000)),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Analyse ${FormatCheck.VIDEOS} videos", color = accent(), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { startCheck() }.padding(horizontal = 14.dp, vertical = 10.dp))
            Text("✕", color = Color.White, fontSize = 15.sp, modifier = Modifier.clickable { FormatCheck.mode.value = false }.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        if (checkNote.isNotEmpty()) Text(checkNote, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter)
            .padding(top = 40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB0000000)).padding(horizontal = 10.dp, vertical = 5.dp))
        if (inPip) {
            // Minimised (picture-in-picture): thin progress bar along the bottom (not for live radio/streams).
            if (p.length > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth((p.time.toFloat() / p.length).coerceIn(0f, 1f)).fillMaxHeight().background(accent()))
            }
            return@Box
        }
        if (fullscreen) {
            // Picture fills the screen; controls and panels sit on top of it.
            if (controls && panel == Panel.NONE) Box(if (headerOn) Modifier.fillMaxSize().safeDrawingPadding().padding(top = headerDp) else Modifier.fillMaxSize()) {
                ControlsOverlay(p, nav, openOutput, locked, scales[scaleIdx].second + "\u0000" + scales[(scaleIdx + 1) % scales.size].second,
                onLock = { locked = !locked }, onPanel = { panel = Panel.TRACKS }, onPicture = { panel = Panel.PICTURE },
                onScale = { scaleIdx = (scaleIdx + 1) % scales.size; player.setScale(scales[scaleIdx].first); toast = scales[scaleIdx].second },
                onRotate = { rotate(activity, landscape) }, onPip = { MainActivity.instance?.enterPip() }, onTouch = { touchTick++ }) }
            if (AudioSyncUi.open) AudioSyncBar { AudioSyncUi.open = false }
            when (panel) {
                // Hue & saturation over the full-screen picture: a slim bar at the bottom, nothing darkened,
                // so most of the picture stays visible and shows the real effect.
                Panel.PICTURE -> PictureBar(onClose = { panel = Panel.NONE })
                Panel.TRACKS, Panel.DOWNLOAD, Panel.STYLE -> SidePanel(landscape, onClose = { panel = Panel.NONE },
                    onBack = { panel = if (panel == Panel.TRACKS) Panel.NONE else Panel.TRACKS }) {
                    PanelContent(panel, p, onPanel = { panel = it })
                }
                Panel.SYNC_SUB, Panel.SYNC_AUDIO -> OverlaySync(panel == Panel.SYNC_SUB, p, st.settings.syncPanelOpacity) { panel = Panel.TRACKS }
                else -> {}
            }
        } else {
            // Phone upright with a widescreen video: video on top, everything else below it.
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.fillMaxWidth().height(headerDp))
                // Tall video: two thirds of the room, or more with "Bigger"; wide video: its own shape.
                val vMod = if (videoPortrait) Modifier.fillMaxHeight(if (tallFull) 0.82f else 0.62f) else Modifier.aspectRatio(ratio)
                Box(Modifier.fillMaxWidth().then(vMod).onSizeChanged { videoHpx = it.height }) {
                    // On the picture upright: only the position bar (comes and goes with a tap); everything else is below.
                    if (controls && panel == Panel.NONE && !locked) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x77000000))) {
                        SeekRow(p, Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    }
                    if (AudioSyncUi.open) AudioSyncBar { AudioSyncUi.open = false }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(C.bg)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                syncPanelOpen && syncBelowVideo -> PortraitSync(if (panel == Panel.SYNC_SUB) 0 else 1, p, onSwitch = { panel = if (it == 0) Panel.SYNC_SUB else Panel.SYNC_AUDIO }) { panel = Panel.TRACKS }
                                panel != Panel.NONE -> Column(Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(C.panel)) {
                                    Row(Modifier.fillMaxWidth().padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                                        SymButton({ panel = if (panel == Panel.TRACKS || panel == Panel.PICTURE) Panel.NONE else Panel.TRACKS }) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
                                        Text(when (panel) { Panel.DOWNLOAD -> "Download subtitles"; Panel.STYLE -> "Subtitle style"; Panel.PICTURE -> "Picture colour"; else -> "Audio & subtitles" },
                                            color = C.text, fontSize = (17 * btnScale()).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                        SymButton({ panel = Panel.NONE }) { Text("✕", color = C.text) }
                                    }
                                    PanelContent(panel, p, onPanel = { panel = it })
                                }
                                else -> PortraitIdle(p, nav, showList = !videoPortrait, onPanel = { panel = Panel.TRACKS }, onOutput = openOutput, onPicture = { panel = Panel.PICTURE },
                                    scaleLabel = scales[scaleIdx].second + "\u0000" + scales[(scaleIdx + 1) % scales.size].second,
                                    onScale = { scaleIdx = (scaleIdx + 1) % scales.size; player.setScale(scales[scaleIdx].first); toast = scales[scaleIdx].second },
                                    onRotate = { rotate(activity, landscape) }, onPip = { MainActivity.instance?.enterPip() })
                            }
                        }
                    }
                }
            }
        }
        // Queue / browse (2026-11-21 14:47): over everything, the video keeps playing.
        if (VideoQueueUi.open) SidePanel(landscape, onClose = { VideoQueueUi.open = false }) {
            VideoQueuePanel(p, nav) { VideoQueueUi.open = false }
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
    private var own = false
    private var warned = false
    private var ownBase = 1f
    fun start() {
        // Speaker picked in the app while Android routes media to Bluetooth: Android's volume would change the
        // Bluetooth device, so the swipe changes the app's volume on the device that is playing.
        warned = false
        own = app.vlcinterface.App.instance.output.overriding && !app.vlcinterface.player.BtQuality.on()
        if (own) ownBase = app.vlcinterface.App.instance.player.appVolume
        base = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
    }
    fun update(total: Float): Int {
        if (own) {
            val p = app.vlcinterface.App.instance.player
            p.setAppVolume(ownBase + total * 1.25f)
            return (p.appVolume * 100).toInt()
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val nv = kotlin.math.round(base + total * max * 1.25f).toInt().coerceIn(0, max)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (nv != cur) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
            // Android's hearing protection ("safe media volume", headphones / Bluetooth) silently refuses raising
            // the volume until you confirm its warning: then ask through Android, which shows that warning.
            if (nv > cur && am.getStreamVolume(AudioManager.STREAM_MUSIC) == cur && !warned) {
                warned = true
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
        }
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
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
    onCenterDouble: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // A slow volume/brightness swipe must not also count as "hold for 2× speed".
    val dragging = remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()
        .pointerInput(locked) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { o ->
                    if (locked) return@detectTapGestures
                    // Middle of a tall video: switch full screen / two thirds; near the edges: seek as before.
                    if (onCenterDouble != null && o.x > size.width / 3f && o.x < size.width * 2f / 3f) onCenterDouble() else onSeek(o.x > size.width / 2)
                },
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
    // Never overlapping: on a short video area (wide films with the phone upright) the controls get smaller
    // so the top bar, the centre buttons and the bottom row always have room.
    BoxWithConstraints(Modifier.matchParentSize()) {
        val k = btnScale()
        val fit = if (compact) (maxHeight.value - 40f) / 164f else (maxHeight.value - 110f) / 200f
        CompositionLocalProvider(LocalBtnScale provides minOf(k, fit).coerceAtLeast(0.4f)) {
            ControlsOverlayBody(p, nav, openOutput, locked, scaleLabel, onLock, onPanel, onPicture, onScale, onRotate, onPip, onTouch, compact)
        }
    }
}

@Composable
private fun BoxScope.ControlsOverlayBody(
    p: PlayerState, nav: Navigator, openOutput: () -> Unit, locked: Boolean, scaleLabel: String,
    onLock: () -> Unit, onPanel: () -> Unit, onPicture: () -> Unit, onScale: () -> Unit, onRotate: () -> Unit, onPip: () -> Unit,
    onTouch: () -> Unit, compact: Boolean,
) {
    val player = App.instance.player
    val ks = btnScale()
    Box(Modifier.matchParentSize().background(Color(0x66000000)))
    if (locked) {
        SymButton(onLock, Modifier.align(Alignment.TopEnd).padding(12.dp)) { SymIcon(Icons.Filled.Lock, "Unlock controls", tint = Color.White) }
        return
    }
    // Top bar, centre buttons and bottom row stacked in one column: they can never overlap each other.
    Column(Modifier.matchParentSize()) {
    // Upright: top bar on two rows, never squeezed: symbols and text keep a readable size (the user's button size, at least 75 %).
    // Row 1: back, home, title, lock, settings and the status symbols. Row 2 (wraps if needed): output, panels, speed,
    // and the other two players.
    val topK = (App.instance.store.current.settings.buttonScale / 100f).coerceAtLeast(0.75f)
    val landTop = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val speedChip: @Composable () -> Unit = {
        OverlayChip("${"%.2f".format(p.rate).trimEnd('0').trimEnd('.')}×") {
            val rates = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
            val i = rates.indexOfFirst { kotlin.math.abs(it - p.rate) < 0.01f }
            player.setRate(rates[(i + 1) % rates.size]); onTouch()
        }
    }
    // Sideways: everything on one clean row (same readable sizes; it scrolls sideways only if a narrow screen needs it).
    if (landTop) CompositionLocalProvider(LocalBtnScale provides topK) {
        // Fits the width: shrunk evenly when everything doesn't fit (15:56), never past the edge.
        FitRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), arrangement = Arrangement.spacedBy(6.dp), minGap = 6.dp) {
            SymButton({ nav.back() }) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            HomeButton(nav, Color.White)
            MarqueeText(p.item?.title ?: "", Color.White, Modifier.widthIn(max = 220.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, list = "player")
            OutputPill(openOutput)
            OverlayChip("Subs/Sync") { onTouch(); onPanel() }
            // Browse: the folder the video came from, in the Library (Back returns here).
            OverlayChip("📂 Browse") { onTouch(); openOrigin(nav, keepStack = true) }
            OverlayChip(syncLabel(p.audioDelayMs)) { onTouch(); AudioSyncUi.open = true }
            OverlayChip("Hue/Sat") { onTouch(); onPicture() }
            speedChip()
            OverlayChip("Songs") { PlayerSwitch.go(nav, "songs") }
            OverlayChip("Radio") { PlayerSwitch.go(nav, "radio") }
            SymButton(onLock) { SymIcon(Icons.Filled.Lock, "Lock controls", tint = Color.White) }
            SymButton({ nav.go(Screen.SettingsGroup("video")) }) { SymIcon(Icons.Filled.Settings, "Video settings", tint = Color.White) }
            ShazamButton(Color.White)
            BackgroundWorkButton()
            ScreenOnSign(Color.White)
            ExitButton(Color.White)
        }
    } else CompositionLocalProvider(LocalBtnScale provides topK) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Upright: back, home, lock, settings, Shazam, signs, exit, the other players and the output are in the header
        // bar above the picture (VideoHeader), not on it.
        // Short video area upright (compact): these chips are under the video instead, so nothing overlaps.
        if (!compact) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayChip("Subs/Sync") { onTouch(); onPanel() }
            // Browse: the folder the video came from, in the Library (Back returns here).
            OverlayChip("📂 Browse") { onTouch(); openOrigin(nav, keepStack = true) }
            OverlayChip(syncLabel(p.audioDelayMs)) { onTouch(); AudioSyncUi.open = true }
            // Dedicated button: hue & saturation, straight to the sliders.
            OverlayChip("Hue/Sat") { onTouch(); onPicture() }
            speedChip()
        }
    }
    }
    // The format shown now, written on the picture (comes and goes with the other controls).
    Text(scaleLabel.substringBefore('\u0000'), color = Color.White, fontSize = maxOf(13f * ks, 13f).sp, fontWeight = FontWeight.SemiBold,
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(1f, 1f), 4f)),
        modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp))
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
    AutoFitRow(Modifier.padding(horizontal = 8.dp), spacing = if (compact) 28.dp else 56.dp) {
        // Previous / next file of the folder (the list it was opened from).
        RoundBtn("⏮", 44) { player.prev(); onTouch() }
        RoundBtn("−10", 52) { player.seekBy(-10_000); onTouch() }
        val k = btnScale()
        Box(Modifier.size(maxOf((if (compact) 60 else 76) * k, 56f).dp).clip(CircleShape).background(accent()).clickable { player.togglePause(); onTouch() }, contentAlignment = Alignment.Center) {
            Text(if (p.isPlaying) "❚❚" else "▶", color = Color(0xFF1A0E05), fontSize = maxOf(24 * k, 20f).sp, fontWeight = FontWeight.Bold)
        }
        RoundBtn("+10", 52) { player.seekBy(10_000); onTouch() }
        RoundBtn("⏭", 44) { player.next(); onTouch() }
    }
    }
    // Bottom controls: never shrunk (symbols at least 22 sp, text at least 13 sp). Sideways one row; upright it wraps if needed.
    val tallVid = p.videoH > p.videoW && p.videoW > 0
    val landB = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val symSize = maxOf(20 * ks, 22f).sp
    val bottomItems: @Composable () -> Unit = {
        FormatControl(scaleLabel, onScale)
        // Rotate: turns only the film's picture (90° steps) when it was filmed / recorded the wrong way; kept for this file.
        OverlayChip(App.instance.player.videoRot.let { if (it == 0) "Rotate" else "Rotate · $it°" }) { App.instance.player.rotateVideo(); onTouch() }
        if (tallVid && !landB) OverlayChip(if (App.instance.store.current.settings.videoTallFull) "Smaller" else "Full screen") {
            App.instance.store.update { it.copy(settings = it.settings.copy(videoTallFull = !it.settings.videoTallFull)) }; onTouch() }
        Text("⏮", color = Color.White, fontSize = symSize, modifier = Modifier.clickable { player.prev() }.padding(8.dp))
        Text("⏭", color = Color.White, fontSize = symSize, modifier = Modifier.clickable { player.next() }.padding(8.dp))
    }
    if (!compact) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SeekRow(p, Modifier)
        if (landB) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { bottomItems() }
        else androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) { bottomItems() }
    } else androidx.compose.foundation.layout.FlowRow(Modifier.align(Alignment.End).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FormatControl(scaleLabel, onScale)
        // Rotate: turns only the film's picture (90° steps) when it was filmed / recorded the wrong way; kept for this file.
        OverlayChip(App.instance.player.videoRot.let { if (it == 0) "Rotate" else "Rotate · $it°" }) { App.instance.player.rotateVideo(); onTouch() }
        if (tallVid && !landB) OverlayChip(if (App.instance.store.current.settings.videoTallFull) "Smaller" else "Full screen") {
            App.instance.store.update { it.copy(settings = it.settings.copy(videoTallFull = !it.settings.videoTallFull)) }; onTouch() }
    }
    }
}

@Composable
private fun SeekRow(p: PlayerState, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = if (p.length > 0) (p.time.toFloat() / p.length).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fmtTime(if (dragging != null) (dragging!! * p.length).toLong() else p.time), color = Color.White, fontSize = 13.sp)
        // Thin track and small knob, like the song player's position bar.
        val sc = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = Color(0x55FFFFFF))
        Slider(
            value = dragging ?: frac, onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { App.instance.player.seekFraction(it) }; dragging = null },
            enabled = p.length > 0 || p.item != null, modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(26.dp),
            colors = sc, thumb = { KnobThumb(Color.White) },
            track = { stt -> SliderDefaults.Track(sliderState = stt, modifier = Modifier.height(4.dp), colors = sc, enabled = p.length > 0 || p.item != null,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
        )
        Text(if (p.length > 0) "-" + fmtTime(p.length - p.time) else "Live", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun OverlayChip(text: String, onClick: () -> Unit) {
    val k = btnScale()
    // Never smaller than readable: at least 34 dp high and 13 sp text.
    Box(Modifier.heightIn(min = maxOf(40 * k, 34f).dp).clip(RoundedCornerShape(18.dp)).background(Color(0x33FFFFFF)).clickable(onClick = onClick).padding(horizontal = maxOf(14 * k, 12f).dp),
        contentAlignment = Alignment.Center) { Text(text, color = Color.White, fontSize = maxOf(14 * k, 13f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun RoundBtn(text: String, sizeDp: Int, onClick: () -> Unit) {
    val k = btnScale()
    Box(Modifier.size(maxOf(sizeDp * k, 44f).dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = maxOf(15 * k, 14f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PortraitIdle(p: PlayerState, nav: Navigator, showList: Boolean, onPanel: () -> Unit, onOutput: () -> Unit, onPicture: () -> Unit = {},
                         scaleLabel: String = "", onScale: () -> Unit = {}, onRotate: () -> Unit = {}, onPip: () -> Unit = {}) {
    val player = App.instance.player
    val tallVid = p.videoH > p.videoW && p.videoW > 0
    val st by App.instance.store.state.collectAsState()
    // Upright (15:32): every control of the video under the picture, in groups — title, playing, sound & picture,
    // files, picture format; the picture itself only carries the position bar.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val rowTitle: @Composable () -> Unit = {
        MarqueeText(p.item?.title ?: "", C.text, Modifier.fillMaxWidth(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, list = "player")
        }
        // Playing: previous / −10 / play-pause / +10 / next, centred.
        val rowPlaying: @Composable () -> Unit = {
        FitRow(Modifier.fillMaxWidth(), arrangement = Arrangement.SpaceEvenly, minGap = 8.dp) {
            UnderBtn("⏮") { player.prev() }
            UnderBtn("−10") { player.seekBy(-10_000) }
            Box(Modifier.size(60.dp).clip(CircleShape).background(accent()).clickable { player.togglePause() }, contentAlignment = Alignment.Center) {
                Text(if (p.isPlaying) "❚❚" else "▶", color = Color(0xFF1A0E05), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            UnderBtn("+10") { player.seekBy(10_000) }
            UnderBtn("⏭") { player.next() }
        }
        }
        // Sound & picture.
        val rowSound: @Composable () -> Unit = {
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Subs/Sync") { onPanel() }
            Pill(syncLabel(p.audioDelayMs)) { AudioSyncUi.open = true }
            Pill("Hue/Sat") { onPicture() }
            Pill("${"%.2f".format(p.rate).trimEnd('0').trimEnd('.')}×") {
                val rates = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                val i = rates.indexOfFirst { kotlin.math.abs(it - p.rate) < 0.01f }
                player.setRate(rates[(i + 1) % rates.size])
            }
        }
        }
        // Files.
        val rowFiles: @Composable () -> Unit = {
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("📂 Browse") { openOrigin(nav, keepStack = true) }
        }
        }
        // Picture format and orientation.
        val rowFormat: @Composable () -> Unit = {
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val parts = scaleLabel.split('\u0000')
            Pill(if (parts.getOrElse(1) { "" }.isEmpty()) parts[0] else "Format: ${parts[0]} → ${parts[1]}") { onScale() }
            Pill(App.instance.player.videoRot.let { if (it == 0) "Rotate" else "Rotate · $it°" }) { App.instance.player.rotateVideo() }
            if (tallVid) Pill(if (App.instance.store.current.settings.videoTallFull) "Smaller" else "Bigger") {
                App.instance.store.update { it.copy(settings = it.settings.copy(videoTallFull = !it.settings.videoTallFull)) } }
        }
        }
        val rowUpNext: @Composable () -> Unit = {
        // Up next (15:36): exactly like the song player's — "Up next" with "Queue › time left" (opens the whole queue),
        // then the next files (as many as Look → "Up next" count), numbered, gliding names, length; long press: menu.
        val next = if (p.index >= 0) p.queue.drop(p.index + 1).take(st.settings.upNextCount) else emptyList()
        val cur = p.item
        if (next.isNotEmpty() && cur != null) Column(Modifier.fillMaxWidth()) {
            var upMenu by remember { mutableIntStateOf(-1) }
            Row(verticalAlignment = Alignment.CenterVertically) { SectionTitle("Up next", Modifier.weight(1f)); QueueTimeLeft(cur) { nav.go(Screen.Queue) } }
            next.forEachIndexed { i, n ->
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { player.jumpTo(p.index + 1 + i) }, onLongClick = { upMenu = i })
                    .padding(vertical = 8.dp)) {
                    Text("${i + 1}.", color = C.muted, maxLines = 1, softWrap = false, modifier = Modifier.width(30.dp))
                    Box(Modifier.width(6.dp))
                    MarqueeText(n.title, C.text, Modifier.weight(1f).padding(end = 8.dp), fontSize = 15.sp, list = "upnext")
                    if (n.durationMs > 0) Text(fmtTime(n.durationMs), color = C.muted, fontSize = 13.sp)
                    Box { if (upMenu == i) FileMenu(nav, n, true, { upMenu = -1 }, FileMenuExtras(
                        onMoveUp = if (i > 0) ({ player.moveInQueue(p.index + 1 + i, -1) }) else null,
                        onMoveDown = if (p.index + 2 + i < p.queue.size) ({ player.moveInQueue(p.index + 1 + i, 1) }) else null,
                        removeLabel = "Remove from queue", onRemove = { player.removeFromQueue(p.index + 1 + i) })) }
                }
            }
        }
        }
        // In the order set in Look → "Order of the player rows" → Video.
        for (id in videoRowOrder(st.settings)) when (id) {
            "title" -> rowTitle(); "playing" -> rowPlaying(); "sound" -> rowSound(); "files" -> rowFiles(); "format" -> rowFormat(); "upnext" -> rowUpNext()
        }
    }
}

@Composable
private fun SidePanel(landscape: Boolean, onClose: () -> Unit, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x80000000)).clickable(onClick = onClose))
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (landscape) 0.6f else 1f)
            .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)).background(C.panel).safeDrawingPadding().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Back: one step up (sub-panel → Audio & subtitles → video); ✕ closes.
                SymButton(onBack ?: onClose) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
                Grow()
                SymButton(onClose) { Text("✕", color = C.text, fontSize = 18.sp) }
            }
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
    var hue by remember(st.settings.pictureHue) { mutableStateOf(st.settings.pictureHue) }
    var sat by remember(st.settings.pictureSat) { mutableStateOf(st.settings.pictureSat) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollbar(scroll).verticalScroll(scroll).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hue", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("%+.1f°".format(java.util.Locale.US, hue), color = accent(), fontWeight = FontWeight.SemiBold)
        }
        // Fine range: −20° … +20° over the whole slider, steps of 0.1°.
        Slider(hue.coerceIn(-20f, 20f), { hue = (if (kotlin.math.abs(it) < 0.3f) 0f else Math.round(it * 10f) / 10f); player.setPicture(hue, sat, false) },
            onValueChangeFinished = { player.setPicture(hue, sat, true) }, valueRange = -20f..20f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = C.line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saturation", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${(sat * 100).toInt()} %", color = accent(), fontWeight = FontWeight.SemiBold)
        }
        Slider(sat, { sat = (if (kotlin.math.abs(it - 1f) < 0.03f) 1f else it); player.setPicture(hue, sat, false) },
            onValueChangeFinished = { player.setPicture(hue, sat, true) }, valueRange = 0f..3f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = C.line))
        var hl by remember(st.settings.pictureHighlights) { mutableStateOf(st.settings.pictureHighlights) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bright areas", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (hl < 0.01f) "Off" else "toned down ${(hl * 100).toInt()} %", color = accent(), fontWeight = FontWeight.SemiBold)
        }
        Slider(hl, { hl = (Math.round(it * 20f) / 20f); player.setHighlights(hl, false) },
            onValueChangeFinished = { player.setHighlights(hl, true) }, valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = C.line))
        Text("Burnt-out white areas glare less and show what detail is left (it can't bring back detail that isn't in the video). Kept for all videos.",
            color = C.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Reset") { hue = 0f; sat = 1f; hl = 0f; player.setPicture(0f, 1f, true); player.setHighlights(0f, true) }
            Pill("Back") { onBack() }
        }
        Text("0° and 100 % = original colours. The first change reopens the video at the same spot for a moment. " +
            "Back to the original colours when you close the video player. Music and radio are not affected.", color = C.muted, fontSize = 12.sp)
        Box(Modifier.height(12.dp))
    }
}

/**
 * Audio sync for Bluetooth (2026-11-21 12:17 / 12:19). Over Bluetooth the sound only ever comes after the picture, so
 * the slider starts at 0 and pushes the sound forward (earlier): 0.00 … 2.00 s in 0.01 s steps on a slider across the
 * whole width for fine setting, with the value in a small box ("0.00 s"). Kept for the sound device and this video
 * together; other videos played on the same device during this session start from it.
 */
@Composable
private fun BoxScope.AudioSyncBar(onClose: () -> Unit) {
    val player = App.instance.player
    val p by player.state.collectAsState()
    // Shown as "sound forward" (seconds ≥ 0); applied as a negative audio delay (sound earlier).
    var v by remember(p.item?.uri, player.currentOutputName) { mutableStateOf((-p.audioDelayMs / 1000f).coerceIn(0f, 2f)) }
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = Color(0x66FFFFFF))
    fun put(x: Float, save: Boolean) = player.setAudioSync(-(x * 1000).toLong(), save)
    Box(Modifier.matchParentSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null, onClick = onClose))
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB30F1115))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
        .safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Audio sync · " + player.currentOutputName.ifEmpty { "this output" }, color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // The small box with the value in seconds.
            Box(Modifier.padding(horizontal = 8.dp).widthIn(min = 70.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1F24))
                .border(1.dp, accent(), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(String.format(java.util.Locale.US, "%.2f s", v), color = accent(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Pill("Reset") { v = 0f; put(0f, true) }
            SymButton(onClose) { Text("✕", color = Color.White, fontSize = 18.sp) }
        }
        // Across the whole width: 2 s over the full screen width — about a millimetre per hundredth on a phone.
        Slider(v, { x -> v = Math.round(x * 100f) / 100f; put(v, false) }, onValueChangeFinished = { put(v, true) },
            valueRange = 0f..2f, colors = colors, modifier = Modifier.fillMaxWidth().height(36.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("0", color = Color(0xFFB8BCC4), fontSize = 11.sp)
            Text("sound forward →", color = Color(0xFFB8BCC4), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("2.00 s", color = Color(0xFFB8BCC4), fontSize = 11.sp)
        }
        Text("Sound comes after the lips? Push it forward until they match. Kept for this video on " + player.currentOutputName.ifEmpty { "this output" } +
            "; other videos this session start from it." + player.audioSyncFrom.takeIf { it.isNotEmpty() }?.let { " Now: from $it." }.orEmpty(),
            color = Color(0xFFB8BCC4), fontSize = 11.sp)
    }
}

/** Compact hue / saturation bar at the bottom of the full-screen picture (no dimming above it). */
@Composable
private fun BoxScope.PictureBar(onClose: () -> Unit) {
    val player = App.instance.player
    val st by App.instance.store.state.collectAsState()
    var hue by remember(st.settings.pictureHue) { mutableStateOf(st.settings.pictureHue) }
    var sat by remember(st.settings.pictureSat) { mutableStateOf(st.settings.pictureSat) }
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent(), inactiveTrackColor = Color(0x66FFFFFF))
    // A tap anywhere on the picture (outside the bar) closes the bar.
    Box(Modifier.matchParentSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null, onClick = onClose))
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB30F1115))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
        .safeDrawingPadding()
        .padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SymButton(onClose) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Hue", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(78.dp))
            Slider(hue.coerceIn(-20f, 20f), { hue = (if (kotlin.math.abs(it) < 0.3f) 0f else Math.round(it * 10f) / 10f); player.setPicture(hue, sat, false) },
                onValueChangeFinished = { player.setPicture(hue, sat, true) }, valueRange = -20f..20f, colors = colors, modifier = Modifier.weight(1f).height(32.dp))
            Text("%+.1f°".format(java.util.Locale.US, hue), color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp).padding(start = 8.dp))
            Pill("Reset") { hue = 0f; sat = 1f; player.setPicture(0f, 1f, true) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saturation", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(78.dp))
            Slider(sat, { sat = (if (kotlin.math.abs(it - 1f) < 0.03f) 1f else it); player.setPicture(hue, sat, false) },
                onValueChangeFinished = { player.setPicture(hue, sat, true) }, valueRange = 0f..3f, colors = colors, modifier = Modifier.weight(1f).height(32.dp))
            Text("${(sat * 100).toInt()} %", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp).padding(start = 8.dp))
            SymButton(onClose) { Text("✕", color = Color.White, fontSize = 18.sp) }
        }
        // Burnt-out white areas: pulls the whites down so they glare less (kept for all videos).
        var hl by remember(st.settings.pictureHighlights) { mutableStateOf(st.settings.pictureHighlights) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bright areas", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(78.dp))
            Slider(hl, { hl = (Math.round(it * 20f) / 20f); player.setHighlights(hl, false) },
                onValueChangeFinished = { player.setHighlights(hl, true) }, valueRange = 0f..1f, colors = colors, modifier = Modifier.weight(1f).height(32.dp))
            Text(if (hl < 0.01f) "Off" else "−${(hl * 100).toInt()} %", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp).padding(start = 8.dp))
            Box(Modifier.width(48.dp))
        }
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
        // ▲ ▼ step through the results (highlighted, scrolled into view).
        val reqs = remember(results) { List(results?.size ?: 0) { androidx.compose.foundation.relocation.BringIntoViewRequester() } }
        var at by remember(results) { mutableStateOf(-1) }
        fun step(k: Int) { if (reqs.isEmpty()) return; at = (k + reqs.size) % reqs.size; scope.launch { reqs[at].bringIntoView() } }
        SmallSearchBar(query, { query = it }, "Search subtitles", reqs.size, at.coerceAtLeast(0), { step(at - 1) }, { step(at + 1) }, onSearch = { search() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (st.settings.subLangs + listOf("en", "es", "fr", "de", "it", "pt", "ro")).distinct().forEach { l ->
                Pill(l.uppercase(), selected = l in langs) { langs = if (l in langs) langs - l else langs + l }
            }
        }
        if (status.isNotEmpty()) Text(status, color = C.muted, fontSize = 13.sp)
        results?.forEachIndexed { ri, r ->
            Row(Modifier.fillMaxWidth().then(if (ri < reqs.size) Modifier.bringIntoViewRequester(reqs[ri]) else Modifier)
                .clip(RoundedCornerShape(10.dp)).background(if (ri == at) accent().copy(alpha = 0.25f) else C.card).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        AlertDialog(
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
/** Colours for the subtitles' border and shadow. */
private val EDGE_COLOURS = listOf(0x000000, 0x404040, 0x808080, 0xFFFFFF, 0x1E3A8A, 0x7F1D1D)

/** A row of round colour swatches, the chosen one ringed. */
@Composable
private fun SubColours(list: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { c ->
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                .border(if (selected == c) 3.dp else 1.dp, if (selected == c) accent() else C.line, CircleShape).clickable { onPick(c) })
        }
    }
}

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
        // Live preview of the chosen look (2026-11-21 16:05): a picture with the line at its height, border and shadow.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B3A4A))) {
            val fam = when (s.font) { "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; else -> FontFamily.SansSerif }
            val fs = (16 * s.sizePct / 100f).sp
            val wt = if (s.bold) FontWeight.Bold else FontWeight.Medium
            val line = "Subtitle preview line"
            val up = maxHeight * (0.03f + 0.09f * (s.posStep - 1))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = up).then(if (s.box) Modifier.background(Color(0xA0000000)).padding(horizontal = 6.dp) else Modifier)) {
                if (s.shadowWidth > 0) {
                    val d = s.shadowWidth * 0.6f
                    val rad = Math.toRadians(s.shadowAngle.toDouble())
                    Text(line, color = Color(0xFF000000 or s.shadowColor.toLong()).copy(alpha = (100 - s.shadowTransp) / 100f), fontSize = fs, fontWeight = wt, fontFamily = fam,
                        modifier = Modifier.offset(x = (d * kotlin.math.cos(rad)).dp, y = (-d * kotlin.math.sin(rad)).dp))
                }
                if (s.outlineWidth > 0) Text(line, color = Color(0xFF000000 or s.outlineColor.toLong()), fontSize = fs, fontWeight = wt, fontFamily = fam,
                    style = androidx.compose.ui.text.TextStyle(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = s.outlineWidth * 1.6f,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round)))
                Text(line, color = Color(0xFF000000 or s.color.toLong()), fontSize = fs, fontWeight = wt, fontFamily = fam)
            }
        }
        Text("Font", color = C.muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fonts.forEach { (f, l) -> Pill(l, selected = s.font == f) { set(s.copy(font = f)) } }
        }
        // Font size.
        Text("Font size · ${s.sizePct}%", color = C.muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(s.sizePct.toFloat(), { set(s.copy(sizePct = it.toInt())) }, valueRange = 50f..200f, steps = 14, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
            Pill("Bold", selected = s.bold, modifier = Modifier.padding(start = 8.dp)) { set(s.copy(bold = !s.bold)) }
        }
        // Font colour.
        Text("Font colour", color = C.muted, fontSize = 13.sp)
        SubColours(colors.map { it.first }, s.color) { set(s.copy(color = it)) }
        // Font border: colour and width.
        Text("Font border · width ${if (s.outlineWidth == 0) "none" else s.outlineWidth.toString()}", color = C.muted, fontSize = 13.sp)
        SubColours(EDGE_COLOURS, s.outlineColor) { set(s.copy(outlineColor = it)) }
        Slider(s.outlineWidth.toFloat(), { set(s.copy(outlineWidth = Math.round(it))) }, valueRange = 0f..10f, steps = 9,
            colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
        // Font shadow: colour, width, direction, transparency.
        Text("Font shadow · width ${if (s.shadowWidth == 0) "none" else s.shadowWidth.toString()}", color = C.muted, fontSize = 13.sp)
        SubColours(EDGE_COLOURS, s.shadowColor) { set(s.copy(shadowColor = it)) }
        Slider(s.shadowWidth.toFloat(), { set(s.copy(shadowWidth = Math.round(it))) }, valueRange = 0f..10f, steps = 9,
            colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
        if (s.shadowWidth > 0) {
            Text("Shadow direction", color = C.muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(315 to "↘", 270 to "↓", 225 to "↙", 180 to "←", 135 to "↖", 90 to "↑", 45 to "↗", 0 to "→").forEach { (deg, l) ->
                    Pill(l, selected = s.shadowAngle == deg) { set(s.copy(shadowAngle = deg)) }
                }
            }
            Text("Shadow transparency · ${s.shadowTransp}%", color = C.muted, fontSize = 13.sp)
            Slider(s.shadowTransp.toFloat(), { set(s.copy(shadowTransp = (Math.round(it / 5f) * 5).coerceIn(0, 100))) }, valueRange = 0f..100f, steps = 19,
                colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dark box behind the text", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            AppSwitch(s.box, { set(s.copy(box = it)) })
        }
        // Position on the picture: 10 steps from the bottom up.
        Text("Position on the picture · ${s.posStep} of 10 (1 = bottom, 10 = top)", color = C.muted, fontSize = 13.sp)
        Slider(s.posStep.toFloat(), { set(s.copy(posStep = Math.round(it).coerceIn(1, 10))) }, valueRange = 1f..10f, steps = 8,
            colors = SliderDefaults.colors(activeTrackColor = accent(), thumbColor = Color.White))
        Text("Long lines are split by the player to the picture's width; every line stays on the picture.", color = C.muted, fontSize = 12.sp)
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
            SymButton(onDone) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
        SymButton(onDone) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.bg).padding(3.dp)) {
            listOf("Subtitle sync", "Sound sync").forEachIndexed { i, l ->
                Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(8.dp)).background(if (which == i) Color(0xFF2A2F38) else Color.Transparent).clickable { onSwitch(i) },
                    contentAlignment = Alignment.Center) { Text(l, color = if (which == i) C.text else C.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        }
        SyncControls(which == 0, p, compact = false, onDone = onDone)
    }
}


/**
 * Long press on a playing video: all its details in fixed sections and order (File, Source, Video, Audio, Subtitles,
 * Tags, Playback), label on the left, value on the right; refreshed every 2 s; tap a line to copy it.
 */
@Composable
private fun VideoDetailsDialog(onDone: () -> Unit) {
    var secs by remember { mutableStateOf(App.instance.player.videoDetails()) }
    LaunchedEffect(Unit) { while (true) { delay(2000); secs = App.instance.player.videoDetails() } }
    DetailsDialog("Video details", secs, emptyText = "No video playing.", onDone = onDone)
}


/**
 * Picture format button: names the format a press will set ("→ Fill"). [label] = current + '\u0000' + next.
 * The format shown now is written on the picture with the other controls (see ControlsOverlayBody).
 */
@Composable
private fun FormatControl(label: String, onScale: () -> Unit) {
    val parts = label.split('\u0000')
    val cur = parts[0]; val nxt = parts.getOrElse(1) { "" }
    OverlayChip(if (nxt.isEmpty()) cur else "→ $nxt", onScale)
}


/**
 * Video queue (2026-11-21 14:47): what's playing, everything after it (tap: play it; long press: Play next, move,
 * details …; ✕: take it out of the queue), "Clear the rest", and Browse — the folder in the Library to pick or queue
 * other files (Back comes back to the video).
 */
@Composable
private fun VideoQueuePanel(p: PlayerState, nav: Navigator, onClose: () -> Unit) {
    val player = App.instance.player
    val next = if (p.index >= 0) p.queue.drop(p.index + 1) else emptyList()
    var menu by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Queue", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val from = p.origin.title.takeIf { it.isNotEmpty() && p.origin.kind != "single" }
        if (from != null) Text((if (p.origin.kind == "playlist") "Playlist: " else "Folder: ") + from, color = C.muted, fontSize = 13.sp, maxLines = 1)
        Text("Now playing", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        MarqueeText(p.item?.title ?: "", C.text, Modifier.fillMaxWidth(), fontSize = 15.sp, list = "upnext")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Up next · ${next.size}", color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (next.isNotEmpty()) STextButton({ for (i in p.queue.lastIndex downTo p.index + 1) player.removeFromQueue(i) }) { Text("Clear the rest", color = C.bad) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card).verticalScroll(rememberScrollState())) {
            if (next.isEmpty()) Text("Nothing after this video. Use Browse to add files.", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            next.forEachIndexed { i, n ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { player.jumpTo(p.index + 1 + i) }, onLongClick = { menu = i })
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", color = C.muted, maxLines = 1, modifier = Modifier.width(30.dp))
                    MarqueeText(n.title, C.text, Modifier.weight(1f).padding(end = 8.dp), fontSize = 15.sp, list = "upnext")
                    if (n.durationMs > 0) Text(fmtTime(n.durationMs), color = C.muted, fontSize = 13.sp)
                    Text("✕", color = C.bad, fontSize = 16.sp, modifier = Modifier.clickable { player.removeFromQueue(p.index + 1 + i) }.padding(horizontal = 14.dp, vertical = 8.dp))
                    Box { if (menu == i) FileMenu(nav, n, true, { menu = -1 }, FileMenuExtras(
                        onMoveUp = if (i > 0) ({ player.moveInQueue(p.index + 1 + i, -1) }) else null,
                        onMoveDown = if (p.index + 2 + i < p.queue.size) ({ player.moveInQueue(p.index + 1 + i, 1) }) else null,
                        removeLabel = "Remove from queue", onRemove = { player.removeFromQueue(p.index + 1 + i) })) }
                }
            }
        }
        Pill("📂 Browse files to add", filled = true, modifier = Modifier.fillMaxWidth()) { onClose(); openOrigin(nav, keepStack = true) }
    }
}


/** Header bar of the video player upright, like the song player's: symbols, then the other two players and the output. */
@Composable
private fun VideoHeader(nav: Navigator, locked: Boolean, openOutput: () -> Unit, onLock: () -> Unit, modifier: Modifier) {
    Scaled {
        Column(modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FitRow(Modifier.fillMaxWidth()) {
                if (locked) {
                    // Locked: only the lock, to unlock.
                    Grow()
                    SymButton(onLock) { SymIcon(Icons.Filled.Lock, "Unlock controls", tint = accent()) }
                } else {
                    SymButton({ nav.back() }) { SymIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    HomeButton(nav, Color.White)
                    SymButton(onLock) { SymIcon(Icons.Filled.Lock, "Lock controls", tint = Color.White) }
                    // Video player → Video settings.
                    SymButton({ nav.go(Screen.SettingsGroup("video")) }) { SymIcon(Icons.Filled.Settings, "Video settings", tint = Color.White) }
                    ShazamButton(Color.White)
                    BackgroundWorkButton()
                    ScreenOnSign(Color.White)
                    ExitButton(Color.White)
                }
            }
            if (!locked) FitRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                // The other two players (each resumes what it last played), then the output on the right.
                Row(verticalAlignment = Alignment.CenterVertically) { PlayerSwitchButtons(nav, "video", Color.White) }
                OutputPill(openOutput)
            }
        }
    }
}


/** Round button under the upright video (previous, −10, +10, next). */
@Composable
private fun UnderBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(C.card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}


/** Videos already asked about their subtitles in this session. */
private object SubAskOnce {
    val asked = HashSet<String>()
}

/** Choose the subtitles of the video just opened: "Off" and every track; the choice closes the pop-up. */
@Composable
private fun SubtitleChoiceDialog(p: PlayerState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val tracks = p.spuTracks.filter { it.id >= 0 }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.panel).padding(vertical = 12.dp)) {
            Text("Subtitles", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text("This video has subtitles. Choose one:", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                (listOf(Track(-1, "Off")) + tracks).forEach { t ->
                    val on = if (t.id < 0) p.spuTrack < 0 else t.id == p.spuTrack
                    Row(Modifier.fillMaxWidth().clickable { onPick(t.id) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (on) "●" else "○", color = if (on) accent() else C.muted, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                        Text(t.name, color = if (on) accent() else C.text, fontSize = 16.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
