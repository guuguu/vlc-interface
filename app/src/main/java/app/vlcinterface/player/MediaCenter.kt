package app.vlcinterface.player

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastSig: List<Any?> = emptyList()
    private var lastPos = 0L
    private var lastPosAt = 0L
    private var lastPlaying = false
    private var art: Pair<String, Bitmap?> = "" to null

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
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
        val sig = listOf(item.uri, item.startMs, item.title, st.metaArtist, st.metaAlbum, st.artworkUrl, st.isPlaying,
            st.nowPlaying, st.hasNext, st.hasPrev, st.length > 0, st.upNext.map { it.uri + it.startMs }, st.ended)
        val posJump = kotlin.math.abs(expectedPos() - st.time) > 2500
        if (sig != lastSig) {
            lastSig = sig
            session.isActive = true
            updateMetadata(st)
            updateState(st)
            nm.notify(ID, build(st))
            updateUpNext(st)
        } else if (posJump && st.time > 0) {
            updateState(st)
        }
    }

    private fun expectedPos(): Long = if (lastPlaying) lastPos + (SystemClock.elapsedRealtime() - lastPosAt) else lastPos

    private fun updateState(st: PlayerState) {
        val item = st.item ?: return
        lastPos = st.time; lastPosAt = SystemClock.elapsedRealtime(); lastPlaying = st.isPlaying
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (!item.isRadio) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (st.queue.size > 1) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
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
        val artist = if (item.isRadio) item.title else st.metaArtist
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
            else -> listOf(st.metaArtist, st.metaAlbum).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(if (st.isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(title)
            .setContentText(text)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent())
            .setOngoing(st.isPlaying)
            .setDeleteIntent(PendingIntent.getService(ctx, 9, Intent(ctx, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        artwork(st.artworkUrl)?.let { b.setLargeIcon(it) }
        b.addAction(action(android.R.drawable.ic_media_previous, "Previous", PlaybackService.ACTION_PREV, 1))
        b.addAction(action(android.R.drawable.ic_media_rew, "Back", PlaybackService.ACTION_BACK, 2))
        b.addAction(if (st.isPlaying) action(android.R.drawable.ic_media_pause, "Pause", PlaybackService.ACTION_TOGGLE, 3)
            else action(android.R.drawable.ic_media_play, "Play", PlaybackService.ACTION_TOGGLE, 3))
        b.addAction(action(android.R.drawable.ic_media_ff, "Forward", PlaybackService.ACTION_FWD, 4))
        b.addAction(action(android.R.drawable.ic_media_next, "Next", PlaybackService.ACTION_NEXT, 5))
        b.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(1, 2, 3))
        return b.build()
    }

    /** Separate silent notification under the player listing the next two items (visible on the lock screen). */
    private fun updateUpNext(st: PlayerState) {
        val next = st.upNext
        if (next.isEmpty() || st.item?.isRadio == true) { nm.cancel(ID_NEXT); return }
        val lines = next.mapIndexed { i, p -> "${i + 1}. ${p.title}" + if (p.artist.isNotEmpty()) " — ${p.artist}" else "" }
        val style = Notification.InboxStyle().setBigContentTitle("Up next")
        lines.forEach { style.addLine(it) }
        val n = Notification.Builder(ctx, CHANNEL_NEXT)
            .setSmallIcon(android.R.drawable.ic_media_next)
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
        const val CHANNEL = "playback"
        const val CHANNEL_NEXT = "upnext"
        const val ID = 42
        const val ID_NEXT = 43
        const val A_BACK = "back10"
        const val A_FWD = "fwd10"
    }
}
