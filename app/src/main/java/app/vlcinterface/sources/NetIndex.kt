package app.vlcinterface.sources

import app.vlcinterface.data.CrashLog

import android.content.Context
import app.vlcinterface.App
import app.vlcinterface.data.PlEntry
import app.vlcinterface.data.Source
import app.vlcinterface.player.PlayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
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
    /**
     * Indexing runs transparently (08:19): on its own pool of background threads at low priority (below the player
     * and the screen), several folders and files at once.
     */
    private val lowPrio = java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
        Thread({ try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) } catch (_: Throwable) {}; r.run() }, "index")
            .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + lowPrio + CrashLog.guard)
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
        partFile = File(ctx.filesDir, "netindex-partial.json")
        loadPartials()
        // ReplayGain has priority (2026-11-22 08:09): while a scan runs or waits, indexing pauses (what it gathered is
        // kept); 5 s after the last scan is done, it goes on by itself.
        scope.launch {
            kotlinx.coroutines.flow.combine(RgScanner.state, RgScanner.queued) { s, q -> s.running || q.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { rg -> if (rg) yieldToRg() else { kotlinx.coroutines.delay(5_000); if (!rgBusy()) afterRg() } }
        }
        startWatch()
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
        return withPartial().filterKeys { it.substringBefore('|') in ids }.values.flatten().map { MusicLibrary.fromPlEntry(it) }.filter { it.isMusic }
    }

    /** Every indexed video (pins of sources that still exist). */
    fun videos(): List<PlayItem> {
        val ids = App.instance.store.current.sources.map { it.id }.toHashSet()
        return withPartial().filterKeys { it.substringBefore('|') in ids }.values.flatten().map { MusicLibrary.fromPlEntry(it) }.filter { !it.isMusic && !it.isRadio }
    }

    /**
     * The finished indexes, plus what an interrupted run has gathered so far for a folder with no finished index yet
     * (searches find those songs already; a folder indexed before keeps its whole old index until the new one ends).
     */
    private fun withPartial(): Map<String, List<PlEntry>> {
        val extra = synchronized(parts) { parts.filterKeys { it !in byPin }.mapValues { (_, p) -> p.songs() } }
        return if (extra.isEmpty()) byPin else byPin + extra
    }

    private val upAt = HashMap<String, Pair<Long, Boolean>>()   // source id → (checked at, reachable)

    /** The network changed (Wi-Fi ↔ VPN): servers are checked again at once — the index itself stays as it is. */
    fun recheckReachable() = synchronized(upAt) { upAt.clear() }

    /**
     * Indexed songs only from servers that answer right now (checked in parallel, ~1.5 s at most, remembered 20 s):
     * a NAS that's off or out of reach gives no search results.
     */
    suspend fun songsReachable(): List<PlayItem> {
        val up = reachableIds()
        return withPartial().filterKeys { it.substringBefore('|') in up }.values.flatten().map { MusicLibrary.fromPlEntry(it) }.filter { it.isMusic }
    }

    /** Servers with an index that answer right now (checked in parallel, remembered 20 s). */
    private suspend fun reachableIds(): Set<String> = kotlinx.coroutines.coroutineScope {
        val now = System.currentTimeMillis()
        val keys = withPartial().keys
        val srcs = App.instance.store.current.sources.filter { s -> keys.any { it.startsWith(s.id + "|") } }
            .filter { s -> s.zone != app.vlcinterface.data.Zone.LAN || VpnLink.atHome(s) || (s.id in VpnLink.useNas.value && VpnLink.vpnUp.value) }
            .filter { s -> !(VpnLink.ignoreSession && s.zone == app.vlcinterface.data.Zone.LAN) }
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
        up
    }

    /** Indexed videos from servers that answer right now (the same check as the songs). */
    suspend fun videosReachable(): List<PlayItem> {
        val ids = reachableIds()
        return withPartial().filterKeys { it.substringBefore('|') in ids }.values.flatten().map { MusicLibrary.fromPlEntry(it) }.filter { !it.isMusic && !it.isRadio }
    }

    fun has(src: Source, pin: String) = byPin.containsKey(key(src, pin))
    /** Its last indexing was interrupted; what it gathered is kept (resumed by itself, or asked when started by hand). */
    fun hasStopped(src: Source, pin: String) = synchronized(parts) { parts.containsKey(key(src, pin)) }

    /** (Re)builds the index of one pinned folder, in the background. */
    private val building = java.util.Collections.synchronizedSet(HashSet<String>())

    private val jobs = java.util.Collections.synchronizedSet(HashSet<kotlinx.coroutines.Job>())
    /** Folders being indexed (to resume them after "Pause background work"), and those waiting while paused. */
    private val inWork = java.util.concurrent.ConcurrentHashMap<String, Pair<Source, String>>()
    private val held = java.util.concurrent.ConcurrentHashMap<String, Pair<Source, String>>()

    /** Player's pause button: indexing stops now; the folders it was doing are done again on resume. */
    fun pause() {
        held.putAll(inWork)
        synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        busy.value = ""
    }
    /** Indexing a folder on a server outside the home network right now. */
    fun internetBusy(): Boolean = inWork.values.any { (s, p) -> try { app.vlcinterface.player.NetGuard.needsInternet(Urls.forSource(s.copy(path = ""), p)) } catch (_: Exception) { false } }
    fun resume() { val l = held.values.toList(); held.clear(); l.forEach { (s, p) -> build(s, p) }; resumeStopped(); autoLan(); runHeldPaths() }

    /** Folders waiting because the VPN is on. */
    private val vpnHeld = java.util.concurrent.ConcurrentHashMap<String, Pair<Source, String>>()

    /** VPN up: indexing running now stops; it and anything asked meanwhile wait for the VPN to go off. */
    fun vpnOn() {
        val running = synchronized(jobs) { jobs.toList() }
        if (running.isEmpty()) return
        vpnHeld.putAll(inWork)
        running.forEach { it.cancel() }
        busy.value = ""
        app.vlcinterface.data.ServerLog.log("vpn", "index", "", "", true, "Indexing stopped: VPN on (goes on when it's off)")
    }

    /** VPN off: the waiting folders are indexed. */
    fun vpnOff() {
        val l = vpnHeld.values.toList(); vpnHeld.clear()
        l.forEach { (s, p) -> build(s, p) }
        resumeStopped()
        autoLan()
        runHeldPaths()
    }

    // ---------- ReplayGain first ----------
    private val rgHeld = java.util.concurrent.ConcurrentHashMap<String, Pair<Source, String>>()
    private fun rgBusy() = RgScanner.state.value.running || RgScanner.queued.value.isNotEmpty()

    /** A ReplayGain scan starts or waits: indexing running now pauses (kept), and resumes after it. */
    private fun yieldToRg() {
        val running = synchronized(jobs) { jobs.toList() }
        if (running.isEmpty()) return
        rgHeld.putAll(inWork)
        running.forEach { it.cancel() }
        busy.value = ""
        app.vlcinterface.data.ServerLog.log("index", "pause", "", "", true, "Indexing paused: ReplayGain scan running (it resumes when the scan is done)")
    }

    /** No ReplayGain scan any more: the paused folders go on, then anything else waiting. */
    private fun afterRg() {
        val l = rgHeld.values.toList(); rgHeld.clear()
        if (l.isNotEmpty()) app.vlcinterface.data.ServerLog.log("index", "resume", "", "", true, "Indexing resumed: ReplayGain scan done")
        l.forEach { (s, p) -> build(s, p) }
        resumeStopped()
        autoLan()
        runHeldPaths()
    }

    /**
     * Automatic indexing (08:09): every pinned folder of a home-network (LAN) location that isn't indexed yet, or whose
     * indexing was interrupted, is indexed by itself once everything allows it — the server answers, background work
     * isn't paused, the VPN is off, the battery is above 15 %, no ReplayGain scan is running. Checked at app start,
     * after each of those changes, and every 5 minutes. Internet locations are indexed when asked (pin, 🔍, Rescan).
     * Phone songs need no indexing: Android keeps their list.
     */
    fun autoLan() {
        val srcs = App.instance.store.current.sources.filter { it.zone == app.vlcinterface.data.Zone.LAN && it.proto != "upnp" && it.proto != "m3u" }
        scope.launch {
            srcs.forEach { s ->
                val todo = s.pins.filter { (!has(s, it) || hasStopped(s, it) || due(key(s, it))) && key(s, it) !in building }
                if (todo.isEmpty()) return@forEach
                if (!Net.reachable(Urls.hostOnly(s.host), if (s.port > 0) s.port else s.defaultPort)) return@forEach
                todo.forEach { build(s, it) }
            }
        }
    }

    // ---------- indexing again (08:19): Never / every N days / when a new file appears ----------

    private val atPrefs by lazy { App.instance.getSharedPreferences("netindex_at", Context.MODE_PRIVATE) }
    private fun markIndexed(k: String) = try { atPrefs.edit().putLong(k, System.currentTimeMillis()).apply() } catch (_: Exception) {}
    /** "Every N days": an indexed folder whose last indexing is that old (indexes from before this setting count from now). */
    private fun due(k: String): Boolean {
        val set = App.instance.store.current.settings
        if (set.indexMode != "days" || k !in byPin) return false
        val at = atPrefs.getLong(k, 0L)
        if (at == 0L) { markIndexed(k); return false }
        return System.currentTimeMillis() - at > set.indexDays * 86_400_000L
    }

    /** Every indexed file address (looked up when a folder is listed or a file is played). */
    @Volatile private var known: Pair<Int, Set<String>>? = null
    private fun knownUris(): Set<String> {
        val v = version.value
        known?.let { if (it.first == v) return it.second }
        val all = withPartial().values.flatten().map { VpnLink.unroute(it.uri) }.toHashSet()
        known = v to all
        return all
    }

    /** The index entry a network folder belongs to: a pinned folder above it, or the folder indexed on its own. */
    private fun keyFor(src: Source, folderUri: String): String? {
        val path = "/" + android.net.Uri.decode(VpnLink.unroute(folderUri).substringAfter("://").substringAfter('/', "")).trim('/')
        return src.pins.map { "/" + it.trim('/') }.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }?.let { key(src, it) }
            ?: key(src, path).takeIf { byPin.containsKey(it) }
    }

    /**
     * A network folder was listed (Library, browsing). "When a new file appears": a song or video there that isn't in
     * the index has that folder read again.
     */
    fun noticeListing(src: Source, folderUri: String, entries: List<Entry>) {
        if (App.instance.store.current.settings.indexMode != "new") return
        if (keyFor(src, folderUri) == null) return
        val have = knownUris()
        if (entries.any { (it.isAudio || it.isVideo) && VpnLink.unroute(it.uri) !in have }) indexPath(src, folderUri, "new files found")
    }

    /** A network file starts playing and isn't indexed yet (08:19): its folder is indexed now, whatever the setting. */
    private val playedFolders = java.util.Collections.synchronizedSet(HashSet<String>())
    fun onPlayed(item: PlayItem) {
        if (item.isRadio || isLocal(item) || item.sourceId.isEmpty() || !item.uri.contains("://")) return
        val src = App.instance.store.current.sources.firstOrNull { it.id == item.sourceId } ?: return
        if (src.proto == "upnp" || src.proto == "m3u") return
        val folder = VpnLink.unroute(item.folderKey.ifEmpty { Urls.parent(item.uri) })
        if (!folder.contains("://") || !playedFolders.add(folder)) return
        scope.launch { if (VpnLink.unroute(item.uri) !in knownUris()) indexPath(src, folder, "played") }
    }

    private val pathHeld = java.util.concurrent.ConcurrentHashMap<String, Triple<Source, String, String>>()
    private val pathBusy = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * One folder (with its subfolders) indexed on its own, merged into the pinned folder above it — or kept on its own
     * when it isn't under a pin. Waits like any indexing (paused, VPN, battery, ReplayGain first).
     */
    fun indexPath(src: Source, folderUri: String, why: String) {
        val f = VpnLink.unroute(folderUri).trimEnd('/') + "/"
        if (app.vlcinterface.player.BatteryGuard.busyBlocked(silent = true)) return
        if (app.vlcinterface.player.BackgroundWork.paused.value || VpnLink.vpnUp.value || rgBusy()) { pathHeld[f] = Triple(src, f, why); return }
        if (!pathBusy.add(f)) return
        scope.launch {
            try {
                val name = Urls.nameOf(f)
                busy.value = "Indexing $name…"
                val url = VpnLink.route(f)
                val done = java.util.concurrent.atomic.AtomicLong(0)
                val gate = ParallelFetch.Gate(Urls.hostOnly(src.host), done).also { it.start(this) }
                val found = try {
                    crawl(src, url, 0, gate.sem, kotlinx.coroutines.sync.Semaphore(8), Partial(System.currentTimeMillis())) { n -> done.addAndGet(n.toLong()) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } finally { gate.stop() }
                if (found != null) lock.withLock {
                    val path = "/" + android.net.Uri.decode(f.substringAfter("://").substringAfter('/', "")).trim('/')
                    val k = keyFor(src, f) ?: key(src, path)
                    val fresh = found.map { MusicLibrary.toPlEntry(it) }
                    val old = byPin[k].orEmpty().filterNot { VpnLink.unroute(it.folderKey).trimEnd('/').let { fk -> fk + "/" == f || fk.startsWith(f) } }
                    byPin = byPin + (k to (old + fresh)); save(); version.value++
                    if (k == key(src, path)) markIndexed(k)
                    app.vlcinterface.data.ServerLog.log(Urls.hostOnly(src.host), "index", src.proto, src.user, true,
                        "Indexed $name ($why): ${found.count { it.isMusic }} songs, ${found.count { !it.isMusic }} videos", f)
                }
            } finally { pathBusy.remove(f); if (building.isEmpty()) busy.value = "" }
        }
    }
    private fun runHeldPaths() { val l = pathHeld.values.toList(); pathHeld.clear(); l.forEach { (s, f, w) -> indexPath(s, f, w) } }

    /** App start: interrupted folders resumed, home-network pins not indexed yet started. */
    fun autoStart() { resumeStopped(); autoLan() }

    /** Low battery: every indexing run stops (what was already indexed is kept). */
    fun stopAll() {
        val running = synchronized(jobs) { jobs.toList() }
        if (running.isEmpty()) return
        running.forEach { it.cancel() }
        busy.value = ""
        app.vlcinterface.data.ServerLog.log("battery", "index", "", "", false, "Indexing stopped: battery 15% or less")
    }

    /**
     * Started by hand (pin, + Folder, ↻ Rescan): a folder whose last indexing was interrupted asks first — resume where
     * it stopped, or start over. Started automatically, it always resumes without asking.
     */
    fun buildManual(src: Source, pins: List<String>) {
        // (A folder being indexed right now isn't asked about: it simply goes on.)
        val (stopped, fresh) = pins.partition { key(src, it) !in building && synchronized(parts) { parts.containsKey(key(src, it)) } }
        fresh.forEach { build(src, it) }
        if (stopped.isNotEmpty()) ask.value = Ask(src, stopped)
    }
    fun buildManual(src: Source, pin: String) = buildManual(src, listOf(pin))

    /** The question for folders whose indexing was interrupted (answered in the app's pop-up). */
    class Ask(val src: Source, val pins: List<String>) {
        val songs: Int get() = pins.sumOf { p -> synchronized(parts) { parts[key(src, p)]?.count() ?: 0 } }
        val at: Long get() = pins.maxOf { p -> synchronized(parts) { parts[key(src, p)]?.at ?: 0L } }
    }
    val ask = MutableStateFlow<Ask?>(null)
    fun answer(resume: Boolean) {
        val a = ask.value ?: return
        ask.value = null
        if (!resume) { synchronized(parts) { a.pins.forEach { parts.remove(key(a.src, it)) } }; scope.launch { savePartials() } }
        a.pins.forEach { build(a.src, it) }
    }

    fun build(src: Source, pin: String) {
        if (app.vlcinterface.player.BatteryGuard.busyBlocked(silent = true)) return   // battery 15 % or less: no indexing
        val k = key(src, pin)
        if (app.vlcinterface.player.BackgroundWork.paused.value) { held[k] = src to pin; return }   // paused: later
        // VPN on: no indexing at all — it waits until the VPN is off.
        if (VpnLink.vpnUp.value) { vpnHeld[k] = src to pin; return }
        // ReplayGain scanning: it goes first; this folder waits (and resumes) until it's done.
        if (rgBusy()) { rgHeld[k] = src to pin; return }
        if (!building.add(k)) return                     // already queued / running
        inWork[k] = src to pin
        lateinit var job: kotlinx.coroutines.Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { lock.withLock {
                val name = pin.trim('/').substringAfterLast('/')
                busy.value = "Indexing $name…"
                val url = Urls.forSource(src.copy(path = ""), pin)
                // 8 at once: up to 8 files' tags read in parallel, and subfolders listed side by side.
                // Files and folders read in parallel; how many at once is tuned to the server and the bandwidth.
                val done = java.util.concurrent.atomic.AtomicLong(0)
                val gate = ParallelFetch.Gate(Urls.hostOnly(src.host), done).also { it.start(this) }
                val files = gate.sem
                val dirs = kotlinx.coroutines.sync.Semaphore(8)
                val count = java.util.concurrent.atomic.AtomicInteger(0)
                // What an interrupted run already gathered is kept and used: its folders aren't read again.
                val part = synchronized(parts) { parts.getOrPut(k) { Partial(System.currentTimeMillis()) } }
                if (part.count() > 0) app.vlcinterface.data.ServerLog.log(Urls.hostOnly(src.host), "index", src.proto, src.user, true,
                    "Indexing $name resumed at ${part.count()} files (${part.folders.size} folders already done)", url)
                val saver = launch { while (true) { kotlinx.coroutines.delay(10_000); if (part.dirty) savePartials() } }
                val songs = try {
                    crawl(src, url, 0, files, dirs, part) { k -> done.addAndGet(k.toLong()); busy.value = "Indexing $name… ${count.addAndGet(k)} files" }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.vlcinterface.data.ServerLog.log(Urls.hostOnly(src.host), "index", src.proto, src.user, false,
                        "Index of $name not finished: ${e.message ?: e.javaClass.simpleName}" +
                            (if (part.count() > 0) " — ${part.count()} files kept, it resumes from there" else ""), url)
                    null
                } finally {
                    gate.stop(); saver.cancel()
                    if (part.folders.isEmpty()) synchronized(parts) { parts.remove(k) }     // nothing gathered: nothing to resume
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { if (part.dirty || part.folders.isEmpty()) savePartials() }
                }
                if (songs != null) {
                    synchronized(parts) { parts.remove(k) }
                    savePartials()
                    markIndexed(k)
                    byPin = byPin + (k to songs.map { MusicLibrary.toPlEntry(it) })
                    save(); version.value++
                    app.vlcinterface.data.ServerLog.log(Urls.hostOnly(src.host), "index", src.proto, src.user, true,
                        "Indexed $name: ${songs.count { it.isMusic }} songs, ${songs.count { !it.isMusic }} videos", url)
                }
                busy.value = ""
            } } finally {
                building.remove(k); inWork.remove(k); jobs.remove(job)
                if (job.isCancelled) {
                    busy.value = ""
                    val n = synchronized(parts) { parts[k]?.count() ?: 0 }
                    if (n > 0) app.vlcinterface.data.ServerLog.log(Urls.hostOnly(src.host), "index", src.proto, src.user, true,
                        "Indexing ${pin.trim('/').substringAfterLast('/')} stopped at $n files — kept, it resumes from there")
                }
            }
        }
        jobs.add(job); job.start()
    }

    /** Pinned folders not indexed yet (pinned before indexing existed, or never reachable so far), and interrupted ones. */
    fun buildMissing() {
        App.instance.store.current.sources.forEach { s -> s.pins.filterNot { has(s, it) }.forEach { build(s, it) } }
        resumeStopped()
    }

    /** Every pin of a source again (its ↻ Rescan; interrupted ones ask first). */
    fun rebuild(src: Source) = buildManual(src, src.pins)

    fun remove(src: Source, pin: String) {
        synchronized(parts) { parts.remove(key(src, pin)) }
        scope.launch { lock.withLock { byPin = byPin - key(src, pin); save(); savePartials(); version.value++ } }
    }

    // ---------- interrupted indexing: kept and resumed ----------

    /** One folder read during an interrupted run: its songs and its subfolders. */
    class Done(val songs: List<PlEntry>, val subs: List<String>)
    /** What a run gathered so far (folder → its songs and subfolders), kept across stops and app restarts. */
    class Partial(val at: Long) {
        val folders = java.util.concurrent.ConcurrentHashMap<String, Done>()
        @Volatile var dirty = false
        fun count() = folders.values.sumOf { it.songs.size }
        fun songs() = folders.values.flatMap { it.songs }
    }
    private val parts = HashMap<String, Partial>()
    private lateinit var partFile: File

    private fun loadPartials() {
        try {
            if (!partFile.exists()) return
            val o = JSONObject(partFile.readText())
            synchronized(parts) {
                o.keys().forEach { k ->
                    val p = o.getJSONObject(k); val part = Partial(p.optLong("at"))
                    val f = p.getJSONObject("folders")
                    f.keys().forEach { u ->
                        val d = f.getJSONObject(u); val sa = d.getJSONArray("s"); val da = d.getJSONArray("d")
                        part.folders[u] = Done((0 until sa.length()).map { PlEntry.fromJson(sa.getJSONObject(it)) }, (0 until da.length()).map { da.getString(it) })
                    }
                    parts[k] = part
                }
            }
        } catch (_: Exception) {}
    }

    private val partLock = Mutex()
    private suspend fun savePartials() = withContext(Dispatchers.IO) {
        partLock.withLock {
            try {
                val o = JSONObject()
                synchronized(parts) {
                    parts.forEach { (k, p) ->
                        p.dirty = false
                        val f = JSONObject()
                        p.folders.forEach { (u, d) -> f.put(u, JSONObject().put("s", JSONArray().also { a -> d.songs.forEach { a.put(it.toJson()) } })
                            .put("d", JSONArray().also { a -> d.subs.forEach { a.put(it) } })) }
                        o.put(k, JSONObject().put("at", p.at).put("folders", f))
                    }
                }
                val tmp = File(partFile.parentFile, partFile.name + ".tmp")
                tmp.writeText(o.toString()); tmp.renameTo(partFile)
            } catch (_: Exception) {}
        }
    }

    /**
     * Interrupted folders go on by themselves once conditions allow (no question): at app start, after background
     * work is resumed, the VPN goes off, the battery is back above 15 %, or the server answers again. Checked every
     * 5 minutes while some are waiting; a server that doesn't answer is skipped quietly until the next check.
     */
    fun resumeStopped() {
        val waiting = synchronized(parts) { parts.keys.toList() }
        if (waiting.isEmpty()) return
        val srcs = App.instance.store.current.sources
        scope.launch {
            waiting.forEach { k ->
                val s = srcs.firstOrNull { it.id == k.substringBefore('|') }
                val pin = s?.pins?.firstOrNull { key(s, it) == k }
                if (s == null || pin == null) { synchronized(parts) { parts.remove(k) }; savePartials(); return@forEach }   // pin gone
                if (k in building) return@forEach
                if (!Net.reachable(Urls.hostOnly(s.host), if (s.port > 0) s.port else s.defaultPort)) return@forEach
                build(s, pin)
            }
        }
        startWatch()
    }
    private var watch: kotlinx.coroutines.Job? = null
    private fun startWatch() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60_000L)
                if (building.isEmpty() && !rgBusy()) { resumeStopped(); autoLan() }
            }
        }
    }

    private suspend fun crawl(src: Source, uri: String, depth: Int, files: kotlinx.coroutines.sync.Semaphore,
                              dirs: kotlinx.coroutines.sync.Semaphore, part: Partial, progress: (Int) -> Unit): List<PlayItem> {
        if (depth > 12) return emptyList()
        val fk = VpnLink.unroute(uri)
        val (here, subs) = part.folders[fk]?.let { d -> d.songs.map { MusicLibrary.fromPlEntry(it) } to d.subs } ?: run {
            // The pinned folder itself can't be read (NAS off, no network): keep the old index, don't store an empty one.
            // A subfolder that can't be read while the server no longer answers: the run stops (kept, resumed later);
            // the server answers but that folder can't be read: it's left out, as before.
            val list = dirs.withPermit { Browse.list(src, uri) }.getOrElse { e ->
                if (depth == 0 || !Net.reachable(Urls.hostOnly(src.host), if (src.port > 0) src.port else src.defaultPort)) throw e
                else return emptyList()
            }
            // Tags: read once and kept (the same cache the Libraries pages use), so a rescan is quick.
            val tagged = MusicLibrary.scanTags(src, list, force = false, sem = files)
            // Songs in album order, then the videos (08:13: indexed the same way), by name.
            val h = MusicLibrary.playOrder(tagged.filter { it.isAudio }, Urls.nameOf(uri)).map { MusicLibrary.toPlayItem(it, src, uri) } +
                tagged.filter { it.isVideo }.sortedBy { it.name.lowercase() }.map { MusicLibrary.toPlayItem(it, src, uri) }
            val sd = tagged.filter { it.isDir }.map { it.uri }
            part.folders[fk] = Done(h.map { MusicLibrary.toPlEntry(it) }, sd.map { VpnLink.unroute(it) }); part.dirty = true
            h to sd
        }
        progress(here.size)
        val sub = kotlinx.coroutines.coroutineScope {
            subs.map { d -> async { crawl(src, VpnLink.route(d), depth + 1, files, dirs, part, progress) } }.map { it.await() }
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
