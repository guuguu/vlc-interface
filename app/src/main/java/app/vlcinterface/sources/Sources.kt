package app.vlcinterface.sources

import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.vlcinterface.App
import app.vlcinterface.data.ServerLog
import app.vlcinterface.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class Entry(
    val name: String,
    val uri: String,
    val isDir: Boolean,
    val size: Long = -1,
    val meta: String = "",
    /** Tags (music): empty / 0 when unknown. */
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val year: Int = 0,
    val track: Int = 0,
    val disc: Int = 0,
    val durationMs: Long = 0,
    val artwork: String = "",
) {
    val isAudio: Boolean get() = !isDir && name.ext() in AUDIO_EXT
    val isVideo: Boolean get() = !isDir && name.ext() in VIDEO_EXT
    val display: String get() = title.ifEmpty { name }
}

// Every format VLC plays (2026-11-23 01:11: VLC showed more music on another phone — audiobooks .m4b, voice notes / recordings
// .amr .3ga .awb, MIDI, Dolby / DTS, tracker modules … were left out here).
val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "ts", "m2ts", "webm", "mpg", "mpeg", "flv", "3gp", "ogv", "vob", "iso",
    "mts", "m2t", "divx", "xvid", "asf", "rm", "rmvb", "f4v", "mxf", "ogm", "dv", "3g2", "m2v", "mp4v", "h264", "hevc", "264", "265", "wtv",
    "trp", "tp", "evo", "vro", "nut", "mpe", "mpv")
val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "aiff", "aif", "dsf", "dff", "mka", "wv", "mpc",
    "m4b", "m4r", "amr", "awb", "3ga", "mid", "midi", "kar", "rmi", "oga", "ogx", "weba", "aifc", "caf", "ac3", "eac3", "ec3", "dts", "tta", "mp2",
    "mp1", "mpga", "spx", "ra", "tak", "xm", "it", "s3m", "mod", "au", "snd", "voc", "w64", "rf64", "dts", "mlp", "thd", "shn", "ofr", "xa")
val SUB_EXT = setOf("srt", "ass", "ssa", "sub", "vtt", "idx")

fun String.ext(): String = substringAfterLast('/').substringAfterLast('.', "").lowercase()
fun isPlayable(name: String) = name.ext().let { it in VIDEO_EXT || it in AUDIO_EXT }

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    const val UA = "vlc-interface/0.1"

    suspend fun reachable(host: String, port: Int, timeoutMs: Int = 1500): Boolean {
        // Never longer than the time given (2026-11-22 19:37: searches hung on an unreachable NAS): the name lookup
        // before the connection has no time limit of its own, so the check runs apart and is given up when time's up.
        val d = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            try {
                // LAN servers: their VPN address when that's the one in use.
                Socket().use { it.connect(InetSocketAddress(VpnLink.routeHost(host), port), timeoutMs) }
                true
            } catch (e: Exception) {
                false
            }
        }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 300L) { d.await() } ?: false
    }
}

/** Builds the URL libVLC plays / browses for a saved source and a path inside it. */
object Urls {
    fun hostOnly(h: String) = h.removePrefix("https://").removePrefix("http://").trim('/').substringBefore('/')

    fun forSource(s: Source, subPath: String = ""): String {
        val path = joinPath(s.path, subPath)
        val portPart = if (s.port > 0 && s.port != s.defaultPort) ":${s.port}" else ""
        return when (s.proto) {
            "upnp" -> if (subPath.startsWith("upnp://")) subPath else s.host
            "webdav", "http", "m3u" -> {
                val base = if (s.host.startsWith("http")) s.host.trimEnd('/') else "https://${s.host.trimEnd('/')}"
                base + portPart + encodePath(path)
            }
            else -> "${s.proto}://${hostOnly(s.host)}$portPart${encodePath(path)}"
        }
    }

    fun joinPath(a: String, b: String): String {
        val parts = (a.split('/') + b.split('/')).filter { it.isNotEmpty() }
        return "/" + parts.joinToString("/")
    }

    fun encodePath(p: String): String = p.split('/').joinToString("/") { if (it.isEmpty()) it else Uri.encode(it) }

    fun nameOf(uri: String): String = Uri.decode(uri.trimEnd('/').substringAfterLast('/')).ifEmpty { uri }

    fun parent(uri: String): String = uri.trimEnd('/').substringBeforeLast('/', uri) + "/"
}

/** Browsing through libVLC itself: SMB, NFS, FTP(S), SFTP and UPnP/DLNA. */
object VlcBrowse {
    private val main = Handler(Looper.getMainLooper())

    suspend fun browse(uri: String, user: String = "", pass: String = "", timeoutMs: Long = 25_000): Result<List<Entry>> {
        App.instance.player.setCredentials(user, pass)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<List<Entry>> { cont ->
                val out = ArrayList<Entry>()
                main.post {
                    val holder = arrayOfNulls<MediaBrowser>(1)
                    val browser = MediaBrowser(App.instance.player.libVlc, object : MediaBrowser.EventListener {
                        override fun onMediaAdded(index: Int, media: IMedia) {
                            val u = VpnLink.unroute(media.uri?.toString() ?: return)
                            val title = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() } ?: Urls.nameOf(u)
                            out += Entry(title, u, media.type == IMedia.Type.Directory)
                        }
                        override fun onMediaRemoved(index: Int, media: IMedia) {}
                        override fun onBrowseEnd() {
                            val b = holder[0]
                            holder[0] = null
                            main.post { b?.release() }
                            if (cont.isActive) cont.resume(out.toList())
                        }
                    }, main)
                    holder[0] = browser
                    // libVLC hides srt/sub/idx/ssa/cue/txt/images by default ("ignore-filetypes"), so a NAS folder's subtitles never
                    // showed (14:11, "No folders or subtitle files here" next to Episode 15.en.srt). The app filters its own lists.
                    browser.setIgnoreFileTypes("db,ini,sfv,lnk,url")
                    browser.browse(Uri.parse(VpnLink.route(uri)), MediaBrowser.Flag.Interact or MediaBrowser.Flag.NoSlavesAutodetect)
                    cont.invokeOnCancellation { main.post { holder[0]?.release(); holder[0] = null } }
                }
            }
        }
        return if (result == null) Result.failure(Exception("No answer from the server")) else Result.success(
            result.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
        )
    }

    /** Servers announced on the LAN (SMB, UPnP/DLNA, ...). Collects for a few seconds. */
    /** Servers already logged as found during this app run (logged once, not on every LAN tab visit). */
    private val announced = java.util.Collections.synchronizedSet(HashSet<String>())

    suspend fun discover(seconds: Long = 6): List<Entry> {
        val out = ArrayList<Entry>()
        val browser = withContext(Dispatchers.Main) {
            MediaBrowser(App.instance.player.libVlc, object : MediaBrowser.EventListener {
                override fun onMediaAdded(index: Int, media: IMedia) {
                    val u = media.uri?.toString() ?: return
                    val title = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() } ?: Urls.nameOf(u)
                    synchronized(out) { out += Entry(title, u, true, meta = u.substringBefore("://").uppercase()) }
                    // Servers that only announce themselves still get a log page.
                    if (announced.add(u)) ServerLog.log(u, "discover", u.substringBefore("://").uppercase(), "", true, "Announced itself on the LAN as '$title' ($u)")
                }
                override fun onMediaRemoved(index: Int, media: IMedia) {}
                override fun onBrowseEnd() {}
            }, main).also { it.discoverNetworkShares() }
        }
        kotlinx.coroutines.delay(seconds * 1000)
        withContext(Dispatchers.Main) { browser.release() }
        return synchronized(out) { out.distinctBy { it.uri } }
    }
}

/**
 * Never shown, played, indexed or added: names starting with a dot (hidden files and folders, e.g. macOS "._song.flac")
 * and anything the user took out with "Remove from lists" (blacklist kept in the settings; the files themselves stay).
 */
object Ignore {
    /**
     * Unreadable files are never shown (2026-11-23 01:08): the offline downloads of streaming apps — encrypted, only their
     * own app can play them (Spotify, Deezer, Tidal, Apple Music, YouTube Music, Amazon Music, SoundCloud, Qobuz) — names
     * or titles that aren't text (scrambled bytes, "�"), and phone files the player found it can't open at all.
     */
    private val DRM_DIRS = listOf("com.spotify.", "com.deezer.", "com.aspiro.tidal", "com.apple.android.music", "com.google.android.apps.youtube.music",
        "com.amazon.mp3", "com.soundcloud.", "com.qobuz.")
    fun encryptedPath(uri: String): Boolean {
        val d = android.net.Uri.decode(uri)
        return d.contains("/Android/data/") && DRM_DIRS.any { d.contains("/Android/data/$it") } ||
            DRM_DIRS.any { d.contains("/Android/media/$it") }
    }
    /** Text that isn't really text: the replacement sign, control characters, or mostly unusual symbols. */
    fun garbled(t: String): Boolean {
        if (t.isEmpty()) return false
        if (t.contains('\uFFFD')) return true
        // By code point, so emoji and every script (Chinese, Arabic, Cyrillic …) count as text; only control characters,
        // private-use, unassigned and broken surrogate halves count as scrambled.
        var odd = 0; var n = 0
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val type = Character.getType(cp)
            if ((Character.isISOControl(cp) && cp != 9) || type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() ||
                type == Character.SURROGATE.toInt()) odd++
            n++
            i += Character.charCount(cp)
        }
        return odd >= 3 || (odd > 0 && odd * 5 >= n)
    }
    /** The phone files the player couldn't open by any way (kept, so they stay hidden). */
    private val prefs get() = app.vlcinterface.App.instance.getSharedPreferences("unreadable", android.content.Context.MODE_PRIVATE)
    @Volatile private var bad: Set<String>? = null
    fun markUnreadable(uri: String) {
        val now = (bad ?: prefs.getStringSet("uris", emptySet())!!.toSet()) + uri
        bad = now; prefs.edit().putStringSet("uris", now).apply()
    }
    fun markedUnreadable(uri: String) = uri in (bad ?: prefs.getStringSet("uris", emptySet())!!.toSet().also { bad = it })
    fun unreadable(uri: String, name: String, title: String = "") =
        encryptedPath(uri) || garbled(android.net.Uri.decode(name)) || garbled(title) || markedUnreadable(uri)
    fun dotName(name: String) = name.trimStart().startsWith(".")
    /** A path with any hidden part ("Music/.thumbnails/x.mp3"). */
    fun dotPath(path: String) = android.net.Uri.decode(path).split('/').any { it.startsWith(".") }
    fun blacklisted(uri: String) = uri in app.vlcinterface.App.instance.store.current.settings.hiddenUris
    fun entry(e: Entry) = dotName(e.name) || blacklisted(e.uri) || unreadable(e.uri, e.name, e.display)
}

/** Browses any saved source with the right backend. */
object Browse {
    /** Raw listing (all files); used for subtitles, covers, .cue lookups. */
    /** Every listing of every saved server is written to that server's log page ([action] says why it was listed). */
    suspend fun listAll(s: Source, uri: String, action: String = "browse"): Result<List<Entry>> {
        val started = System.currentTimeMillis()
        val proto = s.proto.uppercase() + if (s.proto == "smb" && s.smbVersion.isNotEmpty()) " ${s.smbVersion}" else ""
        val what = android.net.Uri.decode(uri.substringAfter("://").substringAfter('/', "")).ifEmpty { "/" }
        val share = what.substringBefore('/')
        val first = when (s.proto) {
            "webdav" -> WebDav.list(uri, s.user, s.password)
            "http" -> HttpIndex.list(uri, s.user, s.password)
            "sftp" -> Sftp.list(uri, s.user, s.password)      // the app's own SFTP client (modern SSH)
            else -> VlcBrowse.browse(uri, s.user, s.password)
        }
        val r = if (first.isSuccess || s.proto != "smb") first else {
            // libVLC couldn't open it (e.g. SMB1-only): list through jcifs-ng instead.
            val e = first.exceptionOrNull()!!
            ServerLog.log(s.host, action, "$proto (libVLC)", s.user, false,
                "libVLC could not list $what (${e.message ?: "no reason given"}) — trying the app's SMB client", SmbErrors.ntStatus(e), started)
            SmbProbe.list(Urls.hostOnly(s.host), what.takeIf { it != "/" } ?: "", s.smbVersion, s.user, s.password, s.domain)
        }
        r.onSuccess { l ->
            ServerLog.log(s.host, action, proto, s.user, true, "Listed $what: ${l.size} entries", startedAt = started)
        }.onFailure { e ->
            if (s.proto == "sftp") ServerLog.log(s.host, action, "SFTP (app client)", s.user, false, e.message ?: "Could not list $what",
                (e.cause ?: e).toString(), started)
            else ServerLog.log(s.host, action, proto, s.user, false, SmbErrors.explain(e, s.user.isEmpty(), share), SmbErrors.ntStatus(e), started)
        }
        return r
    }

    /** Library listing: folders plus audio and video files only. */
    suspend fun list(s: Source, uri: String, action: String = "browse"): Result<List<Entry>> =
        listAll(s, uri, action).map { l -> l.filter { (it.isDir || isPlayable(it.name)) && !Ignore.entry(it) } }
}
