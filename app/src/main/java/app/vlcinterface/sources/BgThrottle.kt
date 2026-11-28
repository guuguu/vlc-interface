package app.vlcinterface.sources

/**
 * Background network transfers of ReplayGain scans and indexing, throttled (2026-11-28 01:11): they mustn't take all the
 * bandwidth (nor make the NAS look unreachable, 01:24). While they run unthrottled, their rate is measured in 5-second
 * steps; once the last 60 s stayed within ±2 % of their average, that average is taken as the network's maximum and
 * from then on, for the whole listening session (until the app ends), these transfers are kept to 70 % of it
 * (02:13: never more than 30 % slower than 1.92 — was 60 %).
 * Only these transfers are counted: what the players stream (songs, videos, radio) is neither measured nor limited.
 */
object BgThrottle {
    private const val STEP_MS = 5_000L
    const val SHARE = 0.7
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
                            maxBps = avg; limitBps = avg * SHARE
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


/**
 * Indexing's share (2026-11-28 requests 30 / 34): its tag reads go through libVLC (up to 8 files at once over SMB), whose
 * bytes the app can't count, so it is paced by files: the first 60 s of indexing that really read files run at full speed
 * and measure the files per second; from then on, for the session, indexing is kept to 70 % of that rate — the NAS and
 * the network keep room for playback and the server checks, and indexing is never more than 30 % slower than in 1.92.
 * Files whose tags come from the cache cost nothing and aren't counted.
 */
object IndexPace {
    private val lock = Object()
    private var measureFrom = 0L
    private var activeMs = 0L
    private var lastAt = 0L
    private var files = 0
    @Volatile var fullRate = 0.0       // files / s measured unthrottled
    @Volatile var limitRate = 0.0
    private var allowAt = 0L

    /** One file's tags were read from the network; waits as long as the limit asks. */
    fun pass() {
        val sleepMs: Long
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (limitRate <= 0.0) {
                // Only time spent reading counts (gaps over 3 s — between folders, paused — are left out).
                if (lastAt > 0) activeMs += minOf(now - lastAt, 3_000)
                lastAt = now; files++
                if (measureFrom == 0L) measureFrom = now
                if (activeMs >= 60_000 && files >= 20) {
                    fullRate = files * 1000.0 / activeMs; limitRate = fullRate * BgThrottle.SHARE
                    try { app.vlcinterface.data.ServerLog.log("index", "throttle", "", "", true,
                        "Indexing speed measured: %.1f files/s — kept to %.1f files/s (70 %%) for this session".format(fullRate, limitRate)) } catch (_: Exception) {}
                }
                return
            }
            val t = System.nanoTime()
            allowAt = maxOf(allowAt, t)
            allowAt += (1e9 / limitRate).toLong()
            sleepMs = (allowAt - t) / 1_000_000
        }
        if (sleepMs > 0) try { Thread.sleep(sleepMs.coerceAtMost(2_000)) } catch (_: InterruptedException) {}
    }
}
