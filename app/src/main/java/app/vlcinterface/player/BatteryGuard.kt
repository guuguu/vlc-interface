package app.vlcinterface.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.vlcinterface.App
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Low battery while not charging:
 *  - 15 %: a pop-up warns that playback stops at 10 %;
 *  - 10 %: playback stops (paused, the queue and place are kept), the "screen stays on" is switched off, and a
 *    pop-up says why; from then on nothing is played or started (scans, indexing) until the phone charges.
 */
object BatteryGuard {
    /** 0 = nothing to show, 15 = the warning, 10 = "stopped". */
    val notice = MutableStateFlow(0)
    /** At or below 10 % and not charging: no keeping the screen on. */
    val low = MutableStateFlow(false)
    var level = 100
        private set
    private var warned = false
    private var stopped = false

    fun init(ctx: Context) {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (lv < 0 || sc <= 0) return
                val pct = lv * 100 / sc
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                update(pct, charging)
            }
        }
        val sticky = try { ctx.registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        sticky?.let { rx.onReceive(ctx, it) }
    }

    private fun update(pct: Int, charging: Boolean) {
        level = pct
        if (charging || pct > 15) { warned = false; stopped = false; low.value = false; if (charging && notice.value != 0) notice.value = 0; return }
        val player = App.instance.player
        val playing = player.state.value.item != null && player.state.value.isPlaying
        if (pct <= 10) {
            low.value = true
            if (!stopped) {
                stopped = true
                if (playing) { player.pause(); notice.value = 10 }
            }
        } else {
            low.value = false
            if (!warned) { warned = true; if (playing) notice.value = 15 }
        }
    }

    /**
     * At 10 % or less (not charging) nothing new is started: true = refused (and, unless [silent], a pop-up says why).
     */
    fun blocked(silent: Boolean = false): Boolean {
        if (!low.value) return false
        if (!silent) notice.value = 11
        return true
    }

    fun seen() { notice.value = 0 }
}
