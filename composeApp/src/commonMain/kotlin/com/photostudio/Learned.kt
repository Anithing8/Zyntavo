package com.photostudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max

/**
 * One thing the app was taught: [wrong] (lower case) should be written [to].
 * [before] and [after] are the words that stood next to it, [times] is how often the person made this same fix.
 */
data class Lesson(val wrong: String, val to: String, val before: String, val after: String, val times: Int)

/**
 * What the app learns from the person's own changes.
 *  - A word fixed by hand in a caption is fixed the same way next time.
 *  - A word the spelling fix changed, and the person changed back, is left alone from then on.
 * Everything is saved in a small file, so it is still there after the app is closed.
 */
object Learned {
    var lessons by mutableStateOf<List<Lesson>>(emptyList())
        private set
    var keptWords by mutableStateOf<List<String>>(emptyList())
        private set
    private var glossary = ""

    init {
        parse(try { readPrefs() } catch (e: Throwable) { "" })
    }

    // ---------- saving and loading ----------

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun parse(text: String) {
        val ls = ArrayList<Lesson>()
        val ks = ArrayList<String>()
        for (line in text.split('\n')) {
            val p = line.split('\t')
            when (p[0]) {
                "G" -> glossary = if (p.size > 1) p[1] else ""
                "R" -> if (p.size >= 6) ls.add(Lesson(p[1], p[2], p[3], p[4], p[5].toIntOrNull() ?: 1))
                "K" -> if (p.size >= 2 && p[1].isNotEmpty()) ks.add(p[1])
            }
        }
        lessons = ls
        keptWords = ks
    }

    private fun save() {
        val sb = StringBuilder()
        sb.append("G\t").append(clean(glossary.replace('\n', ','))).append('\n')
        for (l in lessons) {
            sb.append("R\t").append(clean(l.wrong)).append('\t').append(clean(l.to)).append('\t')
                .append(clean(l.before)).append('\t').append(clean(l.after)).append('\t').append(l.times.toString()).append('\n')
        }
        for (k in keptWords) sb.append("K\t").append(clean(k)).append('\n')
        try { writePrefs(sb.toString()) } catch (e: Throwable) { }
    }

    /** The names the person typed last time. */
    fun savedGlossary(): String = glossary

    fun saveGlossary(text: String) {
        if (text == glossary) return
        glossary = text
        save()
    }

    // ---------- helpers ----------

    private class Edge(val pre: String, val core: String, val suf: String)

    private fun edges(t: String): Edge {
        var a = 0
        var b = t.length
        while (a < b && !t[a].isLetterOrDigit()) a++
        while (b > a && !t[b - 1].isLetterOrDigit()) b--
        return Edge(t.substring(0, a), t.substring(a, b), t.substring(b))
    }

    private fun core(t: String): String = edges(t).core

    private val spaces = Regex("\\s+")

    // ---------- learning ----------

    /** Looks at what the person changed in one caption and remembers the word fixes. Returns how many it learned. */
    fun learn(beforeText: String, afterText: String): Int {
        val a = beforeText.split(spaces).map { core(it) }.filter { it.isNotEmpty() }
        val b = afterText.split(spaces).map { core(it) }.filter { it.isNotEmpty() }
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0 || n > 200 || m > 200 || a == b) return 0

        // Longest run of words both versions share, so only the words that really changed are left over.
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val gaps = ArrayList<IntArray>()
        var i = 0
        var j = 0
        var ai = 0
        var bj = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                if (i > ai || j > bj) gaps.add(intArrayOf(ai, i, bj, j))
                i++
                j++
                ai = i
                bj = j
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        if (n > ai || m > bj) gaps.add(intArrayOf(ai, n, bj, m))

        // Rewriting a sentence is not a correction.
        var changed = 0
        for (g in gaps) changed += max(g[1] - g[0], g[3] - g[2])
        if (gaps.size > 5 || (gaps.size > 1 && changed * 2 > max(n, m))) return 0

        var learned = 0
        for (g in gaps) {
            val aLen = g[1] - g[0]
            val bLen = g[3] - g[2]
            if (aLen < 1 || aLen > 3 || bLen < aLen || bLen > 3) continue
            val wrong = a.subList(g[0], g[1]).joinToString(" ").lowercase()
            val to = b.subList(g[2], g[3]).joinToString(" ")
            if (wrong.replace(" ", "").length < 3) continue
            if (wrong == to.lowercase()) continue
            if (wrong.all { it.isDigit() || it == ' ' }) continue
            val before = if (g[0] > 0) a[g[0] - 1].lowercase() else ""
            val after = if (g[1] < n) a[g[1]].lowercase() else ""
            addLesson(wrong, to, before, after)
            learned++
        }
        if (learned > 0) save()
        return learned
    }

    private fun addLesson(wrong: String, to: String, before: String, after: String) {
        val old = lessons.firstOrNull { it.wrong == wrong }
        val rest = lessons.filter { it.wrong != wrong }
        val lesson = if (old != null && old.to == to) {
            Lesson(wrong, to, if (old.before.isNotEmpty()) old.before else before, if (old.after.isNotEmpty()) old.after else after, old.times + 1)
        } else {
            Lesson(wrong, to, before, after, 1)
        }
        lessons = (rest + lesson).takeLast(500)
        val parts = wrong.split(' ')
        keptWords = keptWords.filter { it !in parts }
    }

    /** "Find and replace": remembers that [wrong] is always written [to]. Returns false if it cannot be used. */
    fun teach(wrongText: String, toText: String): Boolean {
        val a = wrongText.split(spaces).map { core(it) }.filter { it.isNotEmpty() }
        val b = toText.split(spaces).map { core(it) }.filter { it.isNotEmpty() }
        if (a.isEmpty() || a.size > 3 || b.size < a.size || b.size > 3) return false
        val wrong = a.joinToString(" ").lowercase()
        val to = b.joinToString(" ")
        if (wrong == to || wrong.replace(" ", "").length < 2) return false
        val rest = lessons.filter { it.wrong != wrong }
        lessons = (rest + Lesson(wrong, to, "", "", 2)).takeLast(500)
        val parts = wrong.split(' ')
        keptWords = keptWords.filter { it !in parts }
        save()
        return true
    }

    /** The person changed a fixed word back, so it must not be changed again. */
    fun keep(word: String) {
        val w = core(word).lowercase()
        if (w.length < 3) return
        lessons = lessons.filter { it.wrong != w }
        if (w !in keptWords) keptWords = keptWords + w
        save()
    }

    fun isKept(lowerWord: String): Boolean = keptWords.contains(lowerWord)

    fun forget(l: Lesson) {
        lessons = lessons.filter { it !== l }
        save()
    }

    fun forgetAll() {
        lessons = emptyList()
        keptWords = emptyList()
        save()
    }

    // ---------- using what was learned ----------

    /**
     * Rewrites [texts] in place and returns the positions it touched. A fix is used when the same words stand next
     * to it, when the person has made it more than once, or when [isKnown] says the wrong word is not a real word.
     */
    fun apply(texts: MutableList<String>, isKnown: ((String) -> Boolean)?): Set<Int> {
        val locked = HashSet<Int>()
        val list = lessons
        if (list.isEmpty()) return locked
        val byFirst = HashMap<String, MutableList<Lesson>>()
        for (l in list) byFirst.getOrPut(l.wrong.substringBefore(' ')) { ArrayList() }.add(l)
        var i = 0
        while (i < texts.size) {
            val cands = byFirst[core(texts[i]).lowercase()]
            var done = false
            if (cands != null) {
                for (l in cands.sortedByDescending { it.wrong.length }) {
                    val ws = l.wrong.split(' ')
                    val n = ws.size
                    if (i + n > texts.size) continue
                    var same = true
                    for (k in 0 until n) {
                        if (core(texts[i + k]).lowercase() != ws[k]) { same = false; break }
                    }
                    if (!same) continue
                    val to = l.to.split(' ')
                    if (to.size < n) continue
                    val left = if (i > 0) core(texts[i - 1]).lowercase() else ""
                    val right = if (i + n < texts.size) core(texts[i + n]).lowercase() else ""
                    val context = (l.before.isNotEmpty() && l.before == left) || (l.after.isNotEmpty() && l.after == right)
                    val unknown = isKnown != null && ws.any { !isKnown(it) }
                    if (!(context || l.times >= 2 || unknown)) continue
                    for (k in 0 until n) {
                        val e = edges(texts[i + k])
                        var piece = if (k < n - 1) to[k] else to.subList(k, to.size).joinToString(" ")
                        if (k == 0 && e.core.isNotEmpty() && e.core[0].isUpperCase() && piece.isNotEmpty() && piece[0].isLowerCase()) {
                            piece = piece.replaceFirstChar { it.uppercase() }
                        }
                        texts[i + k] = e.pre + piece + e.suf
                        locked.add(i + k)
                    }
                    i += n
                    done = true
                    break
                }
            }
            if (!done) i++
        }
        return locked
    }

    /** The same as [apply] for one piece of text. Spaces and line breaks stay as they were. */
    fun applyText(text: String): String {
        if (lessons.isEmpty()) return text
        val found = Regex("\\S+").findAll(text).toList()
        if (found.isEmpty()) return text
        val tokens = found.map { it.value }.toMutableList()
        if (apply(tokens, null).isEmpty()) return text
        val sb = StringBuilder()
        var last = 0
        for (k in found.indices) {
            sb.append(text.substring(last, found[k].range.first))
            sb.append(tokens[k])
            last = found[k].range.last + 1
        }
        sb.append(text.substring(last))
        return sb.toString()
    }
}
