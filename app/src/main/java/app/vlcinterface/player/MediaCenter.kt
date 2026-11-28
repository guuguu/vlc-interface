package app.vlcinterface.player

import app.vlcinterface.data.CrashLog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import app.vlcinterface.OpenOriginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Media session + playback notification + "Up next" notification.
 * Updates only when something visible changes (Android silently drops notification updates that come too often).
 */
class MediaCenter(private val ctx: Context, private val player: PlayerController) {
    val session = MediaSession(ctx, "vlc-interface")
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashLog.guard)
    private var lastSig: List<Any?> = emptyList()
    private var lastPos = 0L
    private var lastPosAt = 0L
    private var lastPlaying = false
    private var art: Pair<String, Bitmap?> = "" to null

    init {
        // "Playing" channel at normal importance but silent (no sound, no vibration, no pop-up): a low-importance
        // ("silent") notification has no icon in the status bar on Android 12+ (Samsung hides silent icons by default),
        // so nothing showed a player was working. The old low-importance channel is removed (its importance can't be
        // raised once made).
        try { nm.deleteNotificationChannel("playback") } catch (_: Exception) {}
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Player working", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false); enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = "Shown (with an icon in the status bar) while a song, radio station or video is playing"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NEXT, "Up next", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setSound(null, null)
        })
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = player.resume()
            override fun onPause() = player.pause()
            override fun onStop() = player.stop()
            override fun onSkipToNext() { player.next() }
            override fun onSkipToPrevious() = player.prev()
            override fun onSeekTo(pos: Long) = player.seekTo(pos)
            override fun onFastForward() = player.seekBy(stepMs())
            override fun onRewind() = player.seekBy(-stepMs())
            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) {
                    A_BACK -> player.seekBy(-stepMs())
                    A_FWD -> player.seekBy(stepMs())
                }
            }
        })
        session.setSessionActivity(openIntent())
        scope.launch {
            player.state.collect { st -> onState(st) }
        }
    }

    /**
     * Which notification the phone gets (2026-11-22 11:54), one or the other, never both:
     *  - modern (Android 13 and newer): Android's media player in the notification shade and on the lock screen, drawn by
     *    the system from the app's media session — title, artist / station, picture, position bar and the buttons;
     *  - classic (Android 12, and whenever the modern one can't be used — the media session failed): the app's own
     *    notification with its buttons, plus the "Up next" notification under it.
     * The same buttons on every player (songs, videos, radio): ⏮ ⏪ ⏯ ⏩ ⏭ — radio has no ⏪ ⏩ (nothing to jump in a live
     * stream), ⏮ ⏭ are the previous / next station.
     */
    @Volatile private var sessionOk = true
    val modern get() = android.os.Build.VERSION.SDK_INT >= 33 && sessionOk

    private fun stepMs() = app.vlcinterface.App.instance.store.current.settings.doubleTapSeekSec * 1000L

    /** Tapping the notification / lock-screen player: opens the folder of the playing file, but never while the phone is locked. */
    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, OpenOriginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun onState(st: PlayerState) {
        val item = st.item
        if (item == null) {
            session.isActive = false
            nm.cancel(ID)
            nm.cancel(ID_NEXT)
            lastSig = emptyList()
            return
        }
        val sig = listOf(item.uri, item.startMs, item.title, st.metaArtist, st.metaAlbum, st.artworkUrl, st.isPlaying, minutesLeft(st),
            st.nowPlaying, st.hasNext, st.hasPrev, st.length > 0, st.upNext.map { it.uri + it.startMs }, st.ended)
        val posJump = kotlin.math.abs(expectedPos() - st.time) > 2500
        if (sig != lastSig) {
            lastSig = sig
            try { session.isActive = true; updateMetadata(st); updateState(st) }
            catch (_: Exception) { sessionOk = false }      // media session refused: the classic notification is used
            nm.notify(ID, build(st))
            updateUpNext(st)
        } else if (posJump && st.time > 0) {
            updateState(st)
        }
    }

    /** Whole minutes left of the song / video (null: unknown length, or radio). */
    private fun minutesLeft(st: PlayerState): Int? {
        val cur = st.item ?: return null
        if (cur.isRadio || st.length <= 0) return null
        return ((st.length - st.time).coerceAtLeast(0) / 60_000L).toInt()
    }

    private fun videoFolder(item: app.vlcinterface.player.PlayItem): String =
        Uri.decode(item.folderKey.ifEmpty { item.uri.substringBeforeLast('/') }.trimEnd('/').substringAfterLast('/')).substringAfterLast(':')

    private fun expectedPos(): Long = if (lastPlaying) lastPos + (SystemClock.elapsedRealtime() - lastPosAt) else lastPos

    private fun updateState(st: PlayerState) {
        val item = st.item ?: return
        lastPos = st.time; lastPosAt = SystemClock.elapsedRealtime(); lastPlaying = st.isPlaying
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (!item.isRadio) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        // ⏮ ⏭ on every player (a single song: ⏮ goes to its start, ⏭ ends the queue / plays what's next as set).
        actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val b = PlaybackState.Builder()
            .setActions(actions)
            .setState(if (st.isPlaying) PlaybackState.STATE_PLAYING else if (st.buffering < 100f) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED,
                st.time, if (st.isPlaying) st.rate else 0f)
        if (!item.isRadio) {
            b.addCustomAction(PlaybackState.CustomAction.Builder(A_BACK, "Back ${stepMs() / 1000} s", android.R.drawable.ic_media_rew).build())
            b.addCustomAction(PlaybackState.CustomAction.Builder(A_FWD, "Forward ${stepMs() / 1000} s", android.R.drawable.ic_media_ff).build())
        }
        session.setPlaybackState(b.build())
    }

    private fun artwork(url: String): Bitmap? {
        if (url == art.first) return art.second
        val bmp = try {
            when {
                url.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(url).path)
                url.startsWith("/") -> BitmapFactory.decodeFile(url)
                url.startsWith("mem:") -> app.vlcinterface.sources.CoverArt.memory(url.removePrefix("mem:").let { u -> if (u.contains("#v")) u.substringBeforeLast("#v") else u })
                else -> null
            }
        } catch (_: Exception) { null }
        art = url to bmp
        return bmp
    }

    private fun updateMetadata(st: PlayerState) {
        val item = st.item ?: return
        val title = if (item.isRadio && st.nowPlaying.isNotEmpty()) st.nowPlaying else item.title
        // Videos: their folder (the series / film) under the title, like an artist under a song — the same look on every player.
        val artist = if (item.isRadio) item.title else st.metaArtist.ifEmpty { if (!item.isMusic) videoFolder(item) else item.artist }
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, st.metaAlbum)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (item.isRadio) -1 else st.length)
        artwork(st.artworkUrl)?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(b.build())
    }

    private fun action(icon: Int, title: String, act: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(ctx, icon), title,
            PendingIntent.getService(ctx, code, Intent(ctx, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)).build()

    fun build(st: PlayerState): Notification {
        val item = st.item
        val title = when {
            item == null -> "vlc interface"
            item.isRadio && st.nowPlaying.isNotEmpty() -> st.nowPlaying
            else -> item.title
        }
        val text = when {
            item == null -> ""
            item.isRadio -> item.title + " · Live"
            !item.isMusic -> videoFolder(item)
            else -> listOf(st.metaArtist, st.metaAlbum).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        // Header line next to the orange ▶ square (2026-11-21 11:48): who / what, and the time left — "Pink Floyd ·
        // 3 min left", "RadioBlues-Flac · Live", "Video · 12 min left". Refreshed once a minute.
        val who = when {
            item == null -> ""
            item.isRadio -> item.title
            !item.isMusic -> videoFolder(item).ifEmpty { "Video" }
            else -> st.metaArtist.ifEmpty { item.artist }.ifEmpty { st.metaAlbum.ifEmpty { item.album } }
        }
        val left = when {
            item == null -> ""
            item.isRadio -> "Live"
            else -> minutesLeft(st)?.let { if (it <= 0) "<1 min left" else "$it min left" } ?: ""
        }
        val sub = listOf(who, left).filter { it.isNotEmpty() }.joinToString(" · ")
        val b = Notification.Builder(ctx, CHANNEL)
            // The same orange square in every state, only the symbol changes (▶ playing, ⏸ paused) — 2026-11-21 11:44.
            // Android draws status-bar symbols white only; the orange shows in the shade and on the lock screen.
            .setSmallIcon(if (st.isPlaying) app.vlcinterface.R.drawable.ic_stat_play else app.vlcinterface.R.drawable.ic_stat_pause)
            .setColor(0xFFFF8A3D.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .apply { if (sub.isNotEmpty()) setSubText(sub) }
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }   // Android 12 would wait up to 10 s
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent())
            // Classic notification (11:57): at the top of the notification bar, with its status-bar icon, whenever a player is on —
            // playing or paused, the app in front or in the background — and it can't be swiped away while a player is on.
            .setOngoing(st.isPlaying || (!modern && item != null))
            .apply { if (!modern) { setSortKey("a"); @Suppress("DEPRECATION") setPriority(Notification.PRIORITY_MAX) } }
            .setDeleteIntent(PendingIntent.getService(ctx, 9, Intent(ctx, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        artwork(st.artworkUrl)?.let { b.setLargeIcon(it) }
        // The same buttons on every player; radio without the jumps (a live stream has nothing to jump in).
        val radio = item?.isRadio == true
        b.addAction(action(android.R.drawable.ic_media_previous, if (radio) "Previous station" else "Previous", PlaybackService.ACTION_PREV, 1))
        if (!radio) b.addAction(action(android.R.drawable.ic_media_rew, "Back", PlaybackService.ACTION_BACK, 2))
        b.addAction(if (st.isPlaying) action(android.R.drawable.ic_media_pause, "Pause", PlaybackService.ACTION_TOGGLE, 3)
            else action(android.R.drawable.ic_media_play, "Play", PlaybackService.ACTION_TOGGLE, 3))
        if (!radio) b.addAction(action(android.R.drawable.ic_media_ff, "Forward", PlaybackService.ACTION_FWD, 4))
        b.addAction(action(android.R.drawable.ic_media_next, if (radio) "Next station" else "Next", PlaybackService.ACTION_NEXT, 5))
        val style = Notification.MediaStyle().setShowActionsInCompactView(*(if (radio) intArrayOf(0, 1, 2) else intArrayOf(1, 2, 3)))
        // Modern: tied to the media session, Android draws its media player. Classic: the app's notification as built.
        if (modern) style.setMediaSession(session.sessionToken)
        b.setStyle(style)
        return b.build()
    }

    /** Separate silent notification under the player listing the next two items (visible on the lock screen). */
    private fun updateUpNext(st: PlayerState) {
        val next = st.upNext
        // Only with the classic notification: the modern media player is one card, nothing next to it.
        if (modern || next.isEmpty() || st.item?.isRadio == true) { nm.cancel(ID_NEXT); return }
        val lines = next.mapIndexed { i, p -> "${i + 1}. ${p.title}" + if (p.artist.isNotEmpty()) " — ${p.artist}" else "" }
        val style = Notification.InboxStyle().setBigContentTitle("Up next")
        lines.forEach { style.addLine(it) }
        val n = Notification.Builder(ctx, CHANNEL_NEXT)
            .setSmallIcon(app.vlcinterface.R.drawable.ic_stat_next)      // same orange square, ⏭
            .setColor(0xFFFF8A3D.toInt())
            .setContentTitle("Up next")
            .setContentText(lines.joinToString("   "))
            .setStyle(style)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(st.isPlaying)
            .setSortKey("b")
            .build()
        nm.notify(ID_NEXT, n)
    }

    fun cancelAll() {
        nm.cancel(ID)
        nm.cancel(ID_NEXT)
        session.isActive = false
    }

    companion object {
        const val CHANNEL = "playing"
        const val CHANNEL_NEXT = "upnext"
        const val ID = 42
        const val ID_NEXT = 43
        const val A_BACK = "back10"
        const val A_FWD = "fwd10"
    }
}
