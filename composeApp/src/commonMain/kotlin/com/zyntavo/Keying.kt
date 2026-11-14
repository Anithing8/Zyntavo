package com.zyntavo

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Removes the key colour the way the export does, close enough to judge the result in the preview.
 * The export uses FFmpeg's own chroma key, so the two are similar but not identical pixel for pixel.
 */
fun keyPreview(src: ImageBitmap, key: Int, similarity: Float): ImageBitmap {
    val k = min(1f, 640f / src.width)
    val w = max(1, (src.width * k).toInt())
    val h = max(1, (src.height * k).toInt())
    val px = scaledPixels(src, w, h)
    val kr = 0
    val kg = if (key == 2) 0x47 else 0xB1
    val kb = if (key == 2) 0xBB else 0x40
    val ku = 128f - 0.169f * kr - 0.331f * kg + 0.5f * kb
    val kv = 128f + 0.5f * kr - 0.419f * kg - 0.081f * kb
    val blend = 0.08f
    val out = IntArray(px.size)
    for (i in px.indices) {
        val p = px[i]
        val r = (p shr 16) and 255
        val g = (p shr 8) and 255
        val b = p and 255
        val u = 128f - 0.169f * r - 0.331f * g + 0.5f * b
        val v = 128f + 0.5f * r - 0.419f * g - 0.081f * b
        val du = u - ku
        val dv = v - kv
        val diff = sqrt(du * du + dv * dv) / 255f
        val a = ((diff - similarity) / blend).coerceIn(0f, 1f)
        out[i] = ((a * 255f).toInt() shl 24) or (p and 0xFFFFFF)
    }
    return imageFromPixels(w, h, out)
}

/** A rough width of a line of text in units of its font size, so the preview and the export place typed text the same way. */
fun textWidthEm(line: String): Double {
    var w = 0.0
    for (ch in line) {
        w += when {
            ch == ' ' -> 0.28
            ch in "iljtfI.,:;'!|" -> 0.3
            ch in "mwMW@" -> 0.85
            ch.isUpperCase() -> 0.65
            ch.isDigit() -> 0.56
            else -> 0.52
        }
    }
    return w
}

/** The widest line of a text, in units of the font size. */
fun widestLineEm(text: String): Double = text.lines().maxOfOrNull { textWidthEm(it) } ?: 0.0
