package com.zyntavo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class MatteModel(val file: String, val size: Int, val std: FloatArray)

val matteModels = listOf(
    MatteModel("u2netp.onnx", 320, floatArrayOf(0.229f, 0.224f, 0.225f)),
    MatteModel("silueta.onnx", 320, floatArrayOf(0.229f, 0.224f, 0.225f)),
    MatteModel("u2net.onnx", 320, floatArrayOf(0.229f, 0.224f, 0.225f))
)

/** Subject cutout shared by Windows and Android: an ONNX model, then edge-aware refinement. */
object Matting {
    private const val BASE = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/"
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val SHIFTS = intArrayOf(24, 16, 8, 0)

    fun ensureModel(dir: File, quality: Int, status: (String) -> Unit): File {
        val q = quality.coerceIn(0, matteModels.size - 1)
        val m = matteModels[q]
        val f = File(dir, m.file)
        if (f.exists() && f.length() > 1_000_000) return f
        dir.mkdirs()
        status("Downloading the ${matteQualityNames[q]} cutout model (${matteQualityNotes[q]}, one time)…")
        val tmp = File(dir, m.file + ".part")
        URL(BASE + m.file).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (!tmp.renameTo(f)) throw IllegalStateException("Could not save the model")
        return f
    }

    class Session(modelFile: File, quality: Int) : AutoCloseable {
        private val m = matteModels[quality.coerceIn(0, matteModels.size - 1)]
        private val env = OrtEnvironment.getEnvironment()
        private val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

        /** Raw subject mask, 0..1, at the same size as the picture. */
        fun alpha(argb: IntArray, w: Int, h: Int): FloatArray {
            val n = m.size
            val small = resize(argb, w, h, n, n)
            var mx = 1e-6f
            for (c in small) mx = max(mx, max(((c shr 16) and 255) / 255f, max(((c shr 8) and 255) / 255f, (c and 255) / 255f)))
            val data = FloatArray(3 * n * n)
            for (i in small.indices) {
                val c = small[i]
                data[i] = ((((c shr 16) and 255) / 255f) / mx - MEAN[0]) / m.std[0]
                data[n * n + i] = ((((c shr 8) and 255) / 255f) / mx - MEAN[1]) / m.std[1]
                data[2 * n * n + i] = (((c and 255) / 255f) / mx - MEAN[2]) / m.std[2]
            }
            val mask = FloatArray(n * n)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, n.toLong(), n.toLong())).use { t ->
                session.run(mapOf(session.inputNames.first() to t)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (res[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                    for (y in 0 until n) for (x in 0 until n) mask[y * n + x] = out[y][x]
                }
            }
            var lo = mask[0]
            var hi = mask[0]
            for (v in mask) { if (v < lo) lo = v; if (v > hi) hi = v }
            val range = max(hi - lo, 1e-6f)
            for (i in mask.indices) mask[i] = (mask[i] - lo) / range
            return resizeF(mask, n, n, w, h)
        }

        override fun close() { session.close() }
    }

    fun resize(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        if (w == nw && h == nh) return src
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val fy = if (nh > 1) y * (h - 1f) / (nh - 1) else 0f
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, h - 1)
            val ay = fy - y0
            for (x in 0 until nw) {
                val fx = if (nw > 1) x * (w - 1f) / (nw - 1) else 0f
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, w - 1)
                val ax = fx - x0
                val p00 = src[y0 * w + x0]
                val p01 = src[y0 * w + x1]
                val p10 = src[y1 * w + x0]
                val p11 = src[y1 * w + x1]
                var res = 0
                for (shift in SHIFTS) {
                    val a = (p00 shr shift) and 255
                    val b = (p01 shr shift) and 255
                    val c = (p10 shr shift) and 255
                    val d = (p11 shr shift) and 255
                    val top = a + (b - a) * ax
                    val bot = c + (d - c) * ax
                    val v = (top + (bot - top) * ay + 0.5f).toInt().coerceIn(0, 255)
                    res = res or (v shl shift)
                }
                out[y * nw + x] = res
            }
        }
        return out
    }

    fun resizeF(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        if (w == nw && h == nh) return src
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val fy = if (nh > 1) y * (h - 1f) / (nh - 1) else 0f
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, h - 1)
            val ay = fy - y0
            for (x in 0 until nw) {
                val fx = if (nw > 1) x * (w - 1f) / (nw - 1) else 0f
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, w - 1)
                val ax = fx - x0
                val top = src[y0 * w + x0] * (1 - ax) + src[y0 * w + x1] * ax
                val bot = src[y1 * w + x0] * (1 - ax) + src[y1 * w + x1] * ax
                out[y * nw + x] = top * (1 - ay) + bot * ay
            }
        }
        return out
    }

    /** Average over a (2r+1) square, edges handled, using running sums so it is fast. */
    fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val pre = DoubleArray(max(w, h) + 1)
        for (y in 0 until h) {
            pre[0] = 0.0
            for (x in 0 until w) pre[x + 1] = pre[x] + src[y * w + x]
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                tmp[y * w + x] = ((pre[x1 + 1] - pre[x0]) / (x1 - x0 + 1)).toFloat()
            }
        }
        for (x in 0 until w) {
            pre[0] = 0.0
            for (y in 0 until h) pre[y + 1] = pre[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val y0 = max(0, y - r)
                val y1 = min(h - 1, y + r)
                out[y * w + x] = ((pre[y1 + 1] - pre[y0]) / (y1 - y0 + 1)).toFloat()
            }
        }
        return out
    }

    /** Guided filter: makes the mask follow the real edges of the photo. */
    private fun guided(gray: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = boxMean(gray, w, h, r)
        val meanP = boxMean(p, w, h, r)
        val corrIp = boxMean(FloatArray(n) { gray[it] * p[it] }, w, h, r)
        val corrI = boxMean(FloatArray(n) { gray[it] * gray[it] }, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val cov = corrIp[i] - meanI[i] * meanP[i]
            a[i] = cov / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxMean(a, w, h, r)
        val meanB = boxMean(b, w, h, r)
        return FloatArray(n) { (meanA[it] * gray[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    fun refine(alpha: FloatArray, argb: IntArray, w: Int, h: Int, o: CutoutOptions): FloatArray {
        val n = w * h
        val gray = FloatArray(n) {
            val c = argb[it]
            (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
        }
        val r = max(4, min(w, h) / 120)
        var a = guided(gray, alpha, w, h, r, 1e-3f)
        val lo = 0.10f + 0.25f * o.cleanup
        val hi = 0.90f - 0.25f * o.cleanup
        for (i in 0 until n) {
            val t = ((a[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
            a[i] = t * t * (3 - 2 * t)
        }
        if (o.feather > 0) a = boxMean(a, w, h, o.feather)
        return a
    }

    /** The picture with the mask as its transparency. */
    fun compose(argb: IntArray, alpha: FloatArray): IntArray =
        IntArray(argb.size) { (((alpha[it] * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 24) or (argb[it] and 0xFFFFFF) }

    /** The picture laid over a flat colour. */
    fun flatten(argb: IntArray, alpha: FloatArray, bg: Int): IntArray =
        IntArray(argb.size) { i ->
            val a = alpha[i]
            val c = argb[i]
            fun mix(s: Int) = ((((c shr s) and 255) * a + ((bg shr s) and 255) * (1 - a)) + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
}
