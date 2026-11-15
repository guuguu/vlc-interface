package app.vlcinterface

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle

/**
 * Opened by tapping the playback notification or the lock-screen player.
 * Locked phone: do nothing (lock screen = controls only). Unlocked: open the folder of the playing file.
 */
class OpenOriginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_ORIGIN, true))
        }
        finish()
    }
}
