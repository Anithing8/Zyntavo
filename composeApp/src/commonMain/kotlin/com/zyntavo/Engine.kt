package com.zyntavo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class TextItem(val id: Int, val text: String, val x: Float, val y: Float, val size: Float, val color: Color)

data class EditState(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val filter: Int = 0,
    val rotation: Int = 0,
    val flip: Boolean = false,
    val ratio: Float? = null,
    val cutout: Boolean = false,
    val bg: Color? = null,
    val warmth: Float = 0f,
    val vignette: Float = 0f,
    val border: Float = 0f,
    val borderColor: Color = Color.White,
    val exposure: Float = 0f,
    val tint: Float = 0f,
    val hue: Float = 0f,
    val fade: Float = 0f,
    val flipV: Boolean = false,
    val sharpen: Float = 0f,
    val blur: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val grain: Float = 0f,
    val bgBlur: Float = 0f,
    val angle: Float = 0f,
    val texts: List<TextItem> = emptyList()
)

val filterNames = listOf("None", "B&W", "Sepia", "Warm", "Cool", "Vivid", "Fade", "Noir", "Vintage", "Cinema", "Pop")

/** The video editor uses the first eleven. The photo editor has more. */
val photoFilterNames = filterNames + listOf(
    "Sunset", "Forest", "Ocean", "Matte", "Dramatic", "Teal & Orange", "Mono Blue", "Rose", "Golden hour", "Faded film", "High key", "Low key",
    "Duotone purple", "Duotone teal", "Duotone sunset", "Invert"
)

/** The filters the video editor can also export. */
val videoFilterNames = photoFilterNames.take(23)

private fun sat(s: Float) = ColorMatrix().apply { setToSaturation(s) }
private fun mat(vararg v: Float) = ColorMatrix(floatArrayOf(*v))

/** Turns the picture into shades between two colours (0 to 255 each): the dark colour first, then the light one. */
private fun duo(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): ColorMatrix {
    val dr = (r2 - r1) / 255f
    val dg = (g2 - g1) / 255f
    val db = (b2 - b1) / 255f
    return mat(
        dr * 0.2126f, dr * 0.7152f, dr * 0.0722f, 0f, r1,
        dg * 0.2126f, dg * 0.7152f, dg * 0.0722f, 0f, g1,
        db * 0.2126f, db * 0.7152f, db * 0.0722f, 0f, b1,
        0f, 0f, 0f, 1f, 0f
    )
}

private fun filterMatrix(i: Int): ColorMatrix? = when (i) {
    1 -> sat(0f)
    2 -> mat(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    3 -> mat(1.1f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.9f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)
    4 -> mat(0.9f, 0f, 0f, 0f, -10f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)
    5 -> sat(1.6f)
    6 -> mat(0.9f, 0f, 0f, 0f, 25f, 0f, 0.9f, 0f, 0f, 25f, 0f, 0f, 0.9f, 0f, 25f, 0f, 0f, 0f, 1f, 0f)
    7 -> sat(0f).also { it.timesAssign(contrastMatrix(1.4f, 0f)) }
    8 -> mat(0.5f, 0.5f, 0.1f, 0f, 10f, 0.3f, 0.6f, 0.1f, 0f, 10f, 0.2f, 0.4f, 0.3f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)
    9 -> mat(1.12f, 0f, 0f, 0f, -10f, 0f, 1.08f, 0f, 0f, -6f, 0f, 0f, 1.1f, 0f, 4f, 0f, 0f, 0f, 1f, 0f)
    10 -> sat(1.45f).also { it.timesAssign(contrastMatrix(1.12f, 0.02f)) }
    11 -> mat(1.15f, 0f, 0f, 0f, 12f, 0f, 0.95f, 0f, 0f, 0f, 0f, 0f, 0.8f, 0f, -8f, 0f, 0f, 0f, 1f, 0f)
    12 -> mat(0.9f, 0f, 0f, 0f, 0f, 0f, 1.1f, 0f, 0f, 6f, 0f, 0f, 0.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    13 -> mat(0.85f, 0f, 0f, 0f, -4f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.2f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)
    14 -> sat(0.85f).also { it.timesAssign(contrastMatrix(0.85f, 0.03f)) }
    15 -> sat(0.7f).also { it.timesAssign(contrastMatrix(1.35f, -0.03f)) }
    16 -> mat(1.12f, 0f, -0.04f, 0f, 6f, -0.03f, 1f, 0.08f, 0f, 0f, -0.1f, 0.12f, 1.12f, 0f, -4f, 0f, 0f, 0f, 1f, 0f)
    17 -> mat(0.17f, 0.57f, 0.06f, 0f, 0f, 0.19f, 0.64f, 0.06f, 0f, 0f, 0.26f, 0.86f, 0.09f, 0f, 12f, 0f, 0f, 0f, 1f, 0f)
    18 -> mat(1.1f, 0f, 0f, 0f, 10f, 0f, 0.95f, 0f, 0f, 0f, 0f, 0f, 1.02f, 0f, 8f, 0f, 0f, 0f, 1f, 0f)
    19 -> mat(1.2f, 0f, 0f, 0f, 10f, 0f, 1.05f, 0f, 0f, 4f, 0f, 0f, 0.8f, 0f, -6f, 0f, 0f, 0f, 1f, 0f)
    20 -> mat(0.92f, 0f, 0f, 0f, 16f, 0f, 0.9f, 0f, 0f, 16f, 0f, 0f, 0.86f, 0f, 24f, 0f, 0f, 0f, 1f, 0f)
    21 -> sat(0.95f).also { it.timesAssign(contrastMatrix(0.85f, 0.12f)) }
    22 -> contrastMatrix(1.25f, -0.1f)
    23 -> duo(45f, 10f, 85f, 255f, 185f, 235f)
    24 -> duo(0f, 40f, 65f, 130f, 255f, 225f)
    25 -> duo(60f, 10f, 70f, 255f, 195f, 95f)
    26 -> mat(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)
    else -> null
}

private fun contrastMatrix(c: Float, b: Float): ColorMatrix {
    val t = (0.5f * (1 - c) + b) * 255f
    return mat(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)
}

fun colorMatrixOf(s: EditState): ColorMatrix {
    val m = ColorMatrix()
    filterMatrix(s.filter)?.let { m.timesAssign(it) }
    m.timesAssign(sat(s.saturation))
    if (s.hue != 0f) {
        val a = s.hue * (PI.toFloat() / 180f)
        val c = cos(a)
        val n = sin(a)
        m.timesAssign(
            mat(
                0.213f + c * 0.787f - n * 0.213f, 0.715f - c * 0.715f - n * 0.715f, 0.072f - c * 0.072f + n * 0.928f, 0f, 0f,
                0.213f - c * 0.213f + n * 0.143f, 0.715f + c * 0.285f + n * 0.140f, 0.072f - c * 0.072f - n * 0.283f, 0f, 0f,
                0.213f - c * 0.213f - n * 0.787f, 0.715f - c * 0.715f + n * 0.715f, 0.072f + c * 0.928f + n * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
    if (s.exposure != 0f) {
        val k = 2f.pow(s.exposure)
        m.timesAssign(mat(k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    }
    if (s.tint != 0f) {
        m.timesAssign(mat(1f + 0.1f * s.tint, 0f, 0f, 0f, 0f, 0f, 1f - 0.2f * s.tint, 0f, 0f, 0f, 0f, 0f, 1f + 0.1f * s.tint, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    }
    if (s.fade > 0f) m.timesAssign(contrastMatrix(1f - 0.3f * s.fade, 0.04f * s.fade))
    if (s.warmth != 0f) m.timesAssign(mat(1f + 0.25f * s.warmth, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - 0.25f * s.warmth, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    m.timesAssign(contrastMatrix(s.contrast, s.brightness))
    return m
}

fun outSize(src: ImageBitmap, s: EditState): IntSize {
    val turned = s.rotation % 180 != 0
    val sw = (if (turned) src.height else src.width).toFloat()
    val sh = (if (turned) src.width else src.height).toFloat()
    var w = sw
    var h = sh
    val r = s.ratio
    if (r != null) {
        if (r >= sw / sh) { h = sw / r } else { w = sh * r }
    }
    val k = min(1f, 2048f / max(w, h))
    return IntSize(max(1, (w * k).toInt()), max(1, (h * k).toInt()))
}

fun DrawScope.drawEdits(src: ImageBitmap, s: EditState, tm: TextMeasurer, selected: Int?) {
    s.bg?.let { drawRect(it) }
    val turned = s.rotation % 180 != 0
    val sw = (if (turned) src.height else src.width).toFloat()
    val sh = (if (turned) src.width else src.height).toFloat()
    // Straighten: turning by a small angle leaves empty corners, so the picture is enlarged just enough to cover them.
    val tilt = abs(s.angle) * (PI.toFloat() / 180f)
    val q = size.width / size.height
    val extra = if (tilt == 0f) 1f else cos(tilt) + sin(tilt) * max(q, 1f / q)
    val k = max(size.width / sw, size.height / sh) * extra
    val tiltDeg = if (s.flip != s.flipV) -s.angle else s.angle
    withTransform({
        translate(size.width / 2f, size.height / 2f)
        scale(if (s.flip) -1f else 1f, if (s.flipV) -1f else 1f, Offset.Zero)
        rotate(s.rotation.toFloat() + tiltDeg, Offset.Zero)
        scale(k, k, Offset.Zero)
        translate(-src.width / 2f, -src.height / 2f)
    }) {
        drawImage(src, colorFilter = ColorFilter.colorMatrix(colorMatrixOf(s)))
    }
    if (s.vignette > 0f) {
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f * s.vignette),
                center = center, radius = max(size.width, size.height) * 0.75f
            )
        )
    }
    if (s.border > 0f) {
        val b = s.border * min(size.width, size.height)
        drawRect(s.borderColor, Offset.Zero, Size(size.width, b))
        drawRect(s.borderColor, Offset(0f, size.height - b), Size(size.width, b))
        drawRect(s.borderColor, Offset.Zero, Size(b, size.height))
        drawRect(s.borderColor, Offset(size.width - b, 0f), Size(b, size.height))
    }
    s.texts.forEach { t ->
        val style = TextStyle(
            color = t.color,
            fontSize = (t.size * size.height).toSp(),
            fontWeight = FontWeight.Bold,
            shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(2f, 2f), 4f)
        )
        val r = tm.measure(t.text, style)
        val tl = Offset(t.x * size.width - r.size.width / 2f, t.y * size.height - r.size.height / 2f)
        drawText(r, topLeft = tl)
        if (t.id == selected) {
            drawRect(Color.White, tl, Size(r.size.width.toFloat(), r.size.height.toFloat()), style = Stroke(2f))
        }
    }
}

fun renderExport(src: ImageBitmap, s: EditState, tm: TextMeasurer, density: Density): ImageBitmap {
    val o = outSize(src, s)
    val out = ImageBitmap(o.width, o.height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(out), Size(o.width.toFloat(), o.height.toFloat())) {
        drawEdits(src, s, tm, null)
    }
    return out
}

class Editor {
    var original by mutableStateOf<ImageBitmap?>(null)
    var cutout by mutableStateOf<ImageBitmap?>(null)
    var s by mutableStateOf(EditState())
    var selected by mutableStateOf<Int?>(null)

    /** True while the picture is shown as it was before the adjustments, for comparing. The crop and turn stay. */
    var showOriginal by mutableStateOf(false)

    /** What the preview draws: the edits, or the picture without its adjustments, filter, text and border. */
    fun previewState(): EditState =
        if (!showOriginal) s else s.copy(
            brightness = 0f, contrast = 1f, saturation = 1f, warmth = 0f, vignette = 0f, exposure = 0f,
            tint = 0f, hue = 0f, fade = 0f, filter = 0, texts = emptyList(), border = 0f
        )
    private val undoS = mutableStateListOf<EditState>()
    private val redoS = mutableStateListOf<EditState>()
    private var base: EditState? = null
    private var nextId = 1

    val shown: ImageBitmap? get() = if (s.cutout && cutout != null) cutout else original
    val canUndo get() = undoS.isNotEmpty()
    val canRedo get() = redoS.isNotEmpty()

    fun load(img: ImageBitmap) {
        original = img; cutout = null; s = EditState(); selected = null
        undoS.clear(); redoS.clear(); base = null
        processed = null; processedKey = null
    }

    // ---------- detail work: sharpen, blur, light, grain and portrait blur ----------

    var processed by mutableStateOf<ImageBitmap?>(null)
    var processedKey by mutableStateOf<List<Any?>?>(null)

    fun needsDetail() = s.needsDetail(cutout != null)
    fun detailKey(): List<Any?> = listOf(original, cutout, s.cutout, s.sharpen, s.blur, s.highlights, s.shadows, s.grain, s.bgBlur)

    /** The picture the preview draws: with the detail work done if there is any (the last finished version while a new one is made). */
    val image: ImageBitmap? get() = if (needsDetail()) (processed ?: shown) else shown

    /** Redoes the detail work in the background a moment after the sliders stop moving. */
    suspend fun refreshDetail() {
        if (!needsDetail()) {
            processed = null; processedKey = null
            return
        }
        val key = detailKey()
        if (processedKey == key) return
        delay(120)
        val job = DetailJob(shown ?: return, original, s)
        val out = withContext(Dispatchers.Default) { runDetail(job) }
        processed = out
        processedKey = key
    }

    /** The picture to save: always up to date with every slider. */
    fun exportImage(): ImageBitmap? {
        val b = shown ?: return null
        if (!needsDetail()) return b
        val p = processed
        if (p != null && processedKey == detailKey()) return p
        return runDetail(DetailJob(b, original, s))
    }

    /** Looks at the picture and works out brightness, contrast and colour that make it look balanced. */
    private fun enhanceValues(): FloatArray? {
        val src = shown ?: return null
        val w = 128
        val h = max(1, (src.height * 128f / src.width).toInt())
        val small = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(small), Size(w.toFloat(), h.toFloat())) {
            drawImage(src, dstSize = IntSize(w, h))
        }
        val px = IntArray(w * h)
        small.readPixels(px)
        val hist = IntArray(256)
        var n = 0
        var lumSum = 0.0
        var satSum = 0.0
        for (p in px) {
            if (((p ushr 24) and 255) < 16) continue
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
            hist[lum]++
            lumSum += lum
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx > 0) satSum += (mx - mn).toDouble() / mx
            n++
        }
        if (n < 50) return null
        fun percentile(q: Float): Int {
            val target = (n * q).toInt()
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc > target) return v
            }
            return 255
        }
        val lo = percentile(0.01f)
        val hi = percentile(0.99f)
        val mean = (lumSum / n).toFloat() / 255f
        val spread = max(hi - lo, 40) / 255f
        val c = (0.94f / spread).coerceIn(0.85f, 1.5f)
        val b = (0.46f - 0.5f - c * (mean - 0.5f)).coerceIn(-0.3f, 0.3f)
        val avgSat = (satSum / n).toFloat()
        val sat = (0.38f / max(avgSat, 0.1f)).coerceIn(0.9f, 1.3f)
        return floatArrayOf(b, c, sat)
    }

    fun autoEnhance() {
        val v = enhanceValues() ?: return
        set { it.copy(brightness = v[0], contrast = v[1], saturation = v[2]) }
    }

    // ---------- editing with a written prompt ----------

    var promptText by mutableStateOf("")
    var promptNote by mutableStateOf("")
    val promptHistory = mutableStateListOf<String>()

    /** Does what the written prompt says, as one step that can be undone. */
    /** Looks at the photo, works out what would make it look better for the goal in [text], and makes those edits. */
    fun autoEdit(text: String) {
        val img = original ?: return
        val g = Auto.detect(text) ?: Auto.general()
        val plan = Auto.tune(analyseFrame(img), g)
        set { plan.applyTo(it) }
        val t = text.trim()
        if (t.isNotEmpty()) {
            promptHistory.remove(t)
            promptHistory.add(0, t)
            while (promptHistory.size > 6) promptHistory.removeAt(promptHistory.size - 1)
        }
        promptNote = "Goal: ${g.label}\n" + plan.notes.joinToString("\n") + "\nUse Undo above to take it back."
    }

    /** The Auto edit button: decides the edits itself, and also does anything exact that the text asks for. */
    fun smartPrompt() {
        val text = promptText.trim()
        val explicit = Prompt.parse(text).actions.any { !it.soft && it.key != "note" }
        if (text.isNotEmpty() && explicit && Auto.detect(text) == null) {
            runPrompt()
            return
        }
        autoEdit(text)
        if (explicit) {
            val note = promptNote
            runPrompt()
            promptNote = note + "\n" + promptNote
        }
    }

    fun runPrompt() {
        val text = promptText.trim()
        if (text.isEmpty()) return
        if (shown == null) {
            promptNote = "Open a photo first."
            return
        }
        val r = Prompt.parse(text)
        if (r.actions.isEmpty()) {
            promptNote = "I did not understand that. Try something like \u201Cwarmer, more contrast and a slight vignette\u201D."
            return
        }
        val notes = ArrayList<String>()
        val enhance = if (r.actions.any { it.key == "auto" }) enhanceValues() else null
        set { st -> PromptApply.photo(st, r.actions, enhance, notes) }
        for (a in r.actions) {
            if (a.key == "text" || a.key == "sticker") {
                val ty = a.args["y"]?.toFloatOrNull() ?: 0.5f
                val tsize = a.args["size"]?.toFloatOrNull() ?: 0.07f
                val tcolor = PromptApply.colorOf(a.args["color"] ?: "") ?: Color.White
                addText(a.text, ty.coerceIn(0f, 1f), tsize.coerceIn(0.03f, 0.3f), tcolor)
            }
        }
        val done = r.actions.filter { !it.soft && it.key != "note" }.map { it.label }
        var msg = if (done.isEmpty()) "Nothing changed." else "Done: " + done.joinToString(", ") + "."
        if (notes.isNotEmpty()) msg += " " + notes.joinToString(" ")
        if (r.unknown.isNotEmpty()) msg += " I did not understand: " + r.unknown.joinToString("; ") + "."
        promptNote = msg
        promptHistory.remove(text)
        promptHistory.add(0, text)
        while (promptHistory.size > 6) promptHistory.removeAt(promptHistory.size - 1)
    }

    fun live(f: (EditState) -> EditState) { if (base == null) base = s; s = f(s) }
    fun end() {
        base?.let { if (it != s) { undoS.add(it); redoS.clear() } }
        base = null
    }
    fun set(f: (EditState) -> EditState) { live(f); end() }

    fun undo() {
        if (undoS.isEmpty()) return
        redoS.add(s); s = undoS.removeAt(undoS.lastIndex)
    }
    fun redo() {
        if (redoS.isEmpty()) return
        undoS.add(s); s = redoS.removeAt(redoS.lastIndex)
    }

    fun addText(text: String, y: Float = 0.5f, size: Float = 0.07f, color: Color = Color.White) {
        val id = nextId++
        set { it.copy(texts = it.texts + TextItem(id, text, 0.5f, y, size, color)) }
        selected = id
    }
    fun updateSel(commit: Boolean = false, f: (TextItem) -> TextItem) {
        val id = selected ?: return
        val g = { st: EditState -> st.copy(texts = st.texts.map { if (it.id == id) f(it) else it }) }
        if (commit) set(g) else live(g)
    }
    fun deleteSel() {
        val id = selected ?: return
        set { it.copy(texts = it.texts.filter { t -> t.id != id }) }
        selected = null
    }
    fun pickText(fx: Float, fy: Float) {
        val hit = s.texts.minByOrNull { hypot(it.x - fx, it.y - fy) }
        selected = if (hit != null && hypot(hit.x - fx, hit.y - fy) < 0.15f) hit.id else null
    }
    fun dragText(dx: Float, dy: Float) =
        updateSel { it.copy(x = (it.x + dx).coerceIn(0f, 1f), y = (it.y + dy).coerceIn(0f, 1f)) }
}
