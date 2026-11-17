package app.vlcinterface.sources

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.vlcinterface.player.PlayItem
import java.io.File

/**
 * The real place of a file, never a content:// address: /storage/emulated/0/… for phone files (media library or
 * folder access), smb://server/share/… for network shares (no login in it), the web address for web files.
 */
object PhysicalPath {
    fun of(ctx: Context, item: PlayItem): String = of(ctx, item.uri, item.folderKey, item.fileName)

    fun of(ctx: Context, uri: String, folderKey: String = "", fileName: String = ""): String {
        if (uri.startsWith("content://")) {
            val u = Uri.parse(uri)
            // Folder access (chosen folders): the document id holds the place.
            if (u.authority == "com.android.externalstorage.documents") try {
                val id = DocumentsContract.getDocumentId(u)
                val vol = id.substringBefore(':', ""); val rel = id.substringAfter(':', "")
                if (vol.isNotEmpty()) return if (vol == "primary") File(Environment.getExternalStorageDirectory(), rel).path else "/storage/$vol/$rel"
            } catch (_: Exception) {}
            // Media library: its file path.
            try {
                @Suppress("DEPRECATION")
                ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            } catch (_: Exception) {}
            // Library folder known: volume + relative path + name.
            if (folderKey.startsWith("local:")) {
                val id = folderKey.removePrefix("local:")
                val vol = id.substringBefore(':'); val rel = id.substringAfter(':')
                val base = if (vol == MediaStore.VOLUME_EXTERNAL_PRIMARY || vol == "external") Environment.getExternalStorageDirectory().path else "/storage/$vol"
                return "$base/$rel" + fileName
            }
            return fileName.ifEmpty { "(phone file)" }
        }
        if (uri.startsWith("file://")) return Uri.parse(uri).path ?: Uri.decode(uri)
        // Network: never the password, even when the address holds one.
        return Uri.decode(uri).replace(Regex("^(\\w+://)[^/@]*@"), "$1")
    }
}
