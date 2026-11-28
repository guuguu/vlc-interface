package app.vlcinterface.sources

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore

/**
 * Deleting phone files (2026-11-28, lint: MediaStore.createDeleteRequest is Android 11+ and crashed Android 10).
 * Android 11+: Android's delete request (one confirmation for all). Android 10: deleted directly; a file another app
 * owns gives Android's own "allow" prompt (the first one met) — returned to be shown; null = all done here.
 */
object PhoneDelete {
    fun request(ctx: Context, uris: List<Uri>): IntentSender? {
        if (android.os.Build.VERSION.SDK_INT >= 30) return MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
        for (u in uris) {
            try { ctx.contentResolver.delete(u, null, null) }
            catch (e: android.app.RecoverableSecurityException) { return e.userAction.actionIntent.intentSender }
        }
        return null
    }
}
