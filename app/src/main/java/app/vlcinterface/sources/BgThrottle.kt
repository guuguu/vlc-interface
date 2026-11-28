package app.vlcinterface.sources

/**
 * Background network transfers of ReplayGain scans and indexing, throttled (2026-11-28 01:11): they mustn't take all the
 * bandwidth (nor make the NAS look unreachable, 01:24). While they run unthrottled, their rate is measured in 5-second
 * steps; once the last 60 s stayed within ±2 % of their average, that average is taken as the network's maximum and
 * from then on, for the whole listening session (until the app ends), these transfers are kept to 60 % of it.
 * Only these transfers are counted: what the players stream (songs, videos, radio) is neither measured nor limited.
 */
object BgThrottle {
    private const val STEP_MS = 5_000L
    private val lock = Object()
    private var stepStart = 0L
    private var stepBytes = 0L
    private val steps = ArrayDeque<Double>()          // bytes / s of the last 5-second steps
    @Volatile var maxBps = 0.0
    @Volatile var limitBps = 0.0
    private var allowAt = 0L                            // nanoTime before which the next bytes must wait

    /** [n] bytes of a ReplayGain / indexing transfer just went through; waits as long as the limit asks. */
    fun pass(n: Int) {
        if (n <= 0) return
        val sleepMs: Long
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (stepStart == 0L) stepStart = now
            stepBytes += n
            if (now - stepStart >= STEP_MS) {
                val bps = stepBytes * 1000.0 / (now - stepStart)
                // A step with almost nothing moving (between files, paused) says nothing about the network.
                if (bps > 50_000 && limitBps == 0.0) {
                    steps.addLast(bps); while (steps.size > 12) steps.removeFirst()
                    if (steps.size == 12) {
                        val avg = steps.average()
                        if (steps.all { kotlin.math.abs(it - avg) <= avg * 0.02 }) {
                            maxBps = avg; limitBps = avg * 0.6
                            try { app.vlcinterface.data.ServerLog.log("rg", "throttle", "", "", true,
                                "Network maximum found: %.1f MB/s — ReplayGain and indexing kept to %.1f MB/s for this session".format(avg / 1e6, limitBps / 1e6)) } catch (_: Exception) {}
                        }
                    }
                }
                stepStart = now; stepBytes = 0
            }
            if (limitBps <= 0.0) return
            val t = System.nanoTime()
            allowAt = maxOf(allowAt, t)
            allowAt += (n / limitBps * 1e9).toLong()
            sleepMs = (allowAt - t) / 1_000_000
        }
        if (sleepMs > 0) try { Thread.sleep(sleepMs.coerceAtMost(2_000)) } catch (_: InterruptedException) {}
    }

    /** A transfer of this kind is throttled. */
    fun bg(what: String) = what.contains("ReplayGain", true) || what.contains("index", true) || what.contains("tag", true)
}
