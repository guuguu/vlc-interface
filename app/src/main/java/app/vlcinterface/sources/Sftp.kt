package app.vlcinterface.sources

import android.net.Uri
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The app's own SFTP client (modern SSH algorithms: works with current OpenSSH / Dropbear servers that libVLC's
 * older SSH library can't talk to). Lists folders and streams songs to libVLC through a local HTTP bridge
 * (127.0.0.1 only, byte ranges for seeking). One SSH session per server, reused; one channel per operation.
 */
object Sftp {
    private class Target(val uri: String, val user: String, val pass: String)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val homes = ConcurrentHashMap<String, String>()

    /** sftp://user@host:port/path → host, port, decoded path. */
    private fun parts(uri: String): Triple<String, Int, String> {
        val u = Uri.parse(VpnLink.route(uri))
        val path = Uri.decode(u.encodedPath ?: "/").ifEmpty { "/" }
        return Triple(u.host ?: "", if (u.port > 0) u.port else 22, path)
    }

    private fun session(host: String, port: Int, user: String, pass: String): Session {
        val key = "$user@$host:$port"
        sessions[key]?.let { if (it.isConnected) return it else sessions.remove(key) }
        synchronized(this) {
            sessions[key]?.let { if (it.isConnected) return it }
            val s = JSch().getSession(user, host, port)
            s.setPassword(pass)
            s.userInfo = object : UserInfo, UIKeyboardInteractive {
                override fun getPassphrase(): String? = null
                override fun getPassword(): String = pass
                override fun promptPassword(message: String?) = true
                override fun promptPassphrase(message: String?) = false
                override fun promptYesNo(message: String?) = true        // unknown host key: accepted (like the other SFTP apps on first use)
                override fun showMessage(message: String?) {}
                override fun promptKeyboardInteractive(dest: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?): Array<String> =
                    Array(prompt?.size ?: 0) { pass }
            }
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey")
            s.setServerAliveInterval(20_000)
            s.connect(10_000)
            sessions[key] = s
            return s
        }
    }

    private fun <T> withChannel(uri: String, user: String, pass: String, block: (ChannelSftp, String) -> T): T {
        val (host, port, path) = parts(uri)
        var lastErr: Exception? = null
        repeat(2) { attempt ->
            val s = try { session(host, port, user, pass) } catch (e: Exception) { throw e }
            val ch = try { (s.openChannel("sftp") as ChannelSftp).also { it.connect(10_000) } }
                catch (e: Exception) { sessions.remove("$user@$host:$port"); try { s.disconnect() } catch (_: Exception) {}; lastErr = e; null }
            if (ch != null) {
                try {
                    homes.putIfAbsent("$user@$host:$port", try { ch.home ?: "/" } catch (_: Exception) { "/" })
                    return block(ch, path)
                } finally { try { ch.disconnect() } catch (_: Exception) {} }
            }
            if (attempt == 1) throw lastErr ?: Exception("SFTP channel refused")
        }
        throw lastErr ?: Exception("SFTP channel refused")
    }

    /** Folder listing: folders first, then files; hidden (dot) entries left out. Relative paths tried from the home folder. */
    suspend fun list(uri: String, user: String, pass: String): Result<List<Entry>> = withContext(Dispatchers.IO) {
        try {
            val base = uri.trimEnd('/')
            val out = withChannel(uri, user, pass) { ch, path ->
                @Suppress("UNCHECKED_CAST")
                val v = try { ch.ls(path) as java.util.Vector<ChannelSftp.LsEntry> }
                catch (e: SftpException) {
                    // The path may be meant from the user's home folder (as many SFTP apps show it).
                    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE && path.startsWith("/")) ch.ls(path.removePrefix("/")) as java.util.Vector<ChannelSftp.LsEntry> else throw e
                }
                v.filter { it.filename != "." && it.filename != ".." }.map { e ->
                    val a = e.attrs
                    val isDir = a.isDir || (a.isLink && try { ch.stat(path.trimEnd('/') + "/" + e.filename).isDir } catch (_: Exception) { false })
                    Entry(e.filename, base + "/" + Uri.encode(e.filename), isDir, if (isDir) -1 else a.size)
                }
            }
            Result.success(out.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() })))
        } catch (e: Exception) { Result.failure(Exception(explain(e), e)) }
    }

    /** Plain words for what went wrong. */
    fun explain(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            e is SftpException && e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> "Folder not found on the server (check the path: as SSH shows it with pwd)"
            e is SftpException && e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> "Logged in, but this user may not read that folder"
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> "User or password not accepted by the SSH server"
            m.contains("timeout", true) -> "The server didn't answer (is it on, same Wi-Fi / VPN?)"
            m.contains("refused", true) -> "Port closed: no SSH server answering on that port"
            m.contains("subsystem", true) || m.contains("channel is not opened", true) -> "SSH works but the server has no SFTP (OpenWrt: install openssh-sftp-server)"
            m.contains("Algorithm negotiation fail", true) -> "The server and the app found no common encryption method"
            e is JSchException -> "SSH: $m"
            else -> m.ifEmpty { e.javaClass.simpleName }
        }
    }

    // ---------------------------------------------------------------- streaming bridge

    private val targets = ConcurrentHashMap<String, Target>()
    private var server: ServerSocket? = null
    private var port = 0

    @Synchronized
    private fun ensureStarted() {
        if (server != null) return
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        thread(name = "sftp-bridge", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(s) }
            }
        }
    }

    /** Local address libVLC plays the SFTP file from. */
    fun urlFor(uri: String, user: String, pass: String): String {
        ensureStarted()
        val token = targets.entries.firstOrNull { it.value.uri == uri && it.value.user == user }?.key ?: UUID.randomUUID().toString().take(12)
        targets[token] = Target(uri, user, pass)
        return "http://127.0.0.1:$port/$token/" + Uri.encode(Uri.decode(uri.substringAfterLast('/')))
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                val input = s.getInputStream().bufferedReader()
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
                if (t == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
                withChannel(t.uri, t.user, t.pass) { ch, path0 ->
                    val path = try { ch.lstat(path0); path0 } catch (_: SftpException) { path0.removePrefix("/") }
                    val size = ch.stat(path).size
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
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nConnection: close\r\n\r\n".toByteArray()); return@withChannel
                    }
                    val len = end - start + 1
                    val head = StringBuilder()
                    head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    head.append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n")
                    if (partial) head.append("Content-Range: bytes $start-$end/$size\r\n")
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray())
                    if (method == "HEAD") return@withChannel
                    ch.get(path, null, start).use { ins ->
                        val buf = ByteArray(128 * 1024)
                        var left = len
                        while (left > 0) {
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }
}
