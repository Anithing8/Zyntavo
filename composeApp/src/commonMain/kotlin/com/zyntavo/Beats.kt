package com.zyntavo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the beat of a piece of music and the quiet parts of speech. It works on plain audio samples (mono, 11025 Hz),
 * with its own small FFT. Nothing from another project is used.
 */
object Beats {
    const val RATE = 11025
    private const val N = 1024
    private const val HOP = 256

    class Result(val bpm: Double, val beatsMs: List<Long>)

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val xr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val xi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + xr
                    im[i + k] = ui + xi
                    re[i + k + len / 2] = ur - xr
                    im[i + k + len / 2] = ui - xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** How much new sound starts in each step of about 23 ms (spectral flux). */
    private fun onsetEnvelope(s: ShortArray): FloatArray {
        if (s.size < N) return FloatArray(0)
        val frames = (s.size - N) / HOP + 1
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        val prev = DoubleArray(N / 2)
        val env = FloatArray(frames)
        for (f in 0 until frames) {
            for (i in 0 until N) {
                re[i] = s[f * HOP + i] / 32768.0 * win[i]
                im[i] = 0.0
            }
            fft(re, im)
            var flux = 0.0
            for (k in 4 until 460) {
                val mag = ln(1.0 + 10.0 * sqrt(re[k] * re[k] + im[k] * im[k]))
                val d = mag - prev[k]
                if (d > 0) flux += d
                prev[k] = mag
            }
            env[f] = flux.toFloat()
        }
        // Remove the slowly changing level so only the sudden hits are left.
        val out = FloatArray(frames)
        val w = 21
        for (i in 0 until frames) {
            var sum = 0f
            var cnt = 0
            for (k in max(0, i - w)..min(frames - 1, i + w)) { sum += env[k]; cnt++ }
            out[i] = max(0f, env[i] - sum / cnt)
        }
        return out
    }

    /** Finds the tempo and the time of every beat. Returns null if the sound is too short or has no clear beat. */
    fun detect(s: ShortArray): Result? {
        val env = onsetEnvelope(s)
        if (env.size < 200) return null
        val hopSec = HOP.toDouble() / RATE
        val minLag = (60.0 / (180.0 * hopSec)).toInt()
        val maxLag = (60.0 / (70.0 * hopSec)).toInt() + 1
        val ac = DoubleArray(maxLag + 2)
        for (lag in max(1, minLag - 1)..maxLag + 1) {
            var sum = 0.0
            for (i in 0 until env.size - lag) sum += env[i].toDouble() * env[i + lag]
            ac[lag] = sum / (env.size - lag)
        }
        var bestLag = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 / (lag * hopSec)
            val prior = exp(-0.5 * (ln(bpm / 120.0) / 0.6) * (ln(bpm / 120.0) / 0.6))
            val score = ac[lag] * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestScore <= 0.0) return null
        val a = ac[bestLag - 1]
        val b = ac[bestLag]
        val c = ac[bestLag + 1]
        val denom = a - 2 * b + c
        val lagF = if (abs(denom) > 1e-9) bestLag + 0.5 * (a - c) / denom else bestLag.toDouble()
        val period = lagF.coerceIn(minLag.toDouble(), maxLag.toDouble())
        val bpm = 60.0 / (period * hopSec)

        // The beat grid: the start offset that puts the most onset energy on the beats.
        var bestPhase = 0.0
        var bestSum = -1.0
        var ph = 0.0
        while (ph < period) {
            var sum = 0.0
            var pos = ph
            while (pos < env.size - 1) {
                val ix = pos.roundToInt().coerceIn(0, env.size - 1)
                sum += max(env[ix], max(env[max(0, ix - 1)], env[min(env.size - 1, ix + 1)]))
                pos += period
            }
            if (sum > bestSum) { bestSum = sum; bestPhase = ph }
            ph += 0.5
        }
        val beats = ArrayList<Long>()
        var pos = bestPhase
        while (pos < env.size - 1) {
            // Move each beat to the strongest hit close to it, so a slightly changing tempo is followed.
            val c0 = pos.roundToInt()
            val reach = max(1, (period * 0.12).toInt())
            var bi = c0.coerceIn(0, env.size - 1)
            for (k in max(0, c0 - reach)..min(env.size - 1, c0 + reach)) if (env[k] > env[bi]) bi = k
            beats.add((bi * hopSec * 1000.0).toLong())
            pos += period
        }
        return Result(bpm, beats.distinct().sorted())
    }

    /** Quiet stretches of speech: pairs of start and end in milliseconds that stay below [thresholdDb] for at least [minMs]. */
    fun silences(s: ShortArray, thresholdDb: Double, minMs: Long): List<Pair<Long, Long>> {
        val win = RATE / 50
        val out = ArrayList<Pair<Long, Long>>()
        var startW = -1
        val windows = s.size / win
        for (w in 0..windows) {
            var quiet = false
            if (w < windows) {
                var sum = 0.0
                for (i in 0 until win) { val v = s[w * win + i] / 32768.0; sum += v * v }
                val db = 20.0 * log10(sqrt(sum / win) + 1e-9)
                quiet = db < thresholdDb
            }
            if (quiet && startW < 0) startW = w
            if (!quiet && startW >= 0) {
                val from = startW * 20L
                val to = w * 20L
                if (to - from >= minMs) out.add(from to to)
                startW = -1
            }
        }
        return out
    }
}
