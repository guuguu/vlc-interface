package app.vlcinterface

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Runs in its own small process: ends the app's main process, then starts the app again from scratch,
 * so everything (engine, settings, connections) is loaded fresh. Used by "Restart the app" on the exit button.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0) Process.killProcess(pid)
        packageManager.getLaunchIntentForPackage(packageName)?.let { i ->
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object { const val EXTRA_PID = "main_pid" }
}
