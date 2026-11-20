package app.vlcinterface.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vlcinterface.App
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem

/** Switching between the song, radio and video players from their headers, each resuming what it last played. */
object PlayerSwitch {
    class Snap(val queue: List<PlayItem>, val index: Int, val time: Long, val origin: Origin)
    private val last = mutableMapOf<String, Snap>()

    fun kindOf(it: PlayItem?): String = when { it == null -> ""; it.isRadio -> "radio"; it.isMusic -> "songs"; else -> "video" }

    /** Keeps what's playing now, to come back to it later. */
    fun keepNow() {
        val p = App.instance.player.state.value
        val k = kindOf(p.item); if (k.isEmpty() || p.queue.isEmpty()) return
        last[k] = Snap(p.queue, p.index.coerceAtLeast(0), if (k == "radio") -1 else p.time, p.origin)
    }

    fun go(nav: Navigator, target: String) {
        keepNow()
        val player = App.instance.player
        val snap = last[target]
        if (snap == null) {
            // Nothing played there yet: the matching list.
            nav.go(when (target) { "radio" -> Screen.Library(tab = 4); "songs" -> Screen.Library(tab = 3); else -> Screen.Library(tab = 0) })
            return
        }
        player.playQueue(snap.queue, snap.index, snap.origin, startAt = snap.time, keepOrder = true)
        nav.replace(if (target == "video") Screen.Player else Screen.Music)
    }
}

/** Text-only switch buttons for the two other players. */
@Composable
fun PlayerSwitchButtons(nav: Navigator, current: String, color: Color = C.text) {
    listOf("songs" to "Songs", "radio" to "Radio", "video" to "Video").filter { it.first != current }.forEach { (k, l) ->
        Text(l, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable { PlayerSwitch.go(nav, k) }.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
