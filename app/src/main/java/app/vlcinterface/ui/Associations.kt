package app.vlcinterface.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * File associations (2026-11-22 08:24): which media files and radio links (not http / https) open with the app.
 * Each type is its own entry in the app's manifest (an activity alias with that type's filter), switched on or off
 * here — "registered" means Android offers the app for that type. Being the *main* app for a type is Android's own
 * choice ("Always" in its app picker, or "Open by default" in another app's settings): apps can't set it themselves,
 * so the app guides through those steps and checks the result.
 */
class AssocType(val group: String, val key: String, val label: String, val exts: String, val mimes: List<String>) {
    val isLink get() = group == "link"
    val component get() = ComponentName(app.vlcinterface.App.instance.packageName, "app.vlcinterface.Assoc_$key")
}

object Associations {
    val TYPES = listOf(
        AssocType("audio", "mp3", "MP3", "mp3", listOf("audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3")),
        AssocType("audio", "flac", "FLAC", "flac", listOf("audio/flac", "audio/x-flac")),
        AssocType("audio", "m4a", "AAC / M4A / ALAC", "m4a, aac, alac", listOf("audio/mp4", "audio/aac", "audio/x-aac", "audio/m4a", "audio/x-m4a", "audio/mp4a-latm")),
        AssocType("audio", "ogg", "Ogg / Opus", "ogg, oga, opus", listOf("audio/ogg", "audio/opus", "audio/vorbis", "audio/x-vorbis+ogg", "application/ogg")),
        AssocType("audio", "wav", "WAV / AIFF", "wav, aif, aiff", listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/aiff", "audio/x-aiff")),
        AssocType("audio", "wma", "WMA", "wma", listOf("audio/x-ms-wma")),
        AssocType("audio", "hires", "APE / WavPack / DSD", "ape, wv, dsf, dff", listOf("audio/ape", "audio/x-ape", "audio/x-monkeys-audio", "audio/x-wavpack", "audio/wavpack", "audio/dsf", "audio/x-dsf", "audio/x-dff", "audio/dff")),
        AssocType("audio", "mka", "Matroska audio", "mka", listOf("audio/x-matroska")),
        AssocType("audio", "midi", "MIDI / AMR / other", "mid, amr, 3ga", listOf("audio/midi", "audio/x-midi", "audio/amr", "audio/3gpp", "audio/3gpp2")),
        AssocType("video", "mp4", "MP4 / MOV", "mp4, m4v, mov", listOf("video/mp4", "video/quicktime", "video/x-m4v")),
        AssocType("video", "mkv", "MKV", "mkv", listOf("video/x-matroska", "video/matroska")),
        AssocType("video", "avi", "AVI", "avi, divx", listOf("video/x-msvideo", "video/avi", "video/divx", "video/x-divx")),
        AssocType("video", "webm", "WebM", "webm", listOf("video/webm")),
        AssocType("video", "ts", "TS / M2TS", "ts, m2ts, mts", listOf("video/mp2t", "video/MP2T", "video/vnd.dlna.mpeg-tts")),
        AssocType("video", "mpeg", "MPEG / VOB", "mpg, mpeg, vob", listOf("video/mpeg", "video/x-mpeg", "video/dvd")),
        AssocType("video", "wmv", "WMV / ASF", "wmv, asf", listOf("video/x-ms-wmv", "video/x-ms-asf")),
        AssocType("video", "flv", "FLV", "flv", listOf("video/x-flv", "video/flv")),
        AssocType("video", "3gp", "3GP", "3gp, 3g2", listOf("video/3gpp", "video/3gpp2")),
        AssocType("video", "ogv", "Ogg video", "ogv", listOf("video/ogg")),
        AssocType("playlist", "pls", "PLS radio playlists", "pls", listOf("audio/x-scpls", "audio/scpls", "application/pls+xml")),
        AssocType("playlist", "m3u", "M3U playlists", "m3u, m3u8", listOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl")),
        AssocType("playlist", "xspf", "XSPF playlists", "xspf", listOf("application/xspf+xml")),
        AssocType("link", "icy", "icy:// radio links", "icy://", listOf("icy")),
        AssocType("link", "mms", "mms:// streams", "mms://, mmsh://", listOf("mms", "mmsh")),
        AssocType("link", "rtsp", "rtsp:// streams", "rtsp://", listOf("rtsp")),
        AssocType("link", "rtmp", "rtmp:// streams", "rtmp://", listOf("rtmp"))
    )
    val GROUPS = listOf("audio" to "Audio files", "video" to "Video files", "playlist" to "Radio playlists", "link" to "Radio links")

    /** Marks the test opening ("Make main"): the app then only says it's the main app, nothing is played. */
    const val EXTRA_TEST = "vlc_assoc_test"

    private fun pm(ctx: Context) = ctx.packageManager

    fun registered(ctx: Context, t: AssocType): Boolean = when (pm(ctx).getComponentEnabledSetting(t.component)) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
        else -> true            // default = as in the manifest (on)
    }

    /** Register / deregister the app for [t] (Android then offers it, or not, for that type). */
    fun setRegistered(ctx: Context, t: AssocType, on: Boolean) {
        try {
            pm(ctx).setComponentEnabledSetting(t.component,
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}
    }

    /** An "open this" request for the type, as other apps send it. */
    fun probe(t: AssocType): Intent =
        if (t.isLink) Intent(Intent.ACTION_VIEW, Uri.parse(t.mimes.first() + "://example.invalid/stream"))
        else Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/file/1"), t.mimes.first())

    /** Who opens the type now. */
    sealed class Opener {
        object Me : Opener()                              // the app is the main app
        object Asks : Opener()                            // Android asks each time
        object Nobody : Opener()                          // no app at all
        class Other(val pkg: String, val name: String) : Opener()
    }

    fun opener(ctx: Context, t: AssocType): Opener {
        return try {
            val r = pm(ctx).resolveActivity(probe(t), PackageManager.MATCH_DEFAULT_ONLY) ?: return Opener.Nobody
            val pkg = r.activityInfo?.packageName ?: return Opener.Nobody
            when {
                pkg == ctx.packageName -> Opener.Me
                pkg == "android" || r.activityInfo.name.contains("Resolver", true) || r.activityInfo.name.contains("Chooser", true) -> Opener.Asks
                else -> Opener.Other(pkg, r.loadLabel(pm(ctx)).toString())
            }
        } catch (_: Exception) { Opener.Nobody }
    }

    /** A real file of that type on the phone, to open once so Android's picker can be answered "Always". */
    fun sampleFile(ctx: Context, t: AssocType): Uri? {
        if (t.isLink) return null
        return try {
            val coll = android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
            val sel = t.mimes.joinToString(" OR ") { android.provider.MediaStore.MediaColumns.MIME_TYPE + "=?" }
            ctx.contentResolver.query(coll, arrayOf(android.provider.MediaStore.MediaColumns._ID), sel, t.mimes.toTypedArray(), null)?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(coll, c.getLong(0)) else null
            }
        } catch (_: Exception) { null }
    }

    /** Opens a file / link of the type the way other apps do, so Android's picker shows (choose the app, "Always"). */
    fun openTest(ctx: Context, t: AssocType): Boolean {
        val i = if (t.isLink) probe(t) else {
            val u = sampleFile(ctx, t) ?: return false
            Intent(Intent.ACTION_VIEW).setDataAndType(u, t.mimes.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try { ctx.startActivity(i.putExtra(EXTRA_TEST, t.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** The other app's own settings page (there: "Open by default" → "Clear defaults"). */
    fun openAppSettings(ctx: Context, pkg: String) {
        try { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    /** Stop being the main app for every type (Android then asks again, or uses another app). */
    @Suppress("DEPRECATION")
    fun clearMine(ctx: Context) { try { pm(ctx).clearPackagePreferredActivities(ctx.packageName) } catch (_: Exception) {} }
}

/** Settings → File associations. */
@Composable
fun ColumnScope.AssociationSettings() {
    val ctx = LocalContext.current
    val helpersOn = app.vlcinterface.alarm.Alarms.state.collectAsState().value.global.helpers
    // Re-read when coming back to the page (the answers are given in Android's own screens).
    val tick = resumeTick()
    var changed by remember { mutableIntStateOf(0) }
    var main by remember { mutableStateOf<AssocType?>(null) }
    var help by remember { mutableStateOf("") }
    if (help.isNotEmpty()) HelperDialog(help, SettingHelp.text(help, "")) { help = "" }
    Text("Which media files and radio links open with this app. Long-press any row for its explanation.", color = C.muted, fontSize = 13.sp,
        modifier = Modifier.anchor("File associations").explain("File associations"))
    Associations.GROUPS.forEach { (g, title) ->
        SectionTitle(title)
        CardBox {
            Associations.TYPES.filter { it.group == g }.forEachIndexed { i, t ->
                if (i > 0) Divider()
                val on = remember(tick, changed) { Associations.registered(ctx, t) }
                val who = remember(tick, changed) { Associations.opener(ctx, t) }
                val status = when {
                    !on -> "Not offered for this type"
                    who is Associations.Opener.Me -> "Main app ✓"
                    who is Associations.Opener.Asks -> "Offered · Android asks which app each time"
                    who is Associations.Opener.Other -> "Offered · main app now: ${who.name}"
                    else -> "Offered"
                }
                ToggleRow("${t.label} (${t.exts})", status, on) { v ->
                    Associations.setRegistered(ctx, t, v); changed++
                    if (helpersOn) help = if (v) "Register for a type" else "Deregister a type"
                }
                if (on && who !is Associations.Opener.Me) Row0Link("Make it the main app for ${t.label}…") { main = t }
            }
        }
    }
    SectionTitle("All types")
    CardBox {
        ValueRow("Register for every type", "The app is offered for all the types above", "Register") {
            Associations.TYPES.forEach { Associations.setRegistered(ctx, it, true) }; changed++
            if (helpersOn) help = "Register for every type"
        }
        Divider()
        ValueRow("Deregister every type", "The app is no longer offered for any of them (still opens its own files)", "Deregister") {
            Associations.TYPES.forEach { Associations.setRegistered(ctx, it, false) }; changed++
            if (helpersOn) help = "Deregister every type"
        }
        Divider()
        ValueRow("Stop being the main app", "For every type it opens by default now: Android asks again", "Stop") {
            Associations.clearMine(ctx); changed++
            ShortNote.show("No longer the main app for any type")
            if (helpersOn) help = "Stop being the main app"
        }
    }
    main?.let { t -> MakeMainDialog(t, onChanged = { changed++ }) { main = null } }
}

/** A small accent link under a row (the page's own actions). */
@Composable
private fun Row0Link(text: String, onClick: () -> Unit) {
    STextButton(onClick, modifier = Modifier.padding(start = 8.dp)) { Text(text, color = accent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** The steps to become the main app for a type, as Android allows them. */
@Composable
private fun MakeMainDialog(t: AssocType, onChanged: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val tick = resumeTick()
    val who = remember(tick) { Associations.opener(ctx, t) }
    if (who is Associations.Opener.Me) {
        androidx.compose.runtime.LaunchedEffect(Unit) { ShortNote.show("Main app for ${t.label} ✓"); onChanged(); onDone() }
        return
    }
    HelperDialog("Main app for ${t.label}", when (who) {
        is Associations.Opener.Other -> "${who.name} opens ${t.exts} now. Android lets only you change that:\n\n" +
            "1. \"Open ${who.name}'s settings\" → \"Open by default\" (or \"Set as default\") → \"Clear defaults\".\n" +
            "2. Come back here: the next step opens a ${t.exts} " + (if (t.isLink) "link" else "file") + " — choose vlc interface and tap \"Always\"."
        else -> "Android asks which app to use each time. To make this app the main one:\n\n" +
            "\"Open a ${t.exts} " + (if (t.isLink) "link" else "file") + "\" — in Android's list choose vlc interface and tap \"Always\". " +
            "Nothing is played: the app only confirms and comes back here."
    }, extra = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
            if (who is Associations.Opener.Other) Pill("Open ${who.name}'s settings", filled = true) { Associations.openAppSettings(ctx, who.pkg) }
            Pill("Open a ${t.exts} " + (if (t.isLink) "link" else "file"), filled = who !is Associations.Opener.Other) {
                if (!Associations.openTest(ctx, t)) ShortNote.show("No ${t.exts} file found on the phone: open one from a file manager and choose vlc interface → Always")
            }
        }
    }) { onDone() }
}
