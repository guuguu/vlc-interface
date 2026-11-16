package app.vlcinterface.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.vlcinterface.App
import app.vlcinterface.player.Origin
import app.vlcinterface.player.PlayItem

/**
 * What the page adds to the common file menu: only the choices that make sense where the file is shown.
 * [onRemove]: takes the file out of this list only — the file itself is never touched.
 */
class FileMenuExtras(
    val onSelect: (() -> Unit)? = null,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    val removeLabel: String = "Remove from this list",
    val onRemove: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/**
 * The same long-press menu for a song or video on every page (folders, Songs list, playlists, queue, Up next):
 * Play now, Play next, Add to queue, Add to playlist…, Open song folder, Tags & ReplayGain…, ReplayGain scan…,
 * plus what the page allows (Select, Move up / down, Remove from this list, Delete file).
 */
@Composable
fun FileMenu(nav: Navigator, item: PlayItem, expanded: Boolean, onDismiss: () -> Unit, x: FileMenuExtras = FileMenuExtras()) {
    val ctx = LocalContext.current
    val player = App.instance.player
    var addTo by remember { mutableStateOf(false) }
    var rg by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    fun close(then: () -> Unit) { onDismiss(); then() }
    SDropdownMenu(expanded, onDismiss) {
        SMenuItem({ Text("▶  Play now") }, { close {
            player.playQueue(listOf(item), 0, Origin("single", title = item.title))
            nav.go(if (item.isMusic) Screen.Music else Screen.Player)
        } })
        SMenuItem({ Text("Play next") }, { close { player.queueNext(item) } })
        SMenuItem({ Text("Add to queue") }, { close {
            val n = player.addToQueue(listOf(item))
            android.widget.Toast.makeText(ctx, if (n == 0) "Already in the queue" else "Added to the queue", android.widget.Toast.LENGTH_SHORT).show()
        } })
        SMenuItem({ Text("Add to playlist…") }, { close { addTo = true } })
        x.onSelect?.let { f -> SMenuItem({ Text("Select…") }, { close(f) }) }
        x.onMoveUp?.let { f -> SMenuItem({ Text("Move up") }, { close(f) }) }
        x.onMoveDown?.let { f -> SMenuItem({ Text("Move down") }, { close(f) }) }
        if (!item.isRadio) SMenuItem({ Text("Open song folder") }, { close { openItemFolder(nav, item) } })
        if (!item.isRadio) SMenuItem({ Text("Tags & ReplayGain…") }, { close { info = true } })
        if (item.isMusic) SMenuItem({ Text("ReplayGain scan…") }, { close { rg = true } })
        x.onRemove?.let { f -> SMenuItem({ Text(x.removeLabel, color = C.bad) }, { close(f) }) }
        x.onDelete?.let { f -> SMenuItem({ Text("Delete file…", color = C.bad) }, { close(f) }) }
    }
    if (addTo) AddToPlaylistDialog({ listOf(item) }) { addTo = false }
    if (rg) RgScanDialog(nav, { listOf(item) }) { rg = false }
    if (info) TagInfoDialog(item) { info = false }
}

/** Files taken out of the Library lists (folders, Songs list) with "Remove from this list" — never deleted. */
object HiddenFiles {
    fun isHidden(uri: String) = uri in App.instance.store.current.settings.hiddenUris
    fun hide(uri: String) = App.instance.store.update { it.copy(settings = it.settings.copy(hiddenUris = it.settings.hiddenUris + uri)) }
    fun showAll() = App.instance.store.update { it.copy(settings = it.settings.copy(hiddenUris = emptySet())) }
}
