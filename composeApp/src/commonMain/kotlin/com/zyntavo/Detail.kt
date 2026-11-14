package com.zyntavo

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/** True if this photo needs the pixel work in this file: sharpen, blur, light, grain or the portrait blur. */
fun EditState.needsDetail(hasCutout: Boolean): Boolean =
    sharpen > 0f || blur > 0f || highlights != 0f || shadows != 0f || grain > 0f || (bgBlur > 0f && cutout && hasCutout)

class DetailJob(val base: ImageBitmap, val orig: ImageBitmap?, val s: EditState)

private fun pixels(src: ImageBitmap, w: Int, h: Int): IntArray {
    val tmp = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(tmp), Size(w.toFloat(), h.toFloat())) {
        drawImage(src, dstSize = IntSize(w, h))
    }
    val px = IntArray(w * h)
    tmp.readPixels(px)
    return px
}

/** The picture scaled to w by h, as ARGB pixels. */
fun scaledPixels(src: ImageBitmap, w: Int, h: Int): IntArray = pixels(src, w, h)

private fun cl(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()

private fun tone(c: Int, adj: Float): Int {
    val v = c / 255f
    val o = if (adj > 0f) v + adj * (1f - v) else v * (1f + adj)
    return cl(o * 255f)
}

/** A box blur along rows or columns, using a running sum so it stays fast on big pictures. */
private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val stride = if (horizontal) 1 else w
    val lineStep = if (horizontal) w else 1
    val div = 2 * r + 1
    for (l in 0 until lines) {
        val base = l * lineStep
        var sr = 0
        var sg = 0
        var sb = 0
        for (k in -r..r) {
            val p = src[base + k.coerceIn(0, len - 1) * stride]
            sr += (p shr 16) and 255
            sg += (p shr 8) and 255
            sb += p and 255
        }
        for (i in 0 until len) {
            val here = src[base + i * stride]
            dst[base + i * stride] = (here and (255 shl 24)) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = src[base + min(i + r + 1, len - 1) * stride]
            val rem = src[base + max(i - r, 0) * stride]
            sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
            sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
            sb += (add and 255) - (rem and 255)
        }
    }
}

private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
    if (r < 1) return src
    val tmp = IntArray(w * h)
    val out = IntArray(w * h)
    blurPass(src, tmp, w, h, r, true)
    blurPass(tmp, out, w, h, r, false)
    return out
}

/** Works on at most 2048 pixels on the long side, which is also the largest size the app exports. */
fun runDetail(job: DetailJob): ImageBitmap {
    val s = job.s
    val base = job.base
    val k = min(1f, 2048f / max(base.width, base.height))
    val w = max(1, (base.width * k).toInt())
    val h = max(1, (base.height * k).toInt())
    var px = pixels(base, w, h)
    val scale = w / 2048f

    // Portrait blur: the cut-out person stays sharp on top of a blurred copy of the original photo.
    val orig = job.orig
    if (s.bgBlur > 0f && s.cutout && orig != null) {
        val r = max(2, (s.bgBlur * 22f * max(scale, 0.25f)).toInt())
        val bg = boxBlur(boxBlur(pixels(orig, w, h), w, h, r), w, h, r)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a >= 255) continue
            val q = bg[i]
            val af = a / 255f
            val rr = cl(((p shr 16) and 255) * af + ((q shr 16) and 255) * (1f - af))
            val gg = cl(((p shr 8) and 255) * af + ((q shr 8) and 255) * (1f - af))
            val bb = cl((p and 255) * af + (q and 255) * (1f - af))
            px[i] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    // Highlights and shadows: brighten or darken only the dark or only the bright parts.
    if (s.shadows != 0f || s.highlights != 0f) {
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            val adj = s.shadows * 0.5f * (1f - l) * (1f - l) + s.highlights * 0.5f * l * l
            px[i] = (p and (255 shl 24)) or (tone(r, adj) shl 16) or (tone(g, adj) shl 8) or tone(b, adj)
        }
    }

    if (s.blur > 0f) {
        val r = max(1, (s.blur * 14f * max(scale, 0.25f)).toInt())
        px = boxBlur(boxBlur(px, w, h, r), w, h, r)
    }

    // Sharpen: add back the difference between the picture and a slightly blurred copy of it.
    if (s.sharpen > 0f) {
        val soft = boxBlur(px, w, h, 2)
        val amount = s.sharpen * 1.6f
        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val q = soft[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            out[i] = (p and (255 shl 24)) or
                (cl(r + amount * (r - ((q shr 16) and 255))) shl 16) or
                (cl(g + amount * (g - ((q shr 8) and 255))) shl 8) or
                cl(b + amount * (b - (q and 255)))
        }
        px = out
    }

    // Film grain: the same small random value added to every colour of a pixel.
    if (s.grain > 0f) {
        val amt = s.grain * 40f
        var seed = 12345
        for (i in px.indices) {
            seed = seed * 1103515245 + 12345
            val p = px[i]
            if ((p ushr 24) < 8) continue
            val n = (((seed ushr 16) and 255) - 128) / 128f * amt
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            px[i] = (p and (255 shl 24)) or (cl(r + n) shl 16) or (cl(g + n) shl 8) or cl(b + n)
        }
    }
    return imageFromPixels(w, h, px)
}
