package com.photostudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.URL
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.min

internal fun fetchFile(url: String, to: File) {
    val tmp = File(to.path + ".part")
    URL(url).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
    if (!tmp.renameTo(to)) throw IllegalStateException("Could not save ${to.name}")
}

private class SpmPiece(val text: String, val score: Float, val type: Int)

/** Reads the pieces and scores out of a SentencePiece .spm file (a small protobuf). */
private fun parseSpm(b: ByteArray): List<SpmPiece> {
    val pos = intArrayOf(0)
    fun varint(): Long {
        var shift = 0
        var r = 0L
        while (true) {
            val x = b[pos[0]++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) break
            shift += 7
        }
        return r
    }
    val out = ArrayList<SpmPiece>()
    while (pos[0] < b.size) {
        val tag = varint()
        val field = (tag shr 3).toInt()
        when ((tag and 7L).toInt()) {
            0 -> varint()
            1 -> pos[0] += 8
            5 -> pos[0] += 4
            2 -> {
                val len = varint().toInt()
                val start = pos[0]
                if (field == 1) out.add(parsePiece(b, start, start + len))
                pos[0] = start + len
            }
            else -> return out
        }
    }
    return out
}

private fun parsePiece(b: ByteArray, from: Int, to: Int): SpmPiece {
    var p = from
    var text = ""
    var score = 0f
    var type = 1
    fun varint(): Long {
        var shift = 0
        var r = 0L
        while (true) {
            val x = b[p++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) break
            shift += 7
        }
        return r
    }
    while (p < to) {
        val tag = varint()
        val field = (tag shr 3).toInt()
        when ((tag and 7L).toInt()) {
            0 -> { val v = varint(); if (field == 3) type = v.toInt() }
            1 -> p += 8
            5 -> {
                if (field == 2) {
                    score = java.lang.Float.intBitsToFloat(
                        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
                    )
                }
                p += 4
            }
            2 -> {
                val len = varint().toInt()
                if (field == 1) text = String(b, p, len, Charsets.UTF_8)
                p += len
            }
            else -> return SpmPiece(text, score, type)
        }
    }
    return SpmPiece(text, score, type)
}

/** vocab.json is one flat object: "piece": id. */
private fun parseVocabJson(f: File): HashMap<String, Int> {
    val text = f.readText(Charsets.UTF_8)
    val out = HashMap<String, Int>()
    val n = text.length
    var i = 0
    while (i < n) {
        if (text[i] != '"') { i++; continue }
        val sb = StringBuilder()
        i++
        while (i < n && text[i] != '"') {
            val ch = text[i]
            if (ch == '\\' && i + 1 < n) {
                i++
                when (text[i]) {
                    'u' -> { sb.append(text.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> sb.append(text[i])
                }
            } else sb.append(ch)
            i++
        }
        i++
        while (i < n && text[i] != ':') i++
        i++
        while (i < n && text[i] == ' ') i++
        var num = 0
        var have = false
        while (i < n && text[i].isDigit()) { num = num * 10 + (text[i] - '0'); i++; have = true }
        if (have) out[sb.toString()] = num
    }
    return out
}

private fun intOf(text: String, key: String, def: Int): Int =
    Regex("\"$key\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: def

/** Splits English into the pieces the translation model knows, choosing the most likely split. */
private class Tokenizer(spm: List<SpmPiece>, private val vocab: Map<String, Int>, private val unk: Int) {
    private val score = HashMap<String, Float>()
    private var maxLen = 1

    init {
        for (p in spm) if (p.type == 1 || p.type == 4) {
            score[p.text] = p.score
            if (p.text.length > maxLen) maxLen = p.text.length
        }
    }

    fun encode(text: String): List<Int> {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")
        if (norm.isEmpty()) return emptyList()
        val s = "\u2581" + norm.replace(' ', '\u2581')
        val n = s.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            for (l in 1..min(maxLen, n - i)) {
                val sc = score[s.substring(i, i + l)] ?: continue
                if (best[i] + sc > best[i + l]) { best[i + l] = best[i] + sc; from[i + l] = i }
            }
            if (best[i] - 20.0 > best[i + 1]) { best[i + 1] = best[i] - 20.0; from[i + 1] = i }
        }
        val ids = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            val st = from[pos]
            ids.add(vocab[s.substring(st, pos)] ?: unk)
            pos = st
        }
        ids.reverse()
        return ids
    }
}

/** English to Hindi with Helsinki-NLP opus-mt-en-hi (CC BY 4.0), as ONNX files, run on the device. */
object Marian {
    fun ensureModel(base: File, toHindi: Boolean, status: (String) -> Unit): File {
        val repo = if (toHindi) "Xenova/opus-mt-en-hi" else "Xenova/opus-mt-hi-en"
        val dir = File(base, repo.substringAfter('/'))
        dir.mkdirs()
        val files = listOf(
            "onnx/encoder_model_quantized.onnx" to "encoder.onnx",
            "onnx/decoder_model_quantized.onnx" to "decoder.onnx",
            "source.spm" to "source.spm", "vocab.json" to "vocab.json", "config.json" to "config.json"
        )
        for ((remote, local) in files) {
            val f = File(dir, local)
            if (f.exists() && f.length() > 100) continue
            status("Downloading the translation model ($local, about 115 MB in total, one time)…")
            fetchFile("https://huggingface.co/$repo/resolve/main/$remote", f)
        }
        return dir
    }

    fun translateAll(base: File, lines: List<String>, toHindi: Boolean, status: (String) -> Unit): List<String> {
        val dir = ensureModel(base, toHindi, status)
        return Translator(dir, toHindi).use { t ->
            lines.mapIndexed { i, l ->
                status("Translating… ${i + 1} of ${lines.size}")
                t.translate(l)
            }
        }
    }

    class Translator(dir: File, private val toHindi: Boolean) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val enc = env.createSession(File(dir, "encoder.onnx").absolutePath, OrtSession.SessionOptions())
        private val dec = env.createSession(File(dir, "decoder.onnx").absolutePath, OrtSession.SessionOptions())
        private val vocab = parseVocabJson(File(dir, "vocab.json"))
        private val inv: Array<String?> = run {
            val arr = arrayOfNulls<String>((vocab.values.maxOrNull() ?: 0) + 1)
            for ((k, v) in vocab) arr[v] = k
            arr
        }
        private val cfg = File(dir, "config.json").readText()
        private val eos = intOf(cfg, "eos_token_id", 0)
        private val pad = intOf(cfg, "pad_token_id", 61949)
        private val start = intOf(cfg, "decoder_start_token_id", pad)
        private val tok = Tokenizer(parseSpm(File(dir, "source.spm").readBytes()), vocab, vocab["<unk>"] ?: 1)

        private fun repeats(out: List<Int>, next: Int): Boolean {
            if (out.size < 2) return false
            val a = out[out.size - 2]
            val b = out[out.size - 1]
            for (j in 0 until out.size - 2) if (out[j] == a && out[j + 1] == b && out[j + 2] == next) return true
            return false
        }

        fun translate(text: String): String {
            val ids = tok.encode(text).toMutableList()
            if (ids.isEmpty()) return ""
            ids.add(eos)
            val n = ids.size
            val inputIds = LongArray(n) { ids[it].toLong() }
            val mask = LongArray(n) { 1L }
            return OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, n.toLong())).use { idT ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, n.toLong())).use { maskT ->
                    val encInputs = HashMap<String, OnnxTensor>()
                    for (name in enc.inputNames) encInputs[name] = if (name.contains("mask")) maskT else idT
                    enc.run(encInputs).use { encRes ->
                        val hidden = encRes[0] as OnnxTensor
                        val out = ArrayList<Int>()
                        val prefix = ArrayList<Long>()
                        prefix.add(start.toLong())
                        val limit = min(256, n * 3 + 10)
                        for (step in 0 until limit) {
                            val arr = LongArray(prefix.size) { prefix[it] }
                            val decT = OnnxTensor.createTensor(env, LongBuffer.wrap(arr), longArrayOf(1, prefix.size.toLong()))
                            var next = eos
                            try {
                                val inputs = HashMap<String, OnnxTensor>()
                                for (name in dec.inputNames) {
                                    inputs[name] = when {
                                        name.contains("encoder_attention_mask") -> maskT
                                        name.contains("encoder_hidden_states") -> hidden
                                        else -> decT
                                    }
                                }
                                dec.run(inputs).use { r ->
                                    val t = r[0] as OnnxTensor
                                    val v = t.info.shape[2].toInt()
                                    val fb = t.floatBuffer.duplicate()
                                    fb.position((prefix.size - 1) * v)
                                    val last = FloatArray(v)
                                    fb.get(last)
                                    var top = -1e30f
                                    var arg = eos
                                    for (i in 0 until v) {
                                        if (i == pad || repeats(out, i)) continue
                                        if (last[i] > top) { top = last[i]; arg = i }
                                    }
                                    next = arg
                                }
                            } finally {
                                decT.close()
                            }
                            if (next == eos) break
                            out.add(next)
                            prefix.add(next.toLong())
                        }
                        finish(out)
                    }
                }
            }
        }

        private fun finish(out: List<Int>): String {
            val raw = out.mapNotNull { inv.getOrNull(it) }.joinToString("").replace('\u2581', ' ').trim()
            return if (toHindi) raw.replace(Regex("\\.$"), "\u0964") else raw
        }

        override fun close() {
            enc.close()
            dec.close()
        }
    }
}
