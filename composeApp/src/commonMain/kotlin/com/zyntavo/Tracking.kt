package com.zyntavo

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Where a tracked object was on the timeline: x and y from 0 to 1 across the video frame. */
data class TrackPt(val tMs: Long, val x: Float, val y: Float)

/** The position of a tracked object at a time on the timeline, or null if there is no track. Holds the first and last position outside the track. */
fun trackAt(pts: List<TrackPt>, ms: Long): Pair<Float, Float>? {
    if (pts.isEmpty()) return null
    if (ms <= pts.first().tMs) return pts.first().x to pts.first().y
    if (ms >= pts.last().tMs) return pts.last().x to pts.last().y
    val i = pts.indexOfLast { it.tMs <= ms }
    val a = pts[i]
    val b = pts[i + 1]
    val p = (ms - a.tMs).toFloat() / max(1L, b.tMs - a.tMs).toFloat()
    return (a.x + (b.x - a.x) * p) to (a.y + (b.y - a.y) * p)
}

class Gray(val w: Int, val h: Int, val v: IntArray)

/**
 * Follows a point through a video by block matching: a small square around the point is compared with every
 * position nearby in the next picture, and the position that differs least wins. It is plain arithmetic on
 * brightness, written for this app, with nothing from any other project.
 */
object Tracker {
    fun gray(img: ImageBitmap): Gray {
        val w = 160
        val h = max(8, (img.height * 160f / img.width).roundToInt())
        val px = scaledPixels(img, w, h)
        val v = IntArray(w * h)
        for (i in v.indices) {
            val p = px[i]
            v[i] = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
        }
        return Gray(w, h, v)
    }

    /** Finds where the square around (px, py) in [a] went in [b]. */
    fun search(a: Gray, px: Int, py: Int, b: Gray): Pair<Int, Int> {
        val half = 10
        val range = 14
        var best = Long.MAX_VALUE
        var bx = px
        var by = py
        for (dy in -range..range) {
            for (dx in -range..range) {
                var sad = 0L
                for (j in -half..half) {
                    val ya = (py + j).coerceIn(0, a.h - 1)
                    val yb = (py + dy + j).coerceIn(0, b.h - 1)
                    for (i in -half..half) {
                        val xa = (px + i).coerceIn(0, a.w - 1)
                        val xb = (px + dx + i).coerceIn(0, b.w - 1)
                        sad += abs(a.v[ya * a.w + xa] - b.v[yb * b.w + xb])
                    }
                }
                sad += (dx * dx + dy * dy).toLong()
                if (sad < best) {
                    best = sad
                    bx = px + dx
                    by = py + dy
                }
            }
        }
        return bx.coerceIn(0, b.w - 1) to by.coerceIn(0, b.h - 1)
    }

    /** Where the video picture sits inside the output frame: left, top, width and height, each from 0 to 1. */
    private fun fit(frameAspect: Float, outAspect: Float): FloatArray =
        if (frameAspect >= outAspect) {
            val h = outAspect / frameAspect
            floatArrayOf(0f, (1f - h) / 2f, 1f, h)
        } else {
            val w = frameAspect / outAspect
            floatArrayOf((1f - w) / 2f, 0f, w, 1f)
        }

    private fun croppedAspect(c: Clip, fa: Float): Float = fa * (1f - 2f * c.cropX) / (1f - 2f * c.cropY)

    /** A point in the source picture (0 to 1) to the same point in the output frame, after crop, fit and mirror. */
    fun srcToOut(c: Clip, fa: Float, oa: Float, sx: Float, sy: Float): Pair<Float, Float> {
        val x = (sx - c.cropX) / (1f - 2f * c.cropX)
        val y = (sy - c.cropY) / (1f - 2f * c.cropY)
        val r = fit(croppedAspect(c, fa), oa)
        var ox = r[0] + x * r[2]
        var oy = r[1] + y * r[3]
        if (c.mirror) ox = 1f - ox
        if (c.flipV) oy = 1f - oy
        return ox to oy
    }

    /** The other way round: a point in the output frame to the point in the source picture. */
    fun outToSrc(c: Clip, fa: Float, oa: Float, ox0: Float, oy0: Float): Pair<Float, Float> {
        var ox = ox0
        var oy = oy0
        if (c.mirror) ox = 1f - ox
        if (c.flipV) oy = 1f - oy
        val r = fit(croppedAspect(c, fa), oa)
        val x = ((ox - r[0]) / r[2]).coerceIn(0f, 1f)
        val y = ((oy - r[1]) / r[3]).coerceIn(0f, 1f)
        return (c.cropX + x * (1f - 2f * c.cropX)) to (c.cropY + y * (1f - 2f * c.cropY))
    }
}
