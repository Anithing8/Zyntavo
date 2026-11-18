package com.zyntavo

import kotlin.math.abs

/** Names and unusual words the person typed in. Read by the speech model and by the spelling fix. */
object Hints {
    var custom: String = ""
}

/**
 * Fixes names and special words by how they SOUND. "Kavali" and "Qawali" look far apart, but both reduce to
 * the same sound key, so a word that is not real English and sounds like a listed word gets the listed spelling.
 * It also joins and splits words ("Fayakun" becomes "Kun Faya Kun"). Real English words are never touched.
 */
object Glossary {
    private val builtIn = listOf(
        "Qawali", "Ramayan", "Mahabharat", "Rahman", "Kun Faya Kun", "Bhajan", "Ghazal", "Kirtan", "Aarti", "Bhakti",
        "Raag", "Tabla", "Sitar", "Harmonium", "Dholak", "Shehnai", "Sarangi", "Mandir", "Masjid", "Gurudwara", "Dargah",
        "Mughal", "Bollywood", "Tollywood", "Hinglish", "Diwali", "Namaste", "Devanagari", "Sanskrit", "Ayodhya",
        "Hanuman", "Lakshman", "Ravan", "Krishna", "Shiva", "Ganesh", "Lucknow", "Bengaluru", "Hyderabad", "Kolkata",
        "Mumbai", "Chennai", "Amitabh", "Shah Rukh Khan", "Salman Khan", "Aamir Khan", "Arijit Singh", "Lata Mangeshkar",
        "Kishore Kumar", "Mohammed Rafi", "Nusrat Fateh Ali Khan", "Amir Khusro", "Bulleh Shah"
    )

    private class Term(val text: String) {
        val words: List<String> = text.split(' ').filter { it.isNotEmpty() }
    }

    private class Entry(val term: Term, val from: Int, val to: Int, val key: String, val custom: Boolean, val plain: String)

    private class Piece(val pre: String, val core: String, val suf: String)

    private class Match(val term: Term, val start: Int, val end: Int)

    /** Terms typed by the person, split at commas, semicolons and new lines. */
    fun customTerms(): List<String> =
        Hints.custom.split(',', ';', '\n').map { it.trim() }.filter { it.length >= 2 }.distinct()

    /** Every word of every term, lower case. The spelling fix leaves these alone. */
    fun termWords(): Set<String> {
        val s = HashSet<String>()
        for (t in customTerms() + builtIn) {
            for (w in t.split(' ')) {
                val l = letters(w).lowercase()
                if (l.length >= 3) s.add(l)
            }
        }
        return s
    }

    private fun letters(s: String): String = s.filter { it in 'a'..'z' || it in 'A'..'Z' }

    /** A rough "how it sounds" form: q=k, w=v, silent h, all vowels alike, doubled letters once. */
    fun key(word: String): String {
        var x = letters(word).lowercase()
        x = x.replace("ph", "f").replace("ck", "k").replace("kh", "k").replace("gh", "g")
            .replace("th", "t").replace("dh", "d").replace("bh", "b").replace("ch", "5").replace("sh", "6")
        x = x.replace('q', 'k').replace('c', 'k').replace('w', 'v').replace('z', 'j').replace('y', 'a')
            .replace("x", "ks").replace("h", "")
        val sb = StringBuilder()
        for (c in x) {
            val ch = if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') 'a' else c
            if (sb.isNotEmpty() && sb[sb.length - 1] == ch) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun split(t: String): Piece {
        var a = 0
        var b = t.length
        while (a < b && !t[a].isLetterOrDigit()) a++
        while (b > a && !t[b - 1].isLetterOrDigit()) b--
        return Piece(t.substring(0, a), t.substring(a, b), t.substring(b))
    }

    private fun addEntry(out: MutableList<Entry>, t: Term, from: Int, to: Int, custom: Boolean) {
        val plain = t.words.subList(from, to).joinToString("") { letters(it) }.lowercase()
        val k = key(plain)
        if (k.length >= 4 && plain.length >= 4) out.add(Entry(t, from, to, k, custom, plain))
    }

    private fun entriesFor(list: List<String>, custom: Boolean): List<Entry> {
        val out = ArrayList<Entry>()
        for (raw in list) {
            val t = Term(raw)
            val n = t.words.size
            if (n == 0) continue
            if (n == 1) {
                addEntry(out, t, 0, 1, custom)
            } else {
                // The whole phrase, and every run of two or more of its words ("Faya Kun" inside "Kun Faya Kun").
                for (a in 0 until n) {
                    for (b in (a + 2)..n) addEntry(out, t, a, b, custom)
                }
            }
        }
        return out
    }

    private fun match(texts: List<String>, i: Int, n: Int, entries: List<Entry>, isKnown: (String) -> Boolean): Match? {
        val pieces = ArrayList<Piece>()
        for (k in i until i + n) pieces.add(split(texts[k]))
        for (k in pieces.indices) {
            val p = pieces[k]
            if (p.core.length < 2 || !p.core.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
            if (k > 0 && p.pre.isNotEmpty()) return null
            if (k < pieces.size - 1 && p.suf.isNotEmpty()) return null
        }
        val joined = pieces.joinToString("") { it.core }
        val lowerJoined = joined.lowercase()
        if (joined.length < 4 || isKnown(lowerJoined) || Learned.isKept(lowerJoined)) return null
        val wk = key(joined)
        var best: Entry? = null
        var bestScore = Int.MAX_VALUE
        var tie = false
        for (e in entries) {
            var d = -1
            if (e.key == wk) {
                d = 0
            } else if (e.key.length >= 5 && wk.length >= 5 && abs(e.key.length - wk.length) <= 1 && lev(e.key, wk) == 1) {
                d = 1
            }
            if (d < 0) continue
            val score = d * 1000 + (if (e.custom) 0 else 100) + lev(lowerJoined, e.plain)
            val cur = best
            if (score < bestScore) {
                bestScore = score
                best = e
                tie = false
            } else if (score == bestScore && cur != null && cur.term !== e.term) {
                tie = true
            }
        }
        val e = best ?: return null
        if (tie) return null
        // Already written the right way.
        if (lowerJoined == e.plain && n == e.to - e.from) return null

        // A matched run of a longer phrase ("Faya Kun"): take in neighbouring words that are the rest of it.
        var start = i
        var end = i + n
        var k = e.from - 1
        while (k >= 0 && start - 1 >= 0) {
            val a = key(split(texts[start - 1]).core)
            if (a.isEmpty() || a != key(e.term.words[k])) break
            start--
            k--
        }
        k = e.to
        while (k < e.term.words.size && end < texts.size) {
            val a = key(split(texts[end]).core)
            if (a.isEmpty() || a != key(e.term.words[k])) break
            end++
            k++
        }
        if (end - start > e.term.words.size) return null
        return Match(e.term, start, end)
    }

    private fun replace(texts: MutableList<String>, m: Match, locked: MutableSet<Int>) {
        val parts = m.term.words
        val count = m.end - m.start
        for (idx in 0 until count) {
            val p = split(texts[m.start + idx])
            val piece = if (idx < count - 1) parts[idx] else parts.subList(idx, parts.size).joinToString(" ")
            val shaped = if (p.core.length > 1 && p.core.all { it.isUpperCase() }) piece.uppercase() else piece
            texts[m.start + idx] = p.pre + shaped + p.suf
            locked.add(m.start + idx)
        }
    }

    /**
     * Rewrites [texts] in place. [isKnown] says whether a lower-case word is real English.
     * Returns the positions it touched, so nothing else changes them afterwards.
     */
    fun fix(texts: MutableList<String>, skip: Set<Int> = emptySet(), isKnown: (String) -> Boolean): Set<Int> {
        val entries = entriesFor(customTerms(), true) + entriesFor(builtIn, false)
        val locked = HashSet<Int>()
        if (entries.isEmpty()) return locked
        var i = 0
        while (i < texts.size) {
            var m: Match? = null
            var n = 3
            while (n >= 1 && m == null) {
                if (i + n <= texts.size) m = match(texts, i, n, entries, isKnown)
                n--
            }
            if (m != null && (m.start until m.end).any { it in skip }) m = null
            if (m != null) {
                replace(texts, m, locked)
                i = m.end
            } else {
                i++
            }
        }
        return locked
    }
}
