package com.zyntavo

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * One thing a prompt asked for.
 * [key] says what, [value] says how much (a number of "normal steps" for adjustments, or an exact value),
 * [text] carries extra words (text to add, a colour name). A [soft] action belongs to a look such as "cinematic":
 * it is used where it can be and silently skipped where it cannot.
 */
data class PromptAction(
    val key: String, val value: Float, val label: String, val text: String = "", val soft: Boolean = false,
    val args: Map<String, String> = emptyMap()
)

class PromptResult(val actions: List<PromptAction>, val unknown: List<String>)

/** Example prompts shown as buttons. */
val promptIdeas = listOf(
    "warm and cinematic",
    "brighter with a slight vignette",
    "moody, more contrast, less colour",
    "vintage look",
    "black and white, very dramatic",
    "make it square",
    "fix the colours",
    "add text \"Summer\" at the top in yellow, big",
    "add a heart sticker",
    "rotate left and flip"
)

val videoPromptIdeas = listOf(
    "slow motion",
    "warm and cinematic",
    "2x speed",
    "slow zoom in with fade in",
    "add film grain and a vignette",
    "split at 5 seconds",
    "cut the first 2 seconds",
    "add a dissolve transition",
    "add title \"My trip\" at the bottom for 4 seconds",
    "1080p 60 fps high quality",
    "robot voice",
    "mirror all clips"
)

/** One-tap looks for photos: a name and the prompt it runs. */
val photoLooks = listOf(
    "Clean and bright" to "fix the colours, slightly brighter",
    "Film" to "vintage look, slight vignette",
    "Moody" to "moody",
    "Golden hour" to "golden hour, warmer",
    "Teal and orange" to "teal and orange, more contrast",
    "Soft pastel" to "dreamy pastel look",
    "Black and white" to "black and white, very dramatic",
    "Neon night" to "cyberpunk"
)

/** One-tap looks for video clips. */
val videoLooks = listOf(
    "Cinematic" to "warm and cinematic, slow zoom in",
    "Vlog" to "brighter, more colour, sharpen",
    "Retro" to "vintage look, add film grain",
    "Dreamy" to "dreamy look, fade in and out",
    "Punchy" to "more contrast, more colour, vignette",
    "Black and white" to "black and white, more contrast",
    "Slow motion" to "slow motion",
    "Timelapse" to "timelapse"
)

/**
 * Understands short written instructions without the internet. It knows many ways of saying the same thing,
 * how strong ("slightly", "very", "30%"), the direction ("less", "remove", "no"), and several things in one sentence.
 */
object Prompt {
    private class Prop(val key: String, val sign: Float)

    private val props = HashMap<String, Prop>()
    private val presets = HashMap<String, List<PromptAction>>()
    private val fxWords = HashMap<String, Pair<Int, String>>()

    private fun prop(key: String, sign: Float, vararg words: String) {
        for (w in words) props[w] = Prop(key, sign)
    }

    private fun act(key: String, value: Float, label: String, soft: Boolean = false) = PromptAction(key, value, label, "", soft)

    private fun preset(words: List<String>, actions: List<PromptAction>) {
        for (w in words) presets[w] = actions
    }

    private fun fx(mask: Int, name: String, vararg words: String) {
        for (w in words) fxWords[w] = Pair(mask, name)
    }

    private val weak = setOf("slightly", "slight", "bit", "little", "touch", "subtle", "subtly", "hint", "tiny", "lightly", "gently", "mild", "mildly", "barely")
    private val strong = setOf("very", "much", "lot", "super", "extremely", "heavily", "strongly", "strong", "intense", "way", "really", "hugely", "massively", "max", "maximum", "full", "deep", "deeply", "bold", "heavy", "totally")
    private val negWords = setOf("less", "reduce", "reduced", "lower", "decrease", "minus", "fewer", "lessen", "down", "drop", "not", "don't", "dont", "never", "cut")
    private val removeWords = setOf("remove", "without", "no", "off", "stop", "eliminate", "delete", "disable", "hide")
    private val resetWords = setOf("reset", "resetall", "restart")
    private val flipWords = setOf("flip", "flipped", "flipping", "mirror", "mirrored", "mirroring", "reflect")
    private val rotateWords = setOf("rotate", "rotated", "rotating", "rotation")
    private val muteWords = setOf("mute", "muted", "silent", "silence")
    private val colourWords = setOf("color", "colour", "colors", "colours")
    private val colorNames = setOf("white", "black", "red", "blue", "yellow", "green", "orange", "pink", "purple", "gray", "grey")
    private val fillers = setOf(
        "make", "it", "the", "a", "an", "please", "can", "you", "i", "want", "to", "look", "looks", "more", "this", "that", "photo", "picture", "image",
        "video", "clip", "of", "in", "on", "by", "so", "be", "is", "my", "me", "just", "and", "some", "bit", "very", "slightly", "little", "feel", "like", "give", "add", "apply", "set", "use", "put",
        "at", "for", "from", "first", "last", "next", "previous", "second", "seconds", "sec", "secs", "start", "end", "beginning", "all", "every", "each", "clips", "title", "text",
        "top", "bottom", "middle", "center", "centre", "big", "large", "small", "huge", "tiny", "with", "then", "here", "only", "long", "keep", "export"
    )
    private val ordinals = mapOf("first" to 0, "second" to 1, "third" to 2, "fourth" to 3, "fifth" to 4, "last" to -1, "next" to -2, "previous" to -3, "prev" to -3)
    private val clipWords = setOf("clip", "scene", "shot", "segment")
    private val numberWords = listOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")
    private val stickerWords = mapOf(
        "heart" to "\u2764\uFE0F", "love" to "\u2764\uFE0F", "fire" to "\uD83D\uDD25", "star" to "\u2B50", "smile" to "\uD83D\uDE00", "smiley" to "\uD83D\uDE00",
        "happy" to "\uD83D\uDE00", "party" to "\uD83C\uDF89", "confetti" to "\uD83C\uDF89", "thumbs" to "\uD83D\uDC4D", "sparkle" to "\u2728", "sparkles" to "\u2728"
    )
    private val commandWords = setOf(
        "slowmo", "speedup", "slowdown", "timelapse", "hyperlapse", "rotate", "flip", "mirror", "zoom", "closer", "wider", "volume", "louder", "quieter", "mute",
        "border", "frame", "square", "portrait", "landscape", "widescreen", "ultrawide", "split", "trim", "delete", "duplicate", "transition", "transitions", "dissolve", "crossfade",
        "kenburns", "fadein", "fadeout", "popin", "subtitles", "captions", "sticker", "emoji", "brightness", "contrast", "saturation", "vignette", "exposure", "highquality",
        "voice", "export", "shorten", "robot", "chipmunk", "telephone", "diptoblack", "diptowhite", "wipeleft", "wiperight", "slideleft", "slideup", "circleopen", "radial", "pixelize"
    )

    private val phrases = listOf(
        "black and white" to "monochrome", "black & white" to "monochrome", "b&w" to "monochrome", "b & w" to "monochrome",
        "grey scale" to "monochrome", "gray scale" to "monochrome",
        "slow motion" to "slowmo", "slow-mo" to "slowmo", "slow mo" to "slowmo", "time lapse" to "timelapse", "time-lapse" to "timelapse",
        "teal and orange" to "tealorange", "teal & orange" to "tealorange", "teal orange" to "tealorange",
        "high key" to "highkey", "high-key" to "highkey", "low key" to "lowkey", "low-key" to "lowkey", "golden hour" to "goldenhour",
        "film grain" to "grain", "washed out" to "washedout", "washed-out" to "washedout", "dark corners" to "vignette",
        "cinematic bars" to "letterbox", "cinema bars" to "letterbox", "black bars" to "letterbox", "letter box" to "letterbox",
        "duotone teal" to "duotoneteal", "duotone sunset" to "duotonesunset", "duotone purple" to "duotone",
        "upside down" to "upsidedown", "upside-down" to "upsidedown",
        "no filter" to "nofilter", "remove the filter" to "nofilter", "remove filter" to "nofilter", "without filter" to "nofilter", "without a filter" to "nofilter",
        "remove all effects" to "resetall", "remove all edits" to "resetall", "start over" to "resetall", "reset everything" to "resetall",
        "clear everything" to "resetall", "undo everything" to "resetall", "back to original" to "resetall", "start again" to "resetall",
        "fade in and out" to "fadein fadeout", "fade in and fade out" to "fadein fadeout", "fade in & out" to "fadein fadeout",
        "fade in" to "fadein", "fade-in" to "fadein", "fade from black" to "fadein", "fade out" to "fadeout", "fade-out" to "fadeout", "fade to black" to "fadeout",
        "dip to black" to "diptoblack", "dip to white" to "diptowhite", "fade to white" to "diptowhite", "cross fade" to "crossfade", "cross-fade" to "crossfade", "cross dissolve" to "dissolve",
        "wipe left" to "wipeleft", "wipe right" to "wiperight", "slide left" to "slideleft", "slide up" to "slideup", "circle open" to "circleopen",
        "no transitions" to "notransition", "no transition" to "notransition", "remove all transitions" to "notransition", "remove the transition" to "notransition", "remove transition" to "notransition",
        "ken burns" to "kenburns", "push in" to "pushin", "pull out" to "pullout", "pop in" to "popin", "pop-in" to "popin", "bounce in" to "popin",
        "no animation" to "noanim", "remove animation" to "noanim", "remove the animation" to "noanim", "clear animation" to "noanim", "remove all animation" to "noanim",
        "full hd" to "fullhd", "highest quality" to "highquality", "high quality" to "highquality", "best quality" to "highquality", "low quality" to "lowquality",
        "smaller file" to "smallfile", "small file" to "smallfile", "good quality" to "goodquality",
        "turn on" to "enable", "switch on" to "enable", "turn off" to "disable", "switch off" to "disable", "voice over" to "voiceover",
        "speed up" to "speedup", "sped up" to "speedup", "slow down" to "slowdown",
        "turn up" to "increase", "turn down" to "decrease", "counter clockwise" to "counterclockwise", "counter-clockwise" to "counterclockwise"
    )

    private val splitRe = Regex("\\s*(?:[,;!?\\n]|(?<!\\d)\\.(?!\\d)|\\band\\b|\\bthen\\b|\\balso\\b|\\bplus\\b|\\bwith\\b|\\bbut\\b|\\bwhile\\b|\\+)\\s*")
    private val tokenRe = Regex("[a-z0-9%.']+")
    private val xRe = Regex("^(\\d+(?:\\.\\d+)?)x$")
    private val ratioRe = Regex("(\\d{1,2})\\s*:\\s*(\\d{1,2})")
    private val timeRe = Regex("(\\d+(?:\\.\\d+)?)\\s*(seconds|second|secs|sec|minutes|minute|mins|min|ms|s|m)\\b")
    private val resRe = Regex("\\b(480|720|1080)\\s*p\\b")
    private val fpsRe = Regex("\\b(\\d{2,3})\\s*fps\\b")
    private val quoteOnlyRe = Regex("[\"\\u201C]([^\"\\u201D]+)[\"\\u201D]")
    private val sepRe = Regex("[,;!?]|(?<!\\d)\\.(?!\\d)|\\s(?:and|then|also|plus)\\s", RegexOption.IGNORE_CASE)
    private val textVerbRe = Regex("\\b(?:add|write|put|insert|type|text|title|caption|label|say|says|saying|message)\\b", RegexOption.IGNORE_CASE)
    private val plainTextRe = Regex("(?:add|write|put|insert)\\s+(?:a\\s+)?(?:text|title|label|message)\\s*(?:saying|that says|reading|:)\\s*(.+)$", RegexOption.IGNORE_CASE)

    init {
        prop("brightness", 1f, "bright", "brighter", "brighten", "brightness", "lighter", "lighten", "luminous")
        prop("brightness", -1f, "dark", "darker", "darken", "dim", "dimmer", "dimmed", "shadowy")
        prop("contrast", 1f, "contrast", "contrasty", "punchy", "punch", "punchier", "bolder")
        prop("contrast", -1f, "flat", "flatter")
        prop("saturation", 1f, "saturation", "saturated", "vivid", "vibrant", "colorful", "colourful", "richer", "color", "colour", "colors", "colours")
        prop("saturation", -1f, "desaturate", "desaturated", "muted", "dull", "pale", "pastel")
        prop("warmth", 1f, "warm", "warmer", "warmth", "golden", "cozy", "cosy", "orange")
        prop("warmth", -1f, "cool", "cooler", "cold", "colder", "bluish", "bluer")
        prop("tint", 1f, "magenta", "pink", "pinker", "pinkish", "purplish", "tint")
        prop("tint", -1f, "green", "greener", "greenish")
        prop("hue", 1f, "hue")
        prop("exposure", 1f, "exposure", "overexposed", "overexpose", "blown")
        prop("exposure", -1f, "underexposed", "underexpose")
        prop("fade", 1f, "fade", "faded", "fading", "washedout")
        prop("vignette", 1f, "vignette", "vignetting", "vignetted", "spotlight")

        fx(1, "Blur", "blur", "blurry", "blurred", "blurring", "soften")
        fx(2, "Sharpen", "sharpen", "sharp", "sharper", "sharpness", "crisp", "crisper", "clearer", "clarity", "detail", "detailed")
        fx(8, "Grain", "grain", "grainy", "noisy")
        fx(16, "Denoise", "denoise", "denoised", "cleanup")
        fx(32, "Pixelate", "pixelate", "pixelated", "pixel", "mosaic", "8bit")
        fx(64, "Cinema bars", "letterbox", "letterboxed", "bars")

        preset(listOf("monochrome", "greyscale", "grayscale", "bw"), listOf(act("filter", 1f, "Black & white")))
        preset(listOf("sepia"), listOf(act("filter", 2f, "Sepia")))
        preset(listOf("noir"), listOf(act("filter", 7f, "Noir")))
        preset(listOf("vintage", "retro", "antique", "nostalgic", "oldschool", "old"),
            listOf(act("filter", 8f, "Vintage look"), act("fade", 1f, "Faded", true), act("vignette", 1.5f, "Vignette", true), act("fx", 8f, "Grain", true)))
        preset(listOf("cinematic", "cinema", "filmic", "movie", "hollywood", "blockbuster", "film"),
            listOf(act("filter", 9f, "Cinematic look"), act("contrast", 1f, "More contrast", true), act("vignette", 1.5f, "Vignette", true)))
        preset(listOf("sunset", "dusk", "sunrise"), listOf(act("filter", 11f, "Sunset look")))
        preset(listOf("forest", "nature", "jungle"), listOf(act("filter", 12f, "Forest look")))
        preset(listOf("ocean", "sea", "underwater", "beach", "aqua"), listOf(act("filter", 13f, "Ocean look")))
        preset(listOf("matte"), listOf(act("filter", 14f, "Matte look")))
        preset(listOf("dramatic", "drama"), listOf(act("filter", 15f, "Dramatic look"), act("contrast", 1f, "More contrast", true), act("vignette", 1f, "Vignette", true)))
        preset(listOf("pop", "poppy"), listOf(act("saturation", 1f, "More colour"), act("contrast", 1f, "More contrast")))
        preset(listOf("tealorange"), listOf(act("filter", 16f, "Teal and orange look")))
        preset(listOf("monoblue", "bluemono"), listOf(act("filter", 17f, "Blue monochrome")))
        preset(listOf("rose", "rosy", "romantic"), listOf(act("filter", 18f, "Rose look")))
        preset(listOf("goldenhour", "sunny", "summer", "sunlit"),
            listOf(act("filter", 19f, "Golden look"), act("brightness", 1.5f, "Brighter", true), act("saturation", 1f, "More colour", true)))
        preset(listOf("polaroid", "kodak", "fadedfilm", "filmstock"), listOf(act("filter", 20f, "Faded film look")))
        preset(listOf("highkey", "airy"), listOf(act("filter", 21f, "High key look")))
        preset(listOf("lowkey", "moody"),
            listOf(act("filter", 22f, "Moody look"), act("vignette", 2f, "Vignette", true), act("saturation", -1f, "Less colour", true)))
        preset(listOf("duotone", "violet", "purple"), listOf(act("filter", 23f, "Purple duotone")))
        preset(listOf("duotoneteal"), listOf(act("filter", 24f, "Teal duotone")))
        preset(listOf("duotonesunset"), listOf(act("filter", 25f, "Sunset duotone")))
        preset(listOf("nofilter"), listOf(act("filter", 0f, "No filter")))
        preset(listOf("dreamy", "ethereal", "glowy", "glow", "hazy", "haze"),
            listOf(act("fade", 2f, "Soft fade"), act("brightness", 1.5f, "Brighter", true), act("saturation", -0.6f, "Softer colour", true)))
        preset(listOf("winter", "icy", "frosty", "chilly"),
            listOf(act("warmth", -2f, "Cooler"), act("brightness", 1f, "Brighter", true)))
        preset(listOf("autumn", "fall"),
            listOf(act("warmth", 2f, "Warmer"), act("saturation", 0.8f, "More colour", true), act("vignette", 1f, "Vignette", true)))
        preset(listOf("night", "nighttime"),
            listOf(act("brightness", -2f, "Darker"), act("warmth", -1f, "Cooler", true), act("contrast", 1f, "More contrast", true)))
        preset(listOf("cyberpunk", "neon", "futuristic", "synthwave", "vaporwave"),
            listOf(act("saturation", 3f, "Neon colour"), act("contrast", 2f, "More contrast"), act("tint", 2f, "Magenta tint", true), act("warmth", -1.2f, "Cooler", true), act("vignette", 1f, "Vignette", true)))
        preset(listOf("horror", "scary", "creepy", "eerie"),
            listOf(act("saturation", -2f, "Less colour"), act("brightness", -2f, "Darker"), act("contrast", 2f, "More contrast"), act("vignette", 2.5f, "Vignette", true)))
        preset(listOf("hdr"), listOf(act("contrast", 2f, "More contrast"), act("saturation", 1.5f, "More colour")))
        preset(listOf("professional", "polished", "enhance", "enhanced", "enhancement", "improve", "improved", "fix", "better", "perfect", "optimize", "optimise", "autoenhance", "auto", "retouch", "touchup"),
            listOf(act("auto", 1f, "Auto enhance")))
        preset(listOf("invert", "inverted", "negative", "inverse"), listOf(act("invert", 1f, "Invert colours")))
    }

    private fun labelFor(key: String, sign: Float): String = when (key) {
        "brightness" -> if (sign > 0f) "Brighter" else "Darker"
        "contrast" -> if (sign > 0f) "More contrast" else "Less contrast"
        "saturation" -> if (sign > 0f) "More colour" else "Less colour"
        "warmth" -> if (sign > 0f) "Warmer" else "Cooler"
        "tint" -> if (sign > 0f) "More magenta" else "More green"
        "hue" -> "Shift colours"
        "exposure" -> if (sign > 0f) "More exposure" else "Less exposure"
        "fade" -> if (sign > 0f) "More fade" else "Less fade"
        "vignette" -> if (sign > 0f) "More vignette" else "Less vignette"
        else -> key
    }

    private val relativeKeys = setOf("brightness", "contrast", "saturation", "warmth", "tint", "hue", "exposure", "fade", "vignette")

    private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private val vocab: Set<String> by lazy {
        val v = HashSet<String>()
        v.addAll(props.keys)
        v.addAll(presets.keys)
        v.addAll(fxWords.keys)
        v.addAll(commandWords)
        v
    }

    private fun isKnown(w: String): Boolean =
        w in vocab || w in fillers || w in weak || w in strong || w in negWords || w in removeWords || w in resetWords || w in flipWords ||
            w in rotateWords || w in muteWords || w in colorNames || w in colourWords || w in ordinals || w in clipWords || w in stickerWords

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

    /** "brigther" becomes "brighter": a long word that is not known, one or two letters from exactly one known word. */
    private fun fixTypo(w: String): String {
        if (w.length < 5 || isKnown(w) || w.any { !it.isLetter() }) return w
        val limit = if (w.length >= 8) 2 else 1
        var best: String? = null
        var bestD = 99
        var tie = false
        for (c in vocab) {
            if (c[0] != w[0] || abs(c.length - w.length) > limit) continue
            val d = lev(w, c)
            if (d > limit) continue
            if (d < bestD) {
                bestD = d
                best = c
                tie = false
            } else if (d == bestD && best != c) {
                tie = true
            }
        }
        val pick = best
        return if (pick != null && !tie) pick else w
    }

    private fun secondsOf(m: MatchResult): Float {
        val n = m.groupValues[1].toFloatOrNull() ?: return 0f
        return when (m.groupValues[2]) {
            "ms" -> n / 1000f
            "m", "min", "mins", "minute", "minutes" -> n * 60f
            else -> n
        }
    }

    /** Position, size, colour and timing words that go with a piece of text ("at the top in red for 3 seconds"). */
    private fun styleFrom(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        val low = text.lowercase()
        val toks = tokenRe.findAll(low).map { it.value.trim('.', '\'') }.filter { it.isNotEmpty() }.toList()
        for (w in toks) {
            when (w) {
                "top" -> out["y"] = "0.1"
                "bottom" -> out["y"] = "0.88"
                "middle", "center", "centre" -> out["y"] = "0.5"
                "big", "large", "huge", "bigger", "giant" -> out["size"] = "0.12"
                "small", "tiny", "little", "smaller" -> out["size"] = "0.045"
                "start", "beginning" -> out["start"] = "0"
                "end" -> out["start"] = "-1"
            }
            if (w in colorNames && !out.containsKey("color")) out["color"] = w
        }
        for (m in timeRe.findAll(low)) {
            val before = low.substring(0, m.range.first).trimEnd().substringAfterLast(' ')
            val v = secondsOf(m)
            if (before == "for" || before == "lasting") out["dur"] = v.toString() else if (before == "at" || before == "from" || before == "after" || before == "starting") out["start"] = v.toString()
        }
        return out
    }

    fun parse(input: String): PromptResult {
        val actions = ArrayList<PromptAction>()
        val unknown = ArrayList<String>()
        var raw = input.trim()
        if (raw.isEmpty()) return PromptResult(actions, unknown)

        // Text to put on the picture or video: in quotes, with its position, size, colour and timing words.
        var handled = false
        val q = quoteOnlyRe.find(raw)
        if (q != null) {
            val head = raw.substring(0, q.range.first)
            val sepM = sepRe.findAll(head).lastOrNull()
            val segStart = if (sepM != null) sepM.range.last + 1 else 0
            val seg = head.substring(segStart)
            if (textVerbRe.containsMatchIn(seg)) {
                val tail = raw.substring(q.range.last + 1)
                val tailEnd = sepRe.find(tail)?.range?.first ?: tail.length
                val t = q.groupValues[1].trim()
                if (t.isNotEmpty()) {
                    val style = styleFrom(seg + " " + tail.substring(0, tailEnd))
                    actions.add(PromptAction("text", 0f, "Add text \u201C" + t + "\u201D", t, false, style))
                }
                raw = raw.substring(0, segStart) + " " + raw.substring(q.range.last + 1 + tailEnd)
                handled = true
            }
        }
        if (!handled) {
            val plain = plainTextRe.find(raw)
            if (plain != null) {
                val t = plain.groupValues[1].trim().trimEnd('.', '!', '?')
                if (t.isNotEmpty()) actions.add(PromptAction("text", 0f, "Add text \u201C" + t + "\u201D", t))
                raw = raw.substring(0, plain.range.first) + " " + raw.substring(plain.range.last + 1)
            }
        }

        var s = raw.lowercase()
        for ((a, b) in phrases) s = s.replace(a, " $b ")
        for ((w, d) in numberWords) s = Regex("\\b$w\\b").replace(s, d)

        for (clause in splitRe.split(s).map { it.trim() }.filter { it.isNotEmpty() }) {
            val toks = tokenRe.findAll(clause).map { it.value.trim('.', '\'') }.filter { it.isNotEmpty() }.map { fixTypo(it) }.toList()
            val before = actions.size
            parseClause(toks, clause, actions)
            if (actions.size == before && toks.any { it !in fillers }) unknown.add(clause)
        }
        // Two looks in one sentence: the first one is the filter, the second keeps only its extra touches.
        val fixed = ArrayList<PromptAction>()
        var haveLook = false
        for (a in actions) {
            if ((a.key == "filter" && a.value > 0f) || a.key == "invert") {
                if (haveLook) continue
                haveLook = true
            }
            fixed.add(a)
        }
        // The extra touches of a look give way to anything the person asked for directly.
        val direct = HashSet<String>()
        for (a in fixed) if (!a.soft && a.key in relativeKeys) direct.add(a.key)
        val merged = ArrayList<PromptAction>()
        for (a in fixed) {
            if (a.soft && a.key in relativeKeys) {
                if (a.key in direct) continue
                val at = merged.indexOfFirst { it.soft && it.key == a.key }
                if (at >= 0) {
                    if (abs(a.value) > abs(merged[at].value)) merged[at] = a
                    continue
                }
            }
            merged.add(a)
        }
        return PromptResult(merged, unknown)
    }

    private fun parseClause(t: List<String>, clause: String, out: MutableList<PromptAction>) {
        var strength = 1f
        for (w in t) {
            if (w in weak) strength = 0.5f
            if (w in strong) strength = 1.7f
        }
        for (w in t) {
            if (w.endsWith("%")) {
                val n = w.dropLast(1).toFloatOrNull()
                if (n != null) strength = (n / 30f).coerceIn(0.2f, 3f)
            }
        }
        val remove = t.any { it in removeWords }
        val neg = remove || t.any { it in negWords }
        if (remove) strength = 3f
        val flip = if (neg) -1f else 1f

        if (t.any { it in resetWords }) {
            out.add(act("reset", 0f, "Reset adjustments"))
            return
        }

        val timeM = timeRe.find(clause)
        val sec: Float? = if (timeM != null) secondsOf(timeM) else null
        val dynZoom = (t.contains("zoom") || t.contains("kenburns") || t.contains("pushin") || t.contains("pullout")) &&
            t.any { it == "slow" || it == "slowly" || it == "gradual" || it == "gradually" || it == "animate" || it == "animated" || it == "kenburns" || it == "pushin" || it == "pullout" || it == "smooth" || it == "smoothly" }
        val animCtx = dynZoom || t.contains("fadein") || t.contains("fadeout") || t.contains("popin") || t.contains("transition") || t.contains("dissolve") ||
            t.contains("crossfade") || t.contains("fade") || t.contains("notransition")

        // ----- which clips -----
        val everything = t.contains("everything") ||
            ((t.contains("all") || t.contains("every") || t.contains("each") || t.contains("whole") || t.contains("entire")) &&
                t.any { it in clipWords || it == "clips" || it == "scenes" || it == "shots" || it == "video" || it == "project" })
        if (everything) out.add(act("scope", 1f, "All clips"))
        if (!everything) {
            for (k in t.indices) {
                if (t[k] !in clipWords) continue
                val prev = if (k > 0) t[k - 1] else ""
                val next = if (k + 1 < t.size) t[k + 1] else ""
                val idx: Int? = ordinals[prev] ?: next.toIntOrNull()?.let { it - 1 }
                if (idx != null) {
                    val name = when {
                        idx >= 0 -> "Select clip " + (idx + 1)
                        idx == -1 -> "Select the last clip"
                        idx == -2 -> "Select the next clip"
                        else -> "Select the previous clip"
                    }
                    out.add(act("select", idx.toFloat(), name))
                    break
                }
            }
        }

        // ----- cutting the timeline -----
        val hasClipWord = t.any { it in clipWords }
        val hasSplit = t.contains("split") || t.contains("divide") ||
            (t.contains("cut") && (t.contains("here") || t.contains("playhead") || (t.contains("at") && sec != null && !t.contains("first") && !t.contains("last"))))
        if (hasSplit) out.add(act("split", sec ?: -1f, if (sec != null) "Split at " + fmt(sec) + "s" else "Split at the playhead"))
        val startWord = t.contains("first") || t.contains("beginning") || t.contains("start") || t.contains("opening")
        val endWord = t.contains("last") || t.contains("end") || t.contains("ending")
        val cutWord = t.contains("trim") || t.contains("cut") || t.contains("remove") || t.contains("delete") || t.contains("skip") || t.contains("drop")
        val keepWord = t.contains("keep") || t.contains("shorten") || t.contains("limit") || t.contains("long") || t.contains("length") || t.contains("duration")
        if (sec != null && !hasSplit && sec > 0f) {
            if (keepWord && endWord && !startWord) {
                out.add(act("keeplast", sec, "Keep the last " + fmt(sec) + "s"))
            } else if (keepWord) {
                out.add(act("keep", sec, "Keep the first " + fmt(sec) + "s"))
            } else if (cutWord && startWord && !endWord) {
                out.add(act("trimstart", sec, "Cut the first " + fmt(sec) + "s"))
            } else if (cutWord && endWord && !startWord) {
                out.add(act("trimend", sec, "Cut the last " + fmt(sec) + "s"))
            } else if ((t.contains("trim") || t.contains("cut")) && t.contains("to") && !startWord && !endWord) {
                out.add(act("keep", sec, "Keep the first " + fmt(sec) + "s"))
            }
        }
        if ((t.contains("delete") || t.contains("remove") || t.contains("erase") || t.contains("drop") || t.contains("discard")) && sec == null && hasClipWord) {
            out.add(act("delete", 1f, "Delete clip"))
        }
        if (t.contains("duplicate") || t.contains("clone") || (t.contains("copy") && hasClipWord)) out.add(act("duplicate", 1f, "Duplicate clip"))
        if (t.contains("move") || t.contains("shift")) {
            val dir = when {
                t.any { it == "left" || it == "earlier" || it == "back" || it == "before" } -> -1f
                t.any { it == "right" || it == "later" || it == "forward" || it == "after" } -> 1f
                else -> 0f
            }
            if (dir != 0f && (hasClipWord || t.contains("it") || t.contains("this"))) out.add(act("move", dir, if (dir < 0f) "Move clip earlier" else "Move clip later"))
        }

        // ----- transitions and animations -----
        var tr: Int? = null
        for (w in t) {
            val v = when (w) {
                "notransition" -> 0
                "diptoblack" -> 1
                "diptowhite" -> 2
                "crossfade" -> 3
                "dissolve" -> 4
                "wipeleft" -> 5
                "wiperight" -> 6
                "slideleft" -> 7
                "slideup" -> 8
                "circleopen" -> 9
                "pixelize" -> 10
                "radial" -> 12
                else -> -1
            }
            if (v >= 0) { tr = v; break }
        }
        if (tr == null && (t.contains("transition") || t.contains("transitions"))) {
            tr = when {
                t.contains("pixelate") || t.contains("pixelated") -> 10
                t.contains("zoom") -> 11
                else -> 3
            }
        }
        if (tr == null && t.contains("fade") && t.contains("between")) tr = 3
        if (tr != null) {
            val nm = if (tr == 0) "No transition" else "Transition: " + transNames[tr]
            out.add(PromptAction("transition", tr.toFloat(), nm, "", false, if (sec != null && !hasSplit && tr > 0) mapOf("sec" to sec.toString()) else emptyMap()))
        }
        if (dynZoom) {
            val zoomOut = t.contains("out") || t.contains("pullout")
            out.add(PromptAction("anim", 0f, if (zoomOut) "Slow zoom out" else "Slow zoom in", if (zoomOut) "zoomout" else "zoomin"))
        }
        if (t.contains("fadein")) out.add(PromptAction("anim", 0f, "Fade in", "fadein"))
        if (t.contains("fadeout")) out.add(PromptAction("anim", 0f, "Fade out", "fadeout"))
        if (t.contains("popin")) out.add(PromptAction("anim", 0f, "Pop in", "pop"))
        if (t.contains("noanim")) out.add(PromptAction("anim", 0f, "Remove animation", "clear"))

        // ----- export, captions and voice -----
        val resM = resRe.find(clause)
        if (resM != null) {
            val g = resM.groupValues[1]
            out.add(act("res", if (g == "480") 0f else if (g == "720") 1f else 2f, g + "p"))
        }
        if (t.contains("fullhd")) out.add(act("res", 2f, "1080p"))
        val fpsM = fpsRe.find(clause)
        if (fpsM != null) {
            val n = fpsM.groupValues[1].toIntOrNull()
            if (n != null) {
                val snap = if (n <= 27) 24 else if (n <= 45) 30 else 60
                out.add(act("fps", snap.toFloat(), "$snap fps"))
            }
        }
        if (t.contains("highquality")) out.add(act("quality", 16f, "High quality"))
        if (t.contains("goodquality")) out.add(act("quality", 20f, "Good quality"))
        if (t.contains("lowquality") || t.contains("smallfile")) out.add(act("quality", 26f, "Small file"))
        if (t.contains("export") && resM == null && fpsM == null && !t.contains("fullhd") && !t.contains("highquality") && !t.contains("goodquality") && !t.contains("lowquality") && !t.contains("smallfile")) {
            out.add(act("note", 0f, "Open the Export tab and press Export to save the video."))
        }
        val capWord = t.contains("captions") || t.contains("caption") || t.contains("subtitles") || t.contains("subtitle")
        if (capWord) {
            if (neg) out.add(act("captions", 0f, "Hide captions"))
            else if (t.any { it == "enable" || it == "show" || it == "burn" || it == "burned" || it == "include" || it == "display" || it == "on" }) out.add(act("captions", 1f, "Show captions"))
            else out.add(act("note", 0f, "Use the Captions tab to create subtitles."))
        }
        val voiceCtx = t.contains("voice") || t.contains("voiceover") || t.contains("audio") || t.contains("sound")
        var voiceIdx = -1
        if (voiceCtx) {
            voiceIdx = when {
                t.contains("robot") -> 3
                t.contains("chipmunk") || t.contains("squeaky") -> 4
                t.contains("deep") -> 5
                t.contains("telephone") || t.contains("phone") -> 2
                t.contains("clean") || t.contains("clear") -> 1
                t.contains("normal") || t.contains("original") -> 0
                else -> -1
            }
            if (voiceIdx >= 0) out.add(act("voicefx", voiceIdx.toFloat(), "Voice: " + voiceFxNames[voiceIdx]))
        }
        if (t.contains("cutout") || t.contains("background")) out.add(act("note", 0f, "Use the Cutout tab to remove or change a background."))
        if (t.contains("music") || (t.contains("voiceover") && voiceIdx < 0)) out.add(act("note", 0f, "Use the Audio section to add music or a voice-over."))

        // ----- speed -----
        var speed: Float? = t.firstNotNullOfOrNull { xRe.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
        if (speed == null) {
            if (t.contains("timelapse") || t.contains("hyperlapse")) {
                speed = 4f
            } else if (t.contains("slowmo") || t.contains("slowdown") || (!animCtx && (t.contains("slow") || t.contains("slower") || t.contains("slowly")))) {
                speed = if (strength > 1.2f) 0.25f else 0.5f
            } else if (t.contains("speedup") || (!animCtx && (t.contains("fast") || t.contains("faster") || t.contains("quick") || t.contains("quicker"))) || t.contains("accelerate")) {
                speed = if (strength > 1.2f) 3f else 2f
            } else if (t.contains("speed")) {
                speed = when {
                    t.contains("half") || t.contains("down") -> 0.5f
                    t.contains("triple") -> 3f
                    t.contains("double") || t.contains("twice") || t.contains("up") -> 2f
                    t.contains("normal") || t.contains("regular") -> 1f
                    else -> null
                }
            }
        }
        if (speed != null) {
            if (sec != null && sec > 0f && (startWord || endWord) && !hasSplit) {
                val fromEnd = endWord && !startWord
                out.add(
                    PromptAction(
                        "speedpart", speed, "Speed " + fmt(speed) + "x for the " + (if (fromEnd) "last " else "first ") + fmt(sec) + "s", "", false,
                        mapOf("from" to (if (fromEnd) "last" else "first"), "sec" to sec.toString())
                    )
                )
            } else {
                out.add(act("speed", speed, "Speed " + fmt(speed) + "x"))
            }
        }

        // ----- turning and flipping -----
        if (t.any { it in rotateWords }) {
            var deg = 90f
            if (t.any { it == "left" || it == "anticlockwise" || it == "counterclockwise" || it == "counter" || it == "anti" }) deg = -90f
            if (t.contains("180") || t.contains("upsidedown")) deg = 180f
            if (t.contains("270")) deg = -90f
            out.add(act("rotate", deg, "Rotate " + deg.toInt() + "\u00B0"))
        }
        if (t.any { it in flipWords }) {
            val vertical = t.any { it == "upsidedown" || it == "vertical" || it == "vertically" || it == "upside" }
            if (vertical) out.add(act("flipv", 1f, "Flip upside down")) else out.add(act("flip", 1f, "Flip sideways"))
        }

        // ----- shape of the picture -----
        val explicit = ratioRe.find(clause)
        if (explicit != null) {
            val a = explicit.groupValues[1].toFloatOrNull()
            val b = explicit.groupValues[2].toFloatOrNull()
            if (a != null && b != null && a > 0f && b > 0f) out.add(act("ratio", a / b, "Crop to " + explicit.groupValues[1] + ":" + explicit.groupValues[2]))
        } else {
            for (w in t) {
                when (w) {
                    "square" -> out.add(act("ratio", 1f, "Crop to 1:1"))
                    "portrait", "instagram" -> out.add(act("ratio", 0.8f, "Crop to 4:5"))
                    "landscape" -> out.add(act("ratio", 1.5f, "Crop to 3:2"))
                    "widescreen", "youtube", "thumbnail" -> out.add(act("ratio", 16f / 9f, "Crop to 16:9"))
                    "ultrawide" -> out.add(act("ratio", 21f / 9f, "Crop to 21:9"))
                    "story", "stories", "reel", "reels", "tiktok", "shorts" -> out.add(act("ratio", 9f / 16f, "Crop to 9:16"))
                    "vertical" -> if (t.none { it in flipWords }) out.add(act("ratio", 9f / 16f, "Crop to 9:16"))
                }
            }
        }

        // ----- zoom, sound, frame and stickers -----
        if (!dynZoom && (t.contains("zoom") || t.contains("zoomed") || t.contains("closer"))) {
            val zoomOut = t.contains("out") || t.contains("wider") || neg
            val v = (if (zoomOut) -1f else 1f) * strength
            out.add(act("zoom", v, if (zoomOut) "Zoom out" else "Zoom in"))
        }
        if (t.any { it in muteWords }) {
            out.add(act("mute", 1f, "Mute"))
        } else if (t.contains("louder") || t.contains("volume") || t.contains("quieter") || t.contains("loud") || t.contains("quiet")) {
            val down = t.contains("quieter") || t.contains("quiet") || neg
            out.add(act("gain", (if (down) -1f else 1f) * strength, if (down) "Quieter" else "Louder"))
        }
        val frame = t.any { it == "border" || it == "frame" || it == "outline" }
        if (frame) {
            val color = t.firstOrNull { it in colorNames } ?: ""
            out.add(PromptAction("frame", (if (neg) -1f else 1f) * strength, if (neg) "Remove border" else "Add border", color))
        }
        if (t.contains("sticker") || t.contains("emoji")) {
            val e = t.firstNotNullOfOrNull { stickerWords[it] }
            if (e != null) out.add(PromptAction("sticker", 0f, "Add sticker $e", e, false, mapOf("size" to "0.16")))
        }

        // ----- looks, effects and adjustments, word by word -----
        val used = HashSet<String>()
        for (w in t) {
            if (frame && w in colorNames) continue
            val pre = presets[w]
            if (pre != null) {
                if (neg && w != "nofilter") continue
                if (used.add("p:" + pre[0].label)) {
                    for (a in pre) {
                        if (a.key in relativeKeys || a.key == "fx") {
                            val v = if (a.key == "fx") a.value else a.value * strength
                            out.add(PromptAction(a.key, v, a.label, a.text, a.soft))
                        } else {
                            out.add(a)
                        }
                    }
                }
                continue
            }
            if (w in colourWords && t.any { presets[it]?.get(0)?.key == "auto" }) continue
            val f = fxWords[w]
            if (f != null) {
                if (used.add("f:" + f.first)) {
                    if (neg) out.add(PromptAction("fxoff", f.first.toFloat(), "Remove " + f.second.lowercase())) else out.add(PromptAction("fx", f.first.toFloat(), f.second))
                }
                continue
            }
            val pr = props[w]
            if (pr != null) {
                val sgn = pr.sign * flip
                if (used.add(pr.key + sgn)) out.add(act(pr.key, sgn * strength, labelFor(pr.key, sgn)))
            }
        }
    }
}

/** Puts what a prompt asked for onto a photo or a video clip. Things that do not fit are reported in [notes]. */
object PromptApply {
    private fun order(acts: List<PromptAction>): List<PromptAction> =
        acts.sortedBy { if (it.key == "reset") 0 else if (it.key == "auto") 1 else 2 }

    fun colorOf(name: String): Color? = when (name) {
        "white" -> Color.White
        "black" -> Color.Black
        "red" -> Color.Red
        "blue" -> Color.Blue
        "yellow" -> Color.Yellow
        "green" -> Color.Green
        "orange" -> Color(0xFFFF9800)
        "pink" -> Color(0xFFFF69B4)
        "purple" -> Color(0xFF9C27B0)
        "gray", "grey" -> Color.Gray
        else -> null
    }

    fun photo(s: EditState, acts: List<PromptAction>, enhance: FloatArray?, notes: MutableList<String>): EditState {
        var st = s
        val said = HashSet<String>()
        fun note(msg: String) {
            if (said.add(msg)) notes.add(msg)
        }
        for (a in order(acts)) {
            when (a.key) {
                "reset" -> st = st.copy(
                    brightness = 0f, contrast = 1f, saturation = 1f, warmth = 0f, vignette = 0f,
                    exposure = 0f, tint = 0f, hue = 0f, fade = 0f, filter = 0
                )
                "auto" -> if (enhance != null) st = st.copy(brightness = enhance[0], contrast = enhance[1], saturation = enhance[2]) else note("Auto enhance needs a photo.")
                "brightness" -> st = st.copy(brightness = (st.brightness + a.value * 0.12f).coerceIn(-0.4f, 0.4f))
                "contrast" -> st = st.copy(contrast = (st.contrast + a.value * 0.15f).coerceIn(0.5f, 1.5f))
                "saturation" -> st = st.copy(saturation = (st.saturation + a.value * 0.3f).coerceIn(0f, 2f))
                "warmth" -> st = st.copy(warmth = (st.warmth + a.value * 0.3f).coerceIn(-1f, 1f))
                "tint" -> st = st.copy(tint = (st.tint + a.value * 0.3f).coerceIn(-1f, 1f))
                "hue" -> st = st.copy(hue = (st.hue + a.value * 40f).coerceIn(-180f, 180f))
                "exposure" -> st = st.copy(exposure = (st.exposure + a.value * 0.3f).coerceIn(-1f, 1f))
                "fade" -> st = st.copy(fade = (st.fade + a.value * 0.25f).coerceIn(0f, 1f))
                "vignette" -> st = st.copy(vignette = (st.vignette + a.value * 0.3f).coerceIn(0f, 1f))
                "filter" -> st = st.copy(filter = a.value.toInt().coerceIn(0, photoFilterNames.size - 1))
                "invert" -> st = st.copy(filter = 26)
                "flip" -> st = st.copy(flip = !st.flip)
                "flipv" -> st = st.copy(flipV = !st.flipV)
                "rotate" -> st = st.copy(rotation = ((st.rotation + a.value.toInt()) % 360 + 360) % 360)
                "ratio" -> st = st.copy(ratio = a.value)
                "frame" -> {
                    val c = colorOf(a.text)
                    val b = (st.border + a.value * 0.03f).coerceIn(0f, 0.15f)
                    st = if (c != null) st.copy(border = b, borderColor = c) else st.copy(border = b)
                }
                "note" -> note(a.label)
                "speed", "gain", "mute", "zoom" -> if (!a.soft) note("Speed, volume and zoom work on video clips.")
                "trimstart", "trimend", "keep", "keeplast", "speedpart", "transition", "anim", "split", "delete", "duplicate", "move", "res", "fps", "quality", "captions", "voicefx" ->
                    if (!a.soft) note("Trimming, splitting, transitions, animations, voice and export settings work in the Video section.")
                "fx", "fxoff" -> if (!a.soft) note(a.label + " works on video clips. Photos do not have that effect yet.")
                else -> {}
            }
        }
        return st
    }

    private val limitedFilter = 22

    fun clip(c: Clip, acts: List<PromptAction>, full: Boolean, notes: MutableList<String>, first: Boolean = false): Clip {
        var cl = c
        val said = HashSet<String>()
        fun note(msg: String) {
            if (said.add(msg)) notes.add(msg)
        }
        for (a in order(acts)) {
            when (a.key) {
                "reset" -> cl = cl.copy(
                    filter = 0, brightness = 0f, contrast = 1f, saturation = 1f, fx = 0, mirror = false, flipV = false,
                    cropX = 0f, cropY = 0f, speed = 1f, gain = 1f
                )
                "auto" -> cl = cl.copy(
                    brightness = (cl.brightness + 0.05f).coerceIn(-0.4f, 0.4f),
                    contrast = (cl.contrast + 0.08f).coerceIn(0.5f, 1.5f),
                    saturation = (cl.saturation + 0.12f).coerceIn(0f, 2f)
                )
                "brightness" -> cl = cl.copy(brightness = (cl.brightness + a.value * 0.12f).coerceIn(-0.4f, 0.4f))
                "contrast" -> cl = cl.copy(contrast = (cl.contrast + a.value * 0.15f).coerceIn(0.5f, 1.5f))
                "saturation" -> cl = cl.copy(saturation = (cl.saturation + a.value * 0.3f).coerceIn(0f, 2f))
                "exposure" -> cl = cl.copy(brightness = (cl.brightness + a.value * 0.1f).coerceIn(-0.4f, 0.4f))
                "fade" -> cl = cl.copy(
                    contrast = (cl.contrast - a.value * 0.1f).coerceIn(0.5f, 1.5f),
                    brightness = (cl.brightness + a.value * 0.04f).coerceIn(-0.4f, 0.4f)
                )
                "warmth" -> {
                    if (cl.filter == 0 || cl.filter == 3 || cl.filter == 4) {
                        cl = cl.copy(filter = if (a.value > 0f) 3 else 4)
                        if (!a.soft) note("Warmth and coolness are applied to video with the Warm and Cool filters.")
                    } else if (!a.soft) {
                        note("This clip already has a filter, so warmth was not changed.")
                    }
                }
                "tint", "hue" -> if (!a.soft) note("Tint and hue shifts work on photos only.")
                "vignette" -> {
                    if (full) cl = cl.copy(fx = if (a.value > 0f) cl.fx or 4 else cl.fx and 4.inv())
                    else if (!a.soft) note("Vignette on video needs the full video engine.")
                }
                "filter" -> {
                    val idx = a.value.toInt()
                    if (idx <= limitedFilter) cl = cl.copy(filter = idx) else if (!a.soft) note("That filter is for photos only.")
                }
                "invert" -> if (full) cl = cl.copy(fx = cl.fx or 128) else note("Invert on video needs the full video engine.")
                "flip" -> cl = cl.copy(mirror = !cl.mirror)
                "flipv" -> cl = cl.copy(flipV = !cl.flipV)
                "speed" -> cl = cl.copy(speed = a.value.coerceIn(0.25f, 4f))
                "zoom" -> cl = cl.copy(
                    cropX = (cl.cropX + a.value * 0.08f).coerceIn(0f, 0.4f),
                    cropY = (cl.cropY + a.value * 0.08f).coerceIn(0f, 0.4f)
                )
                "mute" -> if (full) cl = cl.copy(gain = 0f) else note("Clip volume needs the full video engine.")
                "gain" -> if (full) cl = cl.copy(gain = (cl.gain + a.value * 0.3f).coerceIn(0f, 2f)) else note("Clip volume needs the full video engine.")
                "fx" -> {
                    if (full) cl = cl.copy(fx = cl.fx or a.value.toInt()) else if (!a.soft) note(a.label + " needs the full video engine.")
                }
                "fxoff" -> {
                    if (full) cl = cl.copy(fx = cl.fx and a.value.toInt().inv()) else note("Effects need the full video engine.")
                }
                "rotate", "ratio", "frame" -> if (!a.soft) note("Rotating and borders work on photos. The shape of a video is chosen in the Export tab.")
                "sticker" -> note("Stickers work on photos.")
                "trimstart" -> {
                    val d = (a.value * 1000f * cl.speed).toLong()
                    cl = cl.copy(inMs = maxOf(cl.inMs, minOf(cl.inMs + d, cl.outMs - 200L)))
                }
                "trimend" -> {
                    val d = (a.value * 1000f * cl.speed).toLong()
                    cl = cl.copy(outMs = minOf(cl.outMs, maxOf(cl.outMs - d, cl.inMs + 200L)))
                }
                "keep" -> {
                    val d = (a.value * 1000f * cl.speed).toLong()
                    cl = cl.copy(outMs = minOf(cl.outMs, cl.inMs + maxOf(d, 200L)))
                }
                "keeplast" -> {
                    val d = (a.value * 1000f * cl.speed).toLong()
                    cl = cl.copy(inMs = maxOf(cl.inMs, cl.outMs - maxOf(d, 200L)))
                }
                "transition" -> {
                    val idx = a.value.toInt()
                    if (first) {
                        note("The first clip has no clip before it, so it has no transition.")
                    } else if (idx >= 3 && !full) {
                        note("That transition needs the full video engine.")
                    } else {
                        val ms = ((a.args["sec"]?.toFloatOrNull() ?: 0.6f) * 1000f).toLong().coerceIn(200L, 1500L)
                        cl = cl.copy(trans = idx, transMs = ms)
                    }
                }
                "note" -> note(a.label)
                else -> {}
            }
        }
        val animNames = acts.filter { it.key == "anim" }.map { it.text }
        if (animNames.isNotEmpty()) {
            if (!full) {
                note("Animations need the full video engine.")
            } else {
                val zoom = if ("zoomin" in animNames) 1 else if ("zoomout" in animNames) -1 else 0
                val keys = if ("clear" in animNames) emptyList() else ClipAnim.keys(clipDur(cl), zoom, "fadein" in animNames, "fadeout" in animNames, "pop" in animNames)
                if (keys.isEmpty() && "clear" !in animNames) note("The clip is too short to animate.")
                else cl = cl.copy(keys = keys)
            }
        }
        return cl
    }
}

/** Ready-made clip animations: slow zoom, fades and pop-in. Times are in the clip's own time, after speed. */
object ClipAnim {
    fun keys(durMs: Long, zoom: Int, fadeIn: Boolean, fadeOut: Boolean, pop: Boolean): List<Keyframe> {
        if (durMs < 600L || (zoom == 0 && !fadeIn && !fadeOut && !pop)) return emptyList()
        val d = durMs
        val fin = if (fadeIn) minOf(1000L, d / 3) else 0L
        val fout = if (fadeOut) minOf(1000L, d / 3) else 0L
        val popMs = if (pop) minOf(400L, d / 3) else 0L
        val raw = ArrayList<Long>()
        raw.add(0L)
        if (fin > 0L) raw.add(fin)
        if (popMs > 0L) raw.add(popMs)
        if (fout > 0L) raw.add(d - fout)
        raw.add(d)
        val times = ArrayList<Long>()
        for (t in raw.distinct().sorted()) {
            if (times.isEmpty() || t - times[times.size - 1] >= 50L) times.add(t)
        }
        val out = ArrayList<Keyframe>()
        for (t in times) {
            val p = t.toFloat() / d.toFloat()
            var scale = if (zoom > 0) 1f + 0.25f * p else if (zoom < 0) 1.25f - 0.25f * p else 1f
            var opacity = 1f
            var vol = 1f
            if (popMs > 0L && t < popMs) {
                val k = t.toFloat() / popMs.toFloat()
                scale *= 0.7f + 0.3f * k
                opacity = minOf(opacity, k)
            }
            if (fin > 0L && t < fin) {
                val k = t.toFloat() / fin.toFloat()
                opacity = minOf(opacity, k)
                vol = minOf(vol, k)
            }
            if (fout > 0L && t > d - fout) {
                val k = (d - t).toFloat() / fout.toFloat()
                opacity = minOf(opacity, k)
                vol = minOf(vol, k)
            }
            out.add(Keyframe(t, scale, 0f, 0f, 0f, opacity, vol))
        }
        return out
    }
}
