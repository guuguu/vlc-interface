package app.vlcinterface.sources

import android.content.Context
import app.vlcinterface.App
import app.vlcinterface.data.PlEntry
import app.vlcinterface.data.Source
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local index of the songs in pinned (★) LAN folders, subfolders included, with their tags — so searches
 * (Songs list, Jump to, Play next) find them without the NAS being read each time. Built when a folder is pinned,
 * refreshed by the source's ↻ Rescan, dropped when the pin is removed. Kept in the app's own storage.
 */
object NetIndex {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var file: File

    /** pin key ("sourceId|/full/path") → its songs. */
    private var byPin: Map<String, List<PlEntry>> = emptyMap()

    /** What's being indexed right now ("" = idle), for the Libraries page. */
    val busy = MutableStateFlow("")

    /** Bumped whenever the index changes (lists showing it reload). */
    val version = MutableStateFlow(0)

    fun key(src: Source, pin: String) = src.id + "|/" + pin.trim('/')

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "netindex.json")
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                byPin = o.keys().asSequence().associateWith { k ->
                    val a = o.getJSONArray(k)
                    (0 until a.length()).map { i -> PlEntry.fromJson(a.getJSONObject(i)) }
                }
            }
        } catch (_: Exception) { byPin = emptyMap() }
    }

    /** Every indexed song (pins of sources that still exist). */
    fun songs(): List<PlayItem> {
        val ids = App.instance.store.current.sources.map { it.id }.toHashSet()
        return byPin.filterKeys { it.substringBefore('|') in ids }.values.flatten().map { MusicLibrary.fromPlEntry(it) }
    }

    private val upAt = HashMap<String, Pair<Long, Boolean>>()   // source id → (checked at, reachable)

    /**
     * Indexed songs only from servers that answer right now (checked in parallel, ~1.5 s at most, remembered 20 s):
     * a NAS that's off or out of reach gives no search results.
     */
    suspend fun songsReachable(): List<PlayItem> = kotlinx.coroutines.coroutineScope {
        val now = System.currentTimeMillis()
        val srcs = App.instance.store.current.sources.filter { s -> byPin.keys.any { it.startsWith(s.id + "|") } }
        val up = srcs.map { s ->
            async {
                val c = synchronized(upAt) { upAt[s.id] }
                if (c != null && now - c.first < 20_000) s.id to c.second
                else {
                    val ok = Net.reachable(Urls.hostOnly(s.host), if (s.port > 0) s.port else s.defaultPort)
                    synchronized(upAt) { upAt[s.id] = now to ok }
                    s.id to ok
                }
            }
        }.map { it.await() }.filter { it.second }.map { it.first }.toHashSet()
        byPin.filterKeys { it.substringBefore('|') in up }.values.flatten().map { MusicLibrary.fromPlEntry(it) }
    }

    fun has(src: Source, pin: String) = byPin.containsKey(key(src, pin))

    /** (Re)builds the index of one pinned folder, in the background. */
    private val building = java.util.Collections.synchronizedSet(HashSet<String>())

    fun build(src: Source, pin: String) {
        if (app.vlcinterface.player.BatteryGuard.blocked(silent = true)) return   // battery 10 % or less: no indexing
        val k = key(src, pin)
        if (!building.add(k)) return                     // already queued / running
        scope.launch {
            try { lock.withLock {
                val name = pin.trim('/').substringAfterLast('/')
                busy.value = "Indexing $name…"
                val url = Urls.forSource(src.copy(path = ""), pin)
                // 8 at once: up to 8 files' tags read in parallel, and subfolders listed side by side.
                val files = kotlinx.coroutines.sync.Semaphore(8)
                val dirs = kotlinx.coroutines.sync.Semaphore(8)
                val count = java.util.concurrent.atomic.AtomicInteger(0)
                val songs = try {
                    crawl(src, url, 0, files, dirs) { k -> busy.value = "Indexing $name… ${count.addAndGet(k)} songs" }
                } catch (e: Exception) {
                    app.vlcinterface.data.ServerLog.log(Urls.hostOnly(src.host), "index", src.proto, src.user, false,
                        "Index of $name not made: ${e.message ?: e.javaClass.simpleName}", url)
                    null
                }
                if (songs != null) {
                    byPin = byPin + (k to songs.map { MusicLibrary.toPlEntry(it) })
                    save(); version.value++
                    app.vlcinterface.data.ServerLog.log(Urls.hostOnly(src.host), "index", src.proto, src.user, true,
                        "Indexed $name: ${songs.size} songs", url)
                }
                busy.value = ""
            } } finally { building.remove(k) }
        }
    }

    /** Pinned folders not indexed yet (pinned before indexing existed, or never reachable so far). */
    fun buildMissing() {
        App.instance.store.current.sources.forEach { s -> s.pins.filterNot { has(s, it) }.forEach { build(s, it) } }
    }

    /** Every pin of a source again (its ↻ Rescan). */
    fun rebuild(src: Source) = src.pins.forEach { build(src, it) }

    fun remove(src: Source, pin: String) {
        scope.launch { lock.withLock { byPin = byPin - key(src, pin); save(); version.value++ } }
    }

    private suspend fun crawl(src: Source, uri: String, depth: Int, files: kotlinx.coroutines.sync.Semaphore,
                              dirs: kotlinx.coroutines.sync.Semaphore, progress: (Int) -> Unit): List<PlayItem> {
        if (depth > 12) return emptyList()
        // The pinned folder itself can't be read (NAS off, no network): keep the old index, don't store an empty one.
        val list = dirs.withPermit { Browse.list(src, uri) }.getOrElse { if (depth == 0) throw it else return emptyList() }
        // Tags: read once and kept (the same cache the Libraries pages use), so a rescan is quick.
        val tagged = MusicLibrary.scanTags(src, list, force = false, sem = files)
        val here = MusicLibrary.playOrder(tagged.filter { it.isAudio }, Urls.nameOf(uri)).map { MusicLibrary.toPlayItem(it, src, uri) }
        progress(here.size)
        val sub = kotlinx.coroutines.coroutineScope {
            tagged.filter { it.isDir }.map { d -> async { crawl(src, d.uri, depth + 1, files, dirs, progress) } }.map { it.await() }
        }
        return here + sub.flatten()
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject()
            byPin.forEach { (k, l) -> o.put(k, JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString()); tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    // ---------- local copy first ----------

    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Same song = same artist, album and title (case and punctuation ignored); no title → no match. */
    fun songKey(p: PlayItem): String? = if (p.title.isBlank()) null else norm(p.artist) + "|" + norm(p.album) + "|" + norm(p.title)

    fun isLocal(p: PlayItem) = p.uri.startsWith("content://") || p.uri.startsWith("file://") || p.uri.startsWith("/")

    /** Merges lists: a song both on the phone and on the network appears once — the phone file. */
    fun preferLocal(all: List<PlayItem>): List<PlayItem> {
        val localKeys = all.filter(::isLocal).mapNotNull(::songKey).toHashSet()
        return all.filter { isLocal(it) || songKey(it)?.let { k -> k !in localKeys } ?: true }.distinctBy { it.uri }
    }
}
