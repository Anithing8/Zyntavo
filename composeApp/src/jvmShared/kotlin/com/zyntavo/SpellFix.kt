package com.zyntavo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Context-aware spelling help for English subtitles, using Google's BERT (Apache 2.0) as a language model.
 * It hides one word at a time, asks the model which words fit there, and only swaps a word when a very
 * similar word fits far better. Names, numbers and anything it is unsure about are left alone.
 */
object SpellFix {
    private const val REPO = "Xenova/bert-base-uncased"

    fun ensureModel(base: File, status: (String) -> Unit): File {
        val dir = File(base, "bert-base-uncased")
        dir.mkdirs()
        val files = listOf("onnx/model_quantized.onnx" to "model.onnx", "vocab.txt" to "vocab.txt")
        for ((remote, local) in files) {
            val f = File(dir, local)
            if (f.exists() && f.length() > 1000) continue
            fetchFile("https://huggingface.co/$REPO/resolve/main/$remote", f, status)
        }
        return dir
    }

    fun run(base: File, words: List<Word>, deep: Boolean, status: (String) -> Unit): SpellResult {
        val dir = ensureModel(base, status)
        return Session(dir).use { it.fix(words, deep, status) }
    }

    private class Cand(val word: String, val id: Int, val dist: Int)

    private class WordPiece(vocabFile: File) {
        val vocab = HashMap<String, Int>()
        val whole = ArrayList<String>()
        val wholeIds = ArrayList<Int>()
        val unk: Int
        val cls: Int
        val sep: Int
        val mask: Int

        init {
            val shortOk = setOf("an", "as", "at", "be", "by", "do", "go", "he", "if", "in", "is", "it", "me", "my", "no", "of", "on", "or", "so", "to", "up", "us", "we")
            vocabFile.readLines(Charsets.UTF_8).forEachIndexed { i, line ->
                val t = line.trim()
                vocab[t] = i
                if (t.isNotEmpty() && t.all { it in 'a'..'z' } && (t.length >= 3 || t in shortOk)) { whole.add(t); wholeIds.add(i) }
            }
            unk = vocab["[UNK]"] ?: 100
            cls = vocab["[CLS]"] ?: 101
            sep = vocab["[SEP]"] ?: 102
            mask = vocab["[MASK]"] ?: 103
        }

        fun pieces(word: String): List<Int> {
            val w = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            val out = ArrayList<Int>()
            var i = 0
            while (i < w.length) {
                val c = w[i]
                if (c.isLetterOrDigit()) {
                    var j = i
                    while (j < w.length && w[j].isLetterOrDigit()) j++
                    wordPiece(w.substring(i, j), out)
                    i = j
                } else {
                    if (!c.isWhitespace()) out.add(vocab[c.toString()] ?: unk)
                    i++
                }
            }
            return out
        }

        private fun wordPiece(token: String, out: MutableList<Int>) {
            if (token.length > 100) { out.add(unk); return }
            var start = 0
            val sub = ArrayList<Int>()
            while (start < token.length) {
                var end = token.length
                var found = -1
                while (start < end) {
                    var s = token.substring(start, end)
                    if (start > 0) s = "##$s"
                    val id = vocab[s]
                    if (id != null) { found = id; break }
                    end--
                }
                if (found < 0) { out.add(unk); return }
                sub.add(found)
                start = end
            }
            out.addAll(sub)
        }

        /** Words in the vocabulary that are at most [maxDist] typing mistakes away from [word]. */
        fun near(word: String, maxDist: Int): List<Cand> {
            val out = ArrayList<Cand>()
            for (k in whole.indices) {
                val c = whole[k]
                if (abs(c.length - word.length) > maxDist) continue
                val d = distance(word, c, maxDist)
                if (d in 1..maxDist) out.add(Cand(c, wholeIds[k], d))
            }
            return out
        }

        /** Edit distance with swaps of neighbouring letters counted as one mistake. Gives up above [limit]. */
        private fun distance(a: String, b: String, limit: Int): Int {
            val n = a.length
            val m = b.length
            var prev2 = IntArray(m + 1)
            var prev = IntArray(m + 1) { it }
            for (i in 1..n) {
                val cur = IntArray(m + 1)
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..m) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > limit) return limit + 1
                prev2 = prev
                prev = cur
            }
            return prev[m]
        }
    }

    private class Session(dir: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val session = env.createSession(File(dir, "model.onnx").absolutePath, OrtSession.SessionOptions())
        private val tok = WordPiece(File(dir, "vocab.txt"))

        /** Probability of every vocabulary word at the hidden position. */
        private fun maskedProbs(left: List<Int>, right: List<Int>): FloatArray {
            val ids = ArrayList<Long>()
            ids.add(tok.cls.toLong())
            for (t in left) ids.add(t.toLong())
            ids.add(tok.mask.toLong())
            val mi = ids.size - 1
            for (t in right) ids.add(t.toLong())
            ids.add(tok.sep.toLong())
            val n = ids.size
            val arr = LongArray(n) { ids[it] }
            val ones = LongArray(n) { 1L }
            val zeros = LongArray(n)
            val tensors = HashMap<String, OnnxTensor>()
            try {
                for (name in session.inputNames) {
                    val data = when {
                        name.contains("mask") -> ones
                        name.contains("type") -> zeros
                        else -> arr
                    }
                    tensors[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data), longArrayOf(1, n.toLong()))
                }
                val probs = session.run(tensors).use { r ->
                    val t = r[0] as OnnxTensor
                    val v = t.info.shape[2].toInt()
                    val fb = t.floatBuffer.duplicate()
                    fb.position(mi * v)
                    val logits = FloatArray(v)
                    fb.get(logits)
                    var top = -1e30f
                    for (x in logits) if (x > top) top = x
                    var sum = 0.0
                    for (i in logits.indices) { val e = exp((logits[i] - top).toDouble()); logits[i] = e.toFloat(); sum += e }
                    for (i in logits.indices) logits[i] = (logits[i] / sum).toFloat()
                    logits
                }
                return probs
            } finally {
                for (t in tensors.values) t.close()
            }
        }

        private fun matchCase(orig: String, repl: String): String = when {
            orig.length > 1 && orig.all { it.isUpperCase() } -> repl.uppercase()
            orig.first().isUpperCase() -> repl.replaceFirstChar { it.uppercase() }
            else -> repl
        }

        fun fix(words: List<Word>, deep: Boolean, status: (String) -> Unit): SpellResult {
            val texts = words.map { it.text }.toMutableList()
            val changes = ArrayList<SpellChange>()
            // First names and special words, matched by how they sound (see Glossary).
            status("Checking names and special words…")
            val locked = HashSet<Int>()
            // What the person taught the app by hand comes first, then names and special words.
            locked.addAll(Learned.apply(texts) { w -> tok.vocab.containsKey(w) })
            locked.addAll(Glossary.fix(texts, locked) { tok.vocab.containsKey(it) })
            for (g in words.indices) if (texts[g] != words[g].text) changes.add(SpellChange(g, words[g].text, texts[g]))
            val termWords = Glossary.termWords()
            val cache = HashMap<Int, List<Int>>()
            fun tokensOf(i: Int): List<Int> = cache.getOrPut(i) { tok.pieces(texts[i]) }

            for (i in words.indices) {
                if (i % 10 == 0) status("Checking the spelling… $i of ${words.size}")
                val t = texts[i]
                var a = 0
                var b = t.length
                while (a < b && !t[a].isLetterOrDigit()) a++
                while (b > a && !t[b - 1].isLetterOrDigit()) b--
                val prefix = t.substring(0, a)
                val core = t.substring(a, b)
                val suffix = t.substring(b)
                if (core.length < 3 || !core.all { it in 'a'..'z' || it in 'A'..'Z' }) continue
                val startOfSentence = i == 0 || endsSentence(texts[i - 1])
                if (core[0].isUpperCase() && !startOfSentence) continue
                val lower = core.lowercase()
                if (i in locked || lower in termWords || Learned.isKept(lower)) continue
                val known = tok.vocab.containsKey(lower)
                if (known && !deep) continue

                val cands = tok.near(lower, if (lower.length <= 4) 1 else 2)
                if (cands.isEmpty()) continue

                val left = ArrayList<Int>()
                var k = i - 1
                while (k >= 0 && left.size < 48) { left.addAll(0, tokensOf(k)); k-- }
                if (left.size > 48) left.subList(0, left.size - 48).clear()
                val right = ArrayList<Int>()
                k = i + 1
                while (k < words.size && right.size < 48) { right.addAll(tokensOf(k)); k++ }
                if (right.size > 48) right.subList(48, right.size).clear()

                val probs = maskedProbs(left, right)
                var best: Cand? = null
                var bestScore = -1e18
                for (c in cands) {
                    val sc = ln(probs[c.id].toDouble() + 1e-12) - 1.2 * c.dist
                    if (sc > bestScore) { bestScore = sc; best = c }
                }
                val pick = best ?: continue
                val accept = if (!known) {
                    probs[pick.id] >= 1e-4f
                } else {
                    val pOrig = probs[tok.vocab[lower] ?: continue]
                    pOrig < 0.003f && probs[pick.id] >= 0.25f && probs[pick.id] >= 40f * pOrig
                }
                if (accept) {
                    texts[i] = prefix + matchCase(core, pick.word) + suffix
                    changes.add(SpellChange(i, words[i].text, texts[i]))
                    cache.remove(i)
                }
            }
            return SpellResult(words.mapIndexed { i, w -> w.copy(text = texts[i]) }, changes)
        }

        override fun close() { session.close() }
    }
}
