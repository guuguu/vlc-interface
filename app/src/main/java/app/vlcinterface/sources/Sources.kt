package app.vlcinterface.sources

import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.vlcinterface.App
import app.vlcinterface.data.Source
import kotlinx.coroutines.Dispatchers
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
)

val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "ts", "m2ts", "webm", "mpg", "mpeg", "flv", "3gp", "ogv", "vob", "iso")
val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac")
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

    suspend fun reachable(host: String, port: Int, timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: Exception) {
            false
        }
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
                            val u = media.uri?.toString() ?: return
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
                    browser.browse(Uri.parse(uri), MediaBrowser.Flag.Interact or MediaBrowser.Flag.NoSlavesAutodetect)
                    cont.invokeOnCancellation { main.post { holder[0]?.release(); holder[0] = null } }
                }
            }
        }
        return if (result == null) Result.failure(Exception("No answer from the server")) else Result.success(
            result.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
        )
    }

    /** Servers announced on the LAN (SMB, UPnP/DLNA, ...). Collects for a few seconds. */
    suspend fun discover(seconds: Long = 6): List<Entry> {
        val out = ArrayList<Entry>()
        val browser = withContext(Dispatchers.Main) {
            MediaBrowser(App.instance.player.libVlc, object : MediaBrowser.EventListener {
                override fun onMediaAdded(index: Int, media: IMedia) {
                    val u = media.uri?.toString() ?: return
                    val title = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() } ?: Urls.nameOf(u)
                    synchronized(out) { out += Entry(title, u, true, meta = u.substringBefore("://").uppercase()) }
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

/** Browses any saved source with the right backend. */
object Browse {
    suspend fun list(s: Source, uri: String): Result<List<Entry>> = when (s.proto) {
        "webdav" -> WebDav.list(uri, s.user, s.password)
        "http" -> HttpIndex.list(uri, s.user, s.password)
        else -> VlcBrowse.browse(uri, s.user, s.password)
    }
}
