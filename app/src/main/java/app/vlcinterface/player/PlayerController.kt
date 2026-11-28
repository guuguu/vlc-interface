package app.vlcinterface.player

import app.vlcinterface.data.CrashLog

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

/**
 * Gain for songs that carry no ReplayGain at all (when ReplayGain is on): 3 dB under the current level (preamps and the
 * player's Preamp slider as set), from the start of the song until its ReplayGain is measured (2026-11-22 18:58).
 */
const val NO_RG_DB = -3f
/** Loudness and treble act 30 % deeper than their slider values (same logic, stronger effect). */
const val TONE_DEPTH = 1.3f
/** Songs without ReplayGain reached over the VPN: 6 dB lower (not scanned there). */
const val VPN_NO_RG_DB = -6f     // no ReplayGain: played as it is (no preamp either); the scan question handles it

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
    /** Loudness actually applied (Auto loudness may use less of the setting at higher phone volume). */
    val loudEffDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** Pre-amp of this song / station (−12…+12 dB), on top of ReplayGain for songs; kept per song / station. */
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
    /** Phone file: retried once the other way (path ↔ file handle) after libVLC couldn't open it. */
    private var triedOtherWay = false
    private var phoneByPath = true
    private var dp: DynamicsProcessing? = null
    private var eq: MediaPlayer.Equalizer? = null
    /** The queue in its original order while it is shown/played in random order (null = queue is in order). */
    private var orderedQueue: List<PlayItem>? = null
    /** The list as loaded (NAS songs included), in its order: brought back when the NAS is usable again. */
    @Volatile private var fullList: List<PlayItem> = emptyList()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Name of the current audio output, used for per-device offsets, preamp and balance. */
    var currentOutputName: String = ""
        set(v) { field = v; if (::mp.isInitialized) applySoundChain() }

    init {
        createEngine(store.current.subStyle)
    }

    private fun createEngine(style: SubStyle) {
        // Never closing the app over an engine option (1.66.0): if libVLC refuses the options, it's started again with
        // the subtitle look left out, and the reason is written to the Logs.
        libVlc = try { LibVLC(context, buildOptions(style)) } catch (e: Throwable) {
            try { app.vlcinterface.data.ServerLog.log("player", "error", "libVLC", "", false, "Engine options refused: ${e.message ?: e.javaClass.simpleName} — started without the subtitle look", "", System.currentTimeMillis()) } catch (_: Throwable) {}
            LibVLC(context, ArrayList(buildOptions(style).filterNot { it.startsWith("--freetype-") || it.startsWith("--sub-") || it.startsWith("--no-sub") || it.startsWith("--audio-resampler") }))
        }
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
            val why = NativeAudio.reason
            main.post { outputFellBack(why) }
        }
        mp.setEventListener { ev -> onEvent(ev) }
        installDialogs()
        setupSoundChain()
    }

    private fun appOutputWanted() = store.current.settings.soundEngine != "libvlc" && !NativeAudio.failed

    // ---------- ReplayGain (app output) ----------

    /** Gain in dB for the playing music item (ReplayGain + its pre-amp, clip-limited); 0 if untagged. */
    private var rgDb = 0f
    /** The playing song's peak (ReplayGain tag, linear) for clip protection over the whole gain; 0 = unknown. */
    @Volatile private var rgPeak = 0f
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main + CrashLog.guard)
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
            // One change only (18:58): the first measured value is used for the rest of this song; the scan's later
            // reports for it (the same track value again, or the album value at the end of an album scan) are not —
            // the next play of the song uses whatever was written.
            if (scannedRg?.first == uri) return@post
            scannedRg = uri to rg
            loadReplayGain(item, s)
        }
    }
    private suspend fun rgFor(item: PlayItem) = scannedRg?.takeIf { it.first == item.uri }?.second
        ?: app.vlcinterface.sources.ReplayGain.read(context, item.uri, item.user, item.password, item.domain, item.smbVersion)

    /** The song the level in [rgDb] belongs to. */
    @Volatile private var rgUri = ""
    private fun overVpnNoRg(item: PlayItem) = item.uri.startsWith("smb://") &&
        try { android.net.Uri.parse(item.uri).host?.let { h -> app.vlcinterface.sources.VpnLink.routeHost(h) != h } == true } catch (_: Exception) { false }
    /** A song without ReplayGain: 3 dB lower (6 dB over the VPN, where it isn't scanned). */
    private fun noRgDb(item: PlayItem) = if (overVpnNoRg(item)) VPN_NO_RG_DB else NO_RG_DB

    private fun loadReplayGain(item: PlayItem, s: PlayerState) {
        if (scannedRg?.first != item.uri) scannedRg = null      // next song: back to its own value
        rgJob?.cancel()
        val set = store.current.settings
        // The level the song starts at is set right away, so it doesn't start at one level and move once its tags are read
        // (18:58: one change at most — none for a song whose ReplayGain is known, one when a missing one gets measured):
        // known ReplayGain (read before, or kept by a scan) → its value; otherwise the "no ReplayGain" level (3 dB lower).
        // The same song again (ReplayGain just measured, RGain switched): the level stays until the new one is worked out.
        if (rgUri != item.uri || !item.isMusic || set.rgMode == "off") {
            rgUri = item.uri
            rgPeak = 0f
            rgDb = if (!item.isMusic || set.rgMode == "off" || !NativeAudio.active) 0f else {
                val k = app.vlcinterface.sources.ReplayGain.known(item.uri)
                val album = rgModeFor(s) == "album"
                val g = if (album) k?.albumGain ?: k?.trackGain else k?.trackGain ?: k?.albumGain
                val pk = if (album) k?.albumPeak ?: k?.trackPeak else k?.trackPeak ?: k?.albumPeak
                if (g == null) noRgDb(item) else {
                    var d = g + set.rgPreampDb
                    if (set.rgClipProtect && pk != null && pk > 0f) { d = minOf(d, -20f * log10(pk)); rgPeak = pk }
                    d
                }
            }
            // Put on now, before the song is heard (the previous song's level used to stay until the tags were read).
            applySoundChain()
        }
        if (!item.isMusic || set.rgMode == "off") return
        askRgWriteAccess(item)
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
                    rg == null || g == null -> "No RG · −3 dB (libVLC)"
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
            // Over the VPN (home NAS away from home) a missing ReplayGain isn't scanned (too slow there): the song
            // plays 6 dB lower instead, so untagged loud songs don't jump out; songs with ReplayGain play as usual.
            val overVpn = gain == null && overVpnNoRg(item)
            var db = if (gain == null) noRgDb(item) else gain + set.rgPreampDb
            var limited = false
            if (gain != null && set.rgClipProtect && peak != null && peak > 0f) { val cap = -20f * log10(peak); if (cap < db) { db = cap; limited = true } }
            rgDb = db
            rgPeak = if (gain != null && peak != null && peak > 0f) peak else 0f
            if (gain == null && !overVpn) askRgScan(item)
            fun f(v: Float) = (if (v >= 0) "+" else "−") + "%.2f".format(java.util.Locale.US, kotlin.math.abs(v)) + " dB"
            // Shows the song's own value (exactly as in its tags / the scan: track or album). When what is played
            // differs — preamp added, or lowered so the loudest peak can't clip — that is said separately.
            val info = when {
                (rg == null || gain == null) && overVpn -> "No RG · −6 dB (over VPN, no scan)"
                rg == null || gain == null -> "No RG · −3 dB until measured"
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
            // Any rate conversion libVLC does (its own output, or a stream it must convert): soxr at high quality, as VLC's
            // own Android app does. libVLC never picks soxr by itself (priority 0) and fell back to its old "bandlimited" /
            // "ugly" resamplers (aliasing hiss). ",any" keeps the engine starting if soxr were missing (2026-11-21 22:12).
            "--audio-resampler=soxr,any",
            // Any rate conversion libVLC does (its own output, or a stream it must convert): soxr at high quality, as VLC's
            // own Android app does. libVLC never picks soxr by itself (priority 0) and fell back to its old "bandlimited" /
            // "ugly" resamplers (aliasing hiss). ",any" keeps the engine starting if soxr were missing (2026-11-21 22:12).
            "--audio-resampler=soxr,any",
            "--network-caching=1500",
            "--http-reconnect",
            // Video picture quality:
            // - full deblocking: skipping the loop filter (the old "1") left square blocks / smearing on every video
            //   decoded in software (codecs or files the phone's hardware decoder refuses, and the fallback after a
            //   hardware error); today's phones decode 1080p with it at full speed.
            "--avcodec-skiploopfilter=0",
            // - pictures shown in full 32-bit colour (no 16-bit colour banding on skies / dark scenes) when frames
            //   go through the app's display path (software decoding, colour filters).
            "--android-display-chroma=RV32",
            // - quality first: never skip decoding steps (frames, inverse transform) to save time.
            "--avcodec-skip-frame=0",
            "--avcodec-skip-idct=0",
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
        o += "--audio-replay-gain-default=$NO_RG_DB"      // songs without ReplayGain: 3 dB lower (as with the app's output)
        o += if (s.rgClipProtect) "--audio-replay-gain-peak-protection" else "--no-audio-replay-gain-peak-protection"
        // libVLC never fetches album art from the internet itself (pictures are handled by the app, saved only in album folders).
        o += "--no-metadata-network-access"
        // Subtitle look (text subtitles rendered by freetype).
        o += "--freetype-font=${style.font}"
        o += "--freetype-color=${style.color}"
        val rel = (16f * 100f / style.sizePct.coerceIn(50, 200)).toInt().coerceIn(4, 40)
        o += "--freetype-rel-fontsize=$rel"
        if (style.bold) o += "--freetype-bold"
        // (1.66.0 had "--no-subsdec-formatting" here: libVLC 3 has no such option, so the engine refused to start and the
        // app closed at once. Removed in 1.67.0; subtitle tracks inside a video keep their own colours.)
        // Border, shadow and box, each set on its own (2026-11-21 16:05).
        o += "--freetype-outline-thickness=${style.outlineWidth.coerceIn(0, 10)}"
        o += "--freetype-outline-color=${style.outlineColor}"
        o += "--freetype-outline-opacity=255"
        if (style.shadowWidth > 0) {
            o += "--freetype-shadow-color=${style.shadowColor}"
            o += "--freetype-shadow-opacity=${(255 * (100 - style.shadowTransp.coerceIn(0, 100)) / 100)}"
            // libVLC: angle in degrees (0 = right, counter-clockwise), distance as a part of the font size.
            o += "--freetype-shadow-angle=${style.shadowAngle.toFloat()}"
            o += "--freetype-shadow-distance=${String.format(java.util.Locale.US, "%.2f", style.shadowWidth.coerceIn(0, 10) * 0.02f)}"
        } else o += "--freetype-shadow-opacity=0"
        if (style.box) { o += "--freetype-background-opacity=160"; o += "--freetype-background-color=0" }
        else o += "--freetype-background-opacity=0"
        // Height in 10 steps: 1 = bottom … 10 = about 81 % up the picture. Long lines are wrapped by libVLC to the
        // picture's width and the whole block (all its lines) is kept inside the picture.
        val dm = context.resources.displayMetrics
        val h = dm.heightPixels.coerceAtMost(dm.widthPixels)
        val margin = (h * 0.02f + h * 0.09f * (style.posStep.coerceIn(1, 10) - 1)).toInt()
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
            (if (st.settings.toneSliders && _state.value.item?.let { it.isRadio || it.isMusic } == true) _state.value.radioPreDb else 0f)
        var total = (st.settings.preampDb + (st.devicePreamp[dev] ?: 0f) + rg).coerceIn(-36f, 12f)
        // Clip protection over the whole gain (ReplayGain + pre-amps), not ReplayGain alone: the song's loudest peak
        // never goes over full scale, so the limiter has nothing to do and the sound stays untouched.
        if (NativeAudio.active && st.settings.rgClipProtect && rgPeak > 0f && _state.value.item?.isMusic == true)
            total = minOf(total, -20f * log10(rgPeak))
        val bal = (st.deviceBalance[dev] ?: 0).coerceIn(-100, 100)
        fun att(f: Float): Float = if (f <= 0.001f) -60f else (20f * log10(f)).coerceAtLeast(-60f)
        val left = total + if (bal > 0) att(1f - bal / 100f) else 0f
        val right = total + if (bal < 0) att(1f + bal / 100f) else 0f
        if (NativeAudio.active) {
            fun lin(db: Float) = if (db <= -60f) 0f else 10f.pow(db / 20f)
            // Tone in the app's own output, together with the level (one update: no jump between them).
            try { mp.setEqualizer(null) } catch (_: Exception) {}
            NativeAudio.gainsEq(lin(left), lin(right), total > 0f, currentToneGains())
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
        // Sliders switched off in Settings: no loudness / treble at all (values stay saved for when they're back).
        val on = store.current.settings.toneSliders
        val l = if (on) autoLoudness(s.loudDb) else 0f; val t = if (on) s.trebleDb else 0f
        if (l != s.loudEffDb) _state.value = _state.value.copy(loudEffDb = l)
        try {
            if (l == 0f && t == 0f && preamp == 0f) { mp.setEqualizer(null); return }
            val e = eq ?: MediaPlayer.Equalizer.create().also { eq = it }
            val g = toneGains(l, t)
            for (b in 0 until 10) e.setAmp(b, g[b])
            e.setPreAmp(preamp.coerceIn(-20f, 20f))
            mp.setEqualizer(e)
        } catch (_: Exception) {
        }
    }

    /**
     * The app's own output: 10 band gains for loudness, plus an 11th value — the treble shelf in dB (2026-11-21 21:45:
     * a high shelf from ~2 kHz rising to 12 kHz and above, ±10 dB at the ends of the slider; the old treble on the top
     * bands only acted above 8–12 kHz and was hardly heard).
     */
    private fun currentToneGains(): FloatArray {
        val s = _state.value
        val on = store.current.settings.toneSliders
        val l = if (on) autoLoudness(s.loudDb) else 0f; val t = if (on) s.trebleDb else 0f
        if (l != s.loudEffDb) _state.value = _state.value.copy(loudEffDb = l)
        val bands = if (l == 0f) FloatArray(10) else toneGains(l, 0f)
        val set = store.current.settings
        NativeAudio.trebleBand(set.trebleType == "peak", set.trebleHz.toFloat(), if (set.trebleType == "peak") set.trebleQ else set.trebleSlope)
        return bands + floatArrayOf(t * set.trebleMaxDb / 5f * trebleFlatten())
    }

    /** Band gains (10 bands, all ≤ 0 dB) for loudness [l] and treble [t]. */
    private fun toneGains(l: Float, t: Float): FloatArray {
            // ISO 226:2003 equal-loudness compensation, as cuts — 30 % deeper than the plain curve (same shape).
            val loud = Iso226.cuts(l).let { c -> FloatArray(c.size) { c[it] * TONE_DEPTH } }
            // Treble acts from 8 kHz up: an S-curve on a log-frequency scale from 0 at 8 kHz to full at 16 kHz
            // (bands below 8 kHz take the 8 kHz value). The curve's shape follows the setting, not just its depth:
            // small settings give a gentle, nearly straight slope; larger ones a deeper, steeper S that keeps the
            // correction near the top. S(x) = xᵃ / (xᵃ + (1−x)ᵃ), a from 1.2 (near 0) to 3 (±5 dB, the maximum).
            val steep = 1.2 + 1.8 * (kotlin.math.abs(t) / 5.0).coerceIn(0.0, 1.0)
            val highShape = FloatArray(10) { b ->
                val x = (kotlin.math.ln(Iso226.bands[b] / 8000.0) / kotlin.math.ln(2.0)).coerceIn(0.0, 1.0)
                val xa = x.pow(steep); val ya = (1 - x).pow(steep)
                (if (xa + ya == 0.0) 0.0 else xa / (xa + ya)).toFloat()
            }
            return FloatArray(10) { b ->
                val trebleCut = (if (t >= 0f) -t * (1f - highShape[b]) else t * highShape[b]) * TONE_DEPTH
                (loud[b] + trebleCut).coerceIn(-26f, 0f)
            }
    }

    /**
     * How much the overall level drops with these loudness / treble settings (dB, ≥ 0): the band gains weighted by
     * a typical music spectrum (about equal energy per octave, less at the very top).
     */
    private fun toneLevelDrop(l: Float, t0: Float): Float {
        // App output: treble is a shelf on the highs (mids untouched), so only loudness lowers the level.
        val t = if (NativeAudio.active) 0f else t0
        if (l == 0f && t == 0f) return 0f
        val w = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 0.7f, 0.4f, 0.2f, 0.1f)
        val g = toneGains(l, t)
        var p = 0.0; var sw = 0.0
        for (b in 0 until 10) { p += w[b] * 10.0.pow(g[b] / 10.0); sw += w[b] }
        return (-10 * log10(p / sw)).toFloat().coerceAtLeast(0f)
    }

    /**
     * Auto loudness: the phone's media volume decides how much of the Loudness setting is used — all of it up to the
     * output device's start % (Sound settings), then less with every volume step above, down to none at full volume.
     */
    private fun autoLoudness(l: Float): Float {
        val st = store.current
        if (!st.settings.autoLoud || l == 0f) return l
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val pct = vol * 100f / max
        val from = (st.deviceLoudFrom[currentOutputName] ?: 50).coerceIn(0, 99).toFloat()
        if (pct <= from) return l
        // Smooth fade (cosine): the curve flattens gently as the volume goes up, none left at full volume.
        val x = ((pct - from) / (100f - from)).coerceIn(0f, 1f)
        val k = (0.5f + 0.5f * kotlin.math.cos(Math.PI.toFloat() * x)).coerceIn(0f, 1f)
        return Math.round(l * k * 2) / 2f
    }

    /** The volume now, in %: the phone's media volume, times the app's own volume on an output Android isn't routing to. */
    fun volumeNowPct(): Float {
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val pct = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100f / max
        // The output router is made after the player at start: not there yet → not overriding (1.70–1.73 start-up crash).
        return if (app.vlcinterface.App.instance.outputOrNull()?.overriding == true) pct * appVolume else pct
    }

    /**
     * Treble flattening (2026-11-21 22:03): the treble correction shrinks in proportion to the volume — all of it at
     * volume 0, none at Settings → Audio → Treble band → "Flatten at" (e.g. 150 %: two thirds left at 50 %, a third at
     * 100 %). 1 = whole correction (flattening off).
     */
    fun trebleFlatten(): Float = try {
        val target = store.current.settings.trebleFlatPct
        if (target <= 0) 1f else (1f - volumeNowPct() / target).coerceIn(0f, 1f)
    } catch (_: Throwable) { 1f }     // never takes the app down: the whole correction then

    /** Phone volume changed (buttons, speaker): Auto loudness follows at once. */
    private val volumeRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            if ((store.current.settings.autoLoud || store.current.settings.trebleFlatPct > 0) && ::mp.isInitialized) main.post { applySoundChain() }
        }
    }

    // ---------- loudness / treble per song ----------
    private var toneDirty = false

    /** Slider moved (live); [done] = finger lifted. Kept as the "last values" too. */
    fun setTone(loud: Float, treble: Float, done: Boolean) {
        val l = loud.coerceIn(0f, 15f); val t = treble.coerceIn(-5f, 5f)
        // The Preamp follows so the overall level (VU meters) stays where it was: up by the drop Loudness / Treble
        // cause, back down when they're reduced or switched off.
        val s0 = _state.value
        val delta = toneLevelDrop(l, t) - toneLevelDrop(s0.loudDb, s0.trebleDb)
        val pre = (s0.radioPreDb + delta).coerceIn(-12f, 12f)
        _state.value = s0.copy(loudDb = l, trebleDb = t, radioPreDb = pre)
        if (done) s0.item?.let { ToneStore.putPre(it.uri, pre) }
        applySoundChain()
        toneDirty = true
        if (done) store.update { it.copy(settings = it.settings.copy(toneLoud = l, toneTreble = t)) }
        // Radio: the values belong to the station at once (loaded whenever it is selected).
        val item = _state.value.item
        if (done && item?.isRadio == true) { ToneStore.put(item, l, t); toneDirty = false; _state.value = _state.value.copy(toneSaved = true) }
    }

    /** Radio pre-amp slider (cut only); kept for the station when the finger is lifted. */
    fun setRadioPre(db: Float, done: Boolean) {
        val item = _state.value.item ?: return
        val v = db.coerceIn(-12f, 12f)
        _state.value = _state.value.copy(radioPreDb = v)
        applySoundChain()
        if (done) {
            ToneStore.putPre(item.uri, v)
            store.update { it.copy(settings = if (item.isRadio) it.settings.copy(radioPreLast = v) else it.settings.copy(songPreLast = v)) }
        }
    }

    /** Reset button (long press): Loudness, Treble and Preamp back to 0; the item's own sound profile removed. */
    fun resetTone() {
        val item = _state.value.item ?: return
        toneDirty = false
        _state.value = _state.value.copy(loudDb = 0f, trebleDb = 0f, radioPreDb = 0f, toneSaved = false)
        applySoundChain()
        ToneStore.remove(item)
        store.update { it.copy(settings = it.settings.copy(toneLoud = 0f, toneTreble = 0f).let { st ->
            if (item.isRadio) st.copy(radioPreLast = 0f) else st.copy(songPreLast = 0f) }) }
        ServerLog.log("player", "tone", "", "", true, "\"${item.title}\": Loudness, Treble and Preamp reset to 0 — its sound profile removed")
    }

    /** Back to this song's own saved values (if it has some). */
    fun recallTone() {
        val item = _state.value.item ?: return
        val v = ToneStore.get(item)?.let { it.first to it.second.coerceIn(-5f, 5f) } ?: return
        toneDirty = false
        // The song's own preamp comes back with its loudness / treble (it was set together with them).
        _state.value = _state.value.copy(loudDb = v.first, trebleDb = v.second, radioPreDb = ToneStore.getPre(item.uri) ?: _state.value.radioPreDb)
        applySoundChain()
        store.update { it.copy(settings = it.settings.copy(toneLoud = v.first, toneTreble = v.second)) }
    }

    /** Leaving a song: values changed during it are saved with it. */
    private fun toneLeaving() {
        val s = _state.value
        val item = s.item ?: return
        // Radio: every station played keeps its settings (loudness, treble, pre-amp), changed or not.
        // (A station reset to 0 with the reset button keeps no profile: nothing is written back for it.)
        if (item.isRadio) { if (s.loudDb != 0f || s.trebleDb != 0f || s.radioPreDb != 0f) { ToneStore.put(item, s.loudDb, s.trebleDb); ToneStore.putPre(item.uri, s.radioPreDb) } }
        else if (toneDirty) ToneStore.put(item, s.loudDb, s.trebleDb)
        toneDirty = false
    }

    /** A song starts: its own saved values at once, else the last values used. */
    private fun toneFor(item: PlayItem): Triple<Float, Float, Boolean> {
        val v = ToneStore.get(item)?.let { it.first to it.second.coerceIn(-5f, 5f) }
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
    /** A video's first "no sound" verdict: checked once more before giving up the app's output (see below). */
    private var videoDoubt: PlayItem? = null

    private fun watchSound(tries: Int = 0) {
        if (!NativeAudio.active) return
        val s0 = _state.value
        val moving = s0.isPlaying && s0.buffering >= 100f && lastTick > 0 && s0.time > 500 &&
            System.currentTimeMillis() - lastTick < 1_500
        // Still not moving after ~20 s: checked anyway (a silent output can also stop the position from moving).
        if (!moving && tries < 8) { main.postDelayed({ watchSound(tries + 1) }, 2_500); return }
        val before = NativeAudio.stats() ?: return
        val timeBefore = mp.time
        main.postDelayed({
            val s = _state.value
            if (!NativeAudio.active || !s.isPlaying || s.item == null || s.item !== s0.item) return@postDelayed
            if (tries < 8 && (s.buffering < 100f || System.currentTimeMillis() - lastTick > 1_500)) { watchSound(tries + 1); return@postDelayed }
            if (mp.audioTracksCount <= 0) return@postDelayed      // video without sound: nothing to check
            val now = NativeAudio.stats() ?: return@postDelayed
            // Still starting (state 3): Bluetooth in particular can take several seconds to begin taking sound —
            // not a failure yet, look again shortly (your log: "state 3, heard 0" was exactly this).
            if (now.state == 3L && tries < 8) { main.postDelayed({ watchSound(tries + 1) }, 2_500); return@postDelayed }
            // Not a failure, look again: the sound stream was opened anew meanwhile (new song format, output
            // changed, Bluetooth reconnected — its "frames heard" start again from 0, which looked like "nothing
            // played"), libVLC is between two sound outputs (no stream open for a moment: track change, end of a
            // video), or the song itself didn't move (paused, seeking, network wait) — then no sound is expected.
            // These were the false alarms in the logs that switched the app to libVLC's output for nothing.
            val moved = mp.time - timeBefore
            val unclear = now.stream != before.stream || now.setups != before.setups || now.state == -1L || moved < 2_000
            if (unclear && now.setupResult == 0L) {
                if (tries < 8) { watchSound(tries + 1); return@postDelayed }
                // Last look: judged only if the song really moved on the same stream.
                if (moved < 2_000 || now.stream != before.stream) return@postDelayed
            }
            val problem = when {
                now.setupResult != 0L -> "the phone refused the audio stream (open ${now.openResult})"
                now.plays == before.plays -> "libVLC delivered no sound to the app"
                now.heard <= before.heard -> "the phone did not play the sound (state ${now.state}, start ${now.startResult})"
                else -> null
            }
            // A video starting (11:47: the output picked jumped to Bluetooth when a new episode began): its sound can
            // take a while longer to start than a song's — and falling back to libVLC's output sends the sound where
            // Android routes it, away from the device picked in the app. A video's first verdict is checked once more.
            if (problem != null && s.item?.isMusic == false && videoDoubt !== s.item) {
                videoDoubt = s.item
                ServerLog.log("sound-engine", "sound", "AAudio", "", true, "Video sound not heard yet ($problem) — checked again before switching")
                watchSound(tries); return@postDelayed
            }
            if (problem != null) {
                val detail = now.toString()
                android.util.Log.w("PlayerController", "app sound output failed: $problem — $detail")
                ServerLog.log("sound-engine", "sound", "AAudio", "", false, "App output gave no sound: $problem — switched to libVLC output", detail)
                NativeAudio.markFailed("$problem — switched to libVLC output for now")
                val pos = mp.time
                recreateEngine(store.current.subStyle)
                startItem(s.index, pos)
                outputFellBack(problem)
            } else appRetries = 0                                  // the app's own output plays fine
        }, 4000)
    }

    // ---------- fallback to libVLC's output: tried back twice, then left with a pop-up ----------
    private var appRetries = 0
    /** Reason shown in the pop-up when the output stays on libVLC ("" = nothing to show). */
    val fallbackNotice = kotlinx.coroutines.flow.MutableStateFlow("")

    /**
     * The app's own output just failed and libVLC's took over: wait 1 s, then rebuild the sound stack with the app's
     * output again (the song goes on from where it is). Twice; if it still fails, it stays on libVLC and a pop-up
     * says why.
     */
    private fun outputFellBack(reason: String) {
        if (store.current.settings.soundEngine == "libvlc") return       // libVLC chosen on purpose: nothing to undo
        if (appRetries >= 2) {
            appRetries = 0
            fallbackNotice.value = reason
            ServerLog.log("sound-engine", "sound", "AAudio", "", false, "Output left on libVLC after 2 retries: $reason")
            return
        }
        appRetries++
        // When and on what it happened: the song, the device, the phone's state — to find the pattern later.
        val st = _state.value
        val where = "Song: ${st.item?.title ?: "-"} (${st.item?.uri ?: ""})\nDevice: $currentOutputName\nPosition: ${fmtMs(st.time)}" +
            "\nBattery: ${BatteryGuard.level} % · screen ${if ((context.getSystemService(android.os.PowerManager::class.java))?.isInteractive == true) "on" else "off"}" +
            "\nNative stats: ${NativeAudio.stats() ?: "-"}"
        ServerLog.log("sound-engine", "sound", "AAudio", "", true, "Bringing the app output back (try $appRetries of 2) after: $reason", where)
        main.postDelayed({
            if (store.current.settings.soundEngine != "libvlc" && !NativeAudio.active) applySoundEngine()
        }, 1000)
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

    /** A picture (from the song's file) marked wrong for its album. */
    private fun artBanned(item: PlayItem, path: String) = path.isNotEmpty() && try { app.vlcinterface.sources.CoverArt.bannedFile(item, path) } catch (_: Exception) { false }

    /**
     * "Wrong picture" on the playing song: that picture is never used for its album again; another is looked for
     * (album, else the artist's photo). [clear]: pictures marked wrong for the album allowed again.
     */
    fun replacePicture(item: PlayItem, clear: Boolean = false, onDone: (String) -> Unit) {
        val ca = app.vlcinterface.sources.CoverArt
        bg.launch {
            val note = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (clear) ca.clearBans(context, item, item.artist, item.album)
                else if (!ca.banShown(context, item, item.artist, item.album, _state.value.artworkUrl)) return@withContext "No picture to mark"
                val r = try { ca.forPlayer(context, item, item.artist, item.album, true, force = true) } catch (_: Exception) { null }
                val s = _state.value
                if (s.item === item) _state.value = s.copy(artworkUrl = if (r?.bitmap != null) "mem:" + item.uri + "#v" + System.currentTimeMillis() else "")
                when {
                    r?.bitmap != null -> if (clear) "Pictures allowed again" else "Another picture found"
                    clear -> "Pictures allowed again — none found now"
                    else -> r?.note?.ifEmpty { null } ?: "No other picture found for this album or artist"
                }
            }
            onDone(note)
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

    // ---------- the queue kept on disk as it changes (2026-11-28 01:23) ----------
    // It was written only on Exit, so after the phone restarted (no Exit) the last lists weren't there to load. Now every
    // change of the queue, the item playing or the list played from is written (2 s after it settles), and the position
    // every 20 s while playing — the app opens with the last songs, video and radio lists after a reboot too.
    private val queueSaver = bg.launch {
        var lastSig = ""
        var lastAt = 0L
        _state.collect { st ->
            if (st.queue.isEmpty()) return@collect
            val sig = "${st.queue.size}|${st.index}|${st.item?.uri}|${st.origin.kind}|${st.origin.title}|${st.queue.firstOrNull()?.uri}|${st.queue.lastOrNull()?.uri}"
            val now = System.currentTimeMillis()
            if (sig != lastSig || (st.isPlaying && now - lastAt > 20_000)) {
                lastSig = sig; lastAt = now
                kotlinx.coroutines.delay(2_000)
                try { QueueKeeper.save(_state.value, orderedQueue) } catch (_: Exception) {}
            }
        }
    }

    // ---------- radio: no sound for 20 s → next station (2026-11-22 19:22; 20 s and no level check since 2026-11-28) ----------

    /** When the station playing last had sound (or the end of its 10 s to connect). */
    @Volatile private var radioSoundAt = 0L
    private var radioHeard = -1L
    private var radioTime = -1L
    private var radioStream = -1L
    /** Stations skipped one after another for having no sound (all of them silent → stop). */
    private var radioSkips = 0
    private val radioWatch: Runnable = object : Runnable {
        override fun run() {
            try { checkRadioSound() } catch (_: Exception) {}
            main.postDelayed(this, 1000)
        }
    }
    private val radioWatchStart = main.postDelayed(radioWatch, 1000)

    /**
     * A station whose sound is gone for more than 5 s, for any reason — the stream stalled or buffering, the server
     * dropped it, an error, the stream sending only silence (below −66 dBFS), or nothing reaching the output — goes to the
     * next station of the list (the favourites); a station played on its own is stopped. 10 s are allowed to connect at
     * first. Not while paused by you, during an alarm, or while the sleep timer fades out. Every case goes to the Player log.
     */
    private fun checkRadioSound() {
        val s = _state.value
        val item = s.item
        val now = System.currentTimeMillis()
        if (item == null || !item.isRadio || s.userPaused || sleepClosing || app.vlcinterface.alarm.AlarmEngine.ui.value.phase != "idle") {
            radioSoundAt = now; return
        }
        // 2026-11-23 08:39 (every station skipped after 15 s): the engine's figures were read even when the sound didn't go
        // through the app's engine (libVLC's own output, e.g. after a fallback) — they stood still, read as "no sound".
        // Now: sound = the stream moving (libVLC's clock) or the engine's output moving; silence only when the engine is
        // the output and has measured the level, and it stayed under −66 dBFS for more than 5 s.
        val st = if (NativeAudio.active) NativeAudio.stats() else null
        val heardMoving = st != null && st.stream == radioStream && st.heard > radioHeard
        if (st != null) { radioHeard = st.heard; radioStream = st.stream }
        // The play clock: libVLC's time events, or its time read directly (some streams send few events).
        val tNow = try { mp.time } catch (_: Exception) { -1L }
        val polled = tNow > 0 && tNow != radioTime
        radioTime = tNow
        val clockMoving = (lastTick > 0 && now - lastTick < 2_500) || polled
        // 2026-11-28 01:11: the level-based "silence" gave false skips while there was sound (another Wi-Fi network) — it's
        // gone. A station is skipped only when its stream really stands still: neither libVLC's clock nor the output moves,
        // or an error, for 20 s (after its 15 s to connect).
        val sound = s.isPlaying && s.error.isEmpty() && (clockMoving || heardMoving)
        if (sound) { radioSoundAt = now; radioSkips = 0; return }
        if (now - radioSoundAt <= 20_000) return
        val why = when {
            s.error.isNotEmpty() -> "error: ${s.error}"
            !s.isPlaying -> "the stream stopped"
            !clockMoving && !heardMoving -> if (s.buffering < 100f) "buffering ${s.buffering.toInt()} %" else "the stream doesn't move"
            else -> "silence"
        }
        radioSoundAt = now + 15_000                                   // the next station gets its time to connect
        val more = s.queue.count { it.isRadio } > 1
        if (more && radioSkips < s.queue.size - 1) {
            radioSkips++
            ServerLog.log("player", "radio", "", "", false, "\"${item.title}\": no sound for 20 s ($why) — next station")
            next()
        } else {
            radioSkips = 0
            ServerLog.log("player", "radio", "", "", false, "\"${item.title}\": no sound for 20 s ($why) — stopped" +
                if (more) " (no station of the list has sound)" else "")
            stop()
            try { app.vlcinterface.ui.ShortNote.hint(if (more) "No station has sound — stopped" else "\"${item.title}\" has no sound — stopped") } catch (_: Exception) {}
        }
    }

    // ---------- start volume after inactivity (2026-11-22 18:37) ----------

    /**
     * Since when nothing plays (0 = something is playing). Starts at the app's start (the start volume, or the volume kept
     * after Exit / a Bluetooth hiccup, has just been set then).
     */
    @Volatile private var idleSince = System.currentTimeMillis()
    private val idleWatch = bg.launch {
        _state.collect { st -> val p = st.isPlaying; if ((idleSince == 0L) == p) return@collect; idleSince = if (p) 0L else System.currentTimeMillis() }
    }

    /**
     * Settings → Audio → "Reset volume after": nothing played for that many minutes → right before the next sound the
     * volume goes back to the Start volume on the output in use (and the app's own volume on a device it picked to 100 %);
     * the Preamp (everything) is a setting and stays as set. Never while an alarm rings (it sets its own volume).
     * Done at the next sound, not while idle, so other apps' sound on the phone isn't changed meanwhile.
     */
    private fun volumeAfterIdle() {
        val min = store.current.settings.idleResetMin
        val since = idleSince
        if (min <= 0 || since == 0L || System.currentTimeMillis() - since < min * 60_000L) return
        if (app.vlcinterface.alarm.AlarmEngine.ui.value.phase != "idle") return
        idleSince = System.currentTimeMillis()
        val start = store.current.settings.startVolume.coerceIn(0, 100)
        val out = app.vlcinterface.App.instance.output
        val name = try { out.active?.name ?: "" } catch (_: Exception) { "" }
        // 2026-11-23 00:55 (it worked once, then never): on an output the app picked itself (not the one Android routes
        // media to) the phone's volume belongs to Android's output, so setting it changed nothing heard — and the app's own
        // volume was even put up to 100 %. There the app's own volume now takes the level instead.
        if (out.overriding) {
            val streamPct = try { out.volumePercent() } catch (_: Exception) { 100 }
            setAppVolume(if (streamPct <= 0) 1f else start / streamPct.toFloat())
            ServerLog.log("player", "volume", "", "", true, "Nothing played for $min min or more: $name (picked in the app) set back to the start volume ($start %) with the app's own volume")
        } else {
            try { app.vlcinterface.App.instance.applyStartVolume() } catch (_: Exception) {}
            setAppVolume(1f)
            ServerLog.log("player", "volume", "", "", true, "Nothing played for $min min or more: ${name.ifEmpty { "the output" }} set back to the start volume ($start %)")
        }
    }

    // ---------- volume on a device the app picked itself ----------

    /** App volume 0…2 (1 = as the device is set), used only while [OutputRouter.overriding]. */
    var appVolume = 1f
        private set

    fun setAppVolume(v: Float) {
        // Bluetooth quality mode: the app's own (digital) volume stays at 100 %; the device volume does the rest.
        appVolume = if (BtQuality.on()) 1f else v.coerceIn(0f, 2f)
        NativeAudio.appVolume(if (sleepClosing) 0f else appVolume * fadeK)
        if (store.current.settings.trebleFlatPct > 0) applySoundChain()      // treble flattening follows the app's volume too
    }

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
    /** Closes the playing video away from the screen's thread (reopen at the same spot). */
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "video-close").apply { isDaemon = true } }
    private var reloadGen = 0

    /**
     * The video reopened at the same spot (picture colour, decoder, subtitle format, picture check).
     * 2026-11-22: the old video used to be closed on the screen's thread (libVLC's setMedia waits for it); a network video
     * stuck in a read kept that thread waiting >10 s — "app not responding" after a Hue/Sat change. Now the old video is
     * closed first on its own thread; the reopen follows on the screen's thread when that's done (nothing left to wait for).
     * A newer reopen or another item chosen meanwhile → this one is dropped.
     */
    fun reloadVideo() {
        val s = _state.value
        val item = s.item ?: return
        if (item.isMusic || item.isRadio) return
        val pos = mp.time; val playing = mp.isPlaying
        val gen = ++reloadGen
        stopping = true; endHandled = true              // this stop isn't the end of the video
        closer.execute {
            try { mp.stop() } catch (_: Throwable) {}
            main.post {
                if (gen != reloadGen || _state.value.item?.uri != item.uri) { stopping = false; return@post }
                startItem(s.index, pos.coerceAtLeast(0))
                if (!playing) main.postDelayed({ pause() }, 400)
            }
        }
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

    /** An alarm plays the radio: no network buffer. */
    @Volatile var alarmLive = false
    private var resumeOnGain = false
    @Volatile private var ducked = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        // Android lowers the sound by itself for notifications unless the app says it handles ducking: it does
        // (and ignores it), so pop-ups and notifications of any app never lower the volume.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (mp.isPlaying) { resumeOnGain = true; mp.pause() } }
                // Pop-ups and notifications never lower the sound.
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
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

    /** The address libVLC names is the item playing (any form: routed through the VPN or not, encoded or not). */
    private fun isCurrentMrl(mrl: String): Boolean {
        val cur = _state.value.item?.uri ?: return false
        fun n(u: String) = android.net.Uri.decode(app.vlcinterface.sources.VpnLink.unroute(u)).substringAfter("://").substringAfter('@').trimEnd('/').lowercase()
        val a = n(mrl); val b = n(cur)
        return a == b || a.substringAfter('/') == b.substringAfter('/')
    }

    private fun installDialogs() {
        Dialog.setCallbacks(libVlc, object : Dialog.Callbacks {
            override fun onDisplay(dialog: Dialog.ErrorMessage) {
                val text = dialog.text ?: "Playback error"
                // libVLC shows one message for everything it opens — also files read in the background (indexing,
                // tags, covers). 08:57: a NAS file the indexing couldn't open was shown under the song playing as if
                // that song had failed. A message naming another file goes to the Indexing log instead.
                val mrl = Regex("""MRL '([^']+)'""").find(text)?.groupValues?.get(1)
                if (mrl != null && !isCurrentMrl(mrl)) {
                    try { app.vlcinterface.data.ServerLog.log(app.vlcinterface.data.ServerLog.keyOf(android.net.Uri.parse(mrl).host ?: "index"), "index", "libVLC", "", false,
                        "Could not open (background read): " + android.net.Uri.decode(mrl).substringAfterLast('/'), android.net.Uri.decode(mrl)) } catch (_: Exception) {}
                    return
                }
                main.post { _state.value = _state.value.copy(error = text) }
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
    fun loadQueue(items0: List<PlayItem>, origin: Origin) {
        // NAS items never go into the queue while the NAS can't be used (19:35); unreadable files never (01:08).
        val items = items0.filterNot { nasOff(it) || !readable(it) }
        if (items.isEmpty()) { if (items0.isNotEmpty()) nasOfflineToast(); return }
        // A song is playing — or paused (2026-11-28 01:19: queue operations never interrupt the song in the player): it
        // stays, and plays to its end; the new list comes right after it (nothing cut off).
        val s = _state.value
        val cur = s.queue.getOrNull(s.index)
        if (cur != null && !cur.isRadio && isVideo(cur) == isVideo(items.first())) {
            main.post {
                val list = items.filterNot { nasOff(it) || qKey(it) == qKey(cur) }.distinctBy { qKey(it) }
                if (list.isEmpty()) return@post
                fullList = list.filter { !it.isRadio }
                val shuffled = store.current.settings.shuffle && !isVideo(cur)
                orderedQueue = if (shuffled) listOf(cur) + list else null
                val q = listOf(cur) + if (shuffled) list.shuffled() else list
                _state.value = _state.value.copy(queue = q, index = 0, origin = origin)
            }
            return
        }
        startPaused = true
        NativeAudio.appVolume(0f)
        playQueue(items, 0, origin, random = false)
    }

    /** A NAS song that can't be used now (away without "Use NAS", VPN off, or skipped this session). */
    private fun nasOff(it: PlayItem) = app.vlcinterface.sources.VpnLink.waitsForUse(it.uri) || app.vlcinterface.sources.VpnLink.ignored(it.uri) ||
        try { app.vlcinterface.sources.NetIndex.blockedNow(it) } catch (_: Exception) { false }

    /** "NAS offline", 3 s, nothing logged (a song picked by hand). */
    /**
     * The "NAS offline / inaccessible" messages (2026-11-28 01:09: no spam): once per session at most, and only with songs
     * or videos — never while the radio plays.
     */
    @Volatile private var nasNoted = false
    private fun nasNoteOk(): Boolean {
        if (_state.value.item?.isRadio == true || nasNoted) return false
        nasNoted = true; return true
    }
    private fun nasOfflineToast() = main.post {
        if (!nasNoteOk()) return@post
        val t = android.widget.Toast.makeText(context, "NAS offline", android.widget.Toast.LENGTH_LONG); t.show()
        main.postDelayed({ t.cancel() }, 3_000)
    }

    /**
     * VPN-NAS on (NAS usable again): the loaded list comes back whole — its NAS songs return to their places; the
     * playing song goes on, the list continues from it (shuffle: the rest shuffled again).
     */
    /** "NAS is inaccessible" already shown for this drop (video queues). */
    @Volatile private var nasDownNoted = false

    /** A NAS back on line: a NAS item that had stopped on an error is opened again at its place (no app restart needed). */
    // A short wait first: the share's connection settles before the song is opened again (no race with the monitor).
    fun nasBack() = main.postDelayed({ nasBackNow() }, 1500)

    private fun nasBackNow() = main.post {
        nasDownNoted = false
        val s = _state.value
        val item = s.item ?: return@post
        if (item.isRadio || item.uri.startsWith("content://") || item.uri.startsWith("file://")) return@post
        if (s.error.isNotEmpty() && !s.userPaused && !nasOff(item)) {
            ServerLog.log("player", "nas", "", "", true, "NAS back on line: \"${item.title}\" opened again")
            startItem(s.index, s.time.coerceAtLeast(0))
        }
    }

    fun restoreNas() = main.post {
        nasDownNoted = false
        val s = _state.value
        val full = fullList.filterNot { nasOff(it) }
        if (full.isEmpty() || s.item?.isRadio == true) return@post
        val have = s.queue.map { qKey(it) }.toHashSet()
        if (full.all { qKey(it) in have }) return@post
        val cur = s.queue.getOrNull(s.index)
        // Songs added to the queue later (not in the list) are kept at the end.
        val fullKeys = full.map { qKey(it) }.toHashSet()
        val extra = s.queue.filter { qKey(it) !in fullKeys }
        val ordered = (full.map { f -> s.queue.firstOrNull { qKey(it) == qKey(f) } ?: f }) + extra
        if (store.current.settings.shuffle && orderedQueue != null && cur != null && !isVideo(cur)) {
            orderedQueue = ordered
            val played = s.queue.take(s.index + 1)
            val playedKeys = played.map { qKey(it) }.toHashSet()
            val q = played + ordered.filter { qKey(it) !in playedKeys }.shuffled()
            _state.value = s.copy(queue = q, index = s.index)
        } else {
            val idx = cur?.let { c -> ordered.indexOfFirst { it === c } }?.takeIf { it >= 0 } ?: 0
            _state.value = s.copy(queue = ordered, index = idx)
        }
        app.vlcinterface.data.ServerLog.log("player", "nas-back", "", "", true, "NAS usable: list reloaded with its NAS songs (${ordered.size})")
    }

    /** The list in its original order while shuffled (null when not shuffled). */
    fun originalOrder(): List<PlayItem>? = orderedQueue

    /** App start: the queue kept over the restart, its song ready and paused at [time]. */
    fun restoreQueue(q0: List<PlayItem>, index0: Int, time0: Long, origin: Origin, ordered0: List<PlayItem>?, force: Boolean = false) = main.post {
        if (_state.value.item != null && !force) return@post        // something was started meanwhile: kept
        // Unreadable files kept from last time are dropped (01:08); the playing one gone → the next one, from its start.
        val q = q0.filter { readable(it) }
        if (q.isEmpty()) return@post
        val cur0 = q0.getOrNull(index0)
        val index = cur0?.let { c -> q.indexOf(c).takeIf { it >= 0 } } ?: q0.take(index0).count { readable(it) }.coerceAtMost(q.size - 1)
        val time = if (cur0 != null && q.getOrNull(index) === cur0) time0 else 0L
        val ordered = ordered0?.filter { readable(it) }
        _state.value.let { pv -> if (pv.item != null) { QueueKeeper.save(pv, orderedQueue); mp.pause() } }
        orderedQueue = ordered
        fullList = (ordered ?: q).filter { !it.isRadio }
        _state.value = PlayerState(queue = q, index = index, origin = origin)
        if (nasOff(q[index])) { pruneNas(); return@post }           // NAS not usable now: the next local song
        startPaused = true
        NativeAudio.appVolume(0f)
        startItem(index, if (q[index].isRadio) -1 else time.coerceAtLeast(0))
        // The other NAS items of the queue kept from last time don't come back while the NAS can't be used (19:35).
        if (q.any { nasOff(it) }) pruneNas()
    }

    /** NAS not usable (VPN-NAS off, VPN down …): the queue is refreshed to local songs only. */
    fun pruneNas() = main.post {
        val s = _state.value
        if (s.queue.none { nasOff(it) }) return@post
        // A video queue (2026-11-22 19:40) is never cleared: it stays as it is, and a short pop-up says the NAS can't be
        // reached (once per drop; again after it was back).
        if (isVideo(s.item) || (s.item == null && s.queue.any { isVideo(it) })) {
            if (!nasDownNoted) { nasDownNoted = true
                if (nasNoteOk()) try { app.vlcinterface.ui.ShortNote.hint("NAS is inaccessible") } catch (_: Exception) {} }
            return@post
        }
        val cur = s.queue.getOrNull(s.index)
        // A NAS song that is really playing (its clock moving) is never cut (2026-11-28 01:24): it plays on, only the rest
        // of the list follows the NAS state.
        val flowing = s.isPlaying && lastTick > 0 && System.currentTimeMillis() - lastTick < 3_000
        // VPN-NAS off: the list keeps only local songs; a NAS song playing now stops and the next local one starts.
        if (cur != null && nasOff(cur) && !flowing) {
            val next = s.queue.drop(s.index + 1).firstOrNull { !nasOff(it) } ?: s.queue.take(s.index).firstOrNull { !nasOff(it) }
            val q = s.queue.filter { !nasOff(it) }
            orderedQueue = orderedQueue?.filter { !nasOff(it) }
            if (q.isEmpty() || next == null) {
                stopping = true; mp.stop(); main.postDelayed({ stopping = false }, 300)
                _state.value = s.copy(queue = listOf(cur), index = 0, isPlaying = false, error = "NAS offline: no local songs in this list")
                return@post
            }
            _state.value = s.copy(queue = q, index = q.indexOfFirst { it === next }.coerceAtLeast(0))
            if (!s.isPlaying) startPaused = true                     // paused: the local song waits ready
            startItem(_state.value.index, -1)
            return@post
        }
        val q = s.queue.filter { it === cur || !nasOff(it) }
        orderedQueue = orderedQueue?.filter { it === cur || !nasOff(it) }
        _state.value = s.copy(queue = q, index = q.indexOfFirst { it === cur }.coerceAtLeast(0))
    }

    /**
     * [wholeList] (2026-11-22 19:33): the list itself was asked for (a playlist's Play / Random, a list's "Play in order /
     * random") — not one item picked by hand. Every item that can be played now is played; when the NAS can't be used,
     * its items are left out and the list starts on a playable one (Random: a random one), never "NAS offline" while the
     * list has playable (phone) items.
     */
    /** Not an unreadable file (01:08): encrypted downloads of streaming apps, scrambled names / titles, files found unplayable. */
    private fun readable(i: PlayItem) = i.isRadio || !app.vlcinterface.sources.Ignore.unreadable(i.uri, i.fileName.ifEmpty { i.uri.substringAfterLast('/') }, i.title)

    fun playQueue(items0x: List<PlayItem>, index0x: Int, origin: Origin, startAt: Long = -1, random: Boolean? = null, keepOrder: Boolean = false,
                  wholeList: Boolean = false) {
        // Unreadable files never go into the queue; the item asked for keeps its place among the others.
        val tappedX = items0x.getOrNull(index0x)
        val items00 = items0x.filter { readable(it) }
        if (items00.isEmpty()) { encryptedNote(); return }
        val index00 = tappedX?.let { t -> items00.indexOf(t).takeIf { it >= 0 } } ?: items0x.take(index0x).count { readable(it) }.coerceAtMost(items00.size - 1)
        if (items00.isEmpty()) return
        // An episode played from a folder (16:48): the queue is the whole succession of its series, in episode order
        // (never shuffled), starting at the one tapped.
        val tapped0 = items00.getOrNull(index00.coerceIn(0, items00.size - 1))
        val series = if (tapped0 != null && origin.kind in setOf("folder", "local") && !tapped0.isMusic && !tapped0.isRadio) Episodes.series(items00, tapped0) else null
        val items0 = series ?: items00
        val index0 = if (series != null) series.indexOfFirst { qKey(it) == qKey(tapped0!!) }.coerceAtLeast(0) else index00
        val keepOrderAll = keepOrder || series != null
        // Duplicates are left out (first one kept); the item that was tapped stays the one that starts.
        val tapped = items0.getOrNull(index0.coerceIn(0, items0.size - 1))
        // NAS not usable: a NAS song picked by hand doesn't start ("NAS offline"); lists keep only local songs.
        if (!wholeList && tapped != null && nasOff(tapped) && (index0 > 0 || items0.size == 1 || items0.all { nasOff(it) })) { nasOfflineToast(); return }
        fullList = items0.filter { !it.isRadio }.distinctBy { qKey(it) }
        val items = items0.filterNot { nasOff(it) }.distinctBy { qKey(it) }
        if (items.isEmpty()) { nasOfflineToast(); return }
        val index = tapped?.let { t -> items.indexOfFirst { qKey(it) == qKey(t) } }?.takeIf { it >= 0 }
            // The start item can't be played now (NAS): a whole list starts on a playable one — Random: any of them;
            // in order: the first playable one after it (else the first one).
            ?: if (wholeList && random == true) items.indices.random()
            else items0.drop(index0 + 1).firstOrNull { !nasOff(it) }?.let { n -> items.indexOfFirst { qKey(it) == qKey(n) } }?.takeIf { it >= 0 } ?: 0
        if (random != null && random != store.current.settings.shuffle) store.update { it.copy(settings = it.settings.copy(shuffle = random)) }
        var i = index.coerceIn(0, items.size - 1)
        var q = items
        if (!keepOrderAll && store.current.settings.shuffle && items.size > 1 && !isVideo(items[i])) {
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
    /** Not a song: videos (19:20) and radio stations (19:21) are never played in random order — Random is for songs only. */
    private fun isVideo(i: PlayItem?) = i != null && !i.isMusic

    fun setShuffle(on: Boolean) {
        if (on != store.current.settings.shuffle) store.update { it.copy(settings = it.settings.copy(shuffle = on)) }
        val s = _state.value
        val cur = s.queue.getOrNull(s.index)
        // Videos and stations stay in order: the choice is kept for songs, their queue isn't touched.
        if (isVideo(cur) && orderedQueue == null) return
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
        // The station logo (favicon) is its picture until a song with a known album picture is announced.
        playQueue(favs.map { PlayItem(it.url, it.name, isRadio = true, artwork = it.favicon) }, i, Origin("radio", "", url, "Favourite stations"), keepOrder = true)
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

    /**
     * Everything known about the playing video, in fixed sections and order (long press on the video):
     * File, Source, Video, Audio tracks, Subtitles, Tags, Playback. Empty values are left out.
     */
    fun videoDetails(): List<Pair<String, List<Pair<String, String>>>> {
        val s = _state.value
        val item = s.item ?: return emptyList()
        val out = ArrayList<Pair<String, List<Pair<String, String>>>>()
        fun sec(title: String, b: MutableList<Pair<String, String>>.() -> Unit) {
            val l = ArrayList<Pair<String, String>>().apply(b).filter { it.second.isNotBlank() }
            if (l.isNotEmpty()) out += title to l
        }
        fun hms(v: Long): String { val t = v.coerceAtLeast(0) / 1000; return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
        fun ms(v: Long) = if (v <= 0) "" else hms(v)
        fun size(b: Long) = if (b <= 0) "" else app.vlcinterface.player.NetGuard.fmt(b) + " (" + "%,d".format(java.util.Locale.US, b) + " bytes)"
        val uri = item.uri
        val shown = app.vlcinterface.sources.VpnLink.route(uri).replace(Regex("//[^/@]*@"), "//")
        // File name and size.
        var name = item.fileName; var bytes = 0L
        try {
            when {
                uri.startsWith("content://") -> context.contentResolver.query(Uri.parse(uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { if (name.isEmpty()) name = c.getString(0) ?: ""; bytes = c.getLong(1) } }
                uri.startsWith("file://") || uri.startsWith("/") -> java.io.File(Uri.parse(uri).path ?: uri).let { if (name.isEmpty()) name = it.name; bytes = it.length() }
            }
        } catch (_: Exception) {}
        if (name.isEmpty()) name = Uri.decode(uri.substringBefore('?').substringAfterLast('/'))
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = try { mp.media } catch (_: Exception) { null }
        try {
            sec("File") {
                add("Name" to name)
                add("Title" to item.title.takeIf { it != name }.orEmpty())
                add("Type" to when (ext) { "mkv" -> "Matroska (.mkv)"; "mp4", "m4v" -> "MPEG-4 (.$ext)"; "avi" -> "AVI (.avi)"; "mov" -> "QuickTime (.mov)"
                    "webm" -> "WebM (.webm)"; "ts", "m2ts", "mts" -> "MPEG transport stream (.$ext)"; "mpg", "mpeg", "vob" -> "MPEG program stream (.$ext)"
                    "wmv" -> "Windows Media (.wmv)"; "flv" -> "Flash video (.flv)"; "3gp" -> "3GPP (.3gp)"; "" -> ""; else -> ".$ext" })
                add("Size" to size(bytes))
                add("Duration" to ms(if (s.length > 0) s.length else item.durationMs))
                add("Folder" to Uri.decode(shown.substringBeforeLast('/')))
                if (uri.startsWith("content://")) add("Place on the phone" to (try { app.vlcinterface.sources.PhysicalPath.of(context, item) } catch (_: Exception) { "" }))
            }
            sec("Source") {
                val src = store.current.sources.firstOrNull { it.id == item.sourceId }
                add("Source" to (src?.name?.ifBlank { src.host } ?: ""))
                add("Kind" to when {
                    uri.startsWith("content://") || uri.startsWith("file://") || uri.startsWith("/") -> "On the phone"
                    uri.startsWith("smb://") -> "Network share (SMB" + (item.smbVersion.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "") + ")"
                    uri.startsWith("sftp://") -> "SFTP server"
                    uri.startsWith("http") -> "Web (HTTP)"
                    else -> uri.substringBefore(':').uppercase()
                })
                add("Address" to Uri.decode(shown))
                if (shown != uri.replace(Regex("//[^/@]*@"), "//")) add("Reached through" to "VPN")
                add("User" to item.user)
            }
            val tracks = (0 until (m?.trackCount ?: 0)).mapNotNull { m?.getTrack(it) }
            val vt = try { mp.currentVideoTrack } catch (_: Throwable) { null } ?: tracks.filterIsInstance<IMedia.VideoTrack>().firstOrNull()
            sec("Video") {
                if (vt != null) {
                    val w = vt.width; val h = vt.height
                    val c = vt.codec?.trim().orEmpty()
                    add("Codec" to listOf(codecName(c), c.uppercase()).filter { it.isNotEmpty() }.distinct().joinToString(" · "))
                    add("Resolution" to if (w > 0) "$w × $h" + resName(w, h).let { if (it.isEmpty()) "" else "  ($it)" } else "")
                    if (vt.sarNum > 0 && vt.sarDen > 0 && vt.sarNum != vt.sarDen) add("Pixel aspect" to "${vt.sarNum}:${vt.sarDen} → shown ${s.videoW} × ${s.videoH}")
                    if (w > 0 && h > 0) add("Picture format" to ratioName(s.videoW.takeIf { it > 0 } ?: w, s.videoH.takeIf { it > 0 } ?: h))
                    if (vt.frameRateNum > 0 && vt.frameRateDen > 0) add("Frame rate" to "%.3f".format(java.util.Locale.US, vt.frameRateNum.toDouble() / vt.frameRateDen).trimEnd('0').trimEnd('.') + " fps")
                    if (vt.bitrate > 0) add("Bitrate" to "${vt.bitrate / 1000} kbps")
                    if (vt.profile > 0) add("Profile / level" to "${vt.profile} / ${vt.level}")
                    // Original orientation: how the video was recorded (stored size + the rotation flag in the file).
                    if (w > 0 && h > 0) {
                        val turned = vt.orientation >= 4
                        val tall = if (turned) w > h else h > w
                        val shape = if (w == h) "Square" else if (tall) "Portrait (upright, tall)" else "Landscape (wide)"
                        val rot = when (vt.orientation) {
                            // libVLC order: top-left, top-right, bottom-left, bottom-right, left-top, left-bottom, right-top, right-bottom.
                            0 -> "stored as recorded"; 1 -> "stored mirrored"; 2 -> "stored flipped upside down"; 3 -> "stored turned 180°"
                            4 -> "stored turned 90° and mirrored"; 5 -> "stored turned 90°"; 6 -> "stored turned 270°"
                            7 -> "stored turned 270° and mirrored"; else -> "rotation flag ${vt.orientation}"
                        }
                        add("Original orientation" to "$shape · $rot")
                        if (videoRot != 0) add("Turned with Rotate" to "$videoRot° (kept for this video)")
                    }
                    add("Language" to vt.language.orEmpty())
                }
                val n = videoNow()
                if (n != null) {
                    val decM = VideoAuto.manualDecoder(item.uri); val diM = VideoAuto.manualDeinterlace(item.uri)
                    fun how(m: String, def: String) = if (m.ifEmpty { def } == "auto") " (automatic)" else if (m.isNotEmpty()) " (chosen for this video)" else " (default)"
                    add("Decoder" to (if (n.software) "Software" else "Hardware") + how(decM, store.current.settings.videoDecoder))
                    add("Deinterlacing" to (if (n.deinterlace) "On" else "Off") + how(diM, store.current.settings.videoDeinterlace))
                }
                if (pictureFilterOn) add("Colour filter" to "Hue / saturation / bright areas on")
            }
            val auds = tracks.filterIsInstance<IMedia.AudioTrack>()
            auds.forEachIndexed { i, t ->
                sec(if (auds.size > 1) "Audio track ${i + 1}" + (if (t.id == s.audioTrack) " (playing)" else "") else "Audio") {
                    add("Codec" to listOf(codecName(t.codec?.trim().orEmpty()), t.codec?.trim()?.uppercase().orEmpty()).filter { it.isNotEmpty() }.distinct().joinToString(" · "))
                    add("Language" to t.language.orEmpty())
                    add("Name" to t.description.orEmpty())
                    if (t.channels > 0) add("Channels" to when (t.channels) { 1 -> "1 (mono)"; 2 -> "2 (stereo)"; 6 -> "6 (5.1)"; 8 -> "8 (7.1)"; else -> "${t.channels}" })
                    if (t.rate > 0) add("Sample rate" to "${t.rate} Hz")
                    if (t.bitrate > 0) add("Bitrate" to "${t.bitrate / 1000} kbps")
                }
            }
            val subs = tracks.filterIsInstance<IMedia.SubtitleTrack>()
            sec("Subtitles") {
                if (subs.isEmpty() && s.spuTracks.none { it.id >= 0 }) add("Subtitles" to "None")
                subs.forEachIndexed { i, t ->
                    add("Track ${i + 1}" + (if (t.id == s.spuTrack) " (shown)" else "") to listOf(t.language.orEmpty(), t.description.orEmpty(), t.codec?.trim()?.uppercase().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "—" })
                }
                s.spuTracks.filter { it.id >= 0 && subs.none { t -> t.id == it.id } }.forEach { add("Added" + (if (it.id == s.spuTrack) " (shown)" else "") to it.name) }
                if (s.spuDelayMs != 0L) add("Subtitle delay" to "${s.spuDelayMs} ms")
            }
            if (m != null) sec("Tags") {
                fun meta(k: Int, l: String) { try { m.getMeta(k)?.trim()?.let { add(l to it) } } catch (_: Exception) {} }
                meta(IMedia.Meta.Title, "Title"); meta(IMedia.Meta.Artist, "Artist"); meta(IMedia.Meta.Album, "Album")
                meta(IMedia.Meta.Date, "Date"); meta(IMedia.Meta.Genre, "Genre"); meta(IMedia.Meta.Description, "Description")
                meta(IMedia.Meta.EncodedBy, "Encoded by"); meta(IMedia.Meta.Copyright, "Copyright"); meta(IMedia.Meta.Language, "Language")
            }
            sec("Playback") {
                add("Position" to hms(s.time) + (if (s.length > 0) " of " + hms(s.length) else ""))
                if (kotlin.math.abs(s.rate - 1f) > 0.01f) add("Speed" to "%.2f×".format(java.util.Locale.US, s.rate))
                add("Sound output" to currentOutputName)
                if (s.audioDelayMs != 0L) add("Audio delay" to "${s.audioDelayMs} ms")
                m?.stats?.let { st ->
                    if (st.inputBitrate > 0) add("Data rate now" to "${Math.round(st.inputBitrate * 8000)} kbps")
                    if (st.readBytes > 0) add("Data read" to app.vlcinterface.player.NetGuard.fmt(st.readBytes.toLong()))
                    if (st.decodedVideo > 0) add("Frames decoded" to "${st.decodedVideo}")
                    if (st.displayedPictures > 0) add("Frames shown" to "${st.displayedPictures}")
                    add("Frames lost" to "${st.lostPictures}")
                    if (st.demuxCorrupted > 0) add("Damaged data blocks" to "${st.demuxCorrupted}")
                }
            }
        } catch (_: Exception) {
        } finally { m?.release() }
        return out
    }

    private fun codecName(c: String): String = when (c.lowercase()) {
        "h264", "avc1", "x264" -> "H.264 / AVC"; "hevc", "h265", "hvc1", "hev1" -> "H.265 / HEVC"; "av01", "av1" -> "AV1"
        "vp90", "vp9" -> "VP9"; "vp80", "vp8" -> "VP8"; "mp4v", "xvid", "divx", "dx50", "fmp4" -> "MPEG-4 Part 2 (Xvid / DivX)"
        "mpgv", "mp2v", "mpg2" -> "MPEG-2"; "mp1v" -> "MPEG-1"; "wmv3", "wmv2", "wmv1" -> "Windows Media Video"; "vc-1", "wvc1" -> "VC-1"
        "theo" -> "Theora"; "dvsd" -> "DV"; "mjpg" -> "Motion JPEG"; "h263" -> "H.263"
        "mp4a" -> "AAC"; "a52 ", "a52" -> "Dolby Digital (AC-3)"; "eac3" -> "Dolby Digital Plus (E-AC-3)"; "dts " , "dts" -> "DTS"
        "mpga", "mp3 ", "mp3" -> "MPEG audio (MP3)"; "opus" -> "Opus"; "vorb" -> "Vorbis"; "flac" -> "FLAC"; "truehd", "mlp " -> "Dolby TrueHD"
        "araw", "s16l", "s24l", "lpcm" -> "PCM (uncompressed)"; "alac" -> "ALAC"; "wma2", "wmap" -> "Windows Media Audio"
        "subt" -> "Text (SRT)"; "ssa " , "ssa" -> "SSA / ASS"; "spu " , "spu" -> "DVD"; "pgs " -> "Blu-ray (PGS)"; "tx3g" -> "MP4 text"
        else -> ""
    }

    private fun resName(w: Int, h: Int): String {
        val big = maxOf(w, h); val small = minOf(w, h)
        return when {
            big >= 7600 -> "8K"; big >= 3800 || small >= 2100 -> "4K UHD"; big >= 2500 || small >= 1400 -> "QHD 1440p"
            big >= 1900 || small >= 1000 -> "Full HD 1080p"; big >= 1260 || small >= 700 -> "HD 720p"
            small >= 560 -> "SD 576"; small >= 470 -> "SD 480"; small >= 350 -> "360p"; small > 0 -> "Low ${small}p"; else -> ""
        }
    }

    private fun ratioName(w: Int, h: Int): String {
        val r = w.toDouble() / h
        val known = listOf(1.0 to "1:1", 4.0 / 3 to "4:3", 3.0 / 2 to "3:2", 16.0 / 9 to "16:9", 1.85 to "1.85:1 (cinema)", 2.0 to "2:1",
            2.39 to "2.39:1 (cinemascope)", 9.0 / 16 to "9:16 (upright)", 3.0 / 4 to "3:4 (upright)")
        val best = known.minByOrNull { kotlin.math.abs(it.first - r) }!!
        return if (kotlin.math.abs(best.first - r) < 0.03) best.second else "%.2f:1".format(java.util.Locale.US, r)
    }

    /** Starts the current item again (a radio stream after the connection changed). */
    fun replayCurrent() { val s = _state.value; if (s.item != null) startItem(s.index, -1) }

    fun play(item: PlayItem, startAt: Long = -1, origin: Origin = Origin(if (item.isRadio) "radio" else "single", item.sourceId, item.uri, item.title)) =
        playQueue(listOf(item), 0, origin, startAt)

    /**
     * The rest of a list being loaded (2026-11-22 19:37), added at the end of the queue once its NAS items were checked —
     * not an addition by hand (today's session list isn't touched). Only while that list is still what's playing.
     */
    fun appendLoaded(items0: List<PlayItem>, origin: Origin) = main.post {
        val s = _state.value
        if (s.origin != origin || s.item == null) return@post
        val have = s.queue.map { qKey(it) }.toHashSet()
        val items = items0.filter { !nasOff(it) && readable(it) && have.add(qKey(it)) }
        if (items.isEmpty()) return@post
        orderedQueue = orderedQueue?.plus(items)
        fullList = (fullList + items.filter { !it.isRadio }).distinctBy { qKey(it) }
        _state.value = s.copy(queue = s.queue + items)
    }

    /**
     * "Add album to queue" (2026-11-28 01:18): every song of the folder [item] is in goes into the queue right after
     * [item], in the folder's order, without touching the song playing; songs already in the queue aren't doubled. The
     * album of the song playing: the order switches to Sequential, so the album plays on in order after it.
     */
    fun addAlbumToQueue(item: PlayItem) {
        bg.launch {
            val album = try { app.vlcinterface.sources.MusicLibrary.folderOf(context, item) } catch (_: Exception) { emptyList() }
                .filter { it.isMusic && readable(it) && !nasOff(it) }
            main.post {
                val s = _state.value
                val have = s.queue.map { qKey(it) }.toHashSet()
                val add = album.filter { have.add(qKey(it)) }
                if (add.isEmpty()) { try { app.vlcinterface.ui.ShortNote.hint("The whole album is already in the queue") } catch (_: Exception) {}; return@post }
                val at = (s.queue.indexOfFirst { qKey(it) == qKey(item) }.takeIf { it >= 0 } ?: s.index).coerceAtLeast(-1) + 1
                val q = s.queue.toMutableList().apply { addAll(at.coerceAtMost(size), add) }
                val playingAlbum = s.item?.let { qKey(it) == qKey(item) } == true
                if (playingAlbum && store.current.settings.shuffle) {
                    store.update { it.copy(settings = it.settings.copy(shuffle = false)) }
                    orderedQueue = null
                } else orderedQueue = orderedQueue?.plus(add)
                fullList = (fullList + add).distinctBy { qKey(it) }
                val idx = s.item?.let { c -> q.indexOfFirst { qKey(it) == qKey(c) } } ?: s.index
                _state.value = s.copy(queue = q, index = idx)
                try { app.vlcinterface.ui.ShortNote.show("${add.size} songs of the album added to the queue") } catch (_: Exception) {}
            }
        }
    }

    fun playNext(items0: List<PlayItem>) {
        // Unreadable files never go into the queue (01:08).
        if (items0.any { !readable(it) }) {
            if (items0.all { !readable(it) }) encryptedNote() else playNext(items0.filter { readable(it) })
            return
        }
        val s = _state.value
        if (s.item == null) { playQueue(items0, 0, Origin("single")); return }
        // Songs already in the queue aren't added twice.
        val have = s.queue.map { qKey(it) }.toHashSet()
        if (items0.isNotEmpty() && items0.all { nasOff(it) }) { nasOfflineToast(); return }
        val items = items0.filter { !nasOff(it) && have.add(qKey(it)) }
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

    /**
     * Playing a phone song (2026-11-22 07:40): when its music folder has no write access yet, the question for it comes
     * now, once per folder while the app runs, so the ReplayGain of this listening session can be written into the
     * files' tags. Not waited for (the song plays on); "Not now" isn't asked again until the app restarts.
     */
    private val rgWriteAsked = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun askRgWriteAccess(item: PlayItem) {
        if (!item.uri.startsWith("content://") || !store.current.settings.rgAutoFolder) return
        bg.launch {
            val fa = app.vlcinterface.sources.MusicFolderAccess
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (fa.covered(context, item)) null else fa.folderToAsk(context, item)
            } ?: return@launch
            if (fa.declinedFor(f) || !rgWriteAsked.add(f)) return@launch
            if (fa.ask.value == null) fa.ask.value = app.vlcinterface.sources.MusicFolderAccess.Ask(f, null) else rgWriteAsked.remove(f)   // another question open: next song
        }
    }

    private fun askRgScan(item: PlayItem) {
        if (!item.isMusic) return
        // Keyed by the file's own folder (not where it was played from: a pinned folder would have stood for every album in it).
        val key = if (item.uri.contains("://") && !item.uri.startsWith("content://")) app.vlcinterface.sources.Urls.parent(item.uri)
            else item.uri.substringBeforeLast('/') + "|" + item.folderKey
        if (!rgAsked.add(key)) return
        // Automatic ReplayGain (2026-11-21 12:01–12:03): phone songs and home-network songs — the whole folder is
        // scanned silently (only songs without any ReplayGain, track gain kept in the app; no notification, no
        // question), and a 5-second summary shows when it's done. Network songs reached through the VPN (away from
        // home) keep the old behaviour: the question, since scanning over the VPN is slow.
        val net = item.uri.contains("://") && !item.uri.startsWith("content://") && !item.uri.startsWith("file://")
        val overVpn = net && try { android.net.Uri.parse(item.uri).host?.let { h -> app.vlcinterface.sources.VpnLink.routeHost(h) != h } == true } catch (_: Exception) { false }
        if (store.current.settings.rgAutoFolder && !overVpn) {
            bg.launch {
                // The whole album, disc folders included (11:40: an album split in CD1 / CD2 was only scanned for one disc).
                val found = try { app.vlcinterface.sources.MusicLibrary.albumOf(context, item) } catch (_: Exception) { emptyList() }
                // Its folder couldn't be read now (server busy, network): tried again the next time a song of it plays.
                if (found.size <= 1) rgAsked.remove(key)
                val folder = found.ifEmpty { listOf(item) }
                val name = item.folderKey.ifEmpty { android.net.Uri.decode(item.uri).substringBeforeLast('/') }.trimEnd('/').substringAfterLast('/')
                    .ifEmpty { item.album.ifEmpty { "this folder" } }
                // ReplayGain per song — track gain (as asked at 12:01, confirmed 22:53).
                app.vlcinterface.sources.RgScanner.start(context, folder.filter { it.isMusic }, app.vlcinterface.sources.RgMode.TRACK,
                    // Written into the files' tags (2026-11-21 22:51 / 22:53), NAS and phone alike. The playing song is written when it
                    // ends; phone files: Android asks once per folder (declined → kept in the app); a read-only share keeps them in the app.
                    app.vlcinterface.sources.RgTarget.FILES,
                    rescanTagged = false, onlyWithoutRg = true, confirmTitle = name, silent = true)
            }
            return
        }
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
    /** Songs saved from the radio go into today's session playlist (a choice made by hand: the list is kept). */
    fun addToSession(items: List<PlayItem>) = session(null, items, manual = true)

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
        // Unreadable files never go into the queue (01:08).
        if (items0.any { !readable(it) }) {
            if (items0.all { !readable(it) }) { encryptedNote(); return 0 }
            return addToQueue(items0.filter { readable(it) })
        }
        val s = _state.value
        if (s.item == null) { playQueue(items0, 0, Origin("single")); return items0.distinctBy { qKey(it) }.size }
        val have = s.queue.map { qKey(it) }.toHashSet()
        if (items0.isNotEmpty() && items0.all { nasOff(it) }) { nasOfflineToast(); return 0 }
        val items = items0.filter { !nasOff(it) && have.add(qKey(it)) }
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

    private fun encryptedNote() = main.post { try { app.vlcinterface.ui.ShortNote.hint("Encrypted or unreadable file — can't be played") } catch (_: Exception) {} }

    /** The pop-up when an item can't be reached (2026-11-22 19:41): videos the "NAS is inaccessible" hint, songs as before. */
    private fun nasBlockedNote(item: PlayItem) {
        if (isVideo(item)) main.post { if (nasNoteOk()) try { app.vlcinterface.ui.ShortNote.hint("NAS is inaccessible") } catch (_: Exception) {} }
        else nasOfflineToast()
    }

    /**
     * In a video queue (19:42): from [i] on in direction [dir], the first item that can be played now (not on an unreachable
     * NAS), without going round; null when there's none.
     */
    private fun usableFrom(s: PlayerState, i: Int, dir: Int): Int? {
        var j = i
        while (j in s.queue.indices) { if (j != s.index && !nasOff(s.queue[j])) return j; j += dir }
        return null
    }

    fun jumpTo(i: Int) {
        val q0 = _state.value.queue
        if (i in q0.indices && nasOff(q0[i])) {
            nasBlockedNote(q0[i])
            // A video: on to the next item that can be played (a phone video), if there is one.
            if (isVideo(q0[i])) usableFrom(_state.value, i + 1, 1)?.let { sessionManualChoice(); startItem(it, -1) }
            return
        }
        if (i in _state.value.queue.indices) { sessionManualChoice(); startItem(i, -1) } }

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
            u.startsWith("smb://") -> jcifs.smb.SmbFile(app.vlcinterface.sources.VpnLink.route(Uri.decode(u)), app.vlcinterface.sources.SmbProbe.fileContext(item.smbVersion, item.user, item.password, item.domain)).use { it.exists() }
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
        if (!readable(item)) { encryptedNote(); return }
        if (nasOff(item)) { nasOfflineToast(); return }                // never a NAS item while the NAS can't be used (19:35)
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
        if (!readable(item)) { encryptedNote(); return }
        if (nasOff(item)) { nasOfflineToast(); return }                // never a NAS item while the NAS can't be used (19:35)
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

    /**
     * With two or more items, ⏭ / ⏮ and the queue going round never land on the item playing (19:15) — also when the same
     * file is in the queue more than once: from [i] on, in direction [dir], the first item that is another file.
     * Only the same file everywhere: [i] as it is.
     */
    private fun otherThanCurrent(s: PlayerState, i: Int, dir: Int): Int {
        val q = s.queue; val cur = s.item ?: return i
        if (q.size < 2 || i !in q.indices) return i
        var j = i
        repeat(q.size) {
            val c = q[j]
            if (j != s.index && (c.uri != cur.uri || c.startMs != cur.startMs)) return j
            j = ((j + dir) % q.size + q.size) % q.size
        }
        return i
    }

    fun next(): Boolean {
        sessionManualChoice()
        val s = _state.value
        val n = (nextIndex(s, manual = true) ?: return false).let { if (s.item?.isRadio == true) it else otherThanCurrent(_state.value, it, 1) }
        // A video kept in the queue whose NAS can't be reached now (19:41 / 19:42): the pop-up, and on to the next item that
        // can be played (a phone video); none: the video playing stays.
        _state.value.queue.getOrNull(n)?.let { t -> if (isVideo(t) && nasOff(t)) {
            nasBlockedNote(t)
            val u = usableFrom(_state.value, n + 1, 1) ?: return false
            startItem(u, -1); return true
        } }
        startItem(n, -1); return true
    }

    fun prev() {
        sessionManualChoice()
        val s = _state.value
        val item = s.item ?: return
        // Radio: previous station (round), never "back to the start".
        if (item.isRadio) { if (s.queue.size > 1) startItem(if (s.index <= 0) s.queue.size - 1 else s.index - 1, -1); return }
        // Videos (19:18: the circling is for songs and radio): as before — back to the start after 4 s, or on the first one.
        if (!item.isMusic) {
            if (mp.time > 4000 || s.index <= 0) { seekTo(if (item.startMs >= 0) item.startMs else 0); return }
            s.queue.getOrNull(s.index - 1)?.let { t -> if (nasOff(t)) {
                nasBlockedNote(t)
                usableFrom(s, s.index - 2, -1)?.let { startItem(it, -1) }            // the previous one that can be played
                return
            } }
            startItem(s.index - 1, -1); return
        }
        // The queue circles (2026-11-22 19:13): ⏮ always goes to the item before, from the first to the last.
        if (s.queue.size <= 1) { seekTo(if (item.startMs >= 0) item.startMs else 0); return }
        startItem(otherThanCurrent(s, if (s.index <= 0) s.queue.size - 1 else s.index - 1, -1), -1)
    }

    private fun nextIndex(s: PlayerState, manual: Boolean): Int? {
        // The queue is never used up (19:13): after the last item comes the first — by ⏭ and at the end of the last item —
        // so a listening session circles round the queue; Repeat one still repeats the item.
        // Songs only (19:18): videos don't go round (a series doesn't start over by itself); Repeat one still repeats.
        val repeat = store.current.settings.repeat.let { if (it == "one") "one" else if (s.item?.isMusic == true) "all" else "off" }
        if (!manual && repeat == "one") return s.index
        // Radio stations: next one in the list, round.
        if (manual && s.item?.isRadio == true && s.queue.size > 1) return (s.index + 1) % s.queue.size
        return when {
            s.index + 1 < s.queue.size -> s.index + 1
            // Random (19:15: random stays random): past the last item a fresh random order for the next round, not the
            // queue's first item again (and not starting with the item that just ended).
            repeat == "all" && store.current.settings.shuffle && s.queue.size > 1 && !isVideo(s.item) -> {
                val last = s.queue.getOrNull(s.index)
                var q = (orderedQueue ?: s.queue).shuffled()
                // Never the item just heard again (19:15), also when it's in the queue twice: the new round starts with
                // the first item that is another file.
                val k = q.indexOfFirst { it.uri != last?.uri || it.startMs != last.startMs }
                if (k > 0) q = q.drop(k) + q.take(k)
                _state.value = _state.value.copy(queue = q)
                0
            }
            // Sequential: round to the first item. A single song isn't looped (it ends; Repeat one loops it).
            repeat == "all" && s.queue.size > 1 -> 0
            else -> null
        }
    }

    // ---------- playback ----------

    private fun startItem(i0: Int, startAt: Long) {
        if (BatteryGuard.blocked()) return              // battery 10 % or less: nothing is played
        // An unreadable file still in a queue (from before 1.89): taken out, and the next one plays instead (01:08).
        run {
            val st = _state.value
            val q = st.queue
            if (q.isNotEmpty() && q.any { !readable(it) }) {
                val target = q.getOrNull(i0)
                val clean = q.filter { readable(it) }
                orderedQueue = orderedQueue?.filter { readable(it) }
                fullList = fullList.filter { readable(it) }
                if (clean.isEmpty()) { _state.value = st.copy(queue = emptyList(), index = -1); stop(); encryptedNote(); return }
                val ni = target?.let { t -> clean.indexOf(t).takeIf { it >= 0 } } ?: q.take(i0 + 1).count { readable(it) }.coerceAtMost(clean.size - 1)
                val curIdx = st.item?.let { c -> clean.indexOf(c) } ?: -1
                _state.value = st.copy(queue = clean, index = curIdx)
                if (target != null && !readable(target)) encryptedNote()
                return startItem(ni, if (target != null && readable(target)) startAt else -1)
            }
        }
        toneLeaving()                                   // loudness / treble changed during the last song: kept with it
        saveProgress()
        // ReplayGain values waiting for a file the player had open: written now that it moves on.
        app.vlcinterface.sources.RgScanner.flushPendingSoon(context)
        // A network file that isn't indexed yet: its folder is indexed now (08:19), in the background.
        _state.value.queue.getOrNull(i0)?.let { try { app.vlcinterface.sources.NetIndex.onPlayed(it) } catch (_: Exception) {} }
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
        if (pictureRecovered != item.uri) pictureRecovered = null      // a different video may be re-opened once again
        voutNow = 0                                                     // picture outputs are counted per item
        session(item, emptyList())
        loadPictureForFile(item)
        val wanted = store.current.subStyle
        if (wanted != styleInUse || rgSignature() != rgInUse) recreateEngine(wanted)
        setCredentials(item.user, item.password)
        triedBridge = false
        triedOtherWay = false; phoneByPath = true
        val oldPfd = currentPfd
        currentPfd = null
        // No Wi-Fi and mobile data not allowed yet: what needs internet waits for "Use NAS" on the player.
        if (item.isRadio) NetGuard.radioStarted(item)
        // Radio isn't NAS: it never waits for "Use NAS" (mobile data for radio follows its own Wi-Fi / mobile rules).
        if (!item.isRadio && !app.vlcinterface.sources.VpnLink.ignored(item.uri) &&
            (app.vlcinterface.sources.VpnLink.waitsForUse(item.uri) || NetGuard.blocked() && NetGuard.needsInternet(item.uri))) {
            stopping = true; mp.stop(); main.postDelayed({ stopping = false }, 300)
            _state.value = s0.copy(item = item, index = i, isPlaying = false, time = 0, length = item.durationMs, ended = false,
                metaArtist = item.artist, metaAlbum = item.album, artworkUrl = item.artwork, rgInfo = "",
                error = "${NetGuard.BLOCKED} away from home or on mobile data, network songs play after you press it")
            endHandled = true
            return
        }
        // "Not this session" (no VPN away from home): network songs are skipped at once.
        if (app.vlcinterface.sources.VpnLink.ignored(item.uri)) {
            stopping = true; mp.stop(); main.postDelayed({ stopping = false }, 300)
            _state.value = s0.copy(item = item, index = i, isPlaying = false, time = 0, length = item.durationMs, ended = false,
                metaArtist = item.artist, metaAlbum = item.album, artworkUrl = item.artwork, rgInfo = "",
                error = "Skipped ${item.title}: network songs off for this session")
            endHandled = false
            if (s0.queue.all { app.vlcinterface.sources.VpnLink.ignored(it.uri) }) return       // nothing else to play
            main.postDelayed({ if (_state.value.item === item && !endHandled) handleEnd(_state.value) }, 200)
            return
        }
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
        // Every item starts at normal speed: libVLC keeps the player's speed from one item to the next, so a video
        // watched at 1.5× made the next song play fast (time-stretched, with its warble) while 1× was shown.
        try { if (kotlin.math.abs(mp.rate - 1f) > 0.001f) mp.setRate(1f) } catch (_: Throwable) {}
        _state.value = s0.copy(item = item, index = i, isPlaying = false, time = 0, length = item.durationMs, videoW = 0, videoH = 0,
            audioTracks = emptyList(), spuTracks = emptyList(), audioDelayMs = 0, spuDelayMs = 0, rate = 1f, nowPlaying = "",
            metaArtist = item.artist, metaAlbum = item.album, artworkUrl = if (artBanned(item, item.artwork)) "" else item.artwork, buffering = 0f, error = "", ended = false,
            rgInfo = "", userPaused = false, songOver = false)
        // Loudness / treble: the song's (station's) own values at once, else the last ones; videos: none.
        val tone = if (item.isMusic || item.isRadio) toneFor(item) else Triple(0f, 0f, false)
        _state.value = _state.value.copy(loudDb = tone.first, trebleDb = tone.second, toneSaved = tone.third,
            radioPreDb = when {
                item.isRadio -> ToneStore.getPre(item.uri) ?: store.current.settings.radioPreLast
                item.isMusic -> ToneStore.getPre(item.uri) ?: store.current.settings.songPreLast
                else -> 0f
            })
        loadReplayGain(item, _state.value)
        // No picture in the file: looked up here too (folder, pictures store, online), so the notification and lock
        // screen get it even while the app is in the background.
        if (item.isMusic && (artBanned(item, item.artwork) || !item.artwork.startsWith("/") && !item.artwork.startsWith("file://") && !item.artwork.startsWith("content://"))) bg.launch {
            val r = try { app.vlcinterface.sources.CoverArt.forPlayer(context, item, item.artist, item.album, store.current.settings.coverDownload) } catch (_: Exception) { null }
            if (r?.bitmap != null) useDownloadedArtwork(item, "mem:" + item.uri)
        }
        NativeAudio.deepBuffer(item.isMusic || item.isRadio)
        // The sound is taken first (another app playing is paused by Android), then the volume reset — so the other app's
        // last moment isn't played at the start volume (2026-11-23 01:04).
        am.requestAudioFocus(focusRequest)
        volumeAfterIdle()
        radioSoundAt = System.currentTimeMillis() + 15_000           // a station gets 15 s to connect before the 20 s rule
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

    /** A phone file's real path, when Android gives one and it can be read; null otherwise. */
    /** The phone path of a video, for the in-app subtitle browser (null for network items or without a real path). */
    fun phonePathOf(item: PlayItem): String? = phonePath(item)
    private fun phonePath(item: PlayItem): String? = try {
        app.vlcinterface.sources.PhysicalPath.of(context, item).takeIf { it.startsWith("/") && java.io.File(it).canRead() }
    } catch (_: Exception) { null }

    /** "SD card not available" when the file is on a removable card that isn't mounted. */
    private fun cardMissing(item: PlayItem): Boolean {
        val p = try { app.vlcinterface.sources.PhysicalPath.of(context, item) } catch (_: Exception) { return false }
        val m = Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/").find(p) ?: return false
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
        val mounted = try { sm.storageVolumes.any { it.uuid.equals(m.groupValues[1], true) && it.state == android.os.Environment.MEDIA_MOUNTED } } catch (_: Exception) { true }
        return !mounted
    }

    private fun makeMedia(item: PlayItem, s: PlayerState, useBridge: Boolean): Media {
        // Looked up once: the cache may drop the file between two lookups.
        val cached = app.vlcinterface.sources.Prefetch.localFor(item.uri)
        val media = when {
            // Phone / SD card files: by their real path (like VLC's own app); the file handle only as the fallback.
            item.uri.startsWith("content://") -> {
                if (cardMissing(item)) throw java.io.IOException("SD card not available")
                val path = if (phoneByPath) phonePath(item) else null
                if (path != null) Media(libVlc, path) else {
                    val pfd = try { context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r") } catch (e: SecurityException) {
                        throw java.io.IOException("No access to this folder any more (SD card changed?): pick the folder again")
                    }
                    currentPfd = pfd
                    if (pfd != null) Media(libVlc, pfd.fileDescriptor) else Media(libVlc, Uri.parse(item.uri))
                }
            }
            useBridge && item.uri.startsWith("smb://") ->
                Media(libVlc, Uri.parse(SmbBridge.urlFor(item.uri, item.smbVersion.ifEmpty { "SMB1" }, item.user, item.password, item.domain)))
            // A network song cached ahead (the next one while the last played): played from the phone.
            cached != null -> Media(libVlc, cached.absolutePath)
            // SFTP: streamed by the app's own SFTP client (libVLC's SSH library is too old for many servers).
            item.uri.startsWith("sftp://") -> {
                val src = store.current.sources.firstOrNull { it.id == item.sourceId }
                Media(libVlc, Uri.parse(app.vlcinterface.sources.Sftp.urlFor(item.uri, item.user.ifEmpty { src?.user ?: "" }, item.password.ifEmpty { src?.password ?: "" })))
            }
            // LAN servers: their VPN address while away from home with the VPN on.
            else -> Media(libVlc, Uri.parse(app.vlcinterface.sources.VpnLink.route(item.uri)))
        }
        val ps = store.current.settings
        // Settings → Video → Decoding: automatic (hardware, software when it can't), hardware only, software only.
        val isVideo = !item.isMusic && !item.isRadio
        // Automatic: chosen per file from its encoding (VideoAuto): software only for files the phone's hardware can't decode.
        // A choice made by hand for this file wins; then the general setting; Automatic uses what was learnt for the file.
        val decSet = if (!isVideo) "auto" else VideoAuto.manualDecoder(item.uri).ifEmpty { ps.videoDecoder }
        val dec = if (decSet == "auto" && isVideo && VideoAuto.software(item.uri)) "sw" else decSet
        if (isVideo) decoderNow = if (dec == "sw") "sw" else "hw"
        when (dec) {
            "hw" -> media.setHWDecoderEnabled(true, true)
            "sw" -> media.setHWDecoderEnabled(false, false)
            else -> media.setHWDecoderEnabled(true, false)
        }
        // Settings → Video → Deinterlacing: Automatic = by the video's encoding (TV recordings / MPEG-2 / DV), On, Off.
        // The filter treats only fields marked interlaced and needs frames it can touch (no direct rendering).
        readFileRotation(item)
        val diSet = if (!isVideo) "off" else VideoAuto.manualDeinterlace(item.uri).ifEmpty { ps.videoDeinterlace }
        deintOn = isVideo && (diSet == "on" || (diSet == "auto" && VideoAuto.deinterlace(item.uri)))
        // The picture format chosen for this file (Fit when none).
        // Every video starts in Fit (2026-11-21 16:25); a reopen of the same video at the same spot (format check,
        // decoder, subtitle format…) keeps the format chosen meanwhile.
        if (isVideo) { if (item.uri != scaleFor) scale = MediaPlayer.ScaleType.SURFACE_BEST_FIT; scaleFor = item.uri }
        tracksRestored = !isVideo; audioRestored = !isVideo; spuRestored = !isVideo
        // Network videos (2026-11-21 16:09): the same-name subtitle next to the video is loaded by the app instead of
        // libVLC, as a copy with its colour codes taken out (so the chosen subtitle colours apply).
        val netVideo = isVideo && (item.uri.startsWith("smb://") || item.uri.startsWith("http"))
        if (netVideo) {
            media.addOption(":no-sub-autodetect-file")
            bg.launch {
                // Every file named after the video ("…en.srt", "…en.2.srt" too): the preferred language switched on, the rest as choices.
                val refs = try { app.vlcinterface.sources.SubtitleFiles.findSidecars(context, item) } catch (_: Exception) { emptyList() }
                refs.forEachIndexed { i, r -> if (_state.value.item?.uri == item.uri) addSubtitleFile(Uri.fromFile(r.local), select = i == 0) }
            }
        }
        // Subtitles one subfolder level down (2026-11-21 21:11, e.g. "Movie/Subs/2_English.srt"): added as choices in the
        // subtitle pop-up and Subs/Change (not switched on by themselves). Network videos and phone videos with a path.
        if (isVideo) bg.launch {
            val pp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (netVideo) null else phonePath(item) }
            val found = try { app.vlcinterface.sources.SubtitleFiles.findInSubfolders(context, item, pp) } catch (_: Exception) { emptyList() }
            for (r in found) { if (_state.value.item?.uri != item.uri) break; addSubtitleFile(Uri.fromFile(r.local), select = false) }
            if (found.isNotEmpty()) android.util.Log.i("PlayerController", "${found.size} subtitle file(s) in subfolders of ${item.title}: " + found.joinToString { it.remoteName })
        }
        if (deintOn) {
            media.addOption(":deinterlace=-1"); media.addOption(":deinterlace-mode=yadif2x")   // full motion (every field), best quality
            media.addOption(":no-mediacodec-dr")
        } else if (isVideo) media.addOption(":deinterlace=0")
        // Hue/saturation need decoded frames the filter can touch: hardware decoding without direct rendering.
        pictureFilterOn = !item.isMusic && !item.isRadio && (!pictureNeutral(ps.pictureHue, ps.pictureSat) || ps.pictureHighlights > 0.01f)
        // Rotate button: the picture itself turned (90° steps) for a video filmed / stored the wrong way, kept per file.
        videoRot = if (isVideo) (VideoAuto.int(item.uri, "rot") ?: 0).let { ((it % 360) + 360) % 360 } else 0
        val filters = buildList { if (pictureFilterOn) add("adjust"); if (videoRot != 0) add("transform") }
        if (filters.isNotEmpty()) {
            media.addOption(":no-mediacodec-dr")
            media.addOption(":video-filter=" + filters.joinToString(":"))
        }
        if (videoRot != 0) media.addOption(":transform-type=$videoRot")
        if (pictureFilterOn) {
            val (c, b, g) = highlightTone(ps.pictureHighlights)
            media.addOption(":hue=${ps.pictureHue}")
            media.addOption(":saturation=${ps.pictureSat}")
            media.addOption(":contrast=$c")
            media.addOption(":brightness=$b")
            media.addOption(":gamma=$g")
        }
        // Bigger read-ahead so a busy phone or network never runs the decoder dry (smooth playback):
        // songs keep 3 s from the network / 1.5 s from the phone; videos 2.5 s / 1 s (seeking stays quick).
        when {
            // Radio as an alarm: live, no network buffer.
            item.isRadio -> media.addOption(if (alarmLive) ":network-caching=0" else ":network-caching=3000")
            item.isMusic -> { media.addOption(":network-caching=3000"); media.addOption(":file-caching=1500") }
            else -> { media.addOption(":network-caching=2500"); media.addOption(":file-caching=1000") }
        }
        if (item.startMs >= 0) media.addOption(":start-time=${item.startMs / 1000.0}")
        if (item.endMs > 0) media.addOption(":stop-time=${item.endMs / 1000.0}")
        if (item.isMusic) {
            // libVLC's own ReplayGain only with libVLC's output; the app's output applies it itself (never twice).
            if (store.current.settings.rgMode != "off" && !appOutputWanted()) media.addOption(":audio-replay-gain-mode=${rgModeFor(s)}")
            else media.addOption(":audio-replay-gain-mode=none")
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
    /** The subtitle format in Settings differs from the one the engine runs with. */
    fun styleChanged(): Boolean = styleInUse != null && store.current.subStyle != styleInUse

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
        else { if (BatteryGuard.blocked()) return; am.requestAudioFocus(focusRequest); volumeAfterIdle(); _state.value = _state.value.copy(userPaused = false); mp.play() }
    }
    fun resume() { if (!mp.isPlaying && _state.value.item != null && !BatteryGuard.blocked()) { am.requestAudioFocus(focusRequest); volumeAfterIdle(); _state.value = _state.value.copy(userPaused = false); mp.play() } }
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
            nm.createNotificationChannel(android.app.NotificationChannel("sleep_quiet", "Sleep timer", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
            val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
            nm.notify(7301, android.app.Notification.Builder(context, "sleep_quiet").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
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

    /**
     * Track switches go to libVLC from their own thread: libVLC waits for the opening file (a slow network read can
     * take many seconds) and, done from a tap, that wait froze the screen until Android said "not responding".
     * The choice shows at once; the real list is read back when libVLC is done.
     */
    private val ops by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-ops").apply { isDaemon = true } } }
    private fun trackOp(block: () -> Unit) {
        ops.execute {
            try { block() } catch (_: Throwable) {}
            main.post { try { refreshTracks() } catch (_: Throwable) {} }
        }
    }
    fun selectAudio(id: Int) {
        _state.value = _state.value.copy(audioTrack = id)
        keepTrack("a", id, _state.value.audioTracks)
        trackOp { mp.setAudioTrack(id) }
    }
    fun selectSpu(id: Int) {
        _state.value = _state.value.copy(spuTrack = id)
        keepTrack("s", id, _state.value.spuTracks)
        trackOp { mp.setSpuTrack(id) }
    }

    /** The audio / subtitle track chosen by hand is kept for this video file. */
    private fun keepTrack(k: String, id: Int, list: List<Track>) {
        val item = _state.value.item ?: return
        if (item.isMusic || item.isRadio) return
        VideoAuto.setInt(item.uri, "${k}Id", id)
        VideoAuto.setStr(item.uri, "${k}Name", list.firstOrNull { it.id == id }?.name ?: "")
    }

    /** Tracks kept for this file put back once its tracks are known (by name first, then by number). */
    private var tracksRestored = true
    private var audioRestored = true
    private var spuRestored = true
    private fun restoreTracks() {
        if (tracksRestored) { audioRestored = true; spuRestored = true; return }
        val s = _state.value; val item = s.item ?: return
        fun pick(k: String, list: List<Track>): Int? {
            val id = VideoAuto.int(item.uri, "${k}Id") ?: return null
            val name = VideoAuto.str(item.uri, "${k}Name")
            return list.firstOrNull { name.isNotEmpty() && it.name == name }?.id ?: list.firstOrNull { it.id == id }?.id ?: if (id < 0) id else null
        }
        // Each kind once its tracks are listed (they arrive one by one while the file opens).
        if (!audioRestored && s.audioTracks.isNotEmpty()) { audioRestored = true; pick("a", s.audioTracks)?.let { if (it != s.audioTrack) { val t = it; trackOp { mp.setAudioTrack(t) } } } }
        if (!spuRestored && s.spuTracks.any { it.id >= 0 }) { spuRestored = true; pick("s", s.spuTracks)?.let { if (it != s.spuTrack) { val t = it; trackOp { mp.setSpuTrack(t) } } } }
        if (audioRestored && spuRestored) tracksRestored = true
    }

    /** [select] false: only added as a choice (subtitles found in subfolders), not switched on. */
    fun addSubtitleFile(uri: Uri, keep: Boolean = false, select: Boolean = true) {
        // Colour codes taken out (a cleaned copy; the original file is never changed); the original if it can't be read.
        val st = store.current.subStyle
        val before = _state.value.spuTracks.map { it.id }.toSet()
        bg.launch {
            val use = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.vlcinterface.sources.SubColors.cleanedCopy(context, uri, st.color, st.outlineColor)
            } ?: uri
            ops.execute {
                try { mp.addSlave(IMedia.Slave.Type.Subtitle, use, select || keep) } catch (_: Throwable) {}
                main.postDelayed({
                    refreshTracks()
                    // [keep]: the new track is shown and saved silently as this video's subtitles.
                    if (keep) _state.value.spuTracks.filter { it.id >= 0 && it.id !in before }.maxByOrNull { it.id }?.let { selectSpu(it.id) }
                }, 800)
            }
        }
    }

    /** The video [scale] belongs to (a new video starts in Fit). */
    private var scaleFor = ""

    /** Zoom mode chosen in the player (kept across items, rotation and engine restarts). */
    var scale: MediaPlayer.ScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        private set

    /**
     * The player sizes the video view itself to the chosen format, as large as the area allows (small videos are
     * enlarged until an edge is reached); libVLC then only stretches the picture over its whole view.
     * Crop is the exception: the view keeps the whole area and libVLC zooms in.
     */
    // libVLC sizes the picture itself in every format (as Crop always did): it knows the picture's real shape after
    // its own rotation and pixel aspect, and fits it to the video view's size at that moment. The app only stretching
    // a box of its own computed shape (Fill inside it) gave wrong proportions whenever the two disagreed — in
    // landscape for every format but Crop. 1:1 grows to the edges like Fit (libVLC's "original size" would leave
    // small videos small).
    private val vlcScale: MediaPlayer.ScaleType
        get() = if (scale == MediaPlayer.ScaleType.SURFACE_ORIGINAL) MediaPlayer.ScaleType.SURFACE_BEST_FIT else scale

    fun setScale(s: MediaPlayer.ScaleType) {
        scale = s; mp.setVideoScale(vlcScale); refreshSurface()
        // Kept for this video file (Fit, the default, isn't stored).
        _state.value.item?.takeIf { !it.isMusic && !it.isRadio }?.let { VideoAuto.setStr(it.uri, "scale", if (s == MediaPlayer.ScaleType.SURFACE_BEST_FIT) "" else s.name) }
    }

    /** Re-fits the picture to the current size of the video area (rotation, split screen, PiP, new video size). */
    fun refreshSurface() {
        if (attachedLayout == null) return
        main.post {
            try { mp.updateVideoSurfaces() } catch (_: Throwable) {}
            try { mp.setVideoScale(vlcScale) } catch (_: Throwable) {}
        }
        verifyGen++
        val gen = verifyGen
        main.postDelayed({ verifySurface(gen, 0) }, 300)
    }

    // ---------- picture size self-check ----------
    // The format analysis (21 Nov 03:18, 60 photos) showed libVLC sizes the picture right, but now and then a new
    // size was never applied: libVLC sets it while Android is already laying the screen out, and that request can be
    // dropped, so the picture kept the previous format's / orientation's size (8 of 60 photos; 1–2 % off or the
    // wrong format entirely). Now the video surface is measured after every change and, while it doesn't have the
    // size the format calls for, the sizing is applied again and a fresh layout requested (up to 6 times).
    private var verifyGen = 0

    /** The video surface inside libVLC's view: the biggest visible SurfaceView (the other one carries subtitles). */
    fun videoSurfaceView(): android.view.SurfaceView? {
        val found = ArrayList<android.view.SurfaceView>()
        fun walk(v: android.view.View) {
            if (v is android.view.SurfaceView && v.visibility == android.view.View.VISIBLE) found += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        attachedLayout?.let { walk(it) }
        return found.maxByOrNull { it.width.toLong() * it.height }
    }

    /** Size the video surface should have in a W × H video view for the chosen format; null when unknown. */
    fun expectedSurface(w: Int, h: Int): Pair<Int, Int>? {
        val s = _state.value
        if (w <= 0 || h <= 0 || s.videoW <= 0 || s.videoH <= 0) return null
        val a = s.videoW.toDouble() / s.videoH
        fun fit(ar: Double) = if (w.toDouble() / h > ar) Pair((h * ar).toInt(), h) else Pair(w, (w / ar).toInt())
        return when (vlcScale) {
            MediaPlayer.ScaleType.SURFACE_FILL -> Pair(w, h)
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN -> if (w.toDouble() / h > a) Pair(w, (w / a).toInt()) else Pair((h * a).toInt(), h)
            MediaPlayer.ScaleType.SURFACE_16_9 -> fit(16.0 / 9)
            MediaPlayer.ScaleType.SURFACE_4_3 -> fit(4.0 / 3)
            else -> fit(a)
        }
    }

    private fun verifySurface(gen: Int, tries: Int) {
        if (gen != verifyGen) return                                   // a newer change is being checked
        val layout = attachedLayout ?: return
        val it0 = _state.value.item ?: return
        if (it0.isMusic || it0.isRadio || voutNow <= 0) return
        val sv = videoSurfaceView() ?: return
        val want = expectedSurface(layout.width, layout.height) ?: return
        val okW = kotlin.math.abs(sv.width - want.first) <= maxOf(3, want.first / 100)
        val okH = kotlin.math.abs(sv.height - want.second) <= maxOf(3, want.second / 100)
        if (okW && okH) return
        // Backstop (23:42): still not the size the format calls for after two re-applies → the surfaces are given that size
        // here, centred in the video view. libVLC re-sizes only when its frame changes, and this doesn't change the frame,
        // so there is no back-and-forth.
        if (tries >= 2 && sv.width < want.first * 0.9 && sv.height < want.second * 0.9) {   // the "drawn small" case only
            try {
                // The surfaces' own frame back to the whole video view, if libVLC had shrunk it.
                (sv.parent as? android.view.View)?.takeIf { it !== layout }?.let { f ->
                    val flp = f.layoutParams
                    if (flp != null && (flp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT || flp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT)) {
                        flp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT; flp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT; f.layoutParams = flp
                    }
                }
                val all = ArrayList<android.view.SurfaceView>()
                fun walk(v: android.view.View) { if (v is android.view.SurfaceView) all += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
                walk(layout)
                all.forEach { v ->
                    val lp = v.layoutParams ?: return@forEach
                    if (lp.width != want.first || lp.height != want.second) {
                        lp.width = want.first; lp.height = want.second
                        if (lp is android.widget.FrameLayout.LayoutParams) lp.gravity = android.view.Gravity.CENTER
                        v.layoutParams = lp
                    }
                }
                android.util.Log.i("PlayerController", "picture sized by the app: ${want.first}x${want.second} (libVLC gave ${sv.width}x${sv.height})")
            } catch (_: Throwable) {}
        }
        if (tries >= 6) {
            android.util.Log.w("PlayerController", "picture size still ${sv.width}x${sv.height}, wanted ${want.first}x${want.second} (${scale.name})")
            return
        }
        try { mp.updateVideoSurfaces() } catch (_: Throwable) {}
        try { mp.setVideoScale(vlcScale) } catch (_: Throwable) {}
        // A fresh layout pass outside the current one, so the new size is really applied this time.
        main.post { try { sv.requestLayout(); layout.requestLayout(); (layout.parent as? android.view.View)?.requestLayout() } catch (_: Throwable) {} }
        main.postDelayed({ verifySurface(gen, tries + 1) }, 350)
    }

    // ---------- sync offsets ----------

    fun setSpuDelay(ms: Long) {
        _state.value = _state.value.copy(spuDelayMs = ms)
        ops.execute { try { mp.setSpuDelay(ms * 1000) } catch (_: Throwable) {} }
    }

    fun setAudioDelay(ms: Long) {
        _state.value = _state.value.copy(audioDelayMs = ms)
        ops.execute { try { mp.setAudioDelay(ms * 1000) } catch (_: Throwable) {} }
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
        if (spu != 0L) setSpuDelay(spu)
        if (!item.isMusic && !item.isRadio) { applyAudioSync(); return }
        val aud = st.audioOffsets["file:${item.uri}"] ?: st.audioOffsets["device:$currentOutputName"] ?: 0L
        if (aud != 0L) setAudioDelay(aud)
    }

    // ---------- video audio sync (Bluetooth), 2026-11-21 12:17 ----------
    // Kept for the sound device AND the video file together ("dev:<device>|file:<uri>"), found again when that video
    // plays on that device. Other videos played on the same device during this run of the app start from the last
    // value set (the device's delay is usually the same), until one of them gets its own.
    private val sessionSync = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun syncKey(uri: String) = "dev:$currentOutputName|file:$uri"

    /** Where the video's sync value comes from: "this video on <device>", "this session", "saved earlier", "". */
    var audioSyncFrom: String = ""
        private set

    /** Puts the right sync value on the playing video (start, or the sound device changed). */
    fun applyAudioSync() {
        val item = _state.value.item ?: return
        if (item.isMusic || item.isRadio) return
        val st = store.current
        val own = st.audioOffsets[syncKey(item.uri)]
        val sess = sessionSync[currentOutputName]
        val old = st.audioOffsets["file:${item.uri}"] ?: st.audioOffsets["device:$currentOutputName"]
        val v = own ?: sess ?: old ?: 0L
        audioSyncFrom = when { own != null -> "this video on this device"; sess != null -> "this session"; old != null -> "saved earlier"; else -> "" }
        setAudioDelay(v)
        // Carried over from the session (2026-11-21 12:21): if the video runs fine with it — not changed after 30 s of
        // playing, or played to its end before that — the value is kept for this video on this device too.
        if (own == null && sess != null) {
            adoptSync = Triple(item.uri, currentOutputName, v)
            main.postDelayed({ adoptSessionSync() }, 30_000)
        } else adoptSync = null
    }

    /** The session value a video started with, waiting to be kept for it: uri, device, value. */
    @Volatile private var adoptSync: Triple<String, String, Long>? = null

    /** Keeps the session value for the video that ran fine with it (same video, same device, value not changed). */
    private fun adoptSessionSync() {
        val (uri, dev, v) = adoptSync ?: return
        val s = _state.value
        if (s.item?.uri != uri || currentOutputName != dev || s.audioDelayMs != v) { if (s.item?.uri != uri) adoptSync = null; return }
        adoptSync = null
        val key = "dev:$dev|file:$uri"
        if (store.current.audioOffsets[key] == null) store.update { st -> st.copy(audioOffsets = st.audioOffsets + (key to v)) }
        audioSyncFrom = "this video on this device"
    }

    /** Sets the sync of the playing video and keeps it for this device + this video, and for this session. */
    fun setAudioSync(ms: Long, save: Boolean) {
        val item = _state.value.item ?: return
        setAudioDelay(ms)
        adoptSync = null                                   // changed by hand: its own value is saved below
        if (!save) return
        sessionSync[currentOutputName] = ms
        audioSyncFrom = "this video on this device"
        val key = syncKey(item.uri)
        store.update { st -> st.copy(audioOffsets = st.audioOffsets + (key to ms)) }
    }

    // ---------- views ----------

    fun attach(layout: VLCVideoLayout) {
        if (attachedLayout === layout) return
        if (attachedLayout != null) mp.detachViews()
        attachedLayout = layout
        mp.attachViews(layout, null, true, false)
        refreshSurface()
        checkPicture("view attached")
    }

    /** Fresh surfaces for the same view (after it was off screen). */
    fun reattach(layout: VLCVideoLayout) {
        if (attachedLayout !== layout) return
        main.post {
            try { mp.detachViews(); mp.attachViews(layout, null, true, false) } catch (_: Throwable) {}
            refreshSurface()
            checkPicture("view back on screen")
        }
    }

    // ---------- picture recovery ----------
    // libVLC makes the picture output once, when the video starts. If the video view isn't there at that moment
    // (the video player screen still opening, e.g. after a song in the same list) or its surface is taken away while
    // playing, libVLC logs "can't get Video Surface" and the video goes on with sound only. The picture is then
    // brought back by re-opening the video at the same spot, once per video.
    private var pictureRecovered: String? = null
    private var voutNow = 0

    private fun checkPicture(why: String, delayMs: Long = 700) {
        main.postDelayed({
            val s = _state.value; val item = s.item ?: return@postDelayed
            if (item.isMusic || item.isRadio || attachedLayout == null || stopping || endHandled) return@postDelayed
            val count = voutNow
            if (count > 0 || !(mp.isPlaying || s.buffering < 100f)) return@postDelayed
            if (pictureRecovered == item.uri) return@postDelayed
            pictureRecovered = item.uri
            android.util.Log.w("PlayerController", "video without picture ($why): re-opening at the same spot")
            ServerLog.log("video", "play", "VOUT", "", false, "Picture missing ($why) for ${item.title}: re-opened at the same spot")
            reloadVideo()
        }, delayMs)
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
        val n0 = nextIndex(_state.value, manual = false) ?: if (wrap && _state.value.queue.isNotEmpty()) 0 else null
        // Repeat one plays the same item on purpose; otherwise never the same file again with 2+ items (19:15).
        var n = if (n0 == null || store.current.settings.repeat == "one" || _state.value.item?.isRadio == true) n0 else otherThanCurrent(_state.value, n0, 1)
        // The next video's NAS can't be reached (19:42): the pop-up, and on to the next video that can be played.
        n?.let { k -> _state.value.queue.getOrNull(k)?.let { t -> if (isVideo(t) && nasOff(t)) { nasBlockedNote(t); n = usableFrom(_state.value, k + 1, 1) } } }
        val nn = n
        if (nn != null) main.post { startItem(nn, -1) }
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
                // A video that started without its picture (no view / no surface at that moment) is re-opened once.
                if (s.item != null && !s.item.isMusic && !s.item.isRadio) checkPicture("video started", 1_500)
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
            MediaPlayer.Event.EndReached -> { adoptSessionSync(); handleEnd(s) }
            MediaPlayer.Event.TimeChanged -> { lastTick = System.currentTimeMillis(); _state.value = s.copy(time = ev.timeChanged) }
            MediaPlayer.Event.LengthChanged -> _state.value = s.copy(length = ev.lengthChanged)
            MediaPlayer.Event.Buffering -> _state.value = s.copy(buffering = ev.buffering)
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.Vout -> {
                val before = voutNow; voutNow = ev.voutCount
                refreshTracks(); refreshSurface()
                // The picture went away while the video plays on (surface lost): bring it back.
                if (before > 0 && voutNow == 0) checkPicture("picture output lost", 1_500)
            }
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
                } else if (item != null && item.uri.startsWith("content://") && !triedOtherWay) {
                    // Phone / SD card file libVLC couldn't open: once more the other way (path ↔ file handle).
                    triedOtherWay = true
                    val way = if (phoneByPath) "its path" else "a file handle"
                    phoneByPath = !phoneByPath
                    ServerLog.log("phone", "play", "FILE", "", false, "libVLC could not open ${item.title} by $way, retrying the other way",
                        app.vlcinterface.sources.PhysicalPath.of(context, item))
                    main.post {
                        stopping = true
                        val old = currentPfd; currentPfd = null
                        try {
                            val m = makeMedia(item, _state.value, useBridge = false)
                            mp.setMedia(m); m.release(); mp.play()
                        } catch (e: Exception) {
                            _state.value = _state.value.copy(isPlaying = false, error = "Can't open ${item.title}: ${e.message ?: e.javaClass.simpleName}")
                        }
                        try { old?.close() } catch (_: Exception) {}
                        main.postDelayed({ stopping = false }, 500)
                    }
                } else if (item != null && !item.isMusic && !item.isRadio && attachedLayout != null && VideoAuto.manualDecoder(item.uri).ifEmpty { store.current.settings.videoDecoder } == "auto" &&
                    !VideoAuto.software(item.uri) && VideoAuto.mayReopen(item.uri)) {
                    // Hardware decoder failed on this video: once more with the software decoder (kept for this file).
                    VideoAuto.markSoftware(item.uri)
                    val at = mp.time.coerceAtLeast(0)
                    main.post { startItem(_state.value.index, at) }
                } else {
                    if (item != null && item.uri.startsWith("content://")) ServerLog.log("phone", "play", "FILE", "", false,
                        "libVLC could not open ${item.title} (by path and by file handle)" + if (cardMissing(item)) ": SD card not available" else "",
                        app.vlcinterface.sources.PhysicalPath.of(context, item))
                    // A phone file that can't be opened either way (card present): unreadable — hidden from now on (01:08).
                    if (item != null && item.uri.startsWith("content://") && !cardMissing(item)) {
                        app.vlcinterface.sources.Ignore.markUnreadable(item.uri)
                        encryptedNote()
                    }
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
                artworkUrl = s.artworkUrl.ifEmpty { if (s.item?.let { artBanned(it, art) } == true) "" else art },
            )
        } catch (_: Exception) {
        }
    }

    /** Rotate button: how far the playing video's picture is turned (0, 90, 180, 270), kept per file. */
    var videoRot = 0
        private set

    /** Rotate button: turns the picture 90° clockwise (a video filmed the wrong way); reopens at the same spot. */
    fun rotateVideo() {
        val item = _state.value.item ?: return
        if (item.isMusic || item.isRadio) return
        val next = (videoRot + 90) % 360
        if (next == 0) VideoAuto.edit0(item.uri, "rot") else VideoAuto.setInt(item.uri, "rot", next)
        reloadVideo()
    }

    /** Rotation stored in the playing phone video (0 / 90 / 180 / 270), read with Android's own reader; null = unknown. */
    @Volatile private var fileRot: Int? = null

    /** Phone videos: the recorded rotation and size, read once when the video starts (off the main thread). */
    private fun readFileRotation(item: PlayItem) {
        fileRot = null
        if (item.isMusic || item.isRadio) return
        val u = item.uri
        if (!(u.startsWith("content://") || u.startsWith("file://") || u.startsWith("/"))) return
        Thread {
            val r = try {
                android.media.MediaMetadataRetriever().use { mmr ->
                    if (u.startsWith("/")) mmr.setDataSource(u) else mmr.setDataSource(context, Uri.parse(u))
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                }
            } catch (_: Exception) { null }
            if (r != null && _state.value.item?.uri == u) { fileRot = r; main.post { refreshTracks() } }
        }.start()
    }

    /** Deinterlacing applied to the playing video. */
    private var deintOn = false
    /** Decoder used for the playing video: "hw" (hardware, software only if libVLC must) or "sw". */
    private var decoderNow = "hw"

    /**
     * What the playing video uses now (for Settings → Video): decoder, deinterlacing and its encoding, or null when
     * no video is playing.
     */
    /** Video format check: the video view in use (null when no video player is open). */
    val videoLayout: VLCVideoLayout? get() = attachedLayout

    /** Video format check: everything that decides the picture's size and shape, as label → value. */
    fun formatDiag(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val s = _state.value
        out += "File" to (s.item?.title ?: "-")
        val vt = try { mp.currentVideoTrack } catch (_: Throwable) { null }
        if (vt != null) {
            out += "libVLC track size" to "${vt.width} × ${vt.height}"
            out += "Pixel aspect (SAR)" to "${vt.sarNum}:${vt.sarDen}"
            out += "libVLC orientation flag" to "${vt.orientation} (0 TopLeft … 4 LeftTop, 5 LeftBottom, 6 RightTop, 7 RightBottom)"
            val dw = if (vt.sarNum > 0 && vt.sarDen > 0) vt.width.toDouble() * vt.sarNum / vt.sarDen else vt.width.toDouble()
            out += "Track aspect (SAR applied, not turned)" to (if (vt.height > 0) "%.4f".format(java.util.Locale.US, dw / vt.height) else "-")
        } else out += "libVLC track" to "none"
        out += "File rotation (Android reader)" to (fileRot?.toString() ?: "unknown")
        out += "Rotate button" to "$videoRot°"
        out += "App picture size (videoW × videoH)" to "${s.videoW} × ${s.videoH}" +
            if (s.videoW > 0 && s.videoH > 0) " (aspect %.4f)".format(java.util.Locale.US, s.videoW.toDouble() / s.videoH) else ""
        out += "Format chosen" to scale.name
        out += "Format given to libVLC" to vlcScale.name
        out += "libVLC aspect ratio" to (try { mp.aspectRatio ?: "(none)" } catch (_: Throwable) { "?" })
        out += "libVLC scale" to (try { "%.4f".format(java.util.Locale.US, mp.scale) } catch (_: Throwable) { "?" })
        out += "Decoder" to decoderNow
        return out
    }

    data class VideoNow(val software: Boolean, val deinterlace: Boolean, val codec: String, val w: Int, val h: Int)
    fun videoNow(): VideoNow? {
        val it0 = _state.value.item ?: return null
        if (it0.isMusic || it0.isRadio) return null
        val vt = try { mp.currentVideoTrack } catch (_: Throwable) { null }
        return VideoNow(decoderNow == "sw", deintOn, vt?.codec?.trim().orEmpty(), vt?.width ?: 0, vt?.height ?: 0)
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
            // Turned 90° / 270° when recorded (phone videos): the file's own rotation as Android reads it (phone
            // files), else the rotation flag libVLC reports — LeftTop…RightBottom.
            val turned = fileRot?.let { it == 90 || it == 270 } ?: (vt.orientation >= 4)
            if (turned && dw > dh) { val t = dw; dw = dh; dh = t }
            if (videoRot == 90 || videoRot == 270) { val t = dw; dw = dh; dh = t }   // turned with the Rotate button
        }
        val sizeChanged = dw != _state.value.videoW || dh != _state.value.videoH
        _state.value = _state.value.copy(
            audioTracks = a, spuTracks = sp, audioTrack = mp.audioTrack, spuTrack = mp.spuTrack,
            videoW = dw, videoH = dh,
            length = if (mp.length > 0) mp.length else _state.value.length,
        )
        if (sizeChanged) refreshSurface()
        restoreTracks()
        // Settings → Video "Automatic": learn from the video's encoding; re-open once the right way if needed.
        val it0 = _state.value.item
        if (vt != null && it0 != null && !it0.isMusic && !it0.isRadio) {
            val ps = store.current.settings
            val decA = VideoAuto.manualDecoder(it0.uri).ifEmpty { ps.videoDecoder } == "auto"
            val diA = VideoAuto.manualDeinterlace(it0.uri).ifEmpty { ps.videoDeinterlace } == "auto"
            if ((decA || diA) && VideoAuto.learn(it0.uri, vt, decA, diA, deintOn))
                main.postDelayed({ if (_state.value.item?.uri == it0.uri) reloadVideo() }, 300)
        }
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
                    // Some streams put the song into the title field instead: "Artist - Song" there counts as the song.
                    val title = m?.getMeta(IMedia.Meta.Title, true)?.trim().orEmpty().takeIf { !it.contains("://") } ?: ""
                    val np0 = m?.getMeta(IMedia.Meta.NowPlaying, true)?.trim().orEmpty()
                    val np = np0.ifEmpty { if (splitNowPlaying(title).first.isNotEmpty()) title else "" }
                    val sn = title.takeIf { it != np } ?: ""
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
        // Whatever form the station announces it in (with or without "Artist - Title"), it is looked up.
        if (np.isBlank()) { _state.value = _state.value.copy(artworkUrl = item.artwork); return }
        bg.launch {
            // Network work off the main thread (it used to run on it, which Android refuses: no picture ever came).
            val f = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.vlcinterface.sources.CoverArt.radioSong(context, np) } } catch (e: Exception) {
                ServerLog.log("radio-pictures", "picture", "web", "", false, "Picture lookup failed for \"$np\": ${e.message}"); null }
            val s = _state.value
            if (s.item === item && s.nowPlaying == np) _state.value = s.copy(artworkUrl = f?.absolutePath ?: item.artwork)
        }
    }

    init {
        main.post(metaPoller)
        try {
            val f = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(volumeRx, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(volumeRx, f)
        } catch (_: Exception) {}
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


private fun fmtMs(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }
