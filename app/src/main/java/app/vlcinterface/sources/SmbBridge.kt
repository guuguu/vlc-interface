package app.vlcinterface.sources

import android.net.Uri
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Tiny local HTTP server (127.0.0.1 only) that streams SMB files through jcifs-ng, with byte-range support.
 * Lets libVLC play from servers it can't open itself, e.g. SMB1-only NAS boxes.
 */
object SmbBridge {
    private data class Target(val url: String, val version: String, val user: String, val pass: String, val domain: String)

    private val targets = ConcurrentHashMap<String, Target>()
    private var server: ServerSocket? = null
    private var port = 0

    @Synchronized
    private fun ensureStarted() {
        if (server != null) return
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        thread(name = "smb-bridge", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(s) }
            }
        }
    }

    fun urlFor(smbUrl: String, version: String, user: String, pass: String, domain: String): String {
        ensureStarted()
        val token = targets.entries.firstOrNull { it.value.url == smbUrl }?.key ?: UUID.randomUUID().toString().take(12)
        targets[token] = Target(smbUrl, version, user, pass, domain)
        return "http://127.0.0.1:$port/$token/" + Uri.encode(Uri.decode(smbUrl.substringAfterLast('/')))
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val request = input.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = input.readLine() ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = request.split(' ')
                val method = parts.getOrElse(0) { "GET" }
                val token = parts.getOrElse(1) { "/" }.trimStart('/').substringBefore('/')
                val t = targets[token]
                val out = s.getOutputStream()
                if (t == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
                }
                val ctx = SmbProbe.fileContext(t.version, t.user, t.pass, t.domain)
                SmbRandomAccessFile(SmbFile(Uri.decode(t.url), ctx), "r").use { raf ->
                    val size = raf.length()
                    var start = 0L
                    var end = size - 1
                    val range = headers["range"]
                    val partial = range != null && range.startsWith("bytes=")
                    if (partial) {
                        val r = range!!.removePrefix("bytes=").split('-')
                        start = r.getOrNull(0)?.toLongOrNull() ?: 0L
                        end = r.getOrNull(1)?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                    }
                    if (start >= size) {
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nConnection: close\r\n\r\n".toByteArray()); return
                    }
                    val len = end - start + 1
                    val head = StringBuilder()
                    head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    head.append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n")
                    head.append("Content-Length: $len\r\n")
                    if (partial) head.append("Content-Range: bytes $start-$end/$size\r\n")
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray())
                    if (method == "HEAD") return
                    raf.seek(start)
                    val buf = ByteArray(256 * 1024)
                    var left = len
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }
}
