package app.vlcinterface.sources

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * EBU R128 / ITU-R BS.1770 integrated loudness, as used by foobar2000's ReplayGain scanner
 * (ReplayGain 2.0: gain = −18 LUFS − measured loudness; sample peak).
 *
 * Feed interleaved float samples with [add]; read [integrated] and [peak]. The 400 ms block energies
 * are kept so several tracks can be gated together for the album value ([album]).
 */
class Loudness(private val rate: Int, private val channels: Int) {
    // K-weighting: high-shelf (stage 1) + high-pass (stage 2), coefficients per libebur128.
    private val b1: DoubleArray; private val a1: DoubleArray
    private val b2 = doubleArrayOf(1.0, -2.0, 1.0); private val a2: DoubleArray
    init {
        var f0 = 1681.974450955533; val g = 3.999843853973347; var q = 0.7071752369554196
        var k = tan(PI * f0 / rate)
        val vh = 10.0.pow(g / 20.0); val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        b1 = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
        a1 = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
        f0 = 38.13547087602444; q = 0.5003270373238773
        k = tan(PI * f0 / rate)
        a0 = 1.0 + k / q + k * k
        a2 = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    /** Channel weights (BS.1770): L R C = 1, LFE = 0, surrounds = 1.41. Android order: L R C LFE Ls Rs … */
    private val weight = DoubleArray(channels) { c ->
        when {
            channels <= 3 -> 1.0
            c == 3 && channels >= 6 -> 0.0
            c >= 3 && c <= 5 -> 1.41
            else -> 1.0
        }
    }
    private val z1 = Array(channels) { DoubleArray(2) }; private val z2 = Array(channels) { DoubleArray(2) }
    private val seg = rate / 10                          // 100 ms
    private var segFill = 0
    private var segSum = 0.0
    private val segments = ArrayList<Double>()           // mean-square energy per 100 ms
    var peak = 0f; private set

    fun add(buf: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val x = buf[i++]
                val ax = abs(x); if (ax > peak) peak = ax
                // stage 1 (direct form II transposed)
                val s1 = z1[c]; val y1 = b1[0] * x + s1[0]
                s1[0] = b1[1] * x - a1[1] * y1 + s1[1]; s1[1] = b1[2] * x - a1[2] * y1
                val s2 = z2[c]; val y2 = b2[0] * y1 + s2[0]
                s2[0] = b2[1] * y1 - a2[1] * y2 + s2[1]; s2[1] = b2[2] * y1 - a2[2] * y2
                sum += weight[c] * y2 * y2
            }
            segSum += sum
            if (++segFill == seg) { segments += segSum / seg; segSum = 0.0; segFill = 0 }
        }
    }

    /** Energies of the 400 ms blocks (75 % overlap). */
    fun blocks(): DoubleArray {
        if (segments.size < 4) return if (segments.isEmpty()) DoubleArray(0) else doubleArrayOf(segments.average())
        return DoubleArray(segments.size - 3) { i -> (segments[i] + segments[i + 1] + segments[i + 2] + segments[i + 3]) / 4.0 }
    }

    val integrated: Double get() = gated(blocks())

    companion object {
        private fun lufs(z: Double) = -0.691 + 10.0 * log10(z)

        /** Gating per BS.1770: absolute −70 LUFS, then relative −10 LU. Returns LUFS (−inf if silent). */
        fun gated(blocks: DoubleArray): Double {
            val abs = blocks.filter { it > 0 && lufs(it) > -70.0 }
            if (abs.isEmpty()) return Double.NEGATIVE_INFINITY
            val rel = lufs(abs.average()) - 10.0
            val g = abs.filter { lufs(it) > rel }
            return if (g.isEmpty()) Double.NEGATIVE_INFINITY else lufs(g.average())
        }

        /** Album loudness: all tracks' blocks gated together. */
        fun album(tracks: List<DoubleArray>): Double {
            val all = DoubleArray(tracks.sumOf { it.size }); var o = 0
            tracks.forEach { System.arraycopy(it, 0, all, o, it.size); o += it.size }
            return gated(all)
        }

        /** ReplayGain 2.0 gain in dB for a loudness in LUFS. */
        fun gainDb(lufs: Double): Double = if (lufs.isInfinite()) 0.0 else -18.0 - lufs
    }
}
