package app.vlcinterface.player

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Loudness compensation from the ISO 226:2003 equal-loudness contours (reference listening level 80 phon).
 *
 * At a lower listening level the ear loses lows (and some highs) compared with the mid range; the compensation for a
 * band is how much more level that band needs at the lower level, relative to 1 kHz:
 *   C(f) = [Lp(f, P) − Lp(f, 80)] − [Lp(1 kHz, P) − Lp(1 kHz, 80)].
 * The slider value is the compensation at the lowest band (60 Hz); the listening level P that gives it is found,
 * and the whole curve for that level is used. Applied by cutting only: the band needing the most stays at 0 dB and
 * every other band is lowered by the difference — the same relative curve, nothing amplified.
 * Above 12.5 kHz (the end of the standard's table) the 12.5 kHz values are used.
 */
object Iso226 {
    private val f = doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0)
    private val af = doubleArrayOf(0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288, 0.276, 0.267,
        0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301)
    private val lu = doubleArrayOf(-31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5,
        0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1)
    private val tf = doubleArrayOf(78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5,
        1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3)

    /** libVLC's equalizer bands (Hz). */
    val bands = doubleArrayOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)

    private fun lp(i: Int, phon: Double): Double {
        val a = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) + (0.4 * 10.0.pow((tf[i] + lu[i]) / 10 - 9)).pow(af[i])
        return 10 / af[i] * log10(a) - lu[i] + 94
    }

    /** Sound pressure level of the [phon] contour at [hz] (interpolated on a log-frequency scale). */
    private fun at(hz: Double, phon: Double): Double {
        if (hz >= f.last()) return lp(f.size - 1, phon)
        if (hz <= f.first()) return lp(0, phon)
        for (i in 0 until f.size - 1) if (hz <= f[i + 1]) {
            val t = (ln(hz) - ln(f[i])) / (ln(f[i + 1]) - ln(f[i]))
            return lp(i, phon) * (1 - t) + lp(i + 1, phon) * t
        }
        return lp(f.size - 1, phon)
    }

    private fun comp(phon: Double) = DoubleArray(bands.size) { b -> (at(bands[b], phon) - at(bands[b], 80.0)) - (at(1000.0, phon) - at(1000.0, 80.0)) }

    private val memo = HashMap<Int, FloatArray>()

    /** Band gains (all ≤ 0 dB) for a loudness setting of [db] (compensation at 60 Hz). */
    fun cuts(db: Float): FloatArray {
        if (db <= 0f) return FloatArray(bands.size)
        val key = Math.round(db * 2)
        synchronized(memo) { memo[key]?.let { return it } }
        // Listening level whose 60 Hz compensation equals the setting (bisection between 80 and 0 phon).
        var lo = 0.0; var hi = 80.0
        repeat(40) { val mid = (lo + hi) / 2; if (comp(mid)[0] > db) lo = mid else hi = mid }
        val c = comp((lo + hi) / 2)
        val top = c.max()
        val out = FloatArray(bands.size) { b -> (c[b] - top).toFloat() }
        synchronized(memo) { memo[key] = out }
        return out
    }
}
