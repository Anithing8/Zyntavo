package com.zyntavo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Word(val start: Double, val end: Double, val text: String)
data class Cue(val start: Double, val end: Double, val text: String, val words: List<Word> = emptyList())
data class Lang(val label: String, val model: String)

/** One word the spelling check changed: its place in the word list, what it was, and what it became. */
data class SpellChange(val index: Int, val before: String, val after: String)
data class SpellResult(val words: List<Word>, val changes: List<SpellChange>)

/** Hinglish is Hindi and English mixed. It is heard with the Hindi models. */
val languages = listOf(
    Lang("English", "vosk-model-small-en-us-0.15"),
    Lang("Hindi", "vosk-model-small-hi-0.22"),
    Lang("Hinglish", "vosk-model-small-hi-0.22"),
    Lang("Spanish", "vosk-model-small-es-0.42"),
    Lang("French", "vosk-model-small-fr-0.22"),
    Lang("German", "vosk-model-small-de-0.15"),
    Lang("Russian", "vosk-model-small-ru-0.22"),
    Lang("Italian", "vosk-model-small-it-0.22"),
    Lang("Portuguese", "vosk-model-small-pt-0.3")
)

private val objRe = Regex("\\{[^{}]*\"word\"[^{}]*\\}")
private val startRe = Regex("\"start\"\\s*:\\s*([0-9.eE+-]+)")
private val endRe = Regex("\"end\"\\s*:\\s*([0-9.eE+-]+)")
private val wordRe = Regex("\"word\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

fun parseWords(json: String): List<Word> = objRe.findAll(json).mapNotNull { m ->
    val t = m.value
    val s = startRe.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
    val e = endRe.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
    val w = wordRe.find(t)?.groupValues?.get(1)
    if (s != null && e != null && w != null) Word(s, e, w) else null
}.toList()

// ---------------------------------------------------------------------------------------------
// Hindi written in Roman letters ("Hinglish"). Rules of thumb, not a dictionary.
// ---------------------------------------------------------------------------------------------

object Hinglish {
    private val vowels = mapOf(
        0x0905 to "a", 0x0906 to "aa", 0x0907 to "i", 0x0908 to "ee", 0x0909 to "u", 0x090A to "oo", 0x090B to "ri",
        0x090D to "e", 0x090F to "e", 0x0910 to "ai", 0x0911 to "o", 0x0913 to "o", 0x0914 to "au"
    )
    private val cons = mapOf(
        0x0915 to "k", 0x0916 to "kh", 0x0917 to "g", 0x0918 to "gh", 0x0919 to "ng", 0x091A to "ch", 0x091B to "chh",
        0x091C to "j", 0x091D to "jh", 0x091E to "ny", 0x091F to "t", 0x0920 to "th", 0x0921 to "d", 0x0922 to "dh",
        0x0923 to "n", 0x0924 to "t", 0x0925 to "th", 0x0926 to "d", 0x0927 to "dh", 0x0928 to "n", 0x0929 to "n",
        0x092A to "p", 0x092B to "ph", 0x092C to "b", 0x092D to "bh", 0x092E to "m", 0x092F to "y", 0x0930 to "r",
        0x0931 to "r", 0x0932 to "l", 0x0933 to "l", 0x0935 to "v", 0x0936 to "sh", 0x0937 to "sh", 0x0938 to "s", 0x0939 to "h"
    )
    private val precomposed = mapOf(0x0958 to "q", 0x0959 to "kh", 0x095A to "g", 0x095B to "z", 0x095C to "r", 0x095D to "rh", 0x095E to "f", 0x095F to "y")
    private val nukta = mapOf(0x0915 to "q", 0x0916 to "kh", 0x0917 to "g", 0x091C to "z", 0x0921 to "r", 0x0922 to "rh", 0x092B to "f", 0x092F to "y")
    private val matras = mapOf(
        0x093E to "aa", 0x093F to "i", 0x0940 to "ee", 0x0941 to "u", 0x0942 to "oo", 0x0943 to "ri", 0x0945 to "e",
        0x0947 to "e", 0x0948 to "ai", 0x0949 to "o", 0x094B to "o", 0x094C to "au"
    )
    private val labials = setOf(0x092A, 0x092B, 0x092C, 0x092D, 0x092E)

    private class Syl(var cons: String, var vowel: String?, var tail: String, val dev: Boolean)

    fun isDevanagari(s: String) = s.any { it.code in 0x0900..0x097F }

    private fun isCons(c: Int) = cons.containsKey(c) || precomposed.containsKey(c)

    fun roman(text: String): String {
        val out = StringBuilder()
        val cur = StringBuilder()
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (cur.isNotEmpty()) { out.append(word(cur.toString())); cur.clear() }
                out.append(ch)
            } else cur.append(ch)
        }
        if (cur.isNotEmpty()) out.append(word(cur.toString()))
        return out.toString()
    }

    private fun word(w: String): String {
        if (!isDevanagari(w)) return w
        val units = ArrayList<Syl>()
        var i = 0
        while (i < w.length) {
            val c = w[i].code
            if (vowels.containsKey(c)) {
                units.add(Syl("", vowels[c]!!, "", true))
                i++
            } else if (isCons(c)) {
                var cluster = ""
                var j = i
                while (true) {
                    val cc = w[j].code
                    var adv = 1
                    val piece: String
                    if (precomposed.containsKey(cc)) {
                        piece = precomposed[cc]!!
                    } else if (j + 1 < w.length && w[j + 1].code == 0x093C && nukta.containsKey(cc)) {
                        piece = nukta[cc]!!
                        adv = 2
                    } else {
                        piece = cons[cc] ?: ""
                    }
                    cluster += piece
                    j += adv
                    if (j + 1 < w.length && w[j].code == 0x094D && isCons(w[j + 1].code)) { j += 1; continue }
                    break
                }
                var vowel: String? = null
                if (j < w.length && matras.containsKey(w[j].code)) { vowel = matras[w[j].code]; j++ }
                else if (j < w.length && w[j].code == 0x094D) { vowel = ""; j++ }
                units.add(Syl(cluster, vowel, "", true))
                i = j
            } else if (c == 0x0902 || c == 0x0901) {
                val next = w.getOrNull(i + 1)?.code
                val m = if (next != null && next in labials) "m" else "n"
                val prev = units.lastOrNull()
                if (prev != null) prev.tail += m else units.add(Syl(m, "", "", false))
                i++
            } else if (c == 0x0903) {
                units.lastOrNull()?.let { it.tail += "h" }
                i++
            } else if (c == 0x0964 || c == 0x0965) {
                units.add(Syl(".", "", "", false)); i++
            } else if (c in 0x0966..0x096F) {
                units.add(Syl((c - 0x0966).toString(), "", "", false)); i++
            } else {
                units.add(Syl(w[i].toString(), "", "", false)); i++
            }
        }
        val dev = units.indices.filter { units[it].dev }
        val lastDev = dev.lastOrNull() ?: -1
        val sb = StringBuilder()
        for ((k, u) in units.withIndex()) {
            sb.append(u.cons)
            var v = u.vowel
            if (u.dev) {
                if (v == null) {
                    val pos = dev.indexOf(k)
                    val next = if (pos + 1 < dev.size) units[dev[pos + 1]] else null
                    val nextExplicit = next != null && next.vowel != null && next.vowel != ""
                    v = if (k == lastDev) "" else if (dev.size >= 3 && pos == dev.size - 2 && nextExplicit) "" else "a"
                } else if (u.tail.isEmpty() && k == lastDev) {
                    v = when (v) { "aa" -> "a"; "ee" -> "i"; "oo" -> "u"; else -> v }
                } else if (u.tail.isNotEmpty() && v == "ee") {
                    v = "i"
                }
            }
            sb.append(v ?: "").append(u.tail)
        }
        return sb.toString()
    }
}

fun hasDevanagari(words: List<Word>) = words.any { Hinglish.isDevanagari(it.text) }

private fun capitaliseSentences(words: List<Word>): List<Word> {
    var cap = true
    return words.map { w ->
        val t = if (cap) w.text.replaceFirstChar { it.uppercase() } else w.text
        cap = endsSentence(w.text)
        w.copy(text = t)
    }
}

fun hinglishWords(words: List<Word>): List<Word> =
    capitaliseSentences(words.map { it.copy(text = Hinglish.roman(it.text)) })

// ---------------------------------------------------------------------------------------------
// Turning words into subtitles
// ---------------------------------------------------------------------------------------------

/** How the words are cut into subtitles. */
data class CapOpts(val maxChars: Int = 36, val lines: Int = 2, val shift: Float = 0f, val tidy: Boolean = true, val dedupe: Boolean = false)

private val fillers = setOf("um", "uh", "uhm", "er", "erm", "hmm", "mm", "ah")
private val weakEnd = setOf("a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "with", "and", "or", "but", "my", "your", "our", "their", "his", "her", "is", "are", "was", "were", "that", "this")

fun endsSentence(t: String) = t.endsWith(".") || t.endsWith("?") || t.endsWith("!") || t.endsWith("\u2026") || t.endsWith("\u0964")

/** Splits a subtitle over at most [lines] lines. Avoids ending a line on "the", "of", "and"... and avoids a lone last word. */
fun wrapLines(text: String, maxChars: Int, lines: Int): String {
    val flat = text.replace("\n", " ").trim()
    if (lines <= 1 || flat.length <= maxChars) return flat
    var best = -1
    var bestCost = Double.MAX_VALUE
    flat.forEachIndexed { i, c ->
        if (c == ' ') {
            val a = flat.substring(0, i)
            val b = flat.substring(i + 1)
            var cost = abs(a.length - b.length).toDouble()
            if (a.length > maxChars || b.length > maxChars) cost += 50.0
            val lastWord = a.substringAfterLast(' ').lowercase().trim(',', '.', ';', ':', '?', '!')
            if (lastWord in weakEnd) cost += 8.0
            if (!b.contains(' ')) cost += 10.0
            if (a.endsWith(",") || a.endsWith(";") || a.endsWith(":") || endsSentence(a)) cost -= 6.0
            if (cost < bestCost) { bestCost = cost; best = i }
        }
    }
    return if (best < 0) flat else flat.substring(0, best) + "\n" + flat.substring(best + 1)
}

/** Fillers out, capitals and full stops added when there are none, then readable subtitles with good timing. */
fun buildCues(input: List<Word>, o: CapOpts): List<Cue> {
    val shift = o.shift.toDouble()
    var words = input
        .filter { it.text.lowercase().trim(',', '.', '?', '!') !in fillers }
        .map { it.copy(start = max(0.0, it.start + shift), end = max(0.05, it.end + shift)) }
    if (words.isEmpty()) return emptyList()
    if (o.dedupe) {
        // "the the" becomes "the": the same word twice in a row, said quickly.
        val once = ArrayList<Word>()
        for (w in words) {
            val prev = once.lastOrNull()
            val same = prev != null && w.text.length > 1 &&
                prev.text.trim(',', '.', '?', '!').lowercase() == w.text.trim(',', '.', '?', '!').lowercase() &&
                w.start - prev.end < 0.7
            if (prev != null && same) once[once.size - 1] = prev.copy(end = w.end, text = w.text) else once.add(w)
        }
        words = once
    }
    val punctuated = words.any { w -> w.text.any { it == '.' || it == ',' || it == '?' || it == '!' || it == '\u0964' } }
    if (o.tidy && !punctuated) {
        var cap = true
        words = words.mapIndexed { i, w ->
            var t = w.text
            if (t == "i") t = "I" else if (t.startsWith("i'")) t = "I" + t.substring(1)
            if (cap) t = t.replaceFirstChar { it.uppercase() }
            val next = words.getOrNull(i + 1)
            val stop = next == null || next.start - w.end > 0.8
            if (stop) t += "."
            cap = stop
            w.copy(text = t)
        }
    }
    val limit = o.maxChars * o.lines
    fun len(l: List<Word>) = l.sumOf { it.text.length + 1 } - 1
    val groups = ArrayList<List<Word>>()
    var cur = ArrayList<Word>()
    for (w in words) {
        if (cur.isNotEmpty()) {
            val last = cur.last()
            val tooLong = len(cur) + 1 + w.text.length > limit
            val pause = w.start - last.end > 0.8
            val sentence = endsSentence(last.text) && len(cur) >= 14
            val clause = (last.text.endsWith(",") || last.text.endsWith(";")) && len(cur) >= limit * 0.6
            if (tooLong || pause || sentence || clause) { groups.add(cur); cur = ArrayList() }
        }
        cur.add(w)
    }
    if (cur.isNotEmpty()) groups.add(cur)

    val merged = ArrayList<List<Word>>()
    var gi = 0
    while (gi < groups.size) {
        val g = groups[gi]
        val n = groups.getOrNull(gi + 1)
        if (g.size == 1 && n != null && !endsSentence(g[0].text) && n.first().start - g[0].end < 0.6 && len(g + n) <= limit) {
            merged.add(g + n); gi += 2
        } else { merged.add(g); gi++ }
    }

    val cues = merged.map { g -> Cue(g.first().start, g.last().end, wrapLines(g.joinToString(" ") { it.text }, o.maxChars, o.lines), g) }.toMutableList()
    for (i in cues.indices) {
        val c = cues[i]
        var end = c.end
        val need = max(0.9, c.text.replace("\n", " ").length / 17.0)
        if (end - c.start < need) end = c.start + need
        val next = cues.getOrNull(i + 1)
        if (next != null) end = min(end, next.start - 0.03)
        cues[i] = c.copy(end = max(end, c.start + 0.3))
    }
    return cues
}

/** Sentences for translation: cut at full stops, long pauses, or after 30 words. */
fun splitSentences(words: List<Word>): List<List<Word>> {
    val out = ArrayList<List<Word>>()
    var cur = ArrayList<Word>()
    for (w in words) {
        if (cur.isNotEmpty() && (w.start - cur.last().end > 1.0 || cur.size >= 30 || endsSentence(cur.last().text))) {
            out.add(cur); cur = ArrayList()
        }
        cur.add(w)
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out
}

/** Gives the translated words of each sentence the time of the sentence they came from. */
fun spreadSentences(sents: List<List<Word>>, texts: List<String>): List<Word> {
    val out = ArrayList<Word>()
    sents.forEachIndexed { i, s ->
        val toks = texts[i].split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return@forEachIndexed
        val a = s.first().start
        val b = s.last().end
        val total = toks.sumOf { it.length + 1 }.toDouble()
        var acc = 0.0
        for (t in toks) {
            val st = a + (b - a) * acc / total
            acc += t.length + 1
            val en = a + (b - a) * acc / total
            out.add(Word(st, max(st + 0.08, en - 0.02), t))
        }
    }
    return out
}

suspend fun translateWords(words: List<Word>, toHindi: Boolean, status: (String) -> Unit): List<Word>? {
    val sents = splitSentences(words)
    val res = translateText(sents.map { s -> s.joinToString(" ") { it.text } }, toHindi, status) ?: return null
    if (res.size != sents.size) return null
    return spreadSentences(sents, res)
}

// ---------------------------------------------------------------------------------------------
// Files: SRT, WebVTT and styled ASS
// ---------------------------------------------------------------------------------------------

private fun pad(v: Long, n: Int) = v.toString().padStart(n, '0')

fun srtTime(t: Double): String {
    val ms = (t * 1000).toLong()
    return "${pad(ms / 3600000, 2)}:${pad(ms / 60000 % 60, 2)}:${pad(ms / 1000 % 60, 2)},${pad(ms % 1000, 3)}"
}

private fun shortTime(t: Double): String {
    val ms = (t * 10).toLong() * 100
    return "${ms / 60000}:${pad(ms / 1000 % 60, 2)}.${ms % 1000 / 100}"
}

private fun assTime(t: Double): String {
    val cs = (max(t, 0.0) * 100).toLong()
    return "${cs / 360000}:${pad(cs / 6000 % 60, 2)}:${pad(cs / 100 % 60, 2)}.${pad(cs % 100, 2)}"
}

val capPresetNames = listOf("Classic", "Outline", "Word highlight", "Bold yellow")

private fun assText(c: Cue, karaoke: Boolean): String {
    val flat = c.text.replace("\n", " ")
    val usable = karaoke && c.words.isNotEmpty() && c.words.joinToString(" ") { it.text } == flat
    if (!usable) return c.text.replace("{", "(").replace("}", ")").replace("\n", "\\N")
    val firstLine = if ('\n' in c.text) c.text.substringBefore('\n').split(' ').size else Int.MAX_VALUE
    val sb = StringBuilder()
    c.words.forEachIndexed { i, w ->
        val next = c.words.getOrNull(i + 1)
        val cs = max(1, (((next?.start ?: c.end) - w.start) * 100).roundToInt())
        sb.append("{\\k").append(cs).append("}").append(w.text.replace("{", "(").replace("}", ")"))
        if (i + 1 == firstLine) sb.append("\\N") else if (next != null) sb.append(' ')
    }
    return sb.toString()
}

/** A styled subtitle file. Word highlight lights up each word as it is spoken. */
fun toAss(cues: List<Cue>, st: CaptionStyle, resX: Int = 1280, resY: Int = 720): String {
    val font = if (cues.any { Hinglish.isDevanagari(it.text) }) "Nirmala UI" else "Arial"
    // Text is sized from the height of a wide frame, and from 90% of the width of a tall or square one.
    val ref = if (resX * 9 >= resY * 16) resY.toDouble() else minOf(resY.toDouble(), resX * 0.9)
    val k = ref / 720.0
    val size = (st.size * ref).roundToInt()
    val marginSide = (60.0 * resX / 1280.0).roundToInt()
    val marginBottom = (44.0 * resY / 720.0).roundToInt()
    val align = when (st.pos) { 0 -> 8; 1 -> 5; else -> 2 }
    val white = "&H00FFFFFF"
    val yellow = "&H0000E6FF"
    val black = "&H00000000"
    val boxCol = "&H99000000"
    val primary = if (st.preset >= 2) yellow else white
    val secondary = if (st.preset == 2) white else primary
    val boxed = st.preset == 0 && st.box
    val borderStyle = if (boxed) 3 else 1
    val outlineCol = if (boxed) boxCol else black
    val outline = ((if (boxed) 6 else 3) * k).roundToInt().coerceAtLeast(1)
    val shadow = if (boxed) 0 else k.roundToInt().coerceAtLeast(1)
    val bold = if (st.preset >= 2) -1 else 0
    val sb = StringBuilder()
    sb.append("[Script Info]\nScriptType: v4.00+\nPlayResX: $resX\nPlayResY: $resY\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n")
    sb.append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
    sb.append("Style: Default,$font,$size,$primary,$secondary,$outlineCol,$boxCol,$bold,0,0,0,100,100,0,0,$borderStyle,$outline,$shadow,$align,$marginSide,$marginSide,$marginBottom,1\n\n")
    sb.append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
    for (c in cues) sb.append("Dialogue: 0,${assTime(c.start)},${assTime(c.end)},Default,,0,0,0,,${assText(c, st.preset == 2)}\n")
    return sb.toString()
}

// ---------------------------------------------------------------------------------------------
// State and screen
// ---------------------------------------------------------------------------------------------

class CaptionsModel {
    var lang by mutableStateOf(0)
    var engine by mutableStateOf(1)
    var translate by mutableStateOf(false)
    var status by mutableStateOf("Choose a video or audio file to create captions.")
    var busy by mutableStateOf(false)
    var words by mutableStateOf<List<Word>>(emptyList())
    var alt by mutableStateOf<List<Word>>(emptyList())
    var altName by mutableStateOf("")
    var mode by mutableStateOf(0)
    var mainEnglish by mutableStateOf(true)
    var glossary by mutableStateOf(Learned.savedGlossary().also { Hints.custom = it })
    var findText by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var deepSpell by mutableStateOf(false)
    var autoSpell by mutableStateOf(true)
    var spellChanges by mutableStateOf<List<SpellChange>>(emptyList())
    var spellTarget by mutableStateOf(0)
    var opts by mutableStateOf(CapOpts())
    var style by mutableStateOf(CaptionStyle())
    val cues = mutableStateListOf<Cue>()

    fun rebuild() {
        cues.clear()
        fun build(w: List<Word>) = buildCues(w, if (hasDevanagari(w)) opts.copy(maxChars = min(opts.maxChars, 30)) else opts)
        when {
            mode == 1 && alt.isNotEmpty() -> cues.addAll(build(alt))
            mode == 2 && alt.isNotEmpty() -> cues.addAll(
                build(alt).map { c ->
                    val other = words.filter { (it.start + it.end) / 2 in c.start..c.end }.joinToString(" ") { it.text }
                    if (other.isBlank()) c else c.copy(text = c.text.replace("\n", " ") + "\n" + other, words = emptyList())
                }
            )
            else -> cues.addAll(build(words))
        }
    }

    fun srt() = toSrt(cues.toList())
    fun vtt() = "WEBVTT\n\n" + cues.joinToString("\n") { "${srtTime(it.start).replace(',', '.')} --> ${srtTime(it.end).replace(',', '.')}\n${it.text}\n" }
    fun ass() = toAss(cues.toList(), style)
    fun plain() = cues.joinToString(" ") { it.text.replace("\n", " ") }
}

@Composable
fun CaptionStyleControls(m: CaptionsModel) {
    val st = m.style
    Label("Look")
    Chips { capPresetNames.forEachIndexed { i, n -> FilterChip(selected = st.preset == i, onClick = { m.style = st.copy(preset = i) }, label = { Text(n) }) } }
    Label("Position")
    Chips {
        listOf("Top", "Middle", "Bottom").forEachIndexed { p, n -> FilterChip(selected = st.pos == p, onClick = { m.style = st.copy(pos = p) }, label = { Text(n) }) }
        if (st.preset == 0) FilterChip(selected = st.box, onClick = { m.style = st.copy(box = !st.box) }, label = { Text("Dark box") })
    }
    Param("Size", st.size, 0.025f..0.09f, "${(st.size * 1000).roundToInt()}", { m.style = st.copy(size = it) })
}

@Composable
fun CaptionsScreen(m: CaptionsModel) {
    val scope = rememberCoroutineScope()
    val toEnglish = m.translate && m.lang != 0
    val engine = if (toEnglish && m.engine == 0) 2 else m.engine
    /** English subtitles only: 0 = the main text, 1 = the translated text, -1 = nothing in English to check. */
    fun englishTarget(): Int = when {
        m.mode == 1 && m.altName == "English" -> 1
        m.mainEnglish -> 0
        m.altName == "English" -> 1
        else -> -1
    }

    fun fixSpellingNow() {
        Hints.custom = m.glossary
        scope.launch {
            val target = englishTarget()
            if (target < 0) { m.status = "The spelling check works on English subtitles."; return@launch }
            m.busy = true
            m.status = "Preparing the spelling model…"
            val src = if (target == 1) m.alt else m.words
            val res = try {
                fixSpelling(src, m.deepSpell) { m.status = it }
            } catch (e: Throwable) {
                m.status = "Spelling check failed: ${e.message ?: "unknown error"}"
                null
            }
            if (res != null) {
                if (target == 1) m.alt = res.words else m.words = res.words
                m.spellChanges = res.changes
                m.spellTarget = target
                m.rebuild()
                m.status = if (res.changes.isEmpty()) "No spelling mistakes found." else "Fixed ${res.changes.size} words. Review them below."
            }
            m.busy = false
        }
    }

    fun applyLearned(l: List<Word>): List<Word> {
        val t = l.map { it.text }.toMutableList()
        return if (Learned.apply(t, null).isEmpty()) l else l.mapIndexed { k, w -> w.copy(text = t[k]) }
    }

    /** Called when the person finishes editing one caption: remember the word fixes and use them everywhere else. */
    /** Uses everything learned on the words and on the captions that are showing now. Returns how many captions changed. */
    fun useLessons(): Int {
        if (m.mode == 1 && m.alt.isNotEmpty()) m.alt = applyLearned(m.alt) else if (m.mode == 0) m.words = applyLearned(m.words)
        var changed = 0
        for (j in m.cues.indices) {
            val nt = Learned.applyText(m.cues[j].text)
            if (nt != m.cues[j].text) {
                m.cues[j] = m.cues[j].copy(text = nt)
                changed++
            }
        }
        return changed
    }

    fun learnFromEdit(before: String, after: String) {
        val n = Learned.learn(before, after)
        if (n > 0) {
            useLessons()
            m.status = if (n == 1) "Learned 1 fix. It will be used next time." else "Learned $n fixes. They will be used next time."
        }
    }

    fun replaceAllNow() {
        val f = m.findText.trim()
        if (f.isEmpty()) return
        if (!Learned.teach(f, m.replaceText)) {
            m.status = "Type 1 to 3 words to find, and the same number of words or more to replace them with."
            return
        }
        val n = useLessons()
        m.status = if (n == 0) "Nothing to replace. It will be replaced next time." else "Replaced in $n captions. It will be replaced next time too."
    }

    fun undoChange(c: SpellChange) {
        Learned.keep(c.before)
        val list = (if (m.spellTarget == 1) m.alt else m.words).toMutableList()
        if (c.index in list.indices) list[c.index] = list[c.index].copy(text = c.before)
        if (m.spellTarget == 1) m.alt = list else m.words = list
        m.spellChanges = m.spellChanges.filter { it !== c }
        m.rebuild()
    }

    val start = rememberCaptionGenerator(
        languages[m.lang].model, engine, toEnglish,
        onBusy = { m.busy = it },
        onStatus = { m.status = it },
        onDone = { w ->
            if (w != null) {
                m.words = applyLearned(w)
                m.alt = emptyList(); m.altName = ""; m.mode = 0
                m.mainEnglish = m.lang == 0 || toEnglish
                m.spellChanges = emptyList()
                m.rebuild()
                m.status = if (m.cues.isEmpty()) "No speech found in this file." else "Done: ${m.cues.size} captions."
                if (m.autoSpell && m.cues.isNotEmpty() && m.mainEnglish) fixSpellingNow()
            }
        }
    )
    val save = rememberTextSaver { m.status = it }
    val clipboard = LocalClipboardManager.current
    val srcDevanagari = hasDevanagari(m.words)

    /** target: 0 English, 1 Hindi, 2 Hinglish (Hindi in Roman letters) */
    fun makeAlt(target: Int) {
        scope.launch {
            m.busy = true
            m.status = "Working…"
            val result: List<Word>? = try {
                when (target) {
                    0 -> translateWords(m.words, false) { m.status = it }
                    1 -> translateWords(m.words, true) { m.status = it }
                    else -> {
                        val hindi = if (srcDevanagari) m.words else translateWords(m.words, true) { m.status = it }
                        hindi?.let { hinglishWords(it) }
                    }
                }
            } catch (e: Throwable) {
                m.status = "Failed: ${e.message ?: "unknown error"}"
                null
            }
            if (result != null && result.isNotEmpty()) {
                m.alt = result
                m.altName = listOf("English", "Hindi", "Hinglish")[target]
                m.mode = 1
                m.spellChanges = emptyList()
                m.rebuild()
                m.status = "${m.altName} captions ready: ${m.cues.size}."
            }
            m.busy = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Column(Modifier.padding(start = 2.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Captions", style = MaterialTheme.typography.titleLarge)
                    Label("Turn speech into subtitles. Everything runs on your device.")
                }
            }
            item {
                Section("Language spoken") {
                    Chips {
                        languages.forEachIndexed { i, l ->
                            FilterChip(
                                selected = m.lang == i,
                                onClick = {
                                    if (!m.busy) {
                                        m.lang = i
                                        if (i == 1 || i == 2) { m.translate = true; m.engine = 3 } else m.translate = false
                                    }
                                },
                                label = { Text(l.label) }
                            )
                        }
                    }
                    if (m.lang != 0) {
                        Chips { FilterChip(selected = m.translate, onClick = { m.translate = !m.translate }, label = { Text("Subtitles in English") }) }
                        Label(
                            if (m.lang == 2) "Hinglish means Hindi and English mixed. English subtitles work best with Max."
                            else if (m.translate) "The speech is written directly in English."
                            else "Captions stay in the language that is spoken."
                        )
                    }
                }
            }
            item {
                Section("Names and special words", startOpen = false) {
                    Label("Type names and unusual words that are said in the video, with commas between them. The speech model is told how to spell them, and the spelling fix uses them too.")
                    OutlinedTextField(
                        value = m.glossary,
                        onValueChange = { v -> m.glossary = v; Hints.custom = v; Learned.saveGlossary(v) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("For example: Qawali, Ramayan, Kun Faya Kun") }
                    )
                    Label("Set this before you choose the video. It is remembered next time. Names are also fixed by their sound, so a word like Kavali becomes Qawali.")
                }
            }
            item {
                Section("Accuracy") {
                    Segmented(listOf("Standard", "Better", "Best", "Max"), engine, { if (!m.busy) m.engine = it })
                    Label(
                        when (engine) {
                            0 -> "Fast. No punctuation, no translation."
                            1 -> "About 40 MB. Adds punctuation."
                            2 -> "About 80 MB. More accurate."
                            else -> "About 250 MB. Slowest, most accurate. Best for Hindi and Hinglish."
                        }
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Hints.custom = m.glossary; start() }, enabled = !m.busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Glyph(Ico.Open, Modifier.size(20.dp), AccentInk)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose video or audio")
                    }
                    if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                    Label(m.status)
                }
            }
            if (m.words.isEmpty() && !m.busy) {
                item {
                    GlassCard {
                        Text("How it works", style = MaterialTheme.typography.titleSmall)
                        StepRow(1, "Choose the language that is spoken.")
                        StepRow(2, "Pick an accuracy level. Bigger models download once.")
                        StepRow(3, "Choose a video or audio file. Edit the captions, then save them as .srt, .vtt or .ass.")
                    }
                }
            }
            if (m.words.isNotEmpty()) {
                item {
                    Section("Other languages") {
                        Chips {
                            if (srcDevanagari) FilterChip(selected = m.altName == "English", onClick = { if (!m.busy) makeAlt(0) }, label = { Text("English") })
                            if (!srcDevanagari) FilterChip(selected = m.altName == "Hindi", onClick = { if (!m.busy) makeAlt(1) }, label = { Text("Hindi") })
                            FilterChip(selected = m.altName == "Hinglish", onClick = { if (!m.busy) makeAlt(2) }, label = { Text("Hinglish") })
                        }
                        if (m.alt.isNotEmpty()) Segmented(listOf("Original", m.altName, "Both"), m.mode, { m.mode = it; m.rebuild() })
                    }
                }
                item {
                    Section("Fix spelling with AI") {
                        if (englishTarget() < 0) {
                            Label("This works on English subtitles. Make English subtitles first.")
                        } else {
                            Label("Subtitles are created first. Then a language model checks every word in its sentence and fixes misspelled ones.")
                            Chips {
                                FilterChip(selected = m.autoSpell, onClick = { m.autoSpell = !m.autoSpell }, label = { Text("Fix automatically") })
                                FilterChip(selected = m.deepSpell, onClick = { m.deepSpell = !m.deepSpell }, label = { Text("Deep check") })
                            }
                            Label(if (m.deepSpell) "Deep check also fixes real words that were clearly heard wrong. Slower. Look through the changes." else "Fast check fixes words that are not real words.")
                            Button(onClick = { if (!m.busy) fixSpellingNow() }, enabled = !m.busy && m.cues.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Fix spelling now") }
                            Label("About 110 MB, downloaded once. Manual edits to captions are replaced when the text is rebuilt.")
                        }
                    }
                }
                if (m.spellChanges.isNotEmpty()) {
                    item {
                        Section("Words that were fixed (${m.spellChanges.size})") {
                            m.spellChanges.take(80).forEach { c ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.before, color = Danger, style = MaterialTheme.typography.bodyMedium)
                                    Text("  \u2192  ", color = TextLo)
                                    Text(c.after, color = Color(0xFF3DDC84), style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { undoChange(c) }) { Text("Undo") }
                                }
                            }
                            if (m.spellChanges.size > 80) Label("And ${m.spellChanges.size - 80} more.")
                        }
                    }
                }
                item {
                    Section("Find and replace", startOpen = false) {
                        Label("Replaces a word or phrase in all captions. It is also remembered for the next video.")
                        OutlinedTextField(value = m.findText, onValueChange = { v -> m.findText = v }, modifier = Modifier.fillMaxWidth(), label = { Text("Find") })
                        OutlinedTextField(value = m.replaceText, onValueChange = { v -> m.replaceText = v }, modifier = Modifier.fillMaxWidth(), label = { Text("Replace with") })
                        Button(onClick = { replaceAllNow() }, enabled = m.findText.isNotBlank() && m.replaceText.isNotBlank()) { Text("Replace all") }
                    }
                }
                item {
                    Section("What the app learned (${Learned.lessons.size})", startOpen = false) {
                        Label("Fix a word in a caption by hand and it is fixed the same way next time. Change a fixed word back with Undo and it is left alone from then on.")
                        if (Learned.lessons.isEmpty()) Label("Nothing yet.")
                        Learned.lessons.take(60).forEach { l ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(l.wrong, color = Danger, style = MaterialTheme.typography.bodyMedium)
                                Text("  \u2192  ", color = TextLo)
                                Text(l.to, color = Color(0xFF3DDC84), style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { Learned.forget(l) }) { Text("Remove") }
                            }
                        }
                        if (Learned.lessons.size > 60) Label("And ${Learned.lessons.size - 60} more.")
                        if (Learned.keptWords.isNotEmpty()) Label("Left alone: " + Learned.keptWords.take(30).joinToString(", "))
                        if (Learned.lessons.isNotEmpty() || Learned.keptWords.isNotEmpty()) {
                            DangerButton(onClick = { Learned.forgetAll() }) { Text("Forget everything it learned") }
                        }
                    }
                }
                item {
                    Section("Layout and style", startOpen = false) {
                        Label("Changing the layout rebuilds the captions and discards manual edits.")
                        Param("Characters per line", m.opts.maxChars.toFloat(), 20f..50f, "${m.opts.maxChars}", { m.opts = m.opts.copy(maxChars = it.roundToInt()) }, { m.rebuild() })
                        Chips {
                            FilterChip(selected = m.opts.lines == 1, onClick = { m.opts = m.opts.copy(lines = 1); m.rebuild() }, label = { Text("1 line") })
                            FilterChip(selected = m.opts.lines == 2, onClick = { m.opts = m.opts.copy(lines = 2); m.rebuild() }, label = { Text("2 lines") })
                            FilterChip(selected = m.opts.tidy, onClick = { m.opts = m.opts.copy(tidy = !m.opts.tidy); m.rebuild() }, label = { Text("Tidy text") })
                            FilterChip(selected = m.opts.dedupe, onClick = { m.opts = m.opts.copy(dedupe = !m.opts.dedupe); m.rebuild() }, label = { Text("Remove repeated words") })
                        }
                        Param("Timing shift", m.opts.shift, -1f..1f, "${(m.opts.shift * 1000).roundToInt()} ms", { m.opts = m.opts.copy(shift = it) }, { m.rebuild() })
                        CaptionStyleControls(m)
                    }
                }
                item {
                    Section("Save") {
                        Chips {
                            OutlinedButton(onClick = { save("captions.srt", m.srt()) }, enabled = m.cues.isNotEmpty()) { Text(".srt") }
                            OutlinedButton(onClick = { save("captions.vtt", m.vtt()) }, enabled = m.cues.isNotEmpty()) { Text(".vtt") }
                            OutlinedButton(onClick = { save("captions.ass", m.ass()) }, enabled = m.cues.isNotEmpty()) { Text(".ass") }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(m.plain())); m.status = "Copied the text." }, enabled = m.cues.isNotEmpty()) { Text("Copy text") }
                        }
                    }
                }
                itemsIndexed(m.cues) { i, c ->
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.8f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${shortTime(c.start)} \u2192 ${shortTime(c.end)}", color = TextLo, style = MaterialTheme.typography.labelLarge)
                        var base by remember { mutableStateOf<String?>(null) }
                        OutlinedTextField(
                            value = c.text,
                            onValueChange = { v -> m.cues[i] = c.copy(text = v) },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                                if (f.isFocused) {
                                    base = m.cues.getOrNull(i)?.text
                                } else {
                                    val b = base
                                    val now = m.cues.getOrNull(i)?.text
                                    base = null
                                    if (b != null && now != null && b != now) learnFromEdit(b, now)
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (i < m.cues.lastIndex) {
                                OutlinedButton(onClick = {
                                    val n = m.cues[i + 1]
                                    m.cues[i] = c.copy(end = n.end, text = c.text.replace("\n", " ") + " " + n.text.replace("\n", " "), words = emptyList())
                                    m.cues.removeAt(i + 1)
                                }) { Text("Merge with next") }
                            }
                            DangerButton(onClick = { m.cues.removeAt(i) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
