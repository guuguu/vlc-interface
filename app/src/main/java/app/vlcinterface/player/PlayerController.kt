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

/** Gain for songs that carry no ReplayGain at all (when ReplayGain is on). */
const val NO_RG_DB = 0f     // no ReplayGain: played as it is (no preamp either); the scan question handles it

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
    /** Paused on purpose (pause button, another app took the sound). Between songs / loading is NOT a pause. */
    val userPaused: Boolean = false,
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
    /** Loudness (0…15 dB of ISO 226 compensation at 60 Hz) and treble (−12…+12 dB) in use; 0 / 0 = no processing. */
    val loudDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** Radio: this station's pre-amp cut (−12…0 dB; stations have no ReplayGain). */
    val radioPreDb: Float = 0f,
    /** This song (station) has its own saved loudness / treble (Recall brings them back). */
    val toneSaved: Boolean = false,
    /** Radio: the name the station announces about itself (ICY name), if any. */
    val stationName: String = "",
    val buffering: Float = 100f,
    val error: String = "",
    val ended: Boolean = false,
    /** The song just ended: its picture is cleared at once (until the next song shows its own). */
    val songOver: Boolean = false,
    val rgInfo: String = "",
    val sleepUntil: Long = 0,
    val sleepEndOfTrack: Boolean = false,
    /** Sleep after this many songs (the playing one counts as 1); 0 = off. */
    val sleepSongs: Int = 0,
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
        // One more try before giving up on the app's own output (a first attach can fail right after a release).
        if (!NativeAudio.attachTo(libVlc, mp, appOutputWanted()) && appOutputWanted() && !NativeAudio.attachTo(libVlc, mp, true)) {
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

    /**
     * A scan just measured the playing song: its value is used at once, for this song only (until its file is
     * written back). The next song loads its own value as usual.
     */
    @Volatile private var scannedRg: Pair<String, app.vlcinterface.sources.Rg>? = null
    fun applyScannedRg(uri: String, values: Map<String, String>) {
        fun f(k: String) = values[k]?.replace("dB", "")?.trim()?.toFloatOrNull()
        val rg = app.vlcinterface.sources.Rg(f("REPLAYGAIN_TRACK_GAIN"), f("REPLAYGAIN_TRACK_PEAK"), f("REPLAYGAIN_ALBUM_GAIN"), f("REPLAYGAIN_ALBUM_PEAK"))
        main.post {
            val s = _state.value; val item = s.item ?: return@post
            if (item.uri != uri) return@post
            scannedRg = uri to rg
            loadReplayGain(item, s)
        }
    }
    private suspend fun rgFor(item: PlayItem) = scannedRg?.takeIf { it.first == item.uri }?.second
        ?: app.vlcinterface.sources.ReplayGain.read(context, item.uri, item.user, item.password, item.domain, item.smbVersion)

    private fun loadReplayGain(item: PlayItem, s: PlayerState) {
        if (scannedRg?.first != item.uri) scannedRg = null      // next song: back to its own value
        rgDb = 0f
        rgJob?.cancel()
        val set = store.current.settings
        if (!item.isMusic || set.rgMode == "off") return
        if (!NativeAudio.active) {
            // libVLC's own output applies ReplayGain itself; the line still shows the song's value from its tags.
            val m = rgModeFor(s)
            _state.value = _state.value.copy(rgInfo = "RG $m (libVLC)")
            rgJob = bg.launch {
                val rg = rgFor(item)
                if (_state.value.item !== item) return@launch
                val album = m == "album"
                val g = if (album) rg?.albumGain ?: rg?.trackGain else rg?.trackGain ?: rg?.albumGain
                val used = if (g == null) "" else if (album && rg?.albumGain != null || !album && rg?.trackGain == null) "album" else "track"
                if (rg == null || g == null) askRgScan(item)
                _state.value = _state.value.copy(rgInfo = when {
                    rg == null || g == null -> "No RG · plays as is (libVLC)"
                    else -> "RG $used " + (if (g >= 0) "+" else "−") + "%.2f".format(java.util.Locale.US, kotlin.math.abs(g)) + " dB (libVLC)"
                })
            }
            return
        }
        val mode = rgModeFor(s)
        rgJob = bg.launch {
            val rg = rgFor(item)
            if (_state.value.item !== item) return@launch
            val album = mode == "album"
            val gain = if (album) rg?.albumGain ?: rg?.trackGain else rg?.trackGain ?: rg?.albumGain
            val peak = if (album) rg?.albumPeak ?: rg?.trackPeak else rg?.trackPeak ?: rg?.albumPeak
            val used = if (gain == null) "" else if (album && rg?.albumGain != null || !album && rg?.trackGain == null) "album" else "track"
            // No ReplayGain in the song: played as it is — no preamp at all (the scan question takes care of it), and the
            // player line says so.
            var db = if (gain == null) NO_RG_DB else gain + set.rgPreampDb
            var limited = false
            if (gain != null && set.rgClipProtect && peak != null && peak > 0f) { val cap = -20f * log10(peak); if (cap < db) { db = cap; limited = true } }
            rgDb = db
            if (gain == null) askRgScan(item)
            fun f(v: Float) = (if (v >= 0) "+" else "−") + "%.2f".format(java.util.Locale.US, kotlin.math.abs(v)) + " dB"
            // Shows the song's own value (exactly as in its tags / the scan: track or album). When what is played
            // differs — preamp added, or lowered so the loudest peak can't clip — that is said separately.
            val info = when {
                rg == null || gain == null -> "No RG · plays as is"
                kotlin.math.abs(db - gain) < 0.005f -> "RG $used ${f(gain)}"
                else -> "RG $used ${f(gain)} · plays ${f(db)}" + when {
                    limited && set.rgPreampDb != 0f -> " (preamp, peak limit)"
                    limited -> " (peak limit)"
                    else -> " (preamp)"
                }
            }
            _state.value = _state.value.copy(rgInfo = info)
            applySoundChain()
        }
    }

    /**
     * What libVLC itself must know about ReplayGain (a change means a new engine). With the app's own output the app
     * applies ReplayGain live, so a change never rebuilds the engine — rebuilding is what made the output fail and
     * drop to libVLC's output when the RGain button was pressed.
     */
    private fun rgSignature(): String {
        if (appOutputWanted()) return "app"
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
        o += "--audio-replay-gain-default=$NO_RG_DB"      // songs without ReplayGain: as they are (as with the app's output)
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
        val rg = (if (NativeAudio.active && _state.value.item?.isMusic == true) rgDb else 0f) +
            (if (_state.value.item?.isRadio == true) _state.value.radioPreDb else 0f)
        val total = (st.settings.preampDb + (st.devicePreamp[dev] ?: 0f) + rg).coerceIn(-36f, 12f)
        val bal = (st.deviceBalance[dev] ?: 0).coerceIn(-100, 100)
        fun att(f: Float): Float = if (f <= 0.001f) -60f else (20f * log10(f)).coerceAtLeast(-60f)
        val left = total + if (bal > 0) att(1f - bal / 100f) else 0f
        val right = total + if (bal < 0) att(1f + bal / 100f) else 0f
        if (NativeAudio.active) {
            fun lin(db: Float) = if (db <= -60f) 0f else 10f.pow(db / 20f)
            NativeAudio.gains(lin(left), lin(right), total > 0f)
            applyTone(0f)
            return
        }
        val d = dp
        if (d != null) {
            try {
                d.setInputGainbyChannel(0, left)
                d.setInputGainbyChannel(1, right)
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, total > 0f, 0, 1f, 60f, 10f, -1f, 0f))
                applyTone(0f)
                return
            } catch (_: Exception) {
            }
        }
        // Fallback without balance: libVLC's equalizer pre-amplifier.
        applyTone(total.coerceIn(-20f, 20f))
    }

    /**
     * Loudness and treble through libVLC's 10-band equalizer (60 Hz … 16 kHz), cutting only — nothing is ever
     * amplified, and the top frequencies stay at their starting level:
     *  - loudness: the ISO 226:2003 equal-loudness compensation (see [Iso226]), applied as cuts;
     *  - treble (8 → 16 kHz S-curve whose steepness grows with the setting): treble down lowers the top by the curve; treble up lowers everything else by the
 *    complement of the curve (the top stays).
     * Both at 0 (and no pre-amp needed): no equalizer at all.
     */
    private fun applyTone(preamp: Float) {
        val s = _state.value
        val l = s.loudDb; val t = s.trebleDb
        try {
            if (l == 0f && t == 0f && preamp == 0f) { mp.setEqualizer(null); return }
            val e = eq ?: MediaPlayer.Equalizer.create().also { eq = it }
            val loud = Iso226.cuts(l)              // ISO 226:2003 equal-loudness compensation, as cuts
            // Treble acts from 8 kHz up: an S-curve on a log-frequency scale from 0 at 8 kHz to full at 16 kHz
            // (bands below 8 kHz take the 8 kHz value). The curve's shape follows the setting, not just its depth:
            // small settings give a gentle, nearly straight slope; larger ones a deeper, steeper S that keeps the
            // correction near the top. S(x) = xᵃ / (xᵃ + (1−x)ᵃ), a from 1.2 (±0.5 dB) to 3 (±12 dB).
            val steep = 1.2 + 1.8 * (kotlin.math.abs(t) / 12.0).coerceIn(0.0, 1.0)
            val highShape = FloatArray(10) { b ->
                val x = (kotlin.math.ln(Iso226.bands[b] / 8000.0) / kotlin.math.ln(2.0)).coerceIn(0.0, 1.0)
                val xa = x.pow(steep); val ya = (1 - x).pow(steep)
                (if (xa + ya == 0.0) 0.0 else xa / (xa + ya)).toFloat()
            }
            for (b in 0 until 10) {
                val loudCut = loud[b]
                val trebleCut = if (t >= 0f) -t * (1f - highShape[b]) else t * highShape[b]
                e.setAmp(b, (loudCut + trebleCut).coerceIn(-20f, 0f))
            }
            e.setPreAmp(preamp)
            mp.setEqualizer(e)
        } catch (_: Exception) {
        }
    }

    // ---------- loudness / treble per song ----------
    private var toneDirty = false

    /** Slider moved (live); [done] = finger lifted. Kept as the "last values" too. */
    fun setTone(loud: Float, treble: Float, done: Boolean) {
        val l = loud.coerceIn(0f, 15f); val t = treble.coerceIn(-12f, 12f)
        _state.value = _state.value.copy(loudDb = l, trebleDb = t)
        applySoundChain()
        toneDirty = true
        if (done) store.update { it.copy(settings = it.settings.copy(toneLoud = l, toneTreble = t)) }
    }

    /** Radio pre-amp slider (cut only); kept for the station when the finger is lifted. */
    fun setRadioPre(db: Float, done: Boolean) {
        val item = _state.value.item ?: return
        val v = db.coerceIn(-12f, 0f)
        _state.value = _state.value.copy(radioPreDb = v)
        applySoundChain()
        if (done) ToneStore.putPre(item.uri, v)
    }

    /** Back to this song's own saved values (if it has some). */
    fun recallTone() {
        val item = _state.value.item ?: return
        val v = ToneStore.get(item) ?: return
        toneDirty = false
        _state.value = _state.value.copy(loudDb = v.first, trebleDb = v.second)
        applySoundChain()
        store.update { it.copy(settings = it.settings.copy(toneLoud = v.first, toneTreble = v.second)) }
    }

    /** Leaving a song: values changed during it are saved with it. */
    private fun toneLeaving() {
        val s = _state.value
        val item = s.item ?: return
        if (toneDirty) ToneStore.put(item, s.loudDb, s.trebleDb)
        toneDirty = false
    }

    /** A song starts: its own saved values at once, else the last values used. */
    private fun toneFor(item: PlayItem): Triple<Float, Float, Boolean> {
        val v = ToneStore.get(item)
        val set = store.current.settings
        return if (v != null) Triple(v.first, v.second, true) else Triple(set.toneLoud, set.toneTreble, false)
    }

    /**
     * Watchdog for the app's own output: if no sound reaches the device within a few seconds of playing,
     * switch to libVLC's output for this run and restart the item at the same position.
     */
    /**
     * Checks that our own output really plays. Judged only once the song is actually moving (play position
     * advancing, not buffering): with the bigger read-ahead a network song can take a few seconds before any sound
     * arrives, and that wait was wrongly taken as a failure — the app then switched to libVLC's output for the rest
     * of the run ("RG … (libVLC)"). Not ready yet → looked at again a little later (up to ~20 s).
     */
    private fun watchSound(tries: Int = 0) {
        if (!NativeAudio.active) return
        val s0 = _state.value
        val moving = s0.isPlaying && s0.buffering >= 100f && lastTick > 0 && s0.time > 500 &&
            System.currentTimeMillis() - lastTick < 1_500
        // Still not moving after ~20 s: checked anyway (a silent output can also stop the position from moving).
        if (!moving && tries < 8) { main.postDelayed({ watchSound(tries + 1) }, 2_500); return }
        val before = NativeAudio.stats() ?: return
        main.postDelayed({
            val s = _state.value
            if (!NativeAudio.active || !s.isPlaying || s.item == null || s.item !== s0.item) return@postDelayed
            if (tries < 8 && (s.buffering < 100f || System.currentTimeMillis() - lastTick > 1_500)) { watchSound(tries + 1); return@postDelayed }
            if (mp.audioTracksCount <= 0) return@postDelayed      // video without sound: nothing to check
            val now = NativeAudio.stats() ?: return@postDelayed
            // Still starting (state 3): Bluetooth in particular can take several seconds to begin taking sound —
            // not a failure yet, look again shortly (your log: "state 3, heard 0" was exactly this).
            if (now.state == 3L && tries < 8) { main.postDelayed({ watchSound(tries + 1) }, 2_500); return@postDelayed }
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
        }, 4000)
    }

    // ---------- picture colour ----------

    /** True when the playing item was opened with the colour filter (live changes possible). */
    private var pictureFilterOn = false

    private fun pictureNeutral(h: Float, s: Float) = kotlin.math.abs(h) < 0.05f && kotlin.math.abs(s - 1f) < 0.01f

    /**
     * "Bright areas" (0…1) → contrast, brightness, gamma for libVLC's adjust filter: whites are pulled down
     * (lower contrast and brightness) so burnt-out areas glare less and show what detail they still have,
     * while gamma lifts the darker parts back so the rest of the picture keeps its look.
     */
    private fun highlightTone(k: Float): Triple<Float, Float, Float> {
        val x = k.coerceIn(0f, 1f)
        return Triple(1f - 0.30f * x, 1f - 0.12f * x, 1f + 0.30f * x)
    }

    /** Bright-areas setting from the player (live while dragging when possible; saved on release). */
    fun setHighlights(k: Float, commit: Boolean) {
        if (commit) { store.update { it.copy(settings = it.settings.copy(pictureHighlights = k.coerceIn(0f, 1f))) }; savePictureForFile() }
        val st = store.current.settings
        applyPicture(st.pictureHue, st.pictureSat, k, commit)
    }

    private fun applyPicture(hue: Float, sat: Float, hl: Float, commit: Boolean) {
        val s = _state.value
        val item = s.item ?: return
        if (item.isMusic || item.isRadio) return
        val (c, b, g) = highlightTone(hl)
        // While dragging: live preview when possible.
        if (!commit) { if (pictureFilterOn) NativeAudio.picture(libVlc, mp, true, hue, sat, c, b, g); return }
        // Slider released / Reset: the video is reloaded at the same spot so the setting is applied fully (user rule).
        reloadVideo()
    }

    /**
     * Hue/saturation from the player. While dragging ([commit] false) changes are applied live when possible;
     * on release they are saved. The first change on a video reopens it at the same spot (a second at most),
     * because hardware decoding must switch to a mode the colour filter can work on.
     */
    fun setPicture(hue: Float, sat: Float, commit: Boolean) {
        if (commit) { store.update { it.copy(settings = it.settings.copy(pictureHue = hue, pictureSat = sat)) }; savePictureForFile() }
        applyPicture(hue, sat, store.current.settings.pictureHighlights, commit)
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

    /**
     * Player's RG button: ReplayGain on (the mode chosen in Settings) / off. Applied at once: the song is reloaded
     * and goes on from where it is now (paused stays paused).
     */
    fun toggleReplayGain() {
        val st = store.current.settings
        val on = st.rgMode != "off"
        store.update { it.copy(settings = if (on) it.settings.copy(rgMode = "off", rgLastMode = st.rgMode) else it.settings.copy(rgMode = st.rgLastMode)) }
        val s = _state.value
        if (s.item == null) return
        // App output: on / off applied at once on the playing song, no reload, no new engine.
        if (NativeAudio.active) {
            loadReplayGain(s.item, s)
            if (store.current.settings.rgMode == "off") { _state.value = _state.value.copy(rgInfo = ""); applySoundChain() }
            return
        }
        val pos = mp.time
        val playing = mp.isPlaying
        startItem(s.index, pos.coerceAtLeast(0))
        if (!playing) main.postDelayed({ pause() }, 400)
    }

    // ---------- volume on a device the app picked itself ----------

    /** App volume 0…2 (1 = as the device is set), used only while [OutputRouter.overriding]. */
    var appVolume = 1f
        private set

    fun setAppVolume(v: Float) { appVolume = v.coerceIn(0f, 2f); NativeAudio.appVolume(if (sleepClosing) 0f else appVolume * fadeK) }

    // ---------- sleep fade ----------

    /** Sleep: before playback stops the volume goes down by itself in 40 steps — over the last 7 minutes when the
     *  sleep time is 30 minutes or more, otherwise over the last 2 minutes (one step every 10.5 s / 3 s). */
    private var fadeK = 1f
    private var fadeLocked = false
    /** Length of the sleep fade: 7 minutes for a sleep time of 30 minutes or more, otherwise 2 minutes (40 steps either way). */
    private var fadeWindowMs = 120_000L
    @Volatile private var sleepClosing = false

    private fun applyFade(k: Float) {
        if (k == fadeK) return
        fadeK = k
        NativeAudio.appVolume(appVolume * k)
        if (!ducked) mp.setVolume((100 * k).toInt())
    }

    /** Milliseconds until sleep stops playback, or -1 when no sleep is set (or the end isn't known yet). */
    private fun msToSleep(s: PlayerState): Long = when {
        s.sleepUntil > 0 -> s.sleepUntil - System.currentTimeMillis()
        // Songs (after 1 song / this album / N songs): rest of this song + the songs still to come before it stops,
        // so the fade runs across them exactly as for a timed sleep.
        s.sleepEndOfTrack || s.sleepSongs >= 1 -> {
            val item = s.item
            val len = if (item != null && item.endMs > 0) item.endMs - item.startMs else s.length
            if (item == null || len <= 0) -1 else {
                var total = (len - (s.time - maxOf(item.startMs, 0L))).coerceAtLeast(0)
                var n = if (s.sleepEndOfTrack) 0 else s.sleepSongs - 1
                var i = s.index + 1
                while (n > 0 && s.queue.isNotEmpty()) {
                    if (i >= s.queue.size) i = 0                      // fewer left: it goes on from the beginning
                    val q = s.queue[i]
                    total += (if (q.endMs > 0) q.endMs - q.startMs else q.durationMs).coerceAtLeast(0)
                    i++; n--
                }
                total
            }
        }
        else -> -1
    }

    private val fadeTicker = object : Runnable {
        override fun run() {
            val s = _state.value
            val left = if (s.item != null && s.isPlaying) msToSleep(s) else -1
            // 40 steps over the last 120 s (one every 3 s). Once the fade has begun it only goes down — the song
            // ending, the pause before closing or the close itself can never bring the volume back up (that
            // was the loud burst at the end). It's released only when sleep is turned off or the app starts again.
            val sleepSet = s.sleepUntil > 0 || s.sleepSongs > 0 || s.sleepEndOfTrack
            if (!sleepSet && !sleepClosing) fadeLocked = false
            val k = when {
                sleepClosing -> 0f
                left in 0..fadeWindowMs -> { val step = fadeWindowMs / 40; ((left + step - 1) / step).toInt().coerceIn(0, 40) / 40f }
                fadeLocked -> fadeK
                else -> 1f
            }
            if (k < 1f) fadeLocked = true
            applyFade(if (fadeLocked) minOf(k, fadeK) else k)
            main.postDelayed(this, 500)
        }
    }

    init { main.postDelayed(fadeTicker, 1_000) }

    /** Volume buttons / swipe step while the app plays on its own picked device. Returns the new percent (100 = as set). */
    fun stepAppVolume(up: Boolean): Int { setAppVolume(appVolume + if (up) 0.0667f else -0.0667f); return (appVolume * 100).toInt() }

    /**
     * Reloads the playing video with the current video settings and goes on from the same spot (paused stays paused).
     * Used whenever a video setting is changed in the player.
     */
    fun reloadVideo() {
        val s = _state.value
        val item = s.item ?: return
        if (item.isMusic || item.isRadio) return
        val pos = mp.time; val playing = mp.isPlaying
        startItem(s.index, pos.coerceAtLeast(0))
        if (!playing) main.postDelayed({ pause() }, 400)
    }

    /**
     * Video picture settings (hue, saturation, bright areas) are kept per file: saved when changed in the player,
     * loaded again when that file is played. Back to default values (all three) = the file's entry is removed.
     */
    private fun savePictureForFile() {
        val item = _state.value.item ?: return
        if (item.isMusic || item.isRadio) return
        val ps = store.current.settings
        val neutral = pictureNeutral(ps.pictureHue, ps.pictureSat) && ps.pictureHighlights <= 0.01f
        store.update { st ->
            val m = st.settings.pictureByFile.toMutableMap()
            if (neutral) m.remove(item.uri) else m[item.uri] = "${ps.pictureHue},${ps.pictureSat},${ps.pictureHighlights}"
            st.copy(settings = st.settings.copy(pictureByFile = m))
        }
    }

    /** The file's own saved picture settings become the current ones (defaults when it has none). */
    private fun loadPictureForFile(item: PlayItem) {
        if (item.isMusic || item.isRadio) return
        val v = store.current.settings.pictureByFile[item.uri]?.split(",")?.mapNotNull { it.toFloatOrNull() }
        val hue = v?.getOrNull(0)?.coerceIn(-20f, 20f) ?: 0f
        val sat = v?.getOrNull(1)?.coerceIn(0f, 3f) ?: 1f
        val hl = v?.getOrNull(2)?.coerceIn(0f, 1f) ?: 0f
        val ps = store.current.settings
        if (ps.pictureHue != hue || ps.pictureSat != sat || ps.pictureHighlights != hl)
            store.update { it.copy(settings = it.settings.copy(pictureHue = hue, pictureSat = sat, pictureHighlights = hl)) }
    }

    /**
     * The sliders always show the playing video's own saved values. Closing the player used to set them back to
     * default while the video went on, so reopening it showed defaults, and the next touch saved defaults over the
     * file's values. Now closing / reopening just reloads that video's saved values.
     */
    fun syncPicture() { _state.value.item?.let { loadPictureForFile(it) } }

    /** Settings → Sound engine changed: rebuild the engine and continue where it was. */
    /**
     * Switches the sound output to the one chosen in Settings: the app output gets a fresh try (an earlier failure is
     * forgotten), the engine is rebuilt, and the current item goes on from the same place, playing or paused as it was.
     */
    fun applySoundEngine() {
        if (store.current.settings.soundEngine != "libvlc") NativeAudio.resetFailed()
        val s = _state.value
        val pos = if (s.item != null) mp.time else -1
        val playing = s.item != null && mp.isPlaying
        recreateEngine(store.current.subStyle)
        if (s.item != null) {
            startItem(s.index, pos)
            if (!playing) main.postDelayed({ pause() }, 400)
        }
    }

    // ---------- audio focus + "headphones unplugged" ----------

    private var resumeOnGain = false
    @Volatile private var ducked = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (mp.isPlaying) { resumeOnGain = true; mp.pause() } }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; mp.setVolume(30) }
                AudioManager.AUDIOFOCUS_GAIN -> { ducked = false; mp.setVolume((100 * fadeK).toInt()); if (resumeOnGain) { resumeOnGain = false; if (!BatteryGuard.blocked(silent = true)) mp.play() } }
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
    /** Same file (and same .cue track) = the same queue entry. */
    private fun qKey(it: PlayItem) = "${it.uri}#${it.startMs}"

    /** Set by [loadQueue]: the first song is paused as soon as it's ready. */
    private var startPaused = false

    /**
     * Playlists page → Load: the playlist becomes the queue, first song ready and paused (press ▶ to start).
     * Silent while it gets ready.
     */
    fun loadQueue(items: List<PlayItem>, origin: Origin) {
        if (items.isEmpty()) return
        startPaused = true
        NativeAudio.appVolume(0f)
        playQueue(items, 0, origin, random = false)
    }

    fun playQueue(items0: List<PlayItem>, index0: Int, origin: Origin, startAt: Long = -1, random: Boolean? = null, keepOrder: Boolean = false) {
        if (items0.isEmpty()) return
        // Duplicates are left out (first one kept); the item that was tapped stays the one that starts.
        val tapped = items0.getOrNull(index0.coerceIn(0, items0.size - 1))
        val items = items0.distinctBy { qKey(it) }
        val index = tapped?.let { t -> items.indexOfFirst { qKey(it) == qKey(t) } }?.takeIf { it >= 0 } ?: 0
        if (random != null && random != store.current.settings.shuffle) store.update { it.copy(settings = it.settings.copy(shuffle = random)) }
        var i = index.coerceIn(0, items.size - 1)
        var q = items
        if (!keepOrder && store.current.settings.shuffle && items.size > 1) {
            orderedQueue = items
            q = listOf(items[i]) + items.filterIndexed { j, _ -> j != i }.shuffled()
            i = 0
        } else orderedQueue = null
        skippedPlaces.clear()                                       // a new list: every place is tried again
        // Today's list: the song playing until now was heard whole or cut off; a specific song picked to start
        // with (not the first of the list, or a single file) is a choice made by hand.
        _state.value.let { pv -> if (pv.item != null) sessionLeaving(pv.item, pv.time, pv.length) }
        if (index0 > 0 || items0.size == 1) sessionManualChoice()
        _state.value = PlayerState(queue = q, index = i, origin = origin)
        // A playlist / folder / queue: ask about the battery setting again if it would stop playback in the background.
        if (items.size > 1) app.vlcinterface.ui.Battery.request()
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

    /**
     * A radio station: played in the audio player with all favourite stations as the list, in their order
     * (⏮ / ⏭ go to the previous / next favourite). A station that isn't a favourite plays on its own.
     */
    fun playStation(name: String, url: String) {
        val favs = store.current.stations
        val i = favs.indexOfFirst { it.url == url }
        if (i < 0) { playQueue(listOf(PlayItem(url, name, isRadio = true)), 0, Origin("radio", "", url, name), keepOrder = true); return }
        playQueue(favs.map { PlayItem(it.url, it.name, isRadio = true) }, i, Origin("radio", "", url, "Favourite stations"), keepOrder = true)
    }

    /**
     * What the stream itself reports right now (radio): its tags (name, genre, description, publisher, link,
     * language, playing now) and the audio as decoded (codec, sample rate, channels, bitrate, data read).
     */
    fun streamInfo(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val m = try { mp.media } catch (_: Exception) { null } ?: return out
        try {
            fun meta(k: Int, label: String) { m.getMeta(k, true)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += label to it } }
            meta(IMedia.Meta.Title, "Stream name")
            meta(IMedia.Meta.NowPlaying, "Playing now")
            meta(IMedia.Meta.Genre, "Stream genre")
            meta(IMedia.Meta.Description, "Stream description")
            meta(IMedia.Meta.Publisher, "Publisher")
            meta(IMedia.Meta.URL, "Stream link")
            meta(IMedia.Meta.Language, "Stream language")
            meta(IMedia.Meta.EncodedBy, "Encoded by")
            for (i in 0 until m.trackCount) {
                val t = m.getTrack(i) as? IMedia.AudioTrack ?: continue
                t.codec?.takeIf { it.isNotBlank() }?.let { out += "Decoded codec" to it.trim() }
                if (t.rate > 0) out += "Sample rate" to "${t.rate} Hz"
                if (t.channels > 0) out += "Channels" to when (t.channels) { 1 -> "1 (mono)"; 2 -> "2 (stereo)"; else -> "${t.channels}" }
                if (t.bitrate > 0) out += "Stream bitrate" to "${t.bitrate / 1000} kbps"
                t.description?.takeIf { it.isNotBlank() }?.let { out += "Track" to it }
                break
            }
            m.stats?.let { s ->
                if (s.inputBitrate > 0) out += "Measured bitrate" to "${Math.round(s.inputBitrate * 8000)} kbps"
                if (s.readBytes > 0) out += "Data read" to app.vlcinterface.player.NetGuard.fmt(s.readBytes.toLong())
                if (s.lostAbuffers > 0) out += "Lost audio buffers" to "${s.lostAbuffers}"
            }
        } catch (_: Exception) {
        } finally { m.release() }
        return out
    }

    /** Starts the current item again (a radio stream after the connection changed). */
    fun replayCurrent() { val s = _state.value; if (s.item != null) startItem(s.index, -1) }

    fun play(item: PlayItem, startAt: Long = -1, origin: Origin = Origin(if (item.isRadio) "radio" else "single", item.sourceId, item.uri, item.title)) =
        playQueue(listOf(item), 0, origin, startAt)

    fun playNext(items0: List<PlayItem>) {
        val s = _state.value
        if (s.item == null) { playQueue(items0, 0, Origin("single")); return }
        // Songs already in the queue aren't added twice.
        val have = s.queue.map { qKey(it) }.toHashSet()
        val items = items0.filter { have.add(qKey(it)) }
        if (items.isEmpty()) return
        session(null, items)
        val q = s.queue.toMutableList(); q.addAll(s.index + 1, items)
        orderedQueue?.let { o ->
            val at = o.indexOfFirst { it === s.queue.getOrNull(s.index) }
            orderedQueue = o.toMutableList().also { it.addAll(if (at >= 0) at + 1 else it.size, items) }
        }
        _state.value = s.copy(queue = q)
    }

    // ---------- songs without ReplayGain ----------

    /** A playing song without ReplayGain: the pop-up asking to scan it (or its folder). */
    val rgAsk = kotlinx.coroutines.flow.MutableStateFlow<PlayItem?>(null)
    /** Folders already asked about while the app runs (answered or not): not asked again until the next start. */
    private val rgAsked = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun askRgScan(item: PlayItem) {
        if (!item.isMusic) return
        val key = item.folderKey.ifEmpty { item.uri.substringBeforeLast('/') }
        if (!rgAsked.add(key)) return
        if (app.vlcinterface.sources.RgScanner.state.value.running) return
        rgAsk.value = item
    }

    // ---------- today's listening session ----------

    /**
     * Every song (and radio station) played during the day goes into that day's session playlist, made
     * automatically: named YY_MM_dd_<first hour>_<last hour>_session (hours only, the last one kept up to date),
     * songs in the order first played (no repeats), radio stations always at the end. Videos aren't counted.
     */
    /** Songs (no repeats) in first-played order, radio stations always last. */
    private fun sessionMerge(old: List<app.vlcinterface.data.PlEntry>, add: List<app.vlcinterface.data.PlEntry>): List<app.vlcinterface.data.PlEntry> {
        val mark = app.vlcinterface.sources.MusicLibrary.RADIO_MARK
        val songs = old.filter { it.sourceId != mark }.toMutableList()
        val radios = old.filter { it.sourceId == mark }.toMutableList()
        add.forEach { e ->
            if (e.sourceId == mark) { if (radios.none { it.uri == e.uri }) radios += e }
            else if (songs.none { it.uri == e.uri && it.startMs == e.startMs }) songs += e
        }
        return songs + radios
    }

    /**
     * Today's session playlist: made when the first song of the day plays and filled with every song played
     * through to its end (a song skipped before its end is taken out again, unless it was heard whole earlier
     * today), plus everything added. It's kept only if something was chosen by hand during the day — songs added,
     * a specific song picked, a song skipped; a day of plain listening leaves no list (removed the next day).
     * One per day, never two; a normal playlist you can play, but it never starts by itself.
     */
    private fun session(played: PlayItem?, added: List<PlayItem>, manual: Boolean = added.isNotEmpty()) {
        val add = added.filter { it.isMusic || it.isRadio }
        val c = java.util.Calendar.getInstance()
        val day = "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        val hour = c.get(java.util.Calendar.HOUR_OF_DAY)
        val playedE = played?.takeIf { it.isMusic || it.isRadio }?.let { listOf(app.vlcinterface.sources.MusicLibrary.toPlEntry(it)) } ?: emptyList()
        val addE = add.map { app.vlcinterface.sources.MusicLibrary.toPlEntry(it) }
        if (playedE.isEmpty() && addE.isEmpty() && !manual) return
        store.update { st0 ->
            var st = st0
            var set = st.settings
            val newDay = set.sessionDay != day
            if (newDay) {
                // Yesterday's list without anything chosen by hand: not kept.
                if (!set.sessionManual && set.sessionPlaylistId.isNotEmpty())
                    st = st.copy(playlists = st.playlists.filterNot { it.id == set.sessionPlaylistId })
                set = set.copy(sessionDay = day, sessionPlaylistId = "", sessionStartHour = hour, sessionLog = emptyList(), sessionManual = false)
                completedToday.clear()
            }
            val log = sessionMerge(set.sessionLog, playedE)
            set = set.copy(sessionLog = log, sessionManual = set.sessionManual || manual)
            val name = "%02d_%02d_%02d_%02d_%02d_session".format(c.get(java.util.Calendar.YEAR) % 100, c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), set.sessionStartHour, hour)
            val cur = st.playlists.firstOrNull { it.id == set.sessionPlaylistId }
            when {
                cur != null -> {
                    val items = sessionMerge(cur.items, playedE + addE)
                    st.copy(settings = set, playlists = if (cur.name == name && cur.items == items) st.playlists
                        else st.playlists.map { if (it.id == cur.id) it.copy(name = name, items = items) else it })
                }
                playedE.isEmpty() && addE.isEmpty() -> st.copy(settings = set)
                else -> {
                    val pl = app.vlcinterface.data.Playlist(name = name, items = sessionMerge(log, addE))
                    st.copy(playlists = st.playlists + pl, settings = set.copy(sessionPlaylistId = pl.id))
                }
            }
        }
    }

    /** Songs heard to (almost) their end today: never taken out of today's list by a later skip. */
    private val completedToday = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Leaving a song: heard to its end (90 % or more) → stays in today's list; skipped before → taken out of it
     * (and the skip counts as a choice made by hand).
     */
    private fun sessionLeaving(prev: PlayItem?, time: Long, length: Long) {
        if (prev == null || (!prev.isMusic && !prev.isRadio) || prev.isRadio) return
        val len = if (prev.endMs > 0) prev.endMs - prev.startMs else length
        val pos = time - maxOf(prev.startMs, 0L)
        if (len > 0 && pos >= len * 9 / 10) { completedToday += prev.uri; return }
        if (prev.uri in completedToday) return
        store.update { st ->
            val id = st.settings.sessionPlaylistId
            st.copy(settings = st.settings.copy(sessionLog = st.settings.sessionLog.filterNot { it.uri == prev.uri }, sessionManual = true),
                playlists = st.playlists.map { if (it.id == id) it.copy(items = it.items.filterNot { e -> e.uri == prev.uri }) else it })
        }
    }

    /** A song picked by hand (jump to, tap in the queue / Up next, skip): today's list is kept. */
    private fun sessionManualChoice() = session(null, emptyList(), manual = true)

    /** Adds to the end of the queue; returns how many were new (songs already in the queue aren't added twice). */
    fun addToQueue(items0: List<PlayItem>): Int {
        val s = _state.value
        if (s.item == null) { playQueue(items0, 0, Origin("single")); return items0.distinctBy { qKey(it) }.size }
        val have = s.queue.map { qKey(it) }.toHashSet()
        val items = items0.filter { have.add(qKey(it)) }
        if (items.isEmpty()) return 0
        session(null, items)                                     // an addition: today's session list gets it
        orderedQueue = orderedQueue?.plus(items)
        _state.value = s.copy(queue = s.queue + items)
        return items.size
    }

    fun removeFromQueue(i: Int) {
        val s = _state.value
        if (i == s.index || i !in s.queue.indices) return
        val q = s.queue.toMutableList(); val gone = q.removeAt(i)
        orderedQueue = orderedQueue?.filterNot { it === gone }
        _state.value = s.copy(queue = q, index = if (i < s.index) s.index - 1 else s.index)
    }

    fun jumpTo(i: Int) { if (i in _state.value.queue.indices) { sessionManualChoice(); startItem(i, -1) } }

    /** Moves a queue entry one place up (-1) or down (+1); the playing song keeps playing. */
    fun moveInQueue(i: Int, dir: Int) {
        val s = _state.value
        val j = i + dir
        if (i !in s.queue.indices || j !in s.queue.indices) return
        val q = s.queue.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
        val idx = when (s.index) { i -> j; j -> i; else -> s.index }
        _state.value = s.copy(queue = q, index = idx)
    }

    private var probeJob: kotlinx.coroutines.Job? = null

    /** Is the file there (and its network place reachable)? Quick check, done in the background. */
    private fun reachable(item: PlayItem): Boolean = try {
        val u = item.uri
        when {
            u.startsWith("content://") -> context.contentResolver.openFileDescriptor(Uri.parse(u), "r")?.use { true } ?: false
            u.startsWith("file://") -> java.io.File(Uri.parse(u).path ?: "").exists()
            u.startsWith("/") -> java.io.File(u).exists()
            u.startsWith("smb://") -> jcifs.smb.SmbFile(Uri.decode(u), app.vlcinterface.sources.SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)).use { it.exists() }
            u.startsWith("http") -> {
                val rq = okhttp3.Request.Builder().url(u).head().apply {
                    if (item.user.isNotEmpty()) header("Authorization", okhttp3.Credentials.basic(item.user, item.password))
                }.build()
                app.vlcinterface.sources.Net.http.newBuilder().connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build().newCall(rq).execute().use { it.code < 400 || it.code == 405 }
            }
            else -> true
        }
    } catch (_: Exception) { false }

    /**
     * Player's "Play next" search: the song goes right after the playing one in the current queue (already in the
     * queue → moved there). The playlist itself isn't changed; nothing else moves.
     */
    fun queueNext(item: PlayItem) {
        val s = _state.value
        session(null, listOf(item))
        if (s.item == null) { playQueue(listOf(item), 0, Origin("single")); return }
        val q = s.queue.toMutableList()
        var idx = s.index
        val at = q.indexOfFirst { qKey(it) == qKey(item) }
        if (at == s.index) return                                    // it's the one playing now
        val song = if (at >= 0) q.removeAt(at).also { if (at < idx) idx-- } else item
        q.add((idx + 1).coerceIn(0, q.size), song)
        if (at < 0) orderedQueue = orderedQueue?.plus(song)
        _state.value = s.copy(queue = q, index = idx)
    }

    /**
     * Player's "Jump to": the song is already in the queue → it plays from there. Otherwise it goes right after the
     * playing song (nothing removed) and plays; when a playlist is playing it's also put into that playlist
     * after the current song (unless already in it).
     */
    fun jumpToSong(item: PlayItem) {
        val s = _state.value
        sessionManualChoice()
        if (s.queue.none { qKey(it) == qKey(item) }) session(null, listOf(item))
        val at = s.queue.indexOfFirst { qKey(it) == qKey(item) }
        if (at >= 0) { startItem(at, -1); return }
        if (s.item == null) { playQueue(listOf(item), 0, Origin("single")); return }
        val pos = (s.index + 1).coerceIn(0, s.queue.size)
        val q = s.queue.toMutableList().also { it.add(pos, item) }
        orderedQueue?.let { o ->
            val cur = s.queue.getOrNull(s.index)
            val oi = o.indexOfFirst { it === cur }
            orderedQueue = o.toMutableList().also { it.add(if (oi >= 0) oi + 1 else it.size, item) }
        }
        _state.value = s.copy(queue = q)
        if (s.origin.kind == "playlist" && s.origin.playlistId.isNotEmpty()) {
            val cur = s.item
            store.update { st -> st.copy(playlists = st.playlists.map { pl ->
                if (pl.id != s.origin.playlistId) pl else {
                    val e = app.vlcinterface.sources.MusicLibrary.toPlEntry(item)
                    if (pl.items.any { it.key == e.key }) pl else {
                        val ci = pl.items.indexOfFirst { it.uri == cur.uri && it.startMs == cur.startMs }
                        pl.copy(items = pl.items.toMutableList().also { it.add(if (ci >= 0) ci + 1 else it.size, e) })
                    }
                }
            }) }
        }
        startItem(pos, -1)
    }

    fun next(): Boolean {
        sessionManualChoice()
        val s = _state.value
        val n = nextIndex(s, manual = true) ?: return false
        startItem(n, -1); return true
    }

    fun prev() {
        sessionManualChoice()
        val s = _state.value
        val item = s.item ?: return
        // Radio: previous station (round), never "back to the start".
        if (item.isRadio) { if (s.queue.size > 1) startItem(if (s.index <= 0) s.queue.size - 1 else s.index - 1, -1); return }
        if (mp.time > 4000 || s.index <= 0) { seekTo(if (item.startMs >= 0) item.startMs else 0); return }
        startItem(s.index - 1, -1)
    }

    private fun nextIndex(s: PlayerState, manual: Boolean): Int? {
        val repeat = store.current.settings.repeat
        if (!manual && repeat == "one") return s.index
        // Radio stations: next one in the list, round.
        if (manual && s.item?.isRadio == true && s.queue.size > 1) return (s.index + 1) % s.queue.size
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

    private fun startItem(i0: Int, startAt: Long) {
        if (BatteryGuard.blocked()) return              // battery 10 % or less: nothing is played
        toneLeaving()                                   // loudness / treble changed during the last song: kept with it
        saveProgress()
        // ReplayGain values waiting for a file the player had open: written now that it moves on.
        app.vlcinterface.sources.RgScanner.flushPendingSoon(context)
        var i = i0
        // Today's list: the song being left was heard whole, or skipped (then it's taken out).
        _state.value.let { pv -> if (pv.item != null && pv.queue.getOrNull(i0) !== pv.item) sessionLeaving(pv.item, pv.time, pv.length) }
        // Songs from a place the user chose to skip: passed over without trying.
        if (skippedPlaces.isNotEmpty()) {
            val q = _state.value.queue
            if (q.getOrNull(i)?.let { placeOf(it) in skippedPlaces } == true) {
                val n = (i + 1 until q.size).firstOrNull { placeOf(q[it]) !in skippedPlaces }
                if (n == null) { stop(); return }
                i = n
            }
        }
        // The same song is on the phone too (same artist, album, title): the phone file is played instead.
        // (The phone's song list is read once in the background the first time a network song plays.)
        if (app.vlcinterface.ui.SongsCache.phone == null && _state.value.queue.getOrNull(i)?.let { !app.vlcinterface.sources.NetIndex.isLocal(it) } == true)
            bg.launch(kotlinx.coroutines.Dispatchers.IO) { try { app.vlcinterface.ui.SongsCache.loadPhone(context) } catch (_: Exception) {} }
        _state.value.queue.getOrNull(i)?.let { q ->
            app.vlcinterface.ui.SongsCache.localTwin(q)?.let { local ->
                _state.value = _state.value.copy(queue = _state.value.queue.toMutableList().also { it[i] = local })
            }
        }
        val s0 = _state.value
        val item = s0.queue.getOrNull(i) ?: return
        session(item, emptyList())
        loadPictureForFile(item)
        val wanted = store.current.subStyle
        if (wanted != styleInUse || rgSignature() != rgInUse) recreateEngine(wanted)
        setCredentials(item.user, item.password)
        triedBridge = false
        val oldPfd = currentPfd
        currentPfd = null
        val media = try { makeMedia(item, s0, useBridge = item.smbVersion == "SMB1") } catch (e: Exception) {
            // The file can't be opened at all (gone, moved, phone permission lost…): say so and go on with the next one,
            // instead of the queue silently stopping on the song before.
            try { oldPfd?.close() } catch (_: Exception) {}
            stopping = true; mp.stop(); main.postDelayed({ stopping = false }, 500)
            _state.value = s0.copy(item = item, index = i, isPlaying = false, time = 0, length = item.durationMs, ended = false,
                metaArtist = item.artist, metaAlbum = item.album, artworkUrl = item.artwork, rgInfo = "",
                error = "Can't open ${item.title}: ${e.message ?: e.javaClass.simpleName}")
            endHandled = false
            failsInRow++
            if (failsInRow < 5) main.postDelayed({ if (_state.value.item === item && !endHandled) handleEnd(_state.value) }, 1_000)
                        else askAfterFails(item)
            return
        }
        // In a list: a file that isn't there (moved, deleted) or whose network place can't be reached within 2 s
        // is skipped, instead of the player waiting on it.
        probeJob?.cancel()
        if (s0.queue.size > 1 && !item.isRadio) probeJob = bg.launch {
            val ok = kotlinx.coroutines.withTimeoutOrNull(2_000) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reachable(item) }
            }
            if (ok == true) return@launch
            if (_state.value.item !== item || endHandled || stopping) return@launch
            if (lastTick > 0 && _state.value.isPlaying) return@launch          // it started meanwhile: keep it
            ServerLog.log("player", "skip", item.uri.substringBefore("://"), "", false,
                "Skipped ${item.title}: ${if (ok == null) "not reached within 2 s" else "not found"}", item.uri)
            _state.value = _state.value.copy(error = "Skipped ${item.title}: ${if (ok == null) "not reachable" else "not found"}")
            failsInRow++
            if (failsInRow < 5) handleEnd(_state.value)
            else askAfterFails(item)
        }
        stopping = true                      // the "stopped" of the old media isn't the end of a song
        mp.setMedia(media)
        media.release()
        try { oldPfd?.close() } catch (_: Exception) {}
        offsetsApplied = false
        // Resume where you left off: videos only. Songs always start from the beginning (user rule),
        // also when they come round again in the queue.
        val resume = if (startAt >= 0) startAt else if (!item.isRadio && !item.isMusic && store.current.settings.resume && item.startMs < 0)
            store.current.recent.firstOrNull { it.uri == item.uri }?.position ?: -1 else -1
        pendingSeek = resume
        _state.value = s0.copy(item = item, index = i, isPlaying = false, time = 0, length = item.durationMs, videoW = 0, videoH = 0,
            audioTracks = emptyList(), spuTracks = emptyList(), audioDelayMs = 0, spuDelayMs = 0, rate = 1f, nowPlaying = "",
            metaArtist = item.artist, metaAlbum = item.album, artworkUrl = item.artwork, buffering = 0f, error = "", ended = false,
            rgInfo = "", userPaused = false, songOver = false)
        // Loudness / treble: the song's (station's) own values at once, else the last ones; videos: none.
        val tone = if (item.isMusic || item.isRadio) toneFor(item) else Triple(0f, 0f, false)
        _state.value = _state.value.copy(loudDb = tone.first, trebleDb = tone.second, toneSaved = tone.third,
            radioPreDb = if (item.isRadio) ToneStore.getPre(item.uri) ?: 0f else 0f)
        loadReplayGain(item, _state.value)
        // No picture in the file: looked up here too (folder, pictures store, online), so the notification and lock
        // screen get it even while the app is in the background.
        if (item.isMusic && !item.artwork.startsWith("/") && !item.artwork.startsWith("file://") && !item.artwork.startsWith("content://")) bg.launch {
            val r = try { app.vlcinterface.sources.CoverArt.forPlayer(context, item, item.artist, item.album, store.current.settings.coverDownload) } catch (_: Exception) { null }
            if (r?.bitmap != null) useDownloadedArtwork(item, "mem:" + item.uri)
        }
        NativeAudio.deepBuffer(item.isMusic || item.isRadio)
        am.requestAudioFocus(focusRequest)
        endHandled = false; lastTick = 0L
        mp.play()
        main.postDelayed({ stopping = false }, 500)
        applySoundChain()
        startService()
    }

    /** The album a song belongs to, for the per-album ReplayGain choice: album title + its folder. */
    fun albumKey(item: PlayItem): String = item.album.trim().lowercase() + "|" + item.folderKey.ifEmpty { item.uri.substringBeforeLast('/') }

    /** Album or song (track) gain for this song's whole album, as picked with the RG button. */
    fun setAlbumRgChoice(item: PlayItem, choice: String) {
        val k = albumKey(item)
        store.update { it.copy(settings = it.settings.copy(rgChoices = it.settings.rgChoices + (k to choice))) }
    }

    fun albumRgChoice(item: PlayItem): String? = store.current.settings.rgChoices[albumKey(item)]

    /**
     * A mixed list: the songs playing come from more than one album (a mixed playlist, a Songs-list selection, a
     * queue built from several places). Then track gain is always used — no question. A complete album / a folder
     * of one album is not mixed: there the album gain (or your choice for that album) applies.
     */
    fun listIsMixed(s: PlayerState = _state.value): Boolean =
        // Same album = same album tag (a 2-CD album in CD1 / CD2 folders stays one album); no tag → its folder.
        s.queue.filter { it.isMusic }.map { it.album.trim().lowercase().ifEmpty { "folder:" + it.folderKey } }.distinct().size > 1

    /** Track gain has priority: mixed lists always track; one album → the choice made for it, else album. */
    private fun rgModeFor(s: PlayerState): String = when {
        listIsMixed(s) -> "track"
        else -> s.item?.let { albumRgChoice(it) } ?: when (store.current.settings.rgMode) {
            "track" -> "track"; "album" -> "album"
            else -> if (store.current.settings.shuffle) "track" else "album"
        }
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
        pictureFilterOn = !item.isMusic && !item.isRadio && (!pictureNeutral(ps.pictureHue, ps.pictureSat) || ps.pictureHighlights > 0.01f)
        if (pictureFilterOn) {
            val (c, b, g) = highlightTone(ps.pictureHighlights)
            media.addOption(":no-mediacodec-dr")
            media.addOption(":video-filter=adjust")
            media.addOption(":hue=${ps.pictureHue}")
            media.addOption(":saturation=${ps.pictureSat}")
            media.addOption(":contrast=$c")
            media.addOption(":brightness=$b")
            media.addOption(":gamma=$g")
        }
        // Bigger read-ahead so a busy phone or network never runs the decoder dry (smooth playback):
        // songs keep 3 s from the network / 1.5 s from the phone; videos 2.5 s / 1 s (seeking stays quick).
        when {
            item.isRadio -> media.addOption(":network-caching=3000")
            item.isMusic -> { media.addOption(":network-caching=3000"); media.addOption(":file-caching=1500") }
            else -> { media.addOption(":network-caching=2500"); media.addOption(":file-caching=1000") }
        }
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
        if (!playing) main.postDelayed({ pause() }, 400)
    }

    fun togglePause() {
        if (mp.isPlaying) { _state.value = _state.value.copy(userPaused = true); mp.pause() }
        else { if (BatteryGuard.blocked()) return; _state.value = _state.value.copy(userPaused = false); am.requestAudioFocus(focusRequest); mp.play() }
    }
    fun resume() { if (!mp.isPlaying && _state.value.item != null && !BatteryGuard.blocked()) { _state.value = _state.value.copy(userPaused = false); am.requestAudioFocus(focusRequest); mp.play() } }
    fun pause() { if (mp.isPlaying) { _state.value = _state.value.copy(userPaused = true); mp.pause() } }
    /**
     * Set when the sleep timer closed the player. Kept across the app being closed, so the pop-up shows when the app
     * is opened next; cleared when seen.
     */
    private val sleepPrefs get() = context.getSharedPreferences("sleep", Context.MODE_PRIVATE)
    val sleptAt = kotlinx.coroutines.flow.MutableStateFlow(try { sleepPrefs.getLong("at", 0L) } catch (_: Exception) { 0L })
    fun sleepSeen() { sleptAt.value = 0L; try { sleepPrefs.edit().remove("at").apply() } catch (_: Exception) {} }

    /**
     * Sleep time is up: silence first (so nothing still queued in the sound buffers is heard at full volume), then
     * the whole app is closed like the Exit button does. A notification and the pop-up at the next start say so.
     */
    private fun sleepStop() {
        val now = System.currentTimeMillis()
        sleptAt.value = now
        try { sleepPrefs.edit().putLong("at", now).commit() } catch (_: Exception) {}
        sleepClosing = true; fadeLocked = true
        fadeK = 0f; NativeAudio.appVolume(0f); try { mp.setVolume(0) } catch (_: Exception) {}
        try { mp.pause() } catch (_: Exception) {}
        try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("sleep", "Sleep timer", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
            nm.notify(7301, android.app.Notification.Builder(context, "sleep").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Sleep").setContentText("Player closed by sleep timer at $t").setAutoCancel(true).build())
        } catch (_: Exception) {}
        main.postDelayed({ app.vlcinterface.ui.exitApp(keepNotice = true) }, 250)
    }

    fun stop() {
        toneLeaving()
        stopping = true; endHandled = true
        app.vlcinterface.sources.RgScanner.flushPendingSoon(context)
        saveProgress()
        syncPicture()
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

    fun setScale(s: MediaPlayer.ScaleType) { scale = s; mp.setVideoScale(s); refreshSurface(); main.postDelayed({ reloadVideo() }, 150) }

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

    // ---------- end of a song → the next one ----------

    /** Set once per item, so the end is never handled twice (end reached + stopped + watchdog). */
    private var endHandled = false
    /** Set while the app itself stops / changes the media (that "stopped" is not the end of a song). */
    private var stopping = false
    /** Songs in a row that failed to play (reset when one plays). */
    private var failsInRow = 0

    /** 5 songs in a row couldn't be played: the place they come from, waiting for an answer (pop-up). */
    val failAsk = kotlinx.coroutines.flow.MutableStateFlow("")

    private fun askAfterFails(item: PlayItem) {
        // Nothing plays (or keeps trying to load) while asking; the phone may sleep.
        endHandled = true
        stopping = true; try { mp.stop() } catch (_: Exception) {}; main.postDelayed({ stopping = false }, 500)
        _state.value = _state.value.copy(userPaused = true, isPlaying = false)
        failAsk.value = placeOf(item)
    }

    /** Where a song comes from, as shown in the pop-up: server/share/folder, or the phone folder. */
    private fun placeOf(item: PlayItem): String {
        val u = item.uri
        return if (u.contains("://") && !u.startsWith("content://") && !u.startsWith("file://"))
            Uri.decode(u.substringAfter("://").substringAfter('@').substringBeforeLast('/'))
        else "this phone · " + item.folderKey.removePrefix("local:").substringAfter(':').ifEmpty { "/" }
    }

    /** Places the user chose to skip for this playback: their songs are passed over without trying. */
    private val skippedPlaces = HashSet<String>()

    /**
     * Answer to the pop-up: "keep" = try the next songs (counted from zero again); "skip" = pass over every song
     * from that place (now and later in this list) and go on with the rest; "stop" = stop playback.
     */
    fun failAnswer(answer: String) {
        val place = failAsk.value
        failAsk.value = ""
        when (answer) {
            "keep" -> { failsInRow = 0; endHandled = false; handleEnd(_state.value) }
            "skip" -> {
                skippedPlaces += place
                failsInRow = 0
                val s = _state.value
                val n = (s.index + 1 until s.queue.size).firstOrNull { placeOf(s.queue[it]) !in skippedPlaces }
                if (n != null) startItem(n, -1) else stop()
            }
            else -> stop()
        }
    }
    private var lastTick = 0L

    /**
     * The song ended: play the next item of the folder / playlist / queue, or stop when nothing follows.
     * (Playback stopped at the end of a song in a folder instead of going on with the next one.)
     */
    private fun handleEnd(s: PlayerState) {
        if (endHandled) return
        endHandled = true
        // The ended song's picture goes at once — in the app and in the notification / lock screen, also in the background.
        _state.value = _state.value.copy(songOver = true, artworkUrl = "")
        if (s.sleepEndOfTrack) { sleepStop(); return }
        s.item?.let { item -> store.update { st -> st.copy(recent = st.recent.filterNot { it.uri == item.uri }) } }
        // Sleep after a number of songs: count down; when the queue / playlist runs out first, go on from its beginning.
        var wrap = false
        if (s.sleepSongs > 0) {
            val left = s.sleepSongs - 1
            if (left <= 0) { sleepStop(); _state.value = _state.value.copy(sleepSongs = 0); return }
            _state.value = _state.value.copy(sleepSongs = left)
            wrap = true
        }
        val n = nextIndex(_state.value, manual = false) ?: if (wrap && _state.value.queue.isNotEmpty()) 0 else null
        if (n != null) main.post { startItem(n, -1) }
        else {
            _state.value = _state.value.copy(isPlaying = false, ended = true)
            // Nothing follows: stop and remove the notification.
            main.postDelayed({ if (_state.value.ended) stop() }, 300)
        }
    }

    /** Watchdog: the time stopped moving right at the end of the song and no end came → treat it as the end. */
    private val endWatch = object : Runnable {
        override fun run() {
            val s = _state.value
            val stuck = if (lastTick > 0) System.currentTimeMillis() - lastTick else 0L
            // The engine says the song is over (ended / stopped by itself) but no end event reached us: go on.
            // A pause is "paused", and a stop you asked for sets "stopping", so neither counts.
            if (!endHandled && !stopping && s.item != null && !s.item.isRadio && lastTick > 0) {
                val ps = try { mp.playerState } catch (_: Throwable) { -1 }
                if (ps == IMedia.State.Ended || ps == IMedia.State.Stopped) { handleEnd(_state.value); main.postDelayed(this, 1_000); return }
            }
            if (!endHandled && !stopping && s.isPlaying && s.item != null && !s.item.isRadio) {
                // Right at the end and the time stopped: the end that never came.
                if (s.length > 0 && s.time >= s.length - 1_500 && stuck > 2_500) handleEnd(s)
                // A song whose header claims more length than it has (VBR / damaged end) can freeze short of it with
                // no event at all: time not moving while "playing" and not buffering → it's over, go on.
                else if (s.item.isMusic && s.time > 0 && s.buffering >= 100f &&
                    stuck > (if (s.item.uri.startsWith("content://") || s.item.uri.startsWith("file://")) 5_000L else 10_000L)) handleEnd(s)
            }
            main.postDelayed(this, 1_000)
        }
    }

    init { main.postDelayed(endWatch, 1_000) }

    // ---------- events ----------

    private fun onEvent(ev: MediaPlayer.Event) {
        val s = _state.value
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                _state.value = s.copy(isPlaying = true, error = "", ended = false, buffering = 100f, userPaused = false)
                failsInRow = 0
                if (startPaused) { startPaused = false; NativeAudio.appVolume(appVolume * fadeK); pause() }   // "Load": ready, not playing
                // Every (re)start of playback — also resuming a paused video — re-applies the sound settings,
                // so volume / preamp / balance always act at once, not only after some other action.
                if (!ducked) mp.setVolume((100 * fadeK).toInt())
                NativeAudio.appVolume(if (sleepClosing) 0f else appVolume * fadeK)
                applySoundChain()
                if (pendingSeek > 500) { mp.setTime(pendingSeek) }   // continue exactly where it was (RG button, engine restarts…)
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
            MediaPlayer.Event.Stopped -> {
                _state.value = s.copy(isPlaying = false)
                // Some files end with "stopped" instead of "end reached" — also well before the length their header claims
                // (VBR files, a damaged end). Nobody asked to stop (that sets "stopping"), so it is the end of the song:
                // go on with the next one instead of halting the queue.
                if (!endHandled && !stopping && s.item != null && !s.item.isRadio) handleEnd(s)
            }
            MediaPlayer.Event.EndReached -> handleEnd(s)
            MediaPlayer.Event.TimeChanged -> { lastTick = System.currentTimeMillis(); _state.value = s.copy(time = ev.timeChanged) }
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
                        stopping = true
                        val m = makeMedia(item, _state.value, useBridge = true)
                        mp.setMedia(m); m.release(); mp.play()
                        main.postDelayed({ stopping = false }, 500)
                    }
                } else {
                    if (item != null && !item.uri.startsWith("content://")) ServerLog.log(item.uri, "play", item.uri.substringBefore("://").uppercase(), item.user, false, "Could not play ${item.title}")
                    _state.value = s.copy(isPlaying = false, error = "Could not play this item")
                    // A song that fails (can't open, or breaks near its end) doesn't halt the queue: go on with the next one.
                    // Several failures in a row (server gone…) stop instead of racing through the whole list.
                    if (item != null && !item.isRadio && !endHandled && !stopping) {
                        failsInRow++
                        if (failsInRow < 5) main.postDelayed({ if (_state.value.item === item && !endHandled) handleEnd(_state.value) }, 1_000)
                        else askAfterFails(item)
                    }
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
                if (s.sleepUntil > 0 && System.currentTimeMillis() >= s.sleepUntil) sleepStop()
                val item = s.item
                if (item != null && item.isRadio) {
                    val m = mp.media
                    // The song the station announces (stream tags), and the station's own announced name.
                    val np = m?.getMeta(IMedia.Meta.NowPlaying, true)?.trim().orEmpty()
                    val sn = m?.getMeta(IMedia.Meta.Title, true)?.trim().orEmpty().takeIf { !it.contains("://") && it != np } ?: ""
                    m?.release()
                    if (np != s.nowPlaying || sn != s.stationName) {
                        _state.value = _state.value.copy(nowPlaying = np, stationName = sn)
                        if (np != s.nowPlaying) radioSongChanged(item, np)
                    }
                }
            } catch (_: Exception) {
            }
            main.postDelayed(this, 3000)
        }
    }

    /** Announced song on a station: its album picture (temporary radio pictures folder), else back to the station's own. */
    private fun radioSongChanged(item: PlayItem, np: String) {
        val (artist, title) = splitNowPlaying(np)
        if (title.isEmpty() || artist.isEmpty()) { _state.value = _state.value.copy(artworkUrl = item.artwork); return }
        bg.launch {
            val f = try { app.vlcinterface.sources.CoverArt.radioSong(context, artist, title) } catch (_: Exception) { null }
            val s = _state.value
            if (s.item === item && s.nowPlaying == np) _state.value = s.copy(artworkUrl = f?.absolutePath ?: item.artwork)
        }
    }

    init {
        main.post(metaPoller)
    }

    fun saveProgress() {
        val s = _state.value
        val item = s.item ?: return
        if (item.isRadio || item.isMusic || item.startMs >= 0) return   // songs: no resume point kept
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
        fadeWindowMs = if (minutes >= 30) 420_000L else 120_000L
        if (minutes == -1) main.post { songFadeWindow() }
        val s = _state.value
        _state.value = when {
            minutes > 0 -> s.copy(sleepUntil = System.currentTimeMillis() + minutes * 60_000L, sleepEndOfTrack = false, sleepSongs = 0)
            minutes == -1 -> s.copy(sleepUntil = 0, sleepEndOfTrack = false, sleepSongs = 1)
            else -> s.copy(sleepUntil = 0, sleepEndOfTrack = false, sleepSongs = 0)
        }
    }

    /** Sleep after [n] songs, the playing one included (fewer left in the queue / playlist → it goes on from its beginning). */
    /** Fade length for a sleep after songs: like a timed sleep, from the total time still to play. */
    private fun songFadeWindow() { val t = msToSleep(_state.value); fadeWindowMs = if (t >= 30 * 60_000L) 420_000L else 120_000L }

    fun setSleepSongs(n: Int) {
        _state.value = _state.value.copy(sleepUntil = 0, sleepEndOfTrack = false, sleepSongs = n.coerceIn(1, 999))
        songFadeWindow()
    }

    /**
     * Sleep at the end of this album: its songs are taken from the Library (the album's folder, album order),
     * played from the current song to the album's last one, then playback stops. Returns how many songs that is.
     */
    suspend fun sleepAfterAlbum(): Int {
        fadeWindowMs = 120_000L
        val s = _state.value
        val item = s.item ?: return 0
        val fk = item.folderKey
        val src = store.current.sources.firstOrNull { it.id == item.sourceId }
        val songs: List<PlayItem> = app.vlcinterface.sources.MusicLibrary.folderOf(context, item)   // same folder reading as everywhere
        val album = songs.filter { it.isMusic && (item.album.isBlank() || it.album.equals(item.album, true)) }
        val at = album.indexOfFirst { it.uri == item.uri }
        val cur = _state.value
        if (at < 0) {
            // Album not found in the Library: stop after the album's songs that follow in the queue.
            val rest = cur.queue.drop(cur.index + 1).takeWhile { it.album.equals(item.album, true) && it.album.isNotBlank() }
            _state.value = cur.copy(sleepUntil = 0, sleepEndOfTrack = false, sleepSongs = 1 + rest.size)
            songFadeWindow()
            return 1 + rest.size
        }
        // The album folder takes over (user rule): the queue / playlist is set aside and the album plays in its own
        // order from the current song to its last one, then stops. The playing song isn't interrupted.
        val origin = if (fk.startsWith("local:")) Origin("local", title = item.album.ifEmpty { "Album" }, localFolderId = fk.removePrefix("local:"))
            else Origin("folder", sourceId = src?.id ?: "", uri = fk, title = item.album.ifEmpty { "Album" })
        orderedQueue = null
        val q = album.toMutableList().also { it[at] = cur.item ?: it[at] }   // keep the playing item object
        _state.value = cur.copy(queue = q, index = at, origin = origin, sleepUntil = 0, sleepEndOfTrack = false, sleepSongs = album.size - at)
        songFadeWindow()
        return album.size - at
    }

    val isPlaying: Boolean get() = mp.isPlaying
    val position: Long get() = mp.time
}


/** "Artist - Title" as stations announce it → (artist, title); anything else → ("", whole text). */
fun splitNowPlaying(np: String): Pair<String, String> {
    val t = np.trim()
    val i = listOf(" - ", " – ", " — ").map { t.indexOf(it) }.filter { it > 0 }.minOrNull() ?: return "" to t
    return t.substring(0, i).trim() to t.substring(i + 3).trim()
}
