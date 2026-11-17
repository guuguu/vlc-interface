package app.vlcinterface.player

import app.vlcinterface.sources.NetIndex
import app.vlcinterface.sources.RadioCapture
import app.vlcinterface.sources.RgScanner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The players' red button: pauses the work done in the background — ReplayGain scans, songs being encoded / saved,
 * LAN folder indexing — keeping every queue; the same button resumes it. Playback (and the radio recording that
 * lets you save songs) goes on.
 */
object BackgroundWork {
    val paused = MutableStateFlow(false)

    /** Anything in the background now or waiting. */
    fun anything(): Boolean = paused.value || RgScanner.state.value.running || RgScanner.queued.value.isNotEmpty() ||
        RadioCapture.saveQueue.value.isNotEmpty() || NetIndex.busy.value.isNotEmpty()

    fun pause() {
        if (paused.value) return
        paused.value = true
        RgScanner.pause()
        NetIndex.pause()
        // Encoding waits at its next chunk (see RadioCapture.breathe).
        app.vlcinterface.data.ServerLog.log("player", "pause", "", "", true, "Background work paused",
            "ReplayGain queue ${RgScanner.queued.value.size} · songs to save ${RadioCapture.saveQueue.value.size}")
    }

    fun resume() {
        if (!paused.value) return
        paused.value = false
        RgScanner.resume()
        NetIndex.resume()
        app.vlcinterface.data.ServerLog.log("player", "resume", "", "", true, "Background work resumed")
    }

    fun toggle() = if (paused.value) resume() else pause()
}
