package com.zyntavo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Where captions come from. Standard is Vosk. Better, Best and Max are Whisper, matched to Vosk for exact word timing. */
interface CaptionSink : AutoCloseable {
    fun feed(b: ByteArray, n: Int)
    fun finish(): List<Word>
}

private class VoskSink(dir: File, onProgress: (Int) -> Unit) : CaptionSink {
    private val s = VoskSession(dir, onProgress)
    override fun feed(b: ByteArray, n: Int) = s.feed(b, n)
    override fun finish(): List<Word> = s.words()
    override fun close() = s.close()
}

/** Collects the whole sound, lets Whisper write the text, then takes word times from Vosk. */
private class WhisperSink(
    private val dir: File, private val base: File, private val voskModel: String,
    private val english: Boolean, private val lang: String, private val translate: Boolean, private val status: (String) -> Unit
) : CaptionSink {
    private var pcm = ShortArray(16000 * 60)
    private var count = 0
    private var carry = -1

    override fun feed(b: ByteArray, n: Int) {
        var i = 0
        if (carry >= 0 && n > 0) { put(((b[0].toInt() and 0xFF) shl 8 or carry).toShort()); carry = -1; i = 1 }
        while (i + 1 < n) {
            put(((b[i + 1].toInt() and 0xFF) shl 8 or (b[i].toInt() and 0xFF)).toShort())
            i += 2
        }
        if (i < n) carry = b[i].toInt() and 0xFF
    }

    private fun put(v: Short) {
        if (count == pcm.size) pcm = pcm.copyOf(pcm.size * 2)
        pcm[count++] = v
    }

    private fun voskWords(): List<Word> {
        val d = ensureVoskModel(base, voskModel, status)
        return VoskSession(d) { }.use { s ->
            val buf = ByteArray(16000)
            var i = 0
            while (i < count) {
                val n = min(8000, count - i)
                for (k in 0 until n) {
                    buf[2 * k] = (pcm[i + k].toInt() and 0xFF).toByte()
                    buf[2 * k + 1] = ((pcm[i + k].toInt() shr 8) and 0xFF).toByte()
                }
                s.feed(buf, n * 2)
                i += n
            }
            s.words()
        }
    }

    override fun finish(): List<Word> {
        if (count < 1600) return emptyList()
        val chunks = Whisper.Session(dir, english, lang, translate).use { it.transcribe(pcm, count, status) }
        if (translate) return chunks.flatMap { it.words }
        status("Matching the words to the sound…")
        val vosk = try { voskWords() } catch (e: Throwable) { emptyList() }
        if (chunks.isEmpty() && vosk.isEmpty()) return emptyList()
        val matched = if (vosk.isEmpty()) chunks.flatMap { it.words } else chunks.flatMap { Whisper.alignToVosk(it, vosk) }
        // Speech that Whisper skipped (too noisy, or unsure) is filled in from the standard recognizer, so nothing that was said goes missing.
        val covered = chunks.map { (it.start - 0.3)..(it.end + 0.3) }
        val gaps = vosk.filter { w -> val mid = (w.start + w.end) / 2; covered.none { mid in it } }
        return (matched + gaps).sortedBy { it.start }
    }

    override fun close() {}
}

object CaptionSinks {
    /** engine: 0 Standard (Vosk), 1 Better (Whisper tiny), 2 Best (Whisper base), 3 Max (Whisper small). */
    fun open(engine: Int, base: File, voskModel: String, translate: Boolean, status: (String) -> Unit): CaptionSink {
        if (engine <= 0) {
            val dir = ensureVoskModel(base, voskModel, status)
            status("Listening to the audio…")
            return VoskSink(dir) { sec -> status("Transcribing… ${sec / 60}:${(sec % 60).toString().padStart(2, '0')} done") }
        }
        val code = Regex("small-([a-z]{2})").find(voskModel)?.groupValues?.get(1) ?: "en"
        val english = code == "en"
        val dir = Whisper.ensureModel(base, (engine - 1).coerceIn(0, 2), english, status)
        status("Listening to the audio…")
        return WhisperSink(dir, base, voskModel, english, code, translate && !english, status)
    }
}

class ChunkWords(val start: Double, val end: Double, val words: List<Word>)

/** OpenAI Whisper (MIT) run with ONNX Runtime. Punctuation and capitals come from the model. */
object Whisper {
    private const val SR = 16000
    private const val N_FFT = 400
    private const val HOP = 160
    private const val N_MELS = 80
    private const val WINDOW = 30 * SR
    private const val FRAMES = 3000
    private const val BINS = N_FFT / 2 + 1

    private val langTokens = mapOf("en" to 50259, "de" to 50261, "es" to 50262, "ru" to 50263, "fr" to 50265, "pt" to 50267, "it" to 50274, "hi" to 50276)

    fun ensureModel(base: File, tier: Int, english: Boolean, status: (String) -> Unit): File {
        val size = listOf("tiny", "base", "small")[tier.coerceIn(0, 2)]
        val repo = "Xenova/whisper-$size" + if (english) ".en" else ""
        val dir = File(base, "whisper-" + repo.substringAfter('/'))
        val files = listOf("onnx/encoder_model_quantized.onnx" to "encoder.onnx", "onnx/decoder_model_quantized.onnx" to "decoder.onnx", "vocab.json" to "vocab.json")
        dir.mkdirs()
        for ((remote, local) in files) {
            val f = File(dir, local)
            if (f.exists() && f.length() > 1000) continue
            download("https://huggingface.co/$repo/resolve/main/$remote", f, status)
        }
        val gen = File(dir, "generation_config.json")
        if (!gen.exists()) try { download("https://huggingface.co/$repo/resolve/main/generation_config.json", gen) } catch (e: Exception) { }
        // The word-splitting table is only needed to turn the typed names into a hint for the model.
        val merges = File(dir, "merges.txt")
        if (!merges.exists() && Glossary.customTerms().isNotEmpty()) try { download("https://huggingface.co/$repo/resolve/main/merges.txt", merges) } catch (e: Exception) { }
        return dir
    }

    private fun download(url: String, to: File, status: ((String) -> Unit)? = null) {
        val what = when (to.name) { "encoder.onnx" -> "the speech model (part 1 of 3)"; "decoder.onnx" -> "the speech model (part 2 of 3)"; else -> "the speech model (${to.name})" }
        downloadFile(url, to, what, status)
    }

    // ---------- text: GPT-2 style byte-level tokens ----------

    private val byteDecoder: Map<Char, Int> by lazy {
        val bs = ArrayList<Int>()
        for (b in 33..126) bs.add(b)
        for (b in 161..172) bs.add(b)
        for (b in 174..255) bs.add(b)
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        val m = HashMap<Char, Int>()
        for (i in bs.indices) m[cs[i].toChar()] = bs[i]
        m
    }

    private val byteEncoder: Map<Int, Char> by lazy { byteDecoder.entries.associate { it.value to it.key } }

    private val gpt2Split = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")

    /** Turns text into token numbers (byte-level BPE), so it can be given to the model as a hint about spelling. */
    private fun encodeText(dir: File, vocab: Array<String?>, text: String): IntArray {
        val merges = HashMap<String, Int>()
        var rank = 0
        for (line in File(dir, "merges.txt").readLines(Charsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#version")) continue
            merges[line] = rank
            rank++
        }
        val ids = HashMap<String, Int>()
        for (i in vocab.indices) {
            val v = vocab[i]
            if (v != null && !ids.containsKey(v)) ids[v] = i
        }
        val out = ArrayList<Int>()
        for (m in gpt2Split.findAll(text)) {
            val sb = StringBuilder()
            for (b in m.value.toByteArray(Charsets.UTF_8)) {
                val ch = byteEncoder[b.toInt() and 0xFF]
                if (ch != null) sb.append(ch)
            }
            val parts = ArrayList<String>()
            for (c in sb) parts.add(c.toString())
            while (parts.size > 1) {
                var bestAt = -1
                var bestRank = Int.MAX_VALUE
                for (k in 0 until parts.size - 1) {
                    val r = merges[parts[k] + " " + parts[k + 1]]
                    if (r != null && r < bestRank) { bestRank = r; bestAt = k }
                }
                if (bestAt < 0) break
                parts[bestAt] = parts[bestAt] + parts[bestAt + 1]
                parts.removeAt(bestAt + 1)
            }
            for (p in parts) {
                val id = ids[p]
                if (id != null) out.add(id)
            }
        }
        return out.toIntArray()
    }

    private fun loadVocab(f: File): Array<String?> {
        val text = f.readText(Charsets.UTF_8)
        val out = arrayOfNulls<String>(51865)
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
            if (have && num < out.size) out[num] = sb.toString()
        }
        return out
    }

    private fun detokenize(ids: List<Int>, vocab: Array<String?>): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            val s = vocab.getOrNull(id) ?: continue
            for (c in s) byteDecoder[c]?.let { bytes.write(it) }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    /** Tokens the model's own settings say should never be written (music notes, stray symbols). */
    private fun suppressSet(f: File): BooleanArray {
        val out = BooleanArray(52000)
        if (!f.exists()) return out
        val t = f.readText()
        val k = t.indexOf("\"suppress_tokens\"")
        if (k < 0) return out
        val a = t.indexOf('[', k)
        val b = t.indexOf(']', max(a, 0))
        if (a < 0 || b < 0) return out
        for (p in t.substring(a + 1, b).split(',')) p.trim().toIntOrNull()?.let { if (it in out.indices) out[it] = true }
        return out
    }

    private fun compressionRatio(text: String): Double {
        val b = text.toByteArray(Charsets.UTF_8)
        if (b.isEmpty()) return 0.0
        val d = Deflater()
        d.setInput(b)
        d.finish()
        val buf = ByteArray(b.size + 64)
        val n = d.deflate(buf)
        d.end()
        return b.size.toDouble() / max(1, n)
    }

    // ---------- sound: log-mel spectrogram, as Whisper expects ----------

    private val hann = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val cosT: FloatArray by lazy { FloatArray(BINS * N_FFT) { cos(2.0 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() } }
    private val sinT: FloatArray by lazy { FloatArray(BINS * N_FFT) { sin(2.0 * PI * (it / N_FFT) * (it % N_FFT) / N_FFT).toFloat() } }

    private val mel: Array<FloatArray> by lazy {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0
        fun hzToMel(f: Double) = if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logStep else f / fSp
        fun melToHz(m: Double) = if (m >= minLogMel) minLogHz * exp(logStep * (m - minLogMel)) else fSp * m
        val lo = hzToMel(0.0)
        val hi = hzToMel(8000.0)
        val pts = DoubleArray(N_MELS + 2) { melToHz(lo + (hi - lo) * it / (N_MELS + 1)) }
        Array(N_MELS) { m ->
            val a = pts[m]
            val b = pts[m + 1]
            val c = pts[m + 2]
            val norm = 2.0 / (c - a)
            FloatArray(BINS) { k ->
                val f = k * SR.toDouble() / N_FFT
                (max(0.0, min((f - a) / (b - a), (c - f) / (c - b))) * norm).toFloat()
            }
        }
    }

    private val melRange: Array<IntArray> by lazy {
        Array(N_MELS) { m ->
            val row = mel[m]
            var a = 0
            while (a < row.size && row[a] == 0f) a++
            var b = row.size - 1
            while (b >= 0 && row[b] == 0f) b--
            intArrayOf(a, max(a, b))
        }
    }

    /** 80 x 3000 values for one piece of up to 30 seconds. */
    private fun logMel(audio: FloatArray, from: Int, len: Int): FloatArray {
        val x = FloatArray(WINDOW + N_FFT)
        val use = min(len, WINDOW)
        for (i in 0 until use) x[200 + i] = audio[from + i]
        for (i in 0 until 200) x[199 - i] = x[201 + i]
        for (i in 0 until 200) x[200 + WINDOW + i] = x[200 + WINDOW - 2 - i]
        val realFrames = min(FRAMES, (use + 200) / HOP + 2)
        val out = FloatArray(N_MELS * FRAMES)
        val fw = FloatArray(N_FFT)
        val power = FloatArray(BINS)
        for (f in 0 until realFrames) {
            val start = f * HOP
            for (n in 0 until N_FFT) fw[n] = x[start + n] * hann[n]
            for (k in 0 until BINS) {
                var re = 0f
                var im = 0f
                val base = k * N_FFT
                for (n in 0 until N_FFT) {
                    re += fw[n] * cosT[base + n]
                    im += fw[n] * sinT[base + n]
                }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                var s = 0f
                val row = mel[m]
                for (k in melRange[m][0]..melRange[m][1]) s += row[k] * power[k]
                out[m * FRAMES + f] = s
            }
        }
        var top = -1e30f
        for (i in out.indices) {
            val v = log10(max(out[i], 1e-10f))
            out[i] = v
            if (v > top) top = v
        }
        for (i in out.indices) out[i] = (max(out[i], top - 8f) + 4f) / 4f
        return out
    }

    /** Quiet recordings are made louder (never quieter) so the pauses and the speech are easier to tell apart. */
    private fun normalise(a: FloatArray) {
        if (a.isEmpty()) return
        val step = max(1, a.size / 20000)
        val sample = FloatArray(a.size / step) { abs(a[it * step]) }
        sample.sort()
        val p = sample[min(sample.size - 1, (sample.size * 0.995).toInt())]
        if (p < 1e-4f) return
        val g = min(8f, 0.6f / p)
        if (g > 1.05f) for (i in a.indices) a[i] = (a[i] * g).coerceIn(-1f, 1f)
    }

    // ---------- the model ----------

    class Session(dir: File, english: Boolean, code: String, translate: Boolean = false) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val enc = env.createSession(File(dir, "encoder.onnx").absolutePath, OrtSession.SessionOptions())
        private val dec = env.createSession(File(dir, "decoder.onnx").absolutePath, OrtSession.SessionOptions())
        private val vocab = loadVocab(File(dir, "vocab.json"))
        private val suppress = suppressSet(File(dir, "generation_config.json"))
        private val encIn = enc.inputNames.first()
        private val decIds = dec.inputNames.firstOrNull { it.contains("ids") } ?: dec.inputNames.first()
        private val decHidden = dec.inputNames.first { it != decIds }
        private val eot = if (english) 50256 else 50257
        private val prompt: IntArray =
            if (english) intArrayOf(50257, 50362) else intArrayOf(50258, langTokens[code] ?: 50259, if (translate) 50358 else 50359, 50363)

        /** The names the person typed, written in front of the start tokens. Whisper then spells those words the same way. */
        private val hint: IntArray = try {
            val terms = Glossary.customTerms().take(25)
            if (terms.isEmpty() || !File(dir, "merges.txt").exists()) {
                IntArray(0)
            } else {
                val ids = encodeText(dir, vocab, " " + terms.joinToString(", "))
                val keep = if (ids.size > 64) ids.copyOfRange(0, 64) else ids
                if (keep.isEmpty()) keep else intArrayOf(if (english) 50360 else 50361) + keep
            }
        } catch (e: Throwable) {
            IntArray(0)
        }

        /** Greedy decoding of one piece. Returns the text and its average log-probability. */
        private fun decode(mel: FloatArray): Pair<String, Float> {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, N_MELS.toLong(), FRAMES.toLong())).use { melT ->
                enc.run(mapOf(encIn to melT)).use { encRes ->
                    val hidden = encRes[0] as OnnxTensor
                    val ids = ArrayList<Long>()
                    for (p in hint) ids.add(p.toLong())
                    for (p in prompt) ids.add(p.toLong())
                    val out = ArrayList<Int>()
                    var logSum = 0.0
                    for (step in 0 until 220) {
                        val idArr = LongArray(ids.size) { ids[it] }
                        val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(idArr), longArrayOf(1, ids.size.toLong()))
                        var best = eot
                        var bestLp = 0f
                        try {
                            dec.run(mapOf(decIds to idT, decHidden to hidden)).use { r ->
                                val t = r[0] as OnnxTensor
                                val v = t.info.shape[2].toInt()
                                val fb = t.floatBuffer.duplicate()
                                fb.position((ids.size - 1) * v)
                                val last = FloatArray(v)
                                fb.get(last)
                                var top = -1e30f
                                var arg = eot
                                val limit = min(v - 1, eot)
                                for (i in 0..limit) {
                                    if (step == 0 && (i == eot || i == 220)) continue
                                    if (i != eot && suppress.getOrElse(i) { false }) continue
                                    if (last[i] > top) { top = last[i]; arg = i }
                                }
                                var sum = 0.0
                                for (i in 0..limit) sum += exp((last[i] - top).toDouble())
                                best = arg
                                bestLp = (-ln(sum)).toFloat()
                            }
                        } finally {
                            idT.close()
                        }
                        if (best == eot) break
                        out.add(best)
                        ids.add(best.toLong())
                        logSum += bestLp
                        if (out.size >= 12 && out.subList(out.size - 6, out.size) == out.subList(out.size - 12, out.size - 6)) {
                            repeat(6) { out.removeAt(out.size - 1) }
                            break
                        }
                    }
                    val avg = if (out.isEmpty()) -10f else (logSum / out.size).toFloat()
                    return detokenize(out, vocab) to avg
                }
            }
        }

        fun transcribe(pcm: ShortArray, count: Int, status: (String) -> Unit): List<ChunkWords> {
            val audio = FloatArray(count) { pcm[it] / 32768f }
            normalise(audio)
            val chunks = planChunks(audio)
            val result = ArrayList<ChunkWords>()
            chunks.forEachIndexed { ci, ch ->
                status("Transcribing… part ${ci + 1} of ${chunks.size}")
                val (text, lp) = decode(logMel(audio, ch.start, ch.end - ch.start))
                // Drop only what is clearly nothing or a made-up repeat. A low score alone is not enough: accents and
                // noise lower it, and dropping those parts left holes in the subtitles.
                if (text.isBlank() || lp < -2.4f) return@forEachIndexed
                if (text.length > 40 && compressionRatio(text) > 2.4) return@forEachIndexed
                val words = align(text, ch)
                if (words.isNotEmpty()) result.add(ChunkWords(ch.voiced.first()[0].toDouble() / SR, ch.voiced.last()[1].toDouble() / SR, words))
            }
            return result
        }

        override fun close() {
            enc.close()
            dec.close()
        }
    }

    // ---------- cutting the sound at pauses, and timing the words ----------

    private class Chunk(val start: Int, val end: Int, val voiced: List<IntArray>)

    private fun planChunks(a: FloatArray): List<Chunk> {
        val frame = 320
        val nf = a.size / frame
        if (nf == 0) return emptyList()
        val rms = FloatArray(nf) { f ->
            var s = 0.0
            for (i in f * frame until (f + 1) * frame) s += a[i] * a[i]
            sqrt(s / frame).toFloat()
        }
        val floor = rms.sortedArray()[(nf * 0.1).toInt()]
        val thr = max(floor * 3f, 0.006f)
        val speech = BooleanArray(nf) { rms[it] > thr }

        val segs = ArrayList<IntArray>()
        var i = 0
        while (i < nf) {
            if (!speech[i]) { i++; continue }
            val s = i
            var e = i
            var k = i
            while (k < nf && k - e < 15) { if (speech[k]) e = k; k++ }
            if (e + 1 - s >= 5) segs.add(intArrayOf(max(0, s - 5), min(nf, e + 1 + 5)))
            i = e + 1
        }
        val maxFrames = 700
        val pieces = ArrayList<IntArray>()
        for (sg in segs) {
            var s = sg[0]
            val e = sg[1]
            while (e - s > maxFrames) {
                var cut = s + 500
                var low = Float.MAX_VALUE
                for (j in s + 500 until s + maxFrames) if (rms[j] < low) { low = rms[j]; cut = j }
                pieces.add(intArrayOf(s, cut))
                s = cut
            }
            pieces.add(intArrayOf(s, e))
        }
        val chunks = ArrayList<Chunk>()
        var cur = ArrayList<IntArray>()
        for (p in pieces) {
            if (cur.isNotEmpty() && p[1] - cur[0][0] > maxFrames) {
                chunks.add(Chunk(cur[0][0] * frame, cur.last()[1] * frame, cur.map { intArrayOf(it[0] * frame, it[1] * frame) }))
                cur = ArrayList()
            }
            cur.add(p)
        }
        if (cur.isNotEmpty()) chunks.add(Chunk(cur[0][0] * frame, cur.last()[1] * frame, cur.map { intArrayOf(it[0] * frame, it[1] * frame) }))
        return chunks
    }

    /** First guess at word times: spread the words over the time the person is really speaking. */
    private fun align(text: String, ch: Chunk): List<Word> {
        val toks = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val total = toks.sumOf { it.length + 1 }.toDouble()
        val voicedLen = ch.voiced.sumOf { (it[1] - it[0]).toLong() }.toDouble()
        fun real(frac: Double): Double {
            var rem = frac * voicedLen
            for (seg in ch.voiced) {
                val l = (seg[1] - seg[0]).toDouble()
                if (rem <= l) return (seg[0] + rem) / SR
                rem -= l
            }
            return ch.end.toDouble() / SR
        }
        var acc = 0.0
        val out = ArrayList<Word>()
        for (t in toks) {
            val s = real(acc / total)
            acc += t.length + 1
            val e = real(acc / total)
            out.add(Word(s, max(s + 0.08, e - 0.03), t))
        }
        return out
    }

    /**
     * Whisper wrote the words; Vosk heard exactly when each one was said. Matching the two gives the
     * accurate timing of Vosk with the spelling and punctuation of Whisper. Words that cannot be matched
     * (numbers, names) are placed between their matched neighbours.
     */
    fun alignToVosk(chunk: ChunkWords, vosk: List<Word>): List<Word> {
        val w = chunk.words
        if (w.isEmpty()) return w
        val v = vosk.filter { it.start >= chunk.start - 0.5 && it.end <= chunk.end + 0.5 }
        if (v.isEmpty()) return w
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val a = w.map { norm(it.text) }
        val b = v.map { norm(it.text) }
        val n = a.size
        val m = b.size
        fun sub(x: String, y: String): Int = when {
            x.isEmpty() || y.isEmpty() -> 2
            x == y -> 0
            x.length > 3 && y.length > 3 && (x.startsWith(y) || y.startsWith(x) || x.endsWith(y) || y.endsWith(x)) -> 1
            else -> 3
        }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) dp[i][0] = i * 2
        for (j in 1..m) dp[0][j] = j * 2
        for (i in 1..n) for (j in 1..m) {
            dp[i][j] = minOf(dp[i - 1][j - 1] + sub(a[i - 1], b[j - 1]), dp[i - 1][j] + 2, dp[i][j - 1] + 2)
        }
        val match = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val s = sub(a[i - 1], b[j - 1])
            if (dp[i][j] == dp[i - 1][j - 1] + s) {
                if (s <= 1) match[i - 1] = j - 1
                i--
                j--
            } else if (dp[i][j] == dp[i - 1][j] + 2) i-- else j--
        }
        val start = DoubleArray(n)
        val end = DoubleArray(n)
        val known = BooleanArray(n)
        for (k in 0 until n) if (match[k] >= 0) { start[k] = v[match[k]].start; end[k] = v[match[k]].end; known[k] = true }
        var k = 0
        while (k < n) {
            if (known[k]) { k++; continue }
            var e = k
            while (e < n && !known[e]) e++
            val left = if (k > 0) end[k - 1] else chunk.start
            val right = if (e < n) start[e] else chunk.end
            val total = (k until e).sumOf { w[it].text.length + 1 }.toDouble()
            val span = max(right - left, 0.12 * (e - k))
            var acc = 0.0
            for (q in k until e) {
                start[q] = left + span * acc / total
                acc += w[q].text.length + 1
                end[q] = left + span * acc / total
            }
            k = e
        }
        val out = ArrayList<Word>(n)
        var prevEnd = 0.0
        for (q in 0 until n) {
            val s = max(start[q], prevEnd)
            val e = max(end[q], s + 0.06)
            out.add(Word(s, e, w[q].text))
            prevEnd = e
        }
        return out
    }
}
