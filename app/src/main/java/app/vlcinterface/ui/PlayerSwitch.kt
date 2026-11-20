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

    private fun prefs() = App.instance.getSharedPreferences("playerSwitch", android.content.Context.MODE_PRIVATE)

    /** Keeps what's playing now (also across restarts: its item and position), to come back to it later. */
    fun keepNow() {
        val p = App.instance.player.state.value
        val it = p.item ?: return
        val k = kindOf(it); if (k.isEmpty() || p.queue.isEmpty()) return
        last[k] = Snap(p.queue, p.index.coerceAtLeast(0), if (k == "radio") -1 else p.time, p.origin)
        prefs().edit().putString("$k.uri", it.uri).putString("$k.title", it.title).putString("$k.src", it.sourceId)
            .putString("$k.file", it.fileName).putLong("$k.time", if (k == "radio") -1 else p.time).apply()
    }

    private fun itemFor(uri: String, title: String, sourceId: String, fileName: String = "", radio: Boolean = false): PlayItem {
        val src = App.instance.store.current.sources.firstOrNull { it.id == sourceId }
        return PlayItem(uri, title, isRadio = radio, sourceId = sourceId, user = src?.user ?: "", password = src?.password ?: "",
            domain = src?.domain ?: "", fileName = fileName)
    }

    /** Straight to the chosen player, always: what it last played, else the most recent of that kind, else (radio) the first favourite. */
    fun go(nav: Navigator, target: String) {
        keepNow()
        val player = App.instance.player
        val screen = if (target == "video") Screen.Player else Screen.Music
        last[target]?.let { snap ->
            player.playQueue(snap.queue, snap.index, snap.origin, startAt = snap.time, keepOrder = true)
            nav.replace(screen); return
        }
        val pr = prefs()
        pr.getString("$target.uri", null)?.let { uri ->
            val item = itemFor(uri, pr.getString("$target.title", "") ?: "", pr.getString("$target.src", "") ?: "", pr.getString("$target.file", "") ?: "", target == "radio")
            if (target == "radio") player.playStation(item.title, item.uri) else player.play(item, startAt = pr.getLong("$target.time", -1))
            nav.replace(screen); return
        }
        val st = App.instance.store.current
        if (target == "radio") {
            st.stations.firstOrNull()?.let { player.playStation(it.name, it.url); nav.replace(screen); return }
        } else {
            st.recent.map { r -> r to itemFor(r.uri, r.title, r.sourceId) }.firstOrNull { (_, it) -> kindOf(it) == target }?.let { (r, it) ->
                player.play(it, startAt = r.position); nav.replace(screen); return
            }
        }
        ShortNote.show(when (target) { "radio" -> "No radio station yet"; "songs" -> "No song played yet"; else -> "No video played yet" })
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
