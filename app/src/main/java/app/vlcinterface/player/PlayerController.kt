package app.vlcinterface.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.vlcinterface.data.RecentItem
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Store
import app.vlcinterface.data.SubStyle
import app.vlcinterface.sources.AUDIO_EXT
import app.vlcinterface.sources.SmbBridge
import app.vlcinterface.sources.ext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.log10
import kotlin.math.pow

data class Track(val id: Int, val name: String)

/** Where playback was started from: used by "open folder" taps and the music screen's shortcut. */
data class Origin(
    val kind: String = "single", // folder, local, playlist, radio, link, single
    val sourceId: String = "",
    val uri: String = "",
    val title: String = "",
    val playlistId: String = "",
    val localFolderId: String = "",
)

data class PlayItem(
    val uri: String,
    val title: String,
    val isRadio: Boolean = false,
    /** Key used for "whole folder" subtitle offsets, e.g. the parent directory uri. */
    val folderKey: String = "",
    val sourceId: String = "",
    val user: String = "",
    val password: String = "",
    val artist: String = "",
    val album: String = "",
    val trackNo: Int = 0,
    val durationMs: Long = 0,
    /** Cut from a single file by a .cue sheet. */
    val startMs: Long = -1,
    val endMs: Long = -1,
    val artwork: String = "",
    /** SMB version detected for the source ("SMB1" plays through the in-app bridge). */
    val smbVersion: String = "",
    val domain: String = "",
    /** Real file name with its extension (phone addresses have none, so it is stored separately). */
    val fileName: String = "",
) {
    /** The file extension decides: audio extension → music screen, anything else → video player. */
    val ext: String get() = fileName.ifEmpty { Uri.decode(uri).substringBefore('?').substringAfterLast('/') }.ext()
    val isMusic: Boolean get() = !isRadio && ext in AUDIO_EXT
}

data class PlayerState(
    val item: PlayItem? = null,
    val queue: List<PlayItem> = emptyList(),
    val index: Int = -1,
    val origin: Origin = Origin(),
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
    val metaArtist: String = "",
    val metaAlbum: String = "",
    val artworkUrl: String = "",
    val buffering: Float = 100f,
    val error: String = "",
    val ended: Boolean = false,
    val rgInfo: String = "",
    val sleepUntil: Long = 0,
    val sleepEndOfTrack: Boolean = false,
) {
    val hasNext: Boolean get() = index >= 0 && index + 1 < queue.size
    val hasPrev: Boolean get() = index > 0
    val upNext: List<PlayItem> get() = if (index >= 0) queue.drop(index + 1).take(2) else emptyList()
}

/** One libVLC instance + one MediaPlayer for the whole app (video, music and radio), with a play queue. */
class PlayerController(private val context: Context, private val store: Store) {
    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)
    private val sessionId: Int = am.generateAudioSessionId()
    lateinit var libVlc: LibVLC
        private set
    private lateinit var mp: MediaPlayer
    private var attachedLayout: VLCVideoLayout? = null
    private var styleInUse: SubStyle? = null
    private var rgInUse: String = ""
    private var pendingSeek: Long = -1
    private var offsetsApplied = false
    private var currentPfd: android.os.ParcelFileDescriptor? = null
    private var triedBridge = false
    private var dp: DynamicsProcessing? = null
    private var eq: MediaPlayer.Equalizer? = null
    /** The queue in its original order while it is shown/played in random order (null = queue is in order). */
    private var orderedQueue: List<PlayItem>? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Name of the current audio output, used for per-device offsets, preamp and balance. */
    var currentOutputName: String = ""
        set(v) { field = v; if (::mp.isInitialized) applySoundChain() }

    init {
        createEngine(store.current.subStyle)
    }

    private fun createEngine(style: SubStyle) {
        libVlc = LibVLC(context, buildOptions(style))
        styleInUse = style
        rgInUse = rgSignature()
        mp = MediaPlayer(libVlc)
        // Our own audio output: real balance/preamp and stereo levels. Falls back to libVLC's output.
        if (!NativeAudio.attachTo(libVlc, mp, appOutputWanted()) && appOutputWanted()) {
            // Could not attach: build the engine again with libVLC doing ReplayGain itself.
            NativeAudio.markFailed(NativeAudio.reason.ifEmpty { "could not attach" })
            mp.release(); libVlc.release()
            libVlc = LibVLC(context, buildOptions(style))
            mp = MediaPlayer(libVlc)
        }
        mp.setEventListener { ev -> onEvent(ev) }
        installDialogs()
        setupSoundChain()
    }

    private fun appOutputWanted() = store.current.settings.soundEngine != "libvlc" && !NativeAudio.failed

    // ---------- ReplayGain (app output) ----------

    /** Gain in dB for the playing music item (ReplayGain + its pre-amp, clip-limited); 0 if untagged. */
    private var rgDb = 0f
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var rgJob: kotlinx.coroutines.Job? = null

    private fun loadReplayGain(item: PlayItem, s: PlayerState) {
        rgDb = 0f
        rgJob?.cancel()
        val set = store.current.settings
        if (!item.isMusic || set.rgMode == "off") return
        if (!NativeAudio.active) { _state.value = _state.value.copy(rgInfo = "RG ${rgModeFor(s)} (libVLC)"); return }
        val mode = rgModeFor(s)
        rgJob = bg.launch {
            val rg = app.vlcinterface.sources.ReplayGain.read(context, item.uri, item.user, item.password, item.domain, item.smbVersion)
            if (_state.value.item !== item) return@launch
            val album = mode == "album"
            val gain = if (album) rg?.albumGain ?: rg?.trackGain else rg?.trackGain ?: rg?.albumGain
            val peak = if (album) rg?.albumPeak ?: rg?.trackPeak else rg?.trackPeak ?: rg?.albumPeak
            val used = if (gain == null) "" else if (album && rg?.albumGain != null || !album && rg?.trackGain == null) "album" else "track"
            var db = if (gain == null) 0f else gain + set.rgPreampDb
            if (gain != null && set.rgClipProtect && peak != null && peak > 0f) db = minOf(db, -20f * log10(peak))
            rgDb = db
            val info = when {
                rg == null -> "RG: tags not readable here"
                gain == null -> "RG: no tags"
                else -> "RG $used ${if (db >= 0) "+" else "−"}${"%.1f".format(kotlin.math.abs(db))} dB"
            }
            _state.value = _state.value.copy(rgInfo = info)
            applySoundChain()
        }
    }

    private fun rgSignature(): String {
        val s = store.current.settings
        return "${s.rgMode}|${s.rgPreampDb}|${s.rgClipProtect}"
    }

    private fun buildOptions(style: SubStyle): ArrayList<String> {
        val s = store.current.settings
        val o = arrayListOf(
            "--audio-time-stretch",
            "--network-caching=1500",
            "--http-reconnect",
            "--avcodec-skiploopfilter=1",
            // A fixed Android audio session, so preamp + balance effects can be attached to it.
            "--audiotrack-session-id=$sessionId",
            // Fallback output (when our own output cannot attach): AudioTrack, so the session id above is used.
            "--aout=audiotrack",
        )
        // ReplayGain: only tagged files change; untagged ones get 0 dB.
        // With the app's own output the app reads the tags and applies the gain itself (libVLC's RG is off then,
        // so it's never applied twice); libVLC does it only when its own output is used.
        val mode = if (appOutputWanted()) "none" else when (s.rgMode) { "track" -> "track"; "album", "auto" -> "album"; else -> "none" }
        o += "--audio-replay-gain-mode=$mode"
        o += "--audio-replay-gain-preamp=${s.rgPreampDb}"
        o += "--audio-replay-gain-default=0"
        o += if (s.rgClipProtect) "--audio-replay-gain-peak-protection" else "--no-audio-replay-gain-peak-protection"
        // libVLC never fetches album art from the internet itself (pictures are handled by the app, saved only in album folders).
        o += "--no-metadata-network-access"
        // Subtitle look (text subtitles rendered by freetype).
        o += "--freetype-font=${style.font}"
        o += "--freetype-color=${style.color}"
        val rel = (16f * 100f / style.sizePct.coerceIn(50, 200)).toInt().coerceIn(4, 40)
        o += "--freetype-rel-fontsize=$rel"
        if (style.bold) o += "--freetype-bold"
        when (style.edges) {
            "outline" -> { o += "--freetype-outline-thickness=4"; o += "--freetype-shadow-opacity=0" }
            "shadow" -> { o += "--freetype-outline-thickness=0"; o += "--freetype-shadow-opacity=200" }
            "box" -> { o += "--freetype-outline-thickness=0"; o += "--freetype-background-opacity=160"; o += "--freetype-background-color=0" }
            else -> { o += "--freetype-outline-thickness=0"; o += "--freetype-shadow-opacity=0" }
        }
        val dm = context.resources.displayMetrics
        val h = dm.heightPixels.coerceAtMost(dm.widthPixels)
        val margin = if (style.position == "top") (h * (88 - style.edgePct) / 100) else (h * style.edgePct / 100)
        o += "--sub-margin=$margin"
        return o
    }

    // ---------- sound chain: general preamp + per-device preamp + per-device balance ----------

    private fun setupSoundChain() {
        if (NativeAudio.active) { dp?.release(); dp = null; applySoundChain(); return }
        try {
            dp?.release()
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                false, 0, false, 0, false, 0, true
            ).build()
            dp = DynamicsProcessing(0, sessionId, cfg).also { it.enabled = true }
        } catch (e: Exception) {
            dp = null
        }
        applySoundChain()
    }

    fun applySoundChain() {
        val st = store.current
        val dev = currentOutputName
        val rg = if (NativeAudio.active && _state.value.item?.isMusic == true) rgDb else 0f
        val total = (st.settings.preampDb + (st.devicePreamp[dev] ?: 0f) + rg).coerceIn(-36f, 12f)
        val bal = (st.deviceBalance[dev] ?: 0).coerceIn(-100, 100)
        fun att(f: Float): Float = if (f <= 0.001f) -60f else (20f * log10(f)).coerceAtLeast(-60f)
        val left = total + if (bal > 0) att(1f - bal / 100f) else 0f
        val right = total + if (bal < 0) att(1f + bal / 100f) else 0f
        if (NativeAudio.active) {
            fun lin(db: Float) = if (db <= -60f) 0f else 10f.pow(db / 20f)
            NativeAudio.gains(lin(left), lin(right), total > 0f)
            return
        }
        val d = dp
        if (d != null) {
            try {
                d.setInputGainbyChannel(0, left)
                d.setInputGainbyChannel(1, right)
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, total > 0f, 0, 1f, 60f, 10f, -1f, 0f))
                return
            } catch (_: Exception) {
            }
        }
        // Fallback without balance: libVLC's equalizer pre-amplifier.
        try {
            val e = eq ?: MediaPlayer.Equalizer.create().also { eq = it }
            e.setPreAmp(total.coerceIn(-20f, 20f))
            mp.setEqualizer(e)
        } catch (_: Exception) {
        }
    }

    /**
     * Watchdog for the app's own output: if no sound reaches the device within a few seconds of playing,
     * switch to libVLC's output for this run and restart the item at the same position.
     */
    private fun watchSound() {
        if (!NativeAudio.active) return
        val before = NativeAudio.stats() ?: return
        main.postDelayed({
            val s = _state.value
            if (!NativeAudio.active || !s.isPlaying || s.item == null) return@postDelayed
            if (mp.audioTracksCount <= 0) return@postDelayed      // video without sound: nothing to check
            val now = NativeAudio.stats() ?: return@postDelayed
            val problem = when {
                now.setupResult != 0L -> "the phone refused the audio stream (open ${now.openResult})"
                now.plays == before.plays -> "libVLC delivered no sound to the app"
                now.heard <= before.heard -> "the phone did not play the sound (state ${now.state}, start ${now.startResult})"
                else -> null
            }
            if (problem != null) {
                val detail = now.toString()
                android.util.Log.w("PlayerController", "app sound output failed: $problem — $detail")
                ServerLog.log("sound-engine", "sound", "AAudio", "", false, "App output gave no sound: $problem — switched to libVLC output", detail)
                NativeAudio.markFailed("$problem — switched to libVLC output for now")
                val pos = mp.time
                recreateEngine(store.current.subStyle)
                startItem(s.index, pos)
            }
        }, 3000)
    }

    // ---------- picture colour ----------

    /** True when the playing item was opened with the colour filter (live changes possible). */
    private var pictureFilterOn = false

    private fun pictureNeutral(h: Float, s: Float) = kotlin.math.abs(h) < 0.5f && kotlin.math.abs(s - 1f) < 0.01f

    /**
     * Hue/saturation from the player. While dragging ([commit] false) changes are applied live when possible;
     * on release they are saved. The first change on a video reopens it at the same spot (a second at most),
     * because hardware decoding must switch to a mode the colour filter can work on.
     */
    fun setPicture(hue: Float, sat: Float, commit: Boolean) {
        if (commit) store.update { it.copy(settings = it.settings.copy(pictureHue = hue, pictureSat = sat)) }
        val s = _state.value
        val item = s.item ?: return
        if (item.isMusic || item.isRadio) return
        if (pictureFilterOn && NativeAudio.picture(libVlc, mp, true, hue, sat)) return
        if (commit && (pictureFilterOn || !pictureNeutral(hue, sat))) {
            val pos = mp.time; val playing = mp.isPlaying
            startItem(s.index, pos)
            if (!playing) main.postDelayed({ mp.pause() }, 400)
        }
    }

    /** Album picture found online for a song with none in its files: also shown in the notification and on the lock screen. */
    fun useDownloadedArtwork(item: PlayItem, path: String) {
        val s = _state.value
        if (s.item === item && s.artworkUrl != path) _state.value = s.copy(artworkUrl = path)
    }

    /**
     * The phone's sound output changed (Android's output switcher, Bluetooth connected, headphones…).
     * Switching back from the speaker to Bluetooth didn't move the sound: the open stream stayed where it was.
     * Now the stream is reopened on the device in use: our own output reopens its stream; libVLC's output is restarted.
     */
    fun outputChanged() {
        if (NativeAudio.active) NativeAudio.outputChanged()
        else main.post { try { mp.setAudioOutput("audiotrack") } catch (_: Throwable) {} }
        applySoundChain()
    }

    /** Closing the video player: hue/saturation go back to the original colours (user rule). */
    fun resetPicture() {
        val ps = store.current.settings
        if (pictureNeutral(ps.pictureHue, ps.pictureSat)) return
        store.update { it.copy(settings = it.settings.copy(pictureHue = 0f, pictureSat = 1f)) }
        if (pictureFilterOn) NativeAudio.picture(libVlc, mp, false, 0f, 1f)
    }

    /** Settings → Sound engine changed: rebuild the engine and continue where it was. */
    fun applySoundEngine() {
        val s = _state.value
        val pos = if (s.item != null) mp.time else -1
        recreateEngine(store.current.subStyle)
        if (s.item != null) startItem(s.index, pos)
    }

    // ---------- audio focus + "headphones unplugged" ----------

    private var resumeOnGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (mp.isPlaying) { resumeOnGain = true; mp.pause() } }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp.setVolume(30)
                AudioManager.AUDIOFOCUS_GAIN -> { mp.setVolume(100); if (resumeOnGain) { resumeOnGain = false; mp.play() } }
            }
        }, main)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (store.current.settings.pauseOnDisconnect) pause() }
    }

    init {
        context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
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

    // ---------- queue ----------

    /**
     * Plays [items] from [index]. [random]: true = random order, false = in order, null = keep the current mode.
     * In random mode the chosen item plays first and the rest of the queue is shuffled after it;
     * the original order is kept so switching back to "in order" restores it.
     */
    fun playQueue(items: List<PlayItem>, index: Int, origin: Origin, startAt: Long = -1, random: Boolean? = null) {
        if (items.isEmpty()) return
        if (random != null && random != store.current.settings.shuffle) store.update { it.copy(settings = it.settings.copy(shuffle = random)) }
        var i = index.coerceIn(0, items.size - 1)
        var q = items
        if (store.current.settings.shuffle && items.size > 1) {
            orderedQueue = items
            q = listOf(items[i]) + items.filterIndexed { j, _ -> j != i }.shuffled()
            i = 0
        } else orderedQueue = null
        _state.value = PlayerState(queue = q, index = i, origin = origin)
        startItem(i, startAt)
    }

    /** Switches the current queue between random and original order without interrupting the playing item. */
    fun setShuffle(on: Boolean) {
        if (on != store.current.settings.shuffle) store.update { it.copy(settings = it.settings.copy(shuffle = on)) }
        val s = _state.value
        val cur = s.queue.getOrNull(s.index)
        if (on) {
            if (orderedQueue != null || s.queue.size < 2 || cur == null) return
            orderedQueue = s.queue
            _state.value = s.copy(queue = listOf(cur) + s.queue.filterIndexed { j, _ -> j != s.index }.shuffled(), index = 0)
        } else {
            val o = orderedQueue ?: return
            orderedQueue = null
            var idx = o.indexOfFirst { it === cur }
            if (idx < 0 && cur != null) idx = o.indexOfFirst { it.uri == cur.uri && it.startMs == cur.startMs }
            _state.value = s.copy(queue = o, index = idx.coerceAtLeast(0))
        }
    }

    fun play(item: PlayItem, startAt: Long = -1, origin: Origin = Origin(if (item.isRadio) "radio" else "single", item.sourceId, item.uri, item.title)) =
        playQueue(listOf(item), 0, origin, startAt)

    fun playNext(items: List<PlayItem>) {
        val s = _state.value
        if (s.item == null) { playQueue(items, 0, Origin("single")); return }
        val q = s.queue.toMutableList(); q.addAll(s.index + 1, items)
        orderedQueue?.let { o ->
            val at = o.indexOfFirst { it === s.queue.getOrNull(s.index) }
            orderedQueue = o.toMutableList().also { it.addAll(if (at >= 0) at + 1 else it.size, items) }
        }
        _state.value = s.copy(queue = q)
    }

    fun addToQueue(items: List<PlayItem>) {
        val s = _state.value
        if (s.item == null) { playQueue(items, 0, Origin("single")); return }
        orderedQueue = orderedQueue?.plus(items)
        _state.value = s.copy(queue = s.queue + items)
    }

    fun removeFromQueue(i: Int) {
        val s = _state.value
        if (i == s.index || i !in s.queue.indices) return
        val q = s.queue.toMutableList(); val gone = q.removeAt(i)
        orderedQueue = orderedQueue?.filterNot { it === gone }
        _state.value = s.copy(queue = q, index = if (i < s.index) s.index - 1 else s.index)
    }

    fun jumpTo(i: Int) { if (i in _state.value.queue.indices) startItem(i, -1) }

    fun next(): Boolean {
        val s = _state.value
        val n = nextIndex(s, manual = true) ?: return false
        startItem(n, -1); return true
    }

    fun prev() {
        val s = _state.value
        val item = s.item ?: return
        if (mp.time > 4000 || s.index <= 0) { seekTo(if (item.startMs >= 0) item.startMs else 0); return }
        startItem(s.index - 1, -1)
    }

    private fun nextIndex(s: PlayerState, manual: Boolean): Int? {
        val repeat = store.current.settings.repeat
        if (!manual && repeat == "one") return s.index
        return when {
            s.index + 1 < s.queue.size -> s.index + 1
            // Random + repeat all: a fresh random order for the next round (not starting with the song that just ended).
            repeat == "all" && store.current.settings.shuffle && s.queue.size > 1 -> {
                val last = s.queue.getOrNull(s.index)
                var q = (orderedQueue ?: s.queue).shuffled()
                if (q.first() === last) q = q.drop(1) + q.first()
                _state.value = _state.value.copy(queue = q)
                0
            }
            repeat == "all" && s.queue.isNotEmpty() -> 0
            else -> null
        }
    }

    // ---------- playback ----------

    private fun startItem(i: Int, startAt: Long) {
        saveProgress()
        val s0 = _state.value
        val item = s0.queue.getOrNull(i) ?: return
        val wanted = store.current.subStyle
        if (wanted != styleInUse || rgSignature() != rgInUse) recreateEngine(wanted)
        setCredentials(item.user, item.password)
        triedBridge = false
        val oldPfd = currentPfd
        currentPfd = null
        val media = makeMedia(item, s0, useBridge = item.smbVersion == "SMB1")
        mp.setMedia(media)
        media.release()
        try { oldPfd?.close() } catch (_: Exception) {}
        offsetsApplied = false
        val resume = if (startAt >= 0) startAt else if (!item.isRadio && store.current.settings.resume && item.startMs < 0)
            store.current.recent.firstOrNull { it.uri == item.uri }?.position ?: -1 else -1
        pendingSeek = resume
        _state.value = s0.copy(item = item, index = i, isPlaying = false, time = 0, length = item.durationMs, videoW = 0, videoH = 0,
            audioTracks = emptyList(), spuTracks = emptyList(), audioDelayMs = 0, spuDelayMs = 0, rate = 1f, nowPlaying = "",
            metaArtist = item.artist, metaAlbum = item.album, artworkUrl = item.artwork, buffering = 0f, error = "", ended = false,
            rgInfo = "")
        loadReplayGain(item, _state.value)
        am.requestAudioFocus(focusRequest)
        mp.play()
        applySoundChain()
        startService()
    }

    private fun rgModeFor(s: PlayerState): String = when (store.current.settings.rgMode) {
        "track" -> "track"; "album" -> "album"
        else -> if (store.current.settings.shuffle || s.origin.kind == "playlist") "track" else "album"
    }

    private fun makeMedia(item: PlayItem, s: PlayerState, useBridge: Boolean): Media {
        val media = when {
            item.uri.startsWith("content://") -> {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")
                currentPfd = pfd
                if (pfd != null) Media(libVlc, pfd.fileDescriptor) else Media(libVlc, Uri.parse(item.uri))
            }
            useBridge && item.uri.startsWith("smb://") ->
                Media(libVlc, Uri.parse(SmbBridge.urlFor(item.uri, item.smbVersion.ifEmpty { "SMB1" }, item.user, item.password, item.domain)))
            else -> Media(libVlc, Uri.parse(item.uri))
        }
        media.setHWDecoderEnabled(true, false)
        // Hue/saturation need decoded frames the filter can touch: hardware decoding without direct rendering.
        val ps = store.current.settings
        pictureFilterOn = !item.isMusic && !item.isRadio && !pictureNeutral(ps.pictureHue, ps.pictureSat)
        if (pictureFilterOn) {
            media.addOption(":no-mediacodec-dr")
            media.addOption(":video-filter=adjust")
            media.addOption(":hue=${ps.pictureHue}")
            media.addOption(":saturation=${ps.pictureSat}")
        }
        if (item.isRadio) media.addOption(":network-caching=3000")
        if (item.startMs >= 0) media.addOption(":start-time=${item.startMs / 1000.0}")
        if (item.endMs > 0) media.addOption(":stop-time=${item.endMs / 1000.0}")
        if (item.isMusic) {
            if (store.current.settings.rgMode != "off") media.addOption(":audio-replay-gain-mode=${rgModeFor(s)}")
        } else {
            media.addOption(":audio-replay-gain-mode=none")
        }
        return media
    }

    private fun recreateEngine(style: SubStyle) {
        val layout = attachedLayout
        if (layout != null) mp.detachViews()
        mp.setEventListener(null)
        NativeAudio.release()
        mp.release()
        libVlc.release()
        createEngine(style)
        if (layout != null) { mp.attachViews(layout, null, true, false); refreshSurface() }
    }

    /** Applies a new subtitle style / ReplayGain setting; restarts the current item at the same position. */
    fun applyStyle() {
        val s = _state.value
        if (s.item == null) { recreateEngine(store.current.subStyle); return }
        val pos = mp.time
        val playing = mp.isPlaying
        startItem(s.index, pos)
        if (!playing) main.postDelayed({ mp.pause() }, 400)
    }

    fun togglePause() { if (mp.isPlaying) mp.pause() else { am.requestAudioFocus(focusRequest); mp.play() } }
    fun resume() { if (!mp.isPlaying && _state.value.item != null) { am.requestAudioFocus(focusRequest); mp.play() } }
    fun pause() { if (mp.isPlaying) mp.pause() }
    fun stop() {
        saveProgress()
        resetPicture()
        mp.stop()
        currentPfd?.close(); currentPfd = null
        am.abandonAudioFocusRequest(focusRequest)
        _state.value = PlayerState()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    fun seekTo(ms: Long) {
        val t = ms.coerceAtLeast(0)
        mp.setTime(t)
        _state.value = _state.value.copy(time = t)   // the bar shows the new place at once
    }

    /**
     * Seek bar: [f] = 0…1 of the playing item (a .cue track counts from its own start).
     * Worked out from the player right now, not from values remembered when the screen was drawn:
     * those could still be 0 when the song's length wasn't known yet, so every jump went nowhere.
     */
    fun seekFraction(f: Float) {
        val s = _state.value
        val item = s.item ?: return
        val base = if (item.startMs > 0) item.startMs else 0
        val len = if (item.endMs > 0) item.endMs - item.startMs else if (mp.length > 0) mp.length else s.length
        val frac = f.coerceIn(0f, 1f)
        if (len > 0) seekTo(base + (frac * len).toLong().coerceAtMost(len - 500).coerceAtLeast(0))
        else mp.setPosition(frac)
    }
    fun seekBy(ms: Long) { mp.setTime((mp.time + ms).coerceIn(0, if (mp.length > 0) mp.length else Long.MAX_VALUE)) }
    fun setRate(r: Float) { mp.setRate(r); _state.value = _state.value.copy(rate = r) }

    fun selectAudio(id: Int) { mp.setAudioTrack(id); refreshTracks() }
    fun selectSpu(id: Int) { mp.setSpuTrack(id); refreshTracks() }

    fun addSubtitleFile(uri: Uri) {
        mp.addSlave(IMedia.Slave.Type.Subtitle, uri, true)
        main.postDelayed({ refreshTracks() }, 800)
    }

    /** Zoom mode chosen in the player (kept across items, rotation and engine restarts). */
    var scale: MediaPlayer.ScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        private set

    fun setScale(s: MediaPlayer.ScaleType) { scale = s; mp.setVideoScale(s); refreshSurface() }

    /** Re-fits the picture to the current size of the video area (rotation, split screen, PiP, new video size). */
    fun refreshSurface() {
        if (attachedLayout == null) return
        main.post {
            try { mp.updateVideoSurfaces() } catch (_: Throwable) {}
            try { mp.setVideoScale(scale) } catch (_: Throwable) {}
        }
    }

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
        store.update { st -> st.copy(subOffsets = if (v == 0L) st.subOffsets - key else st.subOffsets + (key to v)) }
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
        refreshSurface()
    }

    /** Fresh surfaces for the same view (after it was off screen). */
    fun reattach(layout: VLCVideoLayout) {
        if (attachedLayout !== layout) return
        main.post {
            try { mp.detachViews(); mp.attachViews(layout, null, true, false) } catch (_: Throwable) {}
            refreshSurface()
        }
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
                _state.value = s.copy(isPlaying = true, error = "", ended = false, buffering = 100f)
                if (pendingSeek > 5_000) { mp.setTime(pendingSeek) }
                pendingSeek = -1
                if (!offsetsApplied) {
                    offsetsApplied = true; applyStoredOffsets()
                    watchSound()
                    // Successful opens are logged too, so each server page shows what worked, not only failures.
                    s.item?.takeIf { !it.isRadio && it.uri.contains("://") && !it.uri.startsWith("content://") && !it.uri.startsWith("file://") }?.let { item ->
                        ServerLog.log(item.uri, "play", item.uri.substringBefore("://").uppercase(), item.user, true,
                            "Playing ${item.title}" + if (triedBridge) " (through the app's SMB bridge)" else "")
                    }
                }
                refreshTracks()
                readMeta()
            }
            MediaPlayer.Event.Paused -> { _state.value = s.copy(isPlaying = false); saveProgress() }
            MediaPlayer.Event.Stopped -> _state.value = s.copy(isPlaying = false)
            MediaPlayer.Event.EndReached -> {
                if (s.sleepEndOfTrack) { main.post { stop() }; return }
                s.item?.let { item -> store.update { st -> st.copy(recent = st.recent.filterNot { it.uri == item.uri }) } }
                val n = nextIndex(s, manual = false)
                if (n != null) main.post { startItem(n, -1) }
                else {
                    _state.value = s.copy(isPlaying = false, ended = true)
                    // Nothing follows: stop and remove the notification.
                    main.postDelayed({ if (_state.value.ended) stop() }, 300)
                }
            }
            MediaPlayer.Event.TimeChanged -> _state.value = s.copy(time = ev.timeChanged)
            MediaPlayer.Event.LengthChanged -> _state.value = s.copy(length = ev.lengthChanged)
            MediaPlayer.Event.Buffering -> _state.value = s.copy(buffering = ev.buffering)
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.Vout -> { refreshTracks(); refreshSurface() }
            MediaPlayer.Event.EncounteredError -> {
                val item = s.item
                // libVLC can't open some SMB servers (SMB1-only): retry once through the in-app bridge.
                if (item != null && item.uri.startsWith("smb://") && !triedBridge && store.current.settings.allowSmb1) {
                    triedBridge = true
                    ServerLog.log(item.uri, "play", "SMB", item.user, false, "libVLC could not open the file, retrying through the app's SMB bridge")
                    main.post {
                        val m = makeMedia(item, _state.value, useBridge = true)
                        mp.setMedia(m); m.release(); mp.play()
                    }
                } else {
                    if (item != null && !item.uri.startsWith("content://")) ServerLog.log(item.uri, "play", item.uri.substringBefore("://").uppercase(), item.user, false, "Could not play ${item.title}")
                    _state.value = s.copy(isPlaying = false, error = "Could not play this item")
                }
            }
        }
    }

    private fun readMeta() {
        try {
            val m = mp.media ?: return
            val artist = m.getMeta(IMedia.Meta.Artist, true)?.trim().orEmpty()
            val album = m.getMeta(IMedia.Meta.Album, true)?.trim().orEmpty()
            val art = m.getMeta(IMedia.Meta.ArtworkURL, true)?.trim().orEmpty()
            m.release()
            val s = _state.value
            _state.value = s.copy(
                metaArtist = s.metaArtist.ifEmpty { artist }, metaAlbum = s.metaAlbum.ifEmpty { album },
                artworkUrl = s.artworkUrl.ifEmpty { art },
            )
        } catch (_: Exception) {
        }
    }

    private fun refreshTracks() {
        val a = mp.audioTracks?.map { Track(it.id, it.name ?: "Track ${it.id}") } ?: emptyList()
        val sp = mp.spuTracks?.map { Track(it.id, it.name ?: "Track ${it.id}") } ?: emptyList()
        val vt = mp.currentVideoTrack
        // Displayed size: pixel aspect (anamorphic DVDs/TV) and rotation (phone-shot videos) included.
        var dw = _state.value.videoW; var dh = _state.value.videoH
        if (vt != null && vt.width > 0 && vt.height > 0) {
            dw = if (vt.sarNum > 0 && vt.sarDen > 0) (vt.width.toLong() * vt.sarNum / vt.sarDen).toInt() else vt.width
            dh = vt.height
            if (vt.orientation >= 4) { val t = dw; dw = dh; dh = t }   // LeftTop…RightBottom = turned 90°/270°
        }
        val sizeChanged = dw != _state.value.videoW || dh != _state.value.videoH
        _state.value = _state.value.copy(
            audioTracks = a, spuTracks = sp, audioTrack = mp.audioTrack, spuTrack = mp.spuTrack,
            videoW = dw, videoH = dh,
            length = if (mp.length > 0) mp.length else _state.value.length,
        )
        if (sizeChanged) refreshSurface()
    }

    /** Radio "now playing" (ICY metadata) changes without any player event, so read it every few seconds. */
    private val metaPoller: Runnable = object : Runnable {
        override fun run() {
            try {
                val s = _state.value
                if (s.sleepUntil > 0 && System.currentTimeMillis() >= s.sleepUntil) stop()
                val item = s.item
                if (item != null && item.isRadio) {
                    val m = mp.media
                    val np = m?.getMeta(IMedia.Meta.NowPlaying, true)?.trim().orEmpty()
                        .ifEmpty { m?.getMeta(IMedia.Meta.Title, true)?.trim().orEmpty().takeIf { it != item.title && !it.startsWith("http") } ?: "" }
                    m?.release()
                    if (np != s.nowPlaying) _state.value = _state.value.copy(nowPlaying = np)
                }
            } catch (_: Exception) {
            }
            main.postDelayed(this, 3000)
        }
    }

    init {
        main.post(metaPoller)
    }

    fun saveProgress() {
        val s = _state.value
        val item = s.item ?: return
        if (item.isRadio || item.startMs >= 0) return
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

    /** minutes > 0: stop after that time; minutes == -1: stop at the end of the current item; 0: off. */
    fun setSleep(minutes: Int) {
        val s = _state.value
        _state.value = when {
            minutes > 0 -> s.copy(sleepUntil = System.currentTimeMillis() + minutes * 60_000L, sleepEndOfTrack = false)
            minutes == -1 -> s.copy(sleepUntil = 0, sleepEndOfTrack = true)
            else -> s.copy(sleepUntil = 0, sleepEndOfTrack = false)
        }
    }

    val isPlaying: Boolean get() = mp.isPlaying
    val position: Long get() = mp.time
}
