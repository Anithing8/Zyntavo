package com.zyntavo

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Shapes an overlay video can be cut into. Every shape sits in a square from 0 to 1. */
val maskNames = listOf("None", "Circle", "Rounded", "Heart", "Star")

/** Chroma key colours for green screen footage. */
val keyNames = listOf("Off", "Green", "Blue")

private val heartPoly: FloatArray = FloatArray(144).also { out ->
    for (i in 0 until 72) {
        val t = i * 2.0 * PI / 72.0
        val x = 16.0 * sin(t).pow(3)
        val y = 13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t)
        out[2 * i] = (0.5 + x / 34.0).toFloat()
        out[2 * i + 1] = (0.5 - (y + 2.5) / 34.0).toFloat()
    }
}

private val starPoly: FloatArray = FloatArray(20).also { out ->
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 0.49 else 0.2
        val a = -PI / 2 + i * PI / 5
        out[2 * i] = (0.5 + r * cos(a)).toFloat()
        out[2 * i + 1] = (0.54 + r * sin(a)).toFloat()
    }
}

private fun inPoly(poly: FloatArray, x: Float, y: Float): Boolean {
    var inside = false
    val n = poly.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = poly[2 * i]
        val yi = poly[2 * i + 1]
        val xj = poly[2 * j]
        val yj = poly[2 * j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** True if the point (x, y), both from 0 to 1, is inside the shape. Used to draw the mask for the export. */
fun maskInside(shape: Int, x: Float, y: Float): Boolean = when (shape) {
    1 -> (x - 0.5f) * (x - 0.5f) + (y - 0.5f) * (y - 0.5f) <= 0.25f
    2 -> {
        val r = 0.22f
        val dx = max(abs(x - 0.5f) - (0.5f - r), 0f)
        val dy = max(abs(y - 0.5f) - (0.5f - r), 0f)
        dx * dx + dy * dy <= r * r
    }
    3 -> inPoly(heartPoly, x, y)
    4 -> inPoly(starPoly, x, y)
    else -> true
}

/** The shape as a path in a square of the given side, for the preview. */
fun maskPath(shape: Int, left: Float, top: Float, side: Float): Path {
    val p = Path()
    when (shape) {
        1 -> p.addOval(Rect(left, top, left + side, top + side))
        2 -> p.addRoundRect(RoundRect(left, top, left + side, top + side, side * 0.22f, side * 0.22f))
        3, 4 -> {
            val poly = if (shape == 3) heartPoly else starPoly
            p.moveTo(left + poly[0] * side, top + poly[1] * side)
            for (i in 1 until poly.size / 2) p.lineTo(left + poly[2 * i] * side, top + poly[2 * i + 1] * side)
            p.close()
        }
        else -> p.addRect(Rect(left, top, left + side, top + side))
    }
    return p
}
