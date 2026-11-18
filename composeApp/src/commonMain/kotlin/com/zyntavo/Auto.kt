package com.zyntavo

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The auto editor. It reads a request for what the person wants ("make people notice it", "for Instagram",
 * "make it look professional"), looks at the actual picture or video, and decides the edits itself.
 * It is rules and arithmetic that run on the device. It is not a language model, so it knows a fixed set of
 * goals, moods and places, but it can read them from loose wording and spelling.
 */

/** What a picture looks like in numbers. All values are from 0 to 1 unless noted. */
class FrameStats(
    val luma: Float,        // average brightness
    val contrast: Float,    // spread of the brightness
    val sat: Float,         // average colour strength
    val cast: Float,        // blue minus red: above 0 is cool, below 0 is warm
    val dark: Float,        // share of nearly black pixels
    val bright: Float,      // share of nearly white pixels
    val sharp: Float        // how much fine detail there is
)

fun analyseFrame(img: ImageBitmap): FrameStats {
    val w = 160
    val h = max(8, (img.height * 160f / img.width).toInt())
    val px = scaledPixels(img, w, h)
    val l = FloatArray(w * h)
    var sum = 0.0
    var sumSq = 0.0
    var satSum = 0.0
    var rSum = 0.0
    var bSum = 0.0
    var dark = 0
    var bright = 0
    var n = 0
    for (i in px.indices) {
        val p = px[i]
        val r = ((p shr 16) and 255) / 255f
        val g = ((p shr 8) and 255) / 255f
        val b = (p and 255) / 255f
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        l[i] = y
        if (((p ushr 24) and 255) < 16) continue
        val mx = max(r, max(g, b))
        val mn = minOf(r, minOf(g, b))
        sum += y
        sumSq += y * y
        if (mx > 0f) satSum += (mx - mn) / mx
        rSum += r
        bSum += b
        if (y < 0.06f) dark++
        if (y > 0.94f) bright++
        n++
    }
    if (n < 50) return FrameStats(0.5f, 0.2f, 0.35f, 0f, 0f, 0f, 0.04f)
    val mean = sum / n
    val std = sqrt(max(0.0, sumSq / n - mean * mean))
    var lap = 0.0
    var cnt = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val c = y * w + x
        lap += abs(4f * l[c] - l[c - 1] - l[c + 1] - l[c - w] - l[c + w])
        cnt++
    }
    return FrameStats(
        mean.toFloat(), std.toFloat(), (satSum / n).toFloat(), ((bSum - rSum) / n).toFloat(),
        dark.toFloat() / n, bright.toFloat() / n, if (cnt > 0) (lap / cnt).toFloat() else 0.04f
    )
}

fun mergeStats(list: List<FrameStats>): FrameStats {
    val n = list.size.toFloat()
    return FrameStats(
        list.sumOf { it.luma.toDouble() }.toFloat() / n, list.sumOf { it.contrast.toDouble() }.toFloat() / n,
        list.sumOf { it.sat.toDouble() }.toFloat() / n, list.sumOf { it.cast.toDouble() }.toFloat() / n,
        list.sumOf { it.dark.toDouble() }.toFloat() / n, list.sumOf { it.bright.toDouble() }.toFloat() / n,
        list.sumOf { it.sharp.toDouble() }.toFloat() / n
    )
}

/** What the person is after, read from their words. */
class Goal(
    val label: String,
    val attention: Boolean = false,
    val polish: Boolean = false,
    val energetic: Boolean = false,
    val mood: String? = null,
    val platform: String? = null,
    val subject: String? = null,
    val talking: Boolean = false,
    val shaky: Boolean = false,
    val beat: Boolean = false
)

/** The edits chosen for one picture. [apply] is for photos; the video uses the same numbers on its clips. */
class Tune(
    val brightness: Float, val contrast: Float, val saturation: Float, val shadows: Float, val highlights: Float,
    val warmth: Float, val sharpen: Float, val vignette: Float, val fade: Float, val grain: Float,
    val photoFilter: Int?, val videoFilter: Int?, val ratio: Float?, val notes: List<String>
) {
    val fxOn: Int get() = (if (sharpen >= 0.3f) 2 else 0) or (if (vignette > 0.1f) 4 else 0) or (if (grain > 0.1f) 8 else 0)

    fun applyTo(s: EditState): EditState = s.copy(
        brightness = brightness, contrast = contrast, saturation = saturation, shadows = shadows, highlights = highlights,
        warmth = warmth, sharpen = sharpen, vignette = vignette, fade = fade, grain = grain,
        filter = photoFilter ?: s.filter, ratio = ratio ?: s.ratio
    )
}

object Auto {
    private fun has(t: String, vararg keys: String) = keys.any { t.contains(it) }

    fun general() = Goal("a better look", polish = true)

    /** Reads a goal from loose wording. Returns null if the words do not ask for an overall result. */
    fun detect(text: String): Goal? {
        val t = " " + text.lowercase().replace("-", " ").replace("'", "") + " "
        val attention = has(
            t, "attract", "eye catch", "eyecatch", "catchy", "stand out", "standout", "stunning", "wow", "viral", "engag", "scroll",
            "hook", "grab", "striking", "appeal", "trending", "make it pop", "popular", "noticed", "notice it", "impress", "irresist",
            "gorgeous", "amazing", "awesome", "likes", "followers", "interest", "people love", "people like"
        )
        val polish = has(
            t, "professional", "polish", "better", "improve", "enhance", "fix it", "look good", "looks good", "look great", "nice",
            "beautiful", "aesthetic", "pretty", "the best", "high quality", "perfect", "edit it", "edit this", "edit my", "edit the",
            "do something", "surprise me", "your choice", "on your own", "by yourself", "by itself", "automatic", "auto edit",
            "make it look", "good looking", "fancy", "clean up"
        )
        val energetic = has(t, "energetic", "energy", "hype", "fast pace", "fast paced", "punchy", "exciting", "dynamic", "upbeat", "lively", "party", "action", "sporty")
        val mood = when {
            has(t, "dramatic", "moody", "intense", "mysterious", "dark mood") -> "dramatic"
            has(t, "calm", "peaceful", "gentle", "relax", "cozy", "serene", "minimal", "soft look") -> "calm"
            has(t, "happy", "cheerful", "sunny", "joy", "vibrant", "colorful", "colourful", "summer", "fun") -> "happy"
            has(t, "romantic", "love", "dreamy", "wedding") -> "romantic"
            has(t, "vintage", "retro", "nostalgic", "old school", "90s", "80s") -> "retro"
            has(t, "cinematic", "movie", "film look") -> "cinematic"
            has(t, "luxury", "premium", "elegant", "classy", "expensive", "fashion") -> "luxury"
            has(t, "golden", "sunset", "warm tone") -> "golden"
            else -> null
        }
        val platform = when {
            has(t, "instagram", "insta ", "insta,", "ig post") -> "instagram"
            has(t, "reel", "tiktok", "tik tok", "shorts", "story", "stories", "status", "snapchat") -> "short"
            has(t, "youtube", "thumbnail", "widescreen") -> "youtube"
            else -> null
        }
        val subject = when {
            has(t, "portrait", "selfie", "face", "people", "person", "girl", "boy", "model", "couple") -> "portrait"
            has(t, "food", "dish", "meal", "pizza", "burger", "cake", "coffee", "restaurant", "recipe") -> "food"
            has(t, "product", "shop", "sale", "brand", "advert", "store") -> "product"
            has(t, "landscape", "nature", "travel", "mountain", "beach", "sky", "scenery", "trip", "city") -> "landscape"
            has(t, "night", "neon") -> "night"
            else -> null
        }
        val talking = has(t, "talking", "speech", "interview", "podcast", "vlog", "lecture", "tutorial", "dead air", "pauses", "silence", "tighten")
        val shaky = has(t, "shaky", "shake", "stabil", "steady", "smooth", "handheld", "jitter")
        val beat = has(t, "beat", "rhythm", "to the music", "sync", "bass")
        if (!(attention || polish || energetic || platform != null || talking || shaky || beat)) return null
        val parts = ArrayList<String>()
        if (attention) parts.add("eye-catching")
        if (polish) parts.add("polished")
        if (energetic) parts.add("energetic")
        if (mood != null) parts.add("$mood mood")
        when (platform) { "instagram" -> parts.add("for Instagram"); "short" -> parts.add("for short video"); "youtube" -> parts.add("for YouTube") }
        if (subject != null) parts.add("$subject")
        if (talking) parts.add("tighter talking")
        if (shaky) parts.add("steadier")
        if (beat) parts.add("on the beat")
        return Goal(parts.joinToString(", "), attention, polish, energetic, mood, platform, subject, talking, shaky, beat)
    }

    /** Chooses numbers for brightness, contrast, colour and finishing from how the picture looks and what the person wants. */
    fun tune(st: FrameStats, g: Goal): Tune {
        val notes = ArrayList<String>()
        val mood = g.mood
        val targetL = when {
            mood == "dramatic" -> 0.38f; mood == "luxury" -> 0.40f; mood == "happy" -> 0.55f; mood == "calm" -> 0.52f
            g.subject == "food" || g.subject == "product" -> 0.53f
            else -> 0.48f
        }
        val targetC = when (mood) { "dramatic" -> 0.27f; "calm" -> 0.19f; "luxury" -> 0.25f; else -> if (g.attention) 0.25f else 0.22f }
        val targetS = when {
            mood == "calm" -> 0.26f; mood == "luxury" -> 0.28f; mood == "dramatic" -> 0.34f; mood == "happy" -> 0.48f
            g.subject == "food" -> 0.50f; g.subject == "portrait" -> 0.34f
            g.attention -> 0.42f
            else -> 0.38f
        }

        val dl = targetL - st.luma
        val brightness = if (abs(dl) > 0.04f) (dl * 0.9f).coerceIn(-0.22f, 0.22f) else 0f
        if (brightness > 0.04f) notes.add("Brightened it, because it looked dark.")
        else if (brightness < -0.04f) notes.add("Darkened it a little, because it looked washed out.")

        val dc = targetC - st.contrast
        val contrast = if (abs(dc) > 0.02f) 1f + (dc * 1.5f).coerceIn(-0.12f, 0.3f) else 1f
        if (contrast > 1.04f) notes.add("Added contrast so the picture stands out.")
        else if (contrast < 0.96f) notes.add("Softened the contrast.")

        val ds = targetS - st.sat
        val saturation = if (abs(ds) > 0.03f) 1f + (ds * 1.3f).coerceIn(-0.3f, 0.4f) else 1f
        if (saturation > 1.05f) notes.add("Made the colours stronger.")
        else if (saturation < 0.95f) notes.add("Toned the colours down.")

        val shadows = if (st.dark > 0.1f || st.luma < 0.34f) (0.25f + st.dark).coerceAtMost(0.55f) else 0f
        if (shadows > 0f) notes.add("Lifted the dark areas so the details show.")
        val highlights = if (st.bright > 0.05f) (-0.15f - st.bright * 2f).coerceAtLeast(-0.5f) else 0f
        if (highlights < 0f) notes.add("Pulled back the bright spots.")

        // Colour cast: fix it, except in scenes that are meant to be blue or dark.
        var warmth = 0f
        if (abs(st.cast) > 0.1f && g.subject != "landscape" && g.subject != "night") {
            warmth += (st.cast * 1.6f).coerceIn(-0.3f, 0.3f)
            notes.add("Corrected a colour cast.")
        }
        val moodWarm = when {
            mood == "happy" -> 0.08f; mood == "romantic" -> 0.10f; mood == "golden" -> 0.15f; mood == "calm" -> -0.06f
            g.subject == "food" -> 0.10f; g.subject == "portrait" -> 0.05f
            else -> 0f
        }
        if (moodWarm > 0f) notes.add("Warmed the tones.") else if (moodWarm < 0f) notes.add("Cooled the tones slightly.")
        warmth = (warmth + moodWarm).coerceIn(-0.35f, 0.35f)

        val sharpen = when {
            st.sharp < 0.02f -> 0.6f
            st.sharp < 0.035f -> 0.4f
            g.attention || g.subject == "product" || g.subject == "food" -> 0.3f
            else -> 0.2f
        }
        notes.add(if (st.sharp < 0.035f) "Sharpened it, because it looked soft." else "Added a little sharpness.")

        val vignette = when {
            mood == "dramatic" -> 0.35f; mood == "luxury" -> 0.25f
            g.attention || mood == "cinematic" || mood == "romantic" || g.subject == "portrait" -> 0.22f
            else -> 0f
        }
        if (vignette > 0f) notes.add("Darkened the corners to draw the eye to the centre.")
        val fade = when (mood) { "retro" -> 0.25f; "calm" -> 0.12f; else -> 0f }
        val grain = when (mood) { "retro" -> 0.35f; "cinematic" -> 0.12f; else -> 0f }
        if (grain > 0f) notes.add("Added a touch of film grain.")

        val photoFilter = when (mood) { "cinematic" -> 16; "retro" -> 20; "romantic" -> 18; "dramatic" -> 15; "luxury" -> 14; "golden" -> 19; else -> null }
        val videoFilter = when (mood) { "cinematic" -> 9; "retro" -> 8; "romantic" -> 3; "dramatic" -> 9; "golden" -> 3; "happy" -> 5; else -> null }
        if (photoFilter != null) notes.add("Used the ${photoFilterNames[photoFilter]} look for the $mood mood.")

        val ratio = when (g.platform) { "instagram" -> 0.8f; "short" -> 9f / 16f; "youtube" -> 16f / 9f; else -> null }
        if (ratio != null) notes.add(when (g.platform) { "instagram" -> "Cropped to 4:5 for Instagram."; "short" -> "Cropped to 9:16 for short video."; else -> "Cropped to 16:9 for YouTube." })
        return Tune(brightness, contrast, saturation, shadows, highlights, warmth, sharpen, vignette, fade, grain, photoFilter, videoFilter, ratio, notes)
    }
}
