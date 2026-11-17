package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import app.vlcinterface.data.Settings
import app.vlcinterface.data.Source
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SubResult(
    val fileId: Long,
    val release: String,
    val language: String,
    val format: String,
    val downloads: Int,
    val rating: Double,
    val hashMatch: Boolean,
    val hearingImpaired: Boolean,
) {
    val match: String get() = if (hashMatch) "Exact file" else "Title match"
}

/** OpenSubtitles "moviehash": file size + sum of the first and last 64 KiB as little-endian 64-bit words. */
object OsHash {
    private const val CHUNK = 65536

    private fun sum(bytes: ByteArray): Long {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var s = 0L
        while (bb.remaining() >= 8) s += bb.long
        return s
    }

    private fun fmt(size: Long, head: ByteArray, tail: ByteArray) =
        String.format("%016x", size + sum(head) + sum(tail))

    /** Works for content://, smb:// and http(s):// items; reads only 128 KiB, so it is quick over the network. */
    suspend fun compute(ctx: Context, uri: String, source: Source?): Pair<String, Long>? = withContext(Dispatchers.IO) {
        try {
            when {
                uri.startsWith("content://") || uri.startsWith("file://") -> {
                    ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                        val ch = FileInputStream(pfd.fileDescriptor).channel
                        val size = ch.size()
                        if (size < CHUNK * 2) return@withContext null
                        val head = ByteBuffer.allocate(CHUNK); ch.read(head, 0)
                        val tail = ByteBuffer.allocate(CHUNK); ch.read(tail, size - CHUNK)
                        fmt(size, head.array(), tail.array()) to size
                    }
                }
                uri.startsWith("smb://") -> {
                    val s = source ?: return@withContext null
                    val f = SmbFile(VpnLink.route(Uri.decode(uri)), SmbProbe.fileContext(s.smbVersion, s.user, s.password, s.domain))
                    SmbRandomAccessFile(f, "r").use { raf ->
                        val size = raf.length()
                        if (size < CHUNK * 2) return@withContext null
                        val head = ByteArray(CHUNK); raf.seek(0); raf.readFully(head)
                        val tail = ByteArray(CHUNK); raf.seek(size - CHUNK); raf.readFully(tail)
                        fmt(size, head, tail) to size
                    }
                }
                uri.startsWith("http://") || uri.startsWith("https://") -> {
                    fun range(from: Long, to: Long): Pair<ByteArray, Long>? {
                        val b = Request.Builder().url(uri).header("Range", "bytes=$from-$to").header("User-Agent", Net.UA)
                        if (source != null && source.user.isNotEmpty()) b.header("Authorization", okhttp3.Credentials.basic(source.user, source.password))
                        Net.http.newCall(b.build()).execute().use { r ->
                            if (r.code != 206) return null
                            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: return null
                            return (r.body?.bytes() ?: return null) to total
                        }
                    }
                    val (head, size) = range(0, CHUNK - 1L) ?: return@withContext null
                    val (tail, _) = range(size - CHUNK, size - 1) ?: return@withContext null
                    fmt(size, head, tail) to size
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Guesses "Title" and year from a release-style file name. */
fun guessTitle(fileName: String): Pair<String, String> {
    val base = fileName.substringAfterLast('/').substringBeforeLast('.')
    val clean = base.replace(Regex("[._]+"), " ").trim()
    val year = Regex("""\b(19\d\d|20\d\d)\b""").find(clean)
    val stop = Regex("""\b(2160p|1080p|720p|480p|bluray|blu ray|web ?dl|webrip|hdtv|x264|x265|h264|h265|hevc|dvdrip|remux|s\d\de\d\d)\b""", RegexOption.IGNORE_CASE).find(clean)
    val cut = listOfNotNull(year?.range?.first, stop?.range?.first).minOrNull() ?: clean.length
    return clean.substring(0, cut).trim().trimEnd('(', '[', '-').trim() to (year?.value ?: "")
}

/** OpenSubtitles.com REST API. Needs the user's own API key; downloads need their (free) account. */
class OpenSubtitles(private val settings: Settings) {
    private val base = "https://api.opensubtitles.com/api/v1"
    private var token: String = ""

    private fun req(path: String) = Request.Builder().url(base + path)
        .header("Api-Key", settings.osApiKey).header("User-Agent", "vlc-interface v0.1")
        .header("Accept", "application/json")

    val configured: Boolean get() = settings.osApiKey.isNotBlank()

    suspend fun search(hash: String?, query: String, year: String, languages: List<String>): Result<List<SubResult>> =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext Result.failure(Exception("Add your OpenSubtitles API key in Settings"))
            try {
                val q = Uri.parse("$base/subtitles").buildUpon()
                if (!hash.isNullOrEmpty()) q.appendQueryParameter("moviehash", hash)
                if (query.isNotBlank()) q.appendQueryParameter("query", query.lowercase())
                if (year.isNotBlank()) q.appendQueryParameter("year", year)
                q.appendQueryParameter("languages", languages.joinToString(",") { it.lowercase() }.ifEmpty { "en" })
                val url = q.build().toString().removePrefix(base)
                Net.http.newCall(req(url).get().build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext Result.failure(Exception("OpenSubtitles answered ${r.code}"))
                    val data = JSONObject(r.body?.string() ?: "{}").optJSONArray("data") ?: return@withContext Result.success(emptyList())
                    val list = (0 until data.length()).mapNotNull { i ->
                        val a = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
                        val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                        SubResult(
                            fileId = f.optLong("file_id"),
                            release = a.optString("release").ifEmpty { f.optString("file_name") },
                            language = a.optString("language"),
                            format = f.optString("file_name").ext().ifEmpty { "srt" }.uppercase(),
                            downloads = a.optInt("download_count"),
                            rating = a.optDouble("ratings", 0.0),
                            hashMatch = a.optBoolean("moviehash_match"),
                            hearingImpaired = a.optBoolean("hearing_impaired"),
                        )
                    }
                    Result.success(list.sortedWith(compareByDescending<SubResult> { it.hashMatch }.thenByDescending { it.downloads }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun login(): String? {
        if (token.isNotEmpty()) return token
        if (settings.osUser.isBlank()) return null
        val body = JSONObject().put("username", settings.osUser).put("password", settings.osPassword).toString()
        Net.http.newCall(req("/login").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            token = JSONObject(r.body?.string() ?: "{}").optString("token")
            return token.ifEmpty { null }
        }
    }

    /** Downloads one subtitle; returns its bytes and its own file extension (srt, ass, …). */
    suspend fun download(res: SubResult): Result<Pair<ByteArray, String>> = withContext(Dispatchers.IO) {
        try {
            val t = login() ?: return@withContext Result.failure(Exception("Add your OpenSubtitles username and password in Settings"))
            val body = JSONObject().put("file_id", res.fileId).toString()
            var fileName = ""
            val link = Net.http.newCall(
                req("/download").header("Authorization", "Bearer $t")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute().use { r ->
                val txt = r.body?.string() ?: ""
                if (!r.isSuccessful) return@withContext Result.failure(Exception(JSONObject(txt.ifEmpty { "{}" }).optString("message", "Download refused (${r.code})")))
                val o = JSONObject(txt)
                fileName = o.optString("file_name")
                o.optString("link")
            }
            val bytes = Net.http.newCall(Request.Builder().url(link).build()).execute().use { r -> r.body?.bytes() ?: ByteArray(0) }
            val ext = fileName.ext().ifEmpty { res.format.lowercase() }.ifEmpty { "srt" }
            Result.success(bytes to ext)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
