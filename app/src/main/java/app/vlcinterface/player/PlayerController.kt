package app.vlcinterface.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.vlcinterface.data.RecentItem
import app.vlcinterface.data.Store
import app.vlcinterface.data.SubStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

data class Track(val id: Int, val name: String)

data class PlayItem(
    val uri: String,
    val title: String,
    val isRadio: Boolean = false,
    /** Key used for "whole folder" subtitle offsets, e.g. the parent directory uri. */
    val folderKey: String = "",
    val sourceId: String = "",
    val user: String = "",
    val password: String = "",
    /** Other playable items in the same folder, for previous / next. */
    val siblings: List<PlayItem> = emptyList(),
)

data class PlayerState(
    val item: PlayItem? = null,
    val isPlaying: Boolean = false,
    val time: Long = 0,
    val length: Long = 0,
    val videoW: Int = 0,
    val videoH: Int = 0,
    val audioTracks: List<Track> = emptyList(),
    val spuTracks: List<Track> = emptyList(),
    val audioTrack: Int = -1,
    val spuTrack: Int = -1,
    val audioDelayMs: Long = 0,
    val spuDelayMs: Long = 0,
    val rate: Float = 1f,
    val nowPlaying: String = "",
    val buffering: Float = 100f,
    val error: String = "",
    val ended: Boolean = false,
)

/** One libVLC instance + one MediaPlayer for the whole app (video and radio). */
class PlayerController(private val context: Context, private val store: Store) {
    private val main = Handler(Looper.getMainLooper())
    lateinit var libVlc: LibVLC
        private set
    private lateinit var mp: MediaPlayer
    private var attachedLayout: VLCVideoLayout? = null
    private var styleInUse: SubStyle? = null
    private var pendingSeek: Long = -1
    private var offsetsApplied = false
    private var currentPfd: android.os.ParcelFileDescriptor? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Name of the current audio output, used for per-device sound offsets. */
    var currentOutputName: String = ""

    init {
        createEngine(store.current.subStyle)
    }

    private fun createEngine(style: SubStyle) {
        libVlc = LibVLC(context, buildOptions(style))
        styleInUse = style
        mp = MediaPlayer(libVlc)
        mp.setEventListener { ev -> onEvent(ev) }
        installDialogs()
    }

    private fun buildOptions(style: SubStyle): ArrayList<String> {
        val o = arrayListOf(
            "--audio-time-stretch",
            "--network-caching=1500",
            "--http-reconnect",
            "--avcodec-skiploopfilter=1",
        )
        // Subtitle look (text subtitles rendered by freetype).
        o += "--freetype-font=${style.font}"
        o += "--freetype-color=${style.color}"
        // libVLC's relative size is inverted: smaller number = bigger text, 16 = normal.
        val rel = (16f * 100f / style.sizePct.coerceIn(50, 200)).toInt().coerceIn(4, 40)
        o += "--freetype-rel-fontsize=$rel"
        if (style.bold) o += "--freetype-bold"
        when (style.edges) {
            "outline" -> { o += "--freetype-outline-thickness=4"; o += "--freetype-shadow-opacity=0" }
            "shadow" -> { o += "--freetype-outline-thickness=0"; o += "--freetype-shadow-opacity=200" }
            "box" -> { o += "--freetype-outline-thickness=0"; o += "--freetype-background-opacity=160"; o += "--freetype-background-color=0" }
            else -> { o += "--freetype-outline-thickness=0"; o += "--freetype-shadow-opacity=0" }
        }
        // Distance from the bottom edge in pixels; "top" pushes subtitles up most of the screen height.
        val h = context.resources.displayMetrics.heightPixels.coerceAtMost(context.resources.displayMetrics.widthPixels)
        val margin = if (style.position == "top") (h * (88 - style.edgePct) / 100) else (h * style.edgePct / 100)
        o += "--sub-margin=$margin"
        return o
    }

    // ---------- login prompts from libVLC (SMB, FTP, SFTP, HTTP auth) ----------
    @Volatile private var pendingUser = ""
    @Volatile private var pendingPass = ""

    private fun installDialogs() {
        Dialog.setCallbacks(libVlc, object : Dialog.Callbacks {
            override fun onDisplay(dialog: Dialog.ErrorMessage) {
                main.post { _state.value = _state.value.copy(error = dialog.text ?: "Playback error") }
            }

            override fun onDisplay(dialog: Dialog.LoginDialog) {
                if (pendingUser.isNotEmpty()) dialog.postLogin(pendingUser, pendingPass, false) else dialog.dismiss()
            }

            override fun onDisplay(dialog: Dialog.QuestionDialog) {
                // e.g. unknown SFTP host key or self-signed certificate: accept for this session.
                dialog.postAction(1)
            }

            override fun onDisplay(dialog: Dialog.ProgressDialog) {}
            override fun onCanceled(dialog: Dialog?) {}
            override fun onProgressUpdate(dialog: Dialog.ProgressDialog) {}
        })
    }

    fun setCredentials(user: String, pass: String) {
        pendingUser = user; pendingPass = pass
    }

    // ---------- playback ----------

    fun play(item: PlayItem, startAt: Long = -1) {
        saveProgress()
        val wanted = store.current.subStyle
        if (wanted != styleInUse) recreateEngine(wanted)
        setCredentials(item.user, item.password)
        val oldPfd = currentPfd
        currentPfd = null
        val media = if (item.uri.startsWith("content://")) {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")
            currentPfd = pfd
            if (pfd != null) Media(libVlc, pfd.fileDescriptor) else Media(libVlc, Uri.parse(item.uri))
        } else Media(libVlc, Uri.parse(item.uri))
        media.setHWDecoderEnabled(true, false)
        if (item.isRadio) media.addOption(":network-caching=3000")
        mp.setMedia(media)
        media.release()
        try { oldPfd?.close() } catch (_: Exception) {}
        offsetsApplied = false
        val resume = if (startAt >= 0) startAt else if (!item.isRadio && store.current.settings.resume)
            store.current.recent.firstOrNull { it.uri == item.uri }?.position ?: -1 else -1
        pendingSeek = resume
        _state.value = PlayerState(item = item)
        mp.play()
        startService()
    }

    private fun recreateEngine(style: SubStyle) {
        val layout = attachedLayout
        if (layout != null) mp.detachViews()
        mp.setEventListener(null)
        mp.release()
        libVlc.release()
        createEngine(style)
        if (layout != null) mp.attachViews(layout, null, true, false)
    }

    /** Applies a new subtitle style; restarts the current item at the same position (needed for freetype options). */
    fun applyStyle() {
        val s = _state.value
        val item = s.item ?: run { recreateEngine(store.current.subStyle); return }
        val pos = mp.time
        val playing = mp.isPlaying
        play(item, pos)
        if (!playing) main.postDelayed({ mp.pause() }, 400)
    }

    fun togglePause() { if (mp.isPlaying) mp.pause() else mp.play() }
    fun pause() { if (mp.isPlaying) mp.pause() }
    fun stop() {
        saveProgress()
        mp.stop()
        currentPfd?.close(); currentPfd = null
        _state.value = PlayerState()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    fun seekTo(ms: Long) { mp.setTime(ms.coerceAtLeast(0)) }
    fun seekBy(ms: Long) { mp.setTime((mp.time + ms).coerceIn(0, if (mp.length > 0) mp.length else Long.MAX_VALUE)) }
    fun setRate(r: Float) { mp.setRate(r); _state.value = _state.value.copy(rate = r) }

    fun selectAudio(id: Int) { mp.setAudioTrack(id); refreshTracks() }
    fun selectSpu(id: Int) { mp.setSpuTrack(id); refreshTracks() }

    fun addSubtitleFile(uri: Uri) {
        mp.addSlave(IMedia.Slave.Type.Subtitle, uri, true)
        main.postDelayed({ refreshTracks() }, 800)
    }

    fun setScale(scale: MediaPlayer.ScaleType) { mp.setVideoScale(scale) }

    // ---------- sync offsets ----------

    fun setSpuDelay(ms: Long) {
        mp.setSpuDelay(ms * 1000)
        _state.value = _state.value.copy(spuDelayMs = ms)
    }

    fun setAudioDelay(ms: Long) {
        mp.setAudioDelay(ms * 1000)
        _state.value = _state.value.copy(audioDelayMs = ms)
    }

    /** scope: "file" or "folder" */
    fun saveSpuDelay(scope: String) {
        val item = _state.value.item ?: return
        val key = if (scope == "folder" && item.folderKey.isNotEmpty()) "folder:${item.folderKey}" else "file:${item.uri}"
        val v = _state.value.spuDelayMs
        store.update { st -> st.copy(subOffsets = st.subOffsets + (key to v)) }
    }

    /** scope: "file" or "device" */
    fun saveAudioDelay(scope: String) {
        val item = _state.value.item ?: return
        val key = if (scope == "device" && currentOutputName.isNotEmpty()) "device:$currentOutputName" else "file:${item.uri}"
        val v = _state.value.audioDelayMs
        store.update { st -> st.copy(audioOffsets = st.audioOffsets + (key to v)) }
    }

    private fun applyStoredOffsets() {
        val item = _state.value.item ?: return
        val st = store.current
        val spu = st.subOffsets["file:${item.uri}"] ?: st.subOffsets["folder:${item.folderKey}"] ?: 0L
        val aud = st.audioOffsets["file:${item.uri}"] ?: st.audioOffsets["device:$currentOutputName"] ?: 0L
        if (spu != 0L) setSpuDelay(spu)
        if (aud != 0L) setAudioDelay(aud)
    }

    // ---------- views ----------

    fun attach(layout: VLCVideoLayout) {
        if (attachedLayout === layout) return
        if (attachedLayout != null) mp.detachViews()
        attachedLayout = layout
        mp.attachViews(layout, null, true, false)
    }

    fun detach(layout: VLCVideoLayout) {
        if (attachedLayout === layout) {
            mp.detachViews()
            attachedLayout = null
        }
    }

    // ---------- events ----------

    private fun onEvent(ev: MediaPlayer.Event) {
        val s = _state.value
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                _state.value = s.copy(isPlaying = true, error = "", ended = false)
                if (pendingSeek > 5_000) { mp.setTime(pendingSeek) }
                pendingSeek = -1
                if (!offsetsApplied) { offsetsApplied = true; applyStoredOffsets() }
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> { _state.value = s.copy(isPlaying = false); saveProgress() }
            MediaPlayer.Event.Stopped -> _state.value = s.copy(isPlaying = false)
            MediaPlayer.Event.EndReached -> {
                _state.value = s.copy(isPlaying = false, ended = true)
                s.item?.let { item -> store.update { st -> st.copy(recent = st.recent.filterNot { it.uri == item.uri }) } }
            }
            MediaPlayer.Event.TimeChanged -> {
                val np = if (s.item?.isRadio == true) (mp.media?.getMeta(IMedia.Meta.NowPlaying) ?: "") else ""
                _state.value = s.copy(time = ev.timeChanged, nowPlaying = np)
            }
            MediaPlayer.Event.LengthChanged -> _state.value = s.copy(length = ev.lengthChanged)
            MediaPlayer.Event.Buffering -> _state.value = s.copy(buffering = ev.buffering)
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.Vout -> refreshTracks()
            MediaPlayer.Event.EncounteredError -> _state.value = s.copy(isPlaying = false, error = "Could not play this item")
        }
    }

    private fun refreshTracks() {
        val a = mp.audioTracks?.map { Track(it.id, it.name ?: "Track ${it.id}") } ?: emptyList()
        val sp = mp.spuTracks?.map { Track(it.id, it.name ?: "Track ${it.id}") } ?: emptyList()
        val vt = mp.currentVideoTrack
        _state.value = _state.value.copy(
            audioTracks = a, spuTracks = sp, audioTrack = mp.audioTrack, spuTrack = mp.spuTrack,
            videoW = vt?.width ?: _state.value.videoW, videoH = vt?.height ?: _state.value.videoH,
            length = if (mp.length > 0) mp.length else _state.value.length,
        )
    }

    fun saveProgress() {
        val s = _state.value
        val item = s.item ?: return
        if (item.isRadio) return
        val pos = mp.time
        val len = if (mp.length > 0) mp.length else s.length
        if (pos < 5_000) return
        store.update { st ->
            val list = listOf(RecentItem(item.uri, item.title, pos, len, item.sourceId)) +
                st.recent.filterNot { it.uri == item.uri }
            st.copy(recent = list.take(50))
        }
    }

    private fun startService() {
        try {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    val isPlaying: Boolean get() = mp.isPlaying
    val volumeLevel: Int get() = mp.volume
}
