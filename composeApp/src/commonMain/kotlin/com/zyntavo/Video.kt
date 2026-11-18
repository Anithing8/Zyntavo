package com.zyntavo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Source(val ref: String, val name: String, val durationMs: Long, val hasAudio: Boolean)
data class Keyframe(val tMs: Long, val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f, val rot: Float = 0f, val opacity: Float = 1f, val vol: Float = 1f)

data class Clip(
    val src: Source, val inMs: Long, val outMs: Long, val speed: Float = 1f, val filter: Int = 0,
    val brightness: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f,
    val mirror: Boolean = false, val flipV: Boolean = false, val cropX: Float = 0f, val cropY: Float = 0f,
    val gain: Float = 1f, val fx: Int = 0,
    val trans: Int = 0, val transMs: Long = 600L,
    val ease: Int = 1, val keys: List<Keyframe> = emptyList(),
    /** Plays backwards. */
    val reverse: Boolean = false,
    /** A single picture held for the length of the clip. */
    val freeze: Boolean = false,
    /** 0 off, 1 normal, 2 strong steadying. */
    val stab: Int = 0
)

data class Loc(val i: Int, val srcMs: Long, val localMs: Long)
data class Title(val text: String, val startMs: Long, val endMs: Long, val x: Float, val y: Float, val size: Float, val color: Color, val anim: Int = 0, val track: List<TrackPt> = emptyList())

val titleAnimNames = listOf("None", "Fade", "Slide up", "Slide down", "Slide in", "Pop", "Typewriter")

data class Overlay(
    val src: Source, val inMs: Long, val outMs: Long, val startMs: Long,
    val x: Float = 0.3f, val y: Float = -0.3f, val scale: Float = 0.35f, val opacity: Float = 1f,
    val blend: Int = 0, val cropX: Float = 0f, val cropY: Float = 0f,
    /** Green screen: 0 off, 1 green, 2 blue. */
    val key: Int = 0, val keySim: Float = 0.3f,
    /** Shape the overlay is cut into (see maskNames). */
    val mask: Int = 0,
    /** Where an object it follows moves; empty when it stays where it is. */
    val track: List<TrackPt> = emptyList()
)

/** A voiceover or music file mixed into the sound. */
data class Voice(val text: String, val startMs: Long, val wav: String, val durMs: Long, val vol: Float = 1f)

data class ExportSettings(val res: Int = 1, val fps: Int = 30, val crf: Int = 20, val basic: Boolean = false, val aspect: Int = 0, val blurBg: Boolean = false)

/** True if the project uses something only the full FFmpeg engine can draw. */
fun usesFullFeatures(p: VideoProject): Boolean =
    p.settings.blurBg || p.clips.any { it.reverse || it.freeze || it.stab > 0 } ||
        p.overlays.any { it.key > 0 || it.mask > 0 || it.track.isNotEmpty() } || p.titles.any { it.anim > 0 || it.track.isNotEmpty() }

/** A reversed clip is reversed in parts no longer than this, so it never needs more than about 500 MB of memory. */
fun reverseLimitMs(s: ExportSettings): Long {
    val (w, h) = frameSize(s.res, s.aspect)
    val bytesPerSecond = w.toDouble() * h * 1.5 * s.fps
    return (500e6 / bytesPerSecond * 1000.0).toLong()
}

/** The shapes the video can be exported in. */
val aspectNames = listOf("16:9 wide", "9:16 tall", "1:1 square", "4:5 portrait")

/** Output width and height. For tall and square shapes the resolution sets the short side. */
fun frameSize(res: Int, aspect: Int): Pair<Int, Int> {
    val short = when (res) { 0 -> 480; 2 -> 1080; else -> 720 }
    return when (aspect) {
        1 -> short to (short * 16 / 9 / 2 * 2)
        2 -> short to short
        3 -> short to (short * 5 / 4 / 2 * 2)
        else -> when (res) { 0 -> 854 to 480; 2 -> 1920 to 1080; else -> 1280 to 720 }
    }
}

/** Width divided by height of the video frame, as the preview shows it. */
fun frameRatio(s: ExportSettings): Float {
    if (!fullEngine || s.aspect == 0) return 16f / 9f
    val (w, h) = frameSize(s.res, s.aspect)
    return w.toFloat() / h.toFloat()
}
data class CaptionStyle(val size: Float = 0.045f, val pos: Int = 2, val box: Boolean = true, val preset: Int = 0)

data class VideoProject(
    val clips: List<Clip>, val titles: List<Title>, val captions: List<Cue>,
    val overlays: List<Overlay> = emptyList(), val voices: List<Voice> = emptyList(), val voiceFx: Int = 0,
    val settings: ExportSettings = ExportSettings(), val captionStyle: CaptionStyle = CaptionStyle()
) {
    val totalMs: Long get() = totalOf(clips)
}

val transNames = listOf("None", "Dip to black", "Dip to white", "Crossfade", "Dissolve", "Wipe left", "Wipe right", "Slide left", "Slide up", "Circle open", "Pixelize", "Zoom in", "Radial")
val xfadeNames = listOf("fade", "dissolve", "wipeleft", "wiperight", "slideleft", "slideup", "circleopen", "pixelize", "zoomin", "radial")
val blendNames = listOf("Normal", "Screen", "Add", "Lighten", "Multiply", "Darken")
val blendModes = listOf("normal", "screen", "addition", "lighten", "multiply", "darken")
val voiceFxNames = listOf("None", "Clean voice", "Telephone", "Robot", "Chipmunk", "Deep")
val fxNames = listOf("Blur", "Sharpen", "Vignette", "Grain", "Denoise", "Pixelate", "Cinema bars", "Negative")
val cutoutColors = intArrayOf(0x00B140, 0x0047BB, 0xFFFFFF, 0x000000)

val rampNames = listOf("Montage", "Hero", "Bullet", "Speed up", "Slow down")
private val rampCurves = listOf(
    floatArrayOf(2f, 2.5f, 1.5f, 0.6f, 0.4f, 0.4f, 0.6f, 1.5f, 2.5f, 2f),
    floatArrayOf(1f, 1f, 0.8f, 0.5f, 0.3f, 0.3f, 0.5f, 0.8f, 1f, 1f),
    floatArrayOf(2f, 2f, 1.2f, 0.5f, 0.25f, 0.25f, 0.5f, 1.2f, 2f, 2f),
    floatArrayOf(0.5f, 0.5f, 0.75f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f),
    floatArrayOf(4f, 3.5f, 3f, 2.5f, 2f, 1.5f, 1f, 0.75f, 0.5f, 0.5f)
)

private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
private val resNames = listOf("480p", "720p", "1080p")
private val propNames = listOf("Scale", "X", "Y", "Rotate", "Opacity", "Volume")
private val propRanges = listOf(0.3f to 3f, -1f to 1f, -1f to 1f, -180f to 180f, 0f to 1f, 0f to 2f)
private val easeNames = listOf("Linear", "Smooth", "Ease in", "Ease out")

fun clipDur(c: Clip): Long = ((c.outMs - c.inMs) / c.speed).toLong()

/** How much clip i overlaps the previous one (crossfade-style transitions need the full engine). */
fun overlapMs(clips: List<Clip>, i: Int): Long {
    if (!fullEngine || i <= 0 || i >= clips.size) return 0L
    val c = clips[i]
    if (c.trans < 3) return 0L
    return minOf(c.transMs, minOf(clipDur(clips[i - 1]), clipDur(c)) / 2)
}

fun clipStarts(clips: List<Clip>): List<Long> {
    val out = ArrayList<Long>()
    var prevEnd = 0L
    clips.forEachIndexed { i, c ->
        val st = if (i == 0) 0L else prevEnd - overlapMs(clips, i)
        out.add(st)
        prevEnd = st + clipDur(c)
    }
    return out
}

fun totalOf(clips: List<Clip>): Long = if (clips.isEmpty()) 0L else clipStarts(clips).last() + clipDur(clips.last())

fun toSrt(cues: List<Cue>) =
    cues.mapIndexed { i, c -> "${i + 1}\n${srtTime(c.start)} --> ${srtTime(c.end)}\n${c.text}\n" }.joinToString("\n")

private fun easeP(p: Float, mode: Int) = when (mode) { 0 -> p; 1 -> p * p * (3 - 2 * p); 2 -> p * p; else -> 1 - (1 - p) * (1 - p) }

/** Animated values at a time inside the clip (output time, after speed). Keys must be sorted. */
fun animAt(c: Clip, tMs: Long): Keyframe {
    val k = c.keys
    if (k.isEmpty()) return Keyframe(tMs)
    if (tMs <= k.first().tMs) return k.first().copy(tMs = tMs)
    if (tMs >= k.last().tMs) return k.last().copy(tMs = tMs)
    val i = k.indexOfLast { it.tMs <= tMs }
    val a = k[i]
    val b = k[i + 1]
    val p = easeP((tMs - a.tMs).toFloat() / (b.tMs - a.tMs), c.ease)
    fun l(x: Float, y: Float) = x + (y - x) * p
    return Keyframe(tMs, l(a.scale, b.scale), l(a.x, b.x), l(a.y, b.y), l(a.rot, b.rot), l(a.opacity, b.opacity), l(a.vol, b.vol))
}

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Everything in a project that can be undone. */
data class VideoState(
    val clips: List<Clip>, val titles: List<Title>, val overlays: List<Overlay>, val voices: List<Voice>,
    val voiceFx: Int, val captions: Boolean, val settings: ExportSettings
)

/** What the project looked like before a prompt, so the prompt can be taken back. */
data class PromptBackup(
    val clips: List<Clip>, val titles: List<Title>, val settings: ExportSettings, val captions: Boolean,
    val voiceFx: Int, val sel: Int, val after: List<Clip>
)

class VideoModel {
    val clips = mutableStateListOf<Clip>()
    val titles = mutableStateListOf<Title>()
    val overlays = mutableStateListOf<Overlay>()
    val voices = mutableStateListOf<Voice>()
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    var playing by mutableStateOf(false)
    var voiceFx by mutableStateOf(0)
    var selClip by mutableStateOf(0)
    var playMs by mutableStateOf(0f)
    var useCaptions by mutableStateOf(true)
    var status by mutableStateOf("Add a video to start.")
    var promptText by mutableStateOf("")
    val promptHistory = mutableStateListOf<String>()
    var promptNote by mutableStateOf("")
    var promptBackup by mutableStateOf<PromptBackup?>(null)

    private fun addTitleFrom(a: PromptAction) {
        val dur = ((a.args["dur"]?.toFloatOrNull() ?: 3f) * 1000f).toLong().coerceIn(500L, 10000L)
        val startArg = a.args["start"]?.toFloatOrNull()
        val tot = total()
        val start = when {
            startArg == null -> playMs.toLong()
            startArg < 0f -> max(0L, tot - dur)
            else -> (startArg * 1000f).toLong()
        }
        val y = (a.args["y"]?.toFloatOrNull() ?: 0.5f).coerceIn(0f, 1f)
        val size = (a.args["size"]?.toFloatOrNull() ?: 0.07f).coerceIn(0.03f, 0.2f)
        val color = PromptApply.colorOf(a.args["color"] ?: "") ?: Color.White
        titles.add(Title(a.text, start, start + dur, 0.5f, y, size, color))
    }

    /** "Speed up the last 3 seconds": splits the selected clip and changes the speed of that part only. */
    private fun speedPart(a: PromptAction, notes: MutableList<String>) {
        if (clips.isEmpty()) return
        val i = selClip.coerceIn(0, clips.lastIndex)
        val c = clips[i]
        if (c.reverse || c.freeze) {
            notes.add("A reversed or frozen clip cannot be split by time in a prompt.")
            return
        }
        val sec = a.args["sec"]?.toFloatOrNull() ?: return
        val span = (sec * 1000f * c.speed).toLong()
        val fromEnd = a.args["from"] == "last"
        val t = if (fromEnd) c.outMs - span else c.inMs + span
        if (t <= c.inMs + 200L || t >= c.outMs - 200L) {
            notes.add("That part is as long as the whole clip, so I did not split it. Change the speed of the clip instead.")
            return
        }
        val sp = a.value.coerceIn(0.25f, 4f)
        if (fromEnd) {
            clips[i] = c.copy(outMs = t, keys = emptyList())
            clips.add(i + 1, c.copy(inMs = t, speed = sp, trans = 0, keys = emptyList()))
            selClip = i + 1
        } else {
            clips[i] = c.copy(outMs = t, speed = sp, keys = emptyList())
            clips.add(i + 1, c.copy(inMs = t, trans = 0, keys = emptyList()))
            selClip = i
        }
    }

    // ---------- following an object ----------

    var trackX by mutableStateOf(0.5f)
    var trackY by mutableStateOf(0.5f)
    var showCross by mutableStateOf(false)

    /** Follows the point (trackX, trackY) from [fromMs] to [toMs] on the timeline. Returns null if no picture could be read. */
    suspend fun trackFrom(fromMs: Long, toMs: Long): List<TrackPt>? {
        val oa = frameRatio(settings)
        val span = max(1L, toMs - fromMs)
        val step = max(150L, span / 50L)
        val times = ArrayList<Long>()
        var at = fromMs
        while (at < toMs) { times.add(at); at += step }
        times.add(toMs)
        var prev: Gray? = null
        var px = 0
        var py = 0
        val raw = ArrayList<TrackPt>()
        for ((n, tl) in times.withIndex()) {
            val loc = locate(tl) ?: break
            val c = clips.getOrNull(loc.i) ?: break
            val img = frameAt(c.src, loc.srcMs) ?: break
            val g = Tracker.gray(img)
            val fa = img.width.toFloat() / img.height.toFloat()
            val before = prev
            if (before == null) {
                val (sx, sy) = Tracker.outToSrc(c, fa, oa, trackX, trackY)
                px = (sx * g.w).toInt().coerceIn(0, g.w - 1)
                py = (sy * g.h).toInt().coerceIn(0, g.h - 1)
            } else {
                val r = Tracker.search(before, px, py, g)
                px = r.first
                py = r.second
            }
            prev = g
            val (ox, oy) = Tracker.srcToOut(c, fa, oa, (px + 0.5f) / g.w, (py + 0.5f) / g.h)
            raw.add(TrackPt(tl, ox.coerceIn(0f, 1f), oy.coerceIn(0f, 1f)))
            progress = (n + 1f) / times.size
        }
        if (raw.size < 2) return null
        return raw.mapIndexed { i, pt ->
            if (i == 0 || i == raw.lastIndex) pt
            else TrackPt(pt.tMs, (raw[i - 1].x + 2 * pt.x + raw[i + 1].x) / 4f, (raw[i - 1].y + 2 * pt.y + raw[i + 1].y) / 4f)
        }
    }

    // ---------- beats and auto-cut ----------

    var beats by mutableStateOf<List<Long>>(emptyList())
    var beatInfo by mutableStateOf("")
    var beatEvery by mutableStateOf(1)

    /** Finds the beats of a music track (an index of [voices]) or, when [voice] is negative, of the sound of the selected clip. */
    suspend fun findBeats(voice: Int) {
        val pcm: ShortArray?
        val offset: Long
        val speed: Float
        if (voice >= 0) {
            val v = voices.getOrNull(voice) ?: return
            pcm = decodeAudioMono(v.wav, 0L, v.durMs)
            offset = v.startMs
            speed = 1f
        } else {
            val i = selClip
            val c = clips.getOrNull(i) ?: return
            if (!c.src.hasAudio || c.freeze || c.reverse) { beatInfo = "This clip has no sound to listen to."; return }
            pcm = decodeAudioMono(c.src.ref, c.inMs, c.outMs - c.inMs)
            offset = clipStarts(clips)[i]
            speed = c.speed
        }
        if (pcm == null) { beatInfo = "Could not read the sound."; return }
        val r = withContext(Dispatchers.Default) { Beats.detect(pcm) }
        if (r == null) { beatInfo = "No clear beat found."; beats = emptyList(); return }
        beats = r.beatsMs.map { offset + (it / speed).toLong() }.filter { it >= 0L }
        beatInfo = "${r.bpm.roundToInt()} BPM, ${beats.size} beats found."
    }

    private fun splitAt(ms: Long): Boolean {
        val loc = locate(ms) ?: return false
        val c = clips[loc.i]
        if (c.freeze || c.reverse) return false
        val t = loc.srcMs
        if (t <= c.inMs + 150L || t >= c.outMs - 150L) return false
        clips[loc.i] = c.copy(outMs = t)
        clips.add(loc.i + 1, c.copy(inMs = t, trans = 0, keys = emptyList()))
        return true
    }

    /** Splits the clips on every beat (or every second or fourth beat). */
    fun cutAtBeats() {
        if (beats.isEmpty() || clips.isEmpty()) return
        val end = total()
        var n = 0
        beats.filterIndexed { i, _ -> i % beatEvery == 0 }.forEach { b -> if (b in 1 until end && splitAt(b)) n++ }
        status = if (n > 0) "Cut the clips at $n beats." else "No beat fell inside a clip."
    }

    /** Builds a new edit of short shots from all clips, spread across the footage, with a new shot on every beat. */
    fun autoCutToBeats() {
        val pool = clips.filter { !it.freeze && !it.reverse }
        if (beats.size < 4 || pool.isEmpty()) { status = "Find the beats of a music track first."; return }
        val bounds = ArrayList<Long>()
        bounds.add(0L)
        beats.forEachIndexed { i, t -> if (i % beatEvery == 0 && t > bounds.last() + 250L) bounds.add(t) }
        val cut = bounds.take(201)
        val shots = cut.size - 1
        if (shots < 2) { status = "Too few beats to cut on."; return }
        val footage = pool.sumOf { it.outMs - it.inMs }
        val out = ArrayList<Clip>()
        for (k in 0 until shots) {
            val dur = cut[k + 1] - cut[k]
            var pos = footage * k / shots
            var c = pool[0]
            for (cand in pool) {
                c = cand
                val len = cand.outMs - cand.inMs
                if (pos < len) break
                pos -= len
            }
            val len = c.outMs - c.inMs
            val want = (dur * c.speed).toLong().coerceAtLeast(200L)
            val take = min(want, len)
            val start = (c.inMs + pos).coerceAtMost(c.outMs - take)
            val sp = if (take < want) (take.toFloat() / dur).coerceIn(0.25f, 4f) else c.speed
            out.add(c.copy(inMs = start, outMs = start + take, speed = sp, trans = 0, keys = emptyList()))
        }
        clips.clear()
        clips.addAll(out)
        selClip = 0
        playMs = 0f
        status = "Made $shots shots that change on the beat."
    }

    /** Cuts the long quiet parts out of the selected clip, keeping a little room around the speech. */
    suspend fun removeSilences(thresholdDb: Double) {
        val i = selClip
        val c = clips.getOrNull(i) ?: return
        if (!c.src.hasAudio || c.freeze || c.reverse) { status = "This clip has no sound to listen to."; return }
        val pcm = decodeAudioMono(c.src.ref, c.inMs, c.outMs - c.inMs)
        if (pcm == null) { status = "Could not read the sound."; return }
        val quiet = withContext(Dispatchers.Default) { Beats.silences(pcm, thresholdDb, 600L) }
        val pad = 120L
        val cuts = quiet.map { (a, b) -> (a + pad) to (b - pad) }.filter { it.second - it.first >= 250L }
        if (cuts.isEmpty()) { status = "No long quiet parts found."; return }
        val parts = ArrayList<Clip>()
        var from = c.inMs
        for ((a, b) in cuts) {
            val x = c.inMs + a
            val y = c.inMs + b
            if (x - from >= 200L) parts.add(c.copy(inMs = from, outMs = x, trans = if (parts.isEmpty()) c.trans else 0, keys = emptyList()))
            from = y
        }
        if (c.outMs - from >= 200L) parts.add(c.copy(inMs = from, outMs = c.outMs, trans = if (parts.isEmpty()) c.trans else 0, keys = emptyList()))
        if (parts.isEmpty()) { status = "The clip is all quiet, so nothing was cut."; return }
        val removed = cuts.sumOf { it.second - it.first }
        clips.removeAt(i)
        clips.addAll(i, parts)
        selClip = i
        playMs = playMs.coerceAtMost(max(0L, total()).toFloat())
        status = "Cut out ${removed / 100 / 10.0} seconds of silence."
    }

    /** Splits long clips into short shots and punches in on every second one, a common way to make a video feel faster. */
    private fun punchShots() {
        val out = ArrayList<Clip>()
        for (c in clips) {
            val len = clipDur(c)
            if (c.freeze || c.reverse || len < 4500L || out.size > 36) { out.add(c); continue }
            val parts = max(2, (len / 3200L).toInt())
            val piece = (c.outMs - c.inMs) / parts
            for (k in 0 until parts) {
                val a = c.inMs + piece * k
                val b = if (k == parts - 1) c.outMs else a + piece
                val shot = c.copy(inMs = a, outMs = b, trans = if (k == 0) c.trans else 0, keys = emptyList())
                val d = clipDur(shot)
                out.add(if (fullEngine && k % 2 == 1) shot.copy(keys = listOf(Keyframe(0L, scale = 1.12f), Keyframe(d, scale = 1.12f))) else shot)
            }
        }
        clips.clear()
        clips.addAll(out)
    }

    /** Looks at the video, works out what would make it work better for the goal, and makes those edits. */
    suspend fun autoEdit(g: Goal) {
        val before = PromptBackup(clips.toList(), titles.toList(), settings, useCaptions, voiceFx, selClip, emptyList())
        val notes = ArrayList<String>()
        notes.add("Goal: ${g.label}")

        // 1. Colour, clip by clip, from three frames of each.
        val count = clips.size
        var shown = false
        for (i in 0 until count) {
            val c = clips[i]
            val stats = ArrayList<FrameStats>()
            for (f in listOf(0.2f, 0.5f, 0.8f)) {
                val ms = c.inMs + ((c.outMs - c.inMs) * f).toLong()
                frameAt(c.src, ms)?.let { stats.add(analyseFrame(it)) }
            }
            progress = (i + 1f) / count
            if (stats.isEmpty()) continue
            val tune = Auto.tune(mergeStats(stats), g)
            clips[i] = c.copy(
                brightness = tune.brightness, contrast = tune.contrast, saturation = tune.saturation,
                filter = tune.videoFilter ?: c.filter, fx = c.fx or tune.fxOn
            )
            if (!shown) {
                notes.addAll(tune.notes.filter { !it.startsWith("Cropped") && !it.contains("look for the") })
                if (tune.videoFilter != null) notes.add("Used the ${videoFilterNames[tune.videoFilter]} look for the ${g.mood} mood.")
                shown = true
            }
        }
        if (count > 1) notes.add("The colour was set for each clip separately, from how that clip looks.")

        // 2. Shape for the place it will be shown.
        if (g.platform != null) {
            if (fullEngine) {
                val a = when (g.platform) { "instagram" -> 3; "short" -> 1; else -> 0 }
                settings = settings.copy(aspect = a, blurBg = a != 0)
                notes.add(when (g.platform) {
                    "instagram" -> "Set the frame to 4:5 for Instagram, with a blurred background."
                    "short" -> "Set the frame to 9:16 for short video, with a blurred background."
                    else -> "Set the frame to 16:9 for YouTube."
                })
            } else notes.add("Changing the frame shape needs the full video engine.")
        }

        // 3. Pace and motion.
        val fast = g.energetic || g.platform == "short"
        if (fast) {
            val n0 = clips.size
            punchShots()
            if (clips.size > n0) notes.add("Cut long clips into short shots and zoomed in on every second one, to keep it moving.")
        } else if (fullEngine && (g.attention || g.polish || g.mood == "cinematic")) {
            for (i in clips.indices) {
                val c = clips[i]
                if (c.keys.isEmpty() && !c.freeze) {
                    clips[i] = c.copy(keys = ClipAnim.keys(clipDur(c), if (g.attention || g.mood == "cinematic") 1 else 0, i == 0, i == clips.lastIndex, false))
                }
            }
            notes.add(if (g.attention || g.mood == "cinematic") "Added a slow zoom and a fade at the start and end." else "Added a fade at the start and end.")
        }
        if (fullEngine && clips.size >= 2 && !fast) {
            for (i in 1 until clips.size) if (!clips[i].freeze) clips[i] = clips[i].copy(trans = 3, transMs = 500L)
            notes.add("Joined the clips with soft crossfades.")
        }

        // 4. Steadier, tighter, on the beat.
        if (g.shaky) {
            if (fullEngine) {
                for (i in clips.indices) if (!clips[i].freeze) clips[i] = clips[i].copy(stab = 1)
                notes.add("Turned on stabilising for every clip.")
            } else notes.add("Stabilising needs the full video engine.")
        }
        if (g.talking) {
            var any = false
            for (i in clips.indices.reversed()) {
                if (i < clips.size && clips[i].src.hasAudio && !clips[i].freeze && !clips[i].reverse) {
                    val n0 = clips.size
                    val dur0 = totalOf(clips)
                    selClip = i
                    removeSilences(-38.0)
                    if (clips.size != n0 || totalOf(clips) != dur0) any = true
                }
            }
            notes.add(if (any) "Cut the long quiet parts out of the talking." else "I did not find long quiet parts to cut.")
        }
        if (g.beat) {
            if (voices.isEmpty()) notes.add("To cut on the beat, add a music track in the Audio tab and try again.")
            else {
                findBeats(0)
                if (beats.size >= 4) {
                    beatEvery = 2
                    autoCutToBeats()
                    notes.add("Cut the video on the beat of your music (${beatInfo.trim()}).")
                } else notes.add("I could not find a clear beat in the music.")
            }
        }
        selClip = selClip.coerceIn(0, max(0, clips.lastIndex))
        playMs = playMs.coerceAtMost(max(0L, total()).toFloat())
        promptBackup = before
        promptNote = notes.joinToString("\n") + "\nUse Undo last prompt to take it back."
        status = "Auto edit done."
    }

    /** The Auto edit button: decides the edits itself, and also does anything exact that the text asks for. */
    suspend fun smartPrompt(allClips: Boolean) {
        val text = promptText.trim()
        if (clips.isEmpty()) { promptNote = "Add a video first."; return }
        val goal = Auto.detect(text)
        val explicit = Prompt.parse(text).actions.any { !it.soft && it.key != "note" }
        if (goal == null && explicit) { runPrompt(allClips); return }
        if (text.isNotEmpty()) {
            promptHistory.remove(text)
            promptHistory.add(0, text)
            while (promptHistory.size > 6) promptHistory.removeAt(promptHistory.size - 1)
        }
        autoEdit(goal ?: Auto.general())
        if (explicit) {
            val keep = promptBackup
            val note = promptNote
            runPrompt(allClips)
            promptBackup = keep
            promptNote = note + "\n" + promptNote
        }
    }

    /** Does what the written prompt says: cutting the timeline, the clips, titles, captions, voice and export settings. */
    fun runPrompt(allClips: Boolean) {
        val text = promptText.trim()
        if (text.isEmpty()) return
        if (clips.isEmpty()) {
            promptNote = "Add a video first."
            return
        }
        val r = Prompt.parse(text)
        if (r.actions.isEmpty()) {
            promptNote = "I did not understand that. Try something like \u201Cslow motion and warm cinematic\u201D."
            return
        }
        promptHistory.remove(text)
        promptHistory.add(0, text)
        while (promptHistory.size > 6) promptHistory.removeAt(promptHistory.size - 1)
        val notes = ArrayList<String>()
        val saved = PromptBackup(clips.toList(), titles.toList(), settings, useCaptions, voiceFx, selClip, emptyList())
        val all = allClips || r.actions.any { it.key == "scope" }

        // What changes inside a clip is collected, then applied to the clip that is selected at that moment.
        val batch = ArrayList<PromptAction>()
        fun flush() {
            if (batch.isNotEmpty() && clips.isNotEmpty()) {
                val list = ArrayList(batch)
                val targets = if (all) clips.indices.toList() else listOf(selClip.coerceIn(0, clips.lastIndex))
                for (k in targets) clips[k] = PromptApply.clip(clips[k], list, fullEngine, notes, k == 0)
            }
            batch.clear()
        }

        // Everything is done in the order it was written, so "the first clip slow and the second clip fast" works.
        for (a in r.actions) {
            when (a.key) {
                "select" -> {
                    flush()
                    val n = a.value.toInt()
                    val idx = when {
                        n >= 0 -> n
                        n == -1 -> clips.lastIndex
                        n == -2 -> selClip + 1
                        else -> selClip - 1
                    }
                    if (idx in clips.indices) selClip = idx else notes.add("There is no such clip.")
                }
                "split" -> {
                    flush()
                    if (a.value >= 0f) playMs = a.value * 1000f
                    val n = clips.size
                    split()
                    if (clips.size == n) notes.add("I could not split there. The time has to be inside a clip.")
                }
                "delete" -> {
                    flush()
                    if (all) notes.add("I will not delete every clip at once.") else deleteClip()
                }
                "duplicate" -> {
                    flush()
                    duplicateClip()
                }
                "move" -> {
                    flush()
                    move(a.value.toInt())
                }
                "speedpart" -> {
                    flush()
                    speedPart(a, notes)
                }
                "text" -> addTitleFrom(a)
                "captions" -> useCaptions = a.value > 0f
                "voicefx" -> {
                    val idx = a.value.toInt().coerceIn(0, voiceFxNames.size - 1)
                    if (fullEngine || idx == 0 || idx == 4 || idx == 5) voiceFx = idx else notes.add("That voice effect needs the full video engine.")
                }
                "res" -> if (fullEngine) settings = settings.copy(res = a.value.toInt().coerceIn(0, 2)) else notes.add("Resolution needs the full video engine.")
                "fps" -> if (fullEngine) settings = settings.copy(fps = a.value.toInt()) else notes.add("Frame rate needs the full video engine.")
                "quality" -> if (fullEngine) settings = settings.copy(crf = a.value.toInt()) else notes.add("Quality needs the full video engine.")
                "ratio" -> {
                    if (fullEngine) {
                        var best = 0
                        var bestD = 99f
                        for (k in 0..3) {
                            val (fw, fh) = frameSize(1, k)
                            val d = abs(ln(a.value / (fw.toFloat() / fh.toFloat())))
                            if (d < bestD) { bestD = d; best = k }
                        }
                        settings = settings.copy(aspect = best)
                    } else {
                        notes.add("Tall and square video needs the full video engine.")
                    }
                }
                "freeze" -> {
                    flush()
                    freezeFrame(((a.args["dur"]?.toFloatOrNull() ?: 2f) * 1000f).toLong().coerceIn(500L, 8000L))
                }
                "ramp" -> {
                    flush()
                    speedRamp(a.value.toInt())
                }
                "blurbg" -> if (fullEngine) settings = settings.copy(blurBg = a.value > 0f) else notes.add("Blurred background needs the full video engine.")
                "scope" -> {}
                else -> batch.add(a)
            }
            if (clips.isEmpty()) break
        }
        flush()
        if (clips.isEmpty()) {
            promptNote = "Done. There are no clips left."
            promptBackup = saved.copy(after = clips.toList())
            return
        }
        selClip = selClip.coerceIn(0, clips.lastIndex)
        playMs = playMs.coerceAtMost(max(0L, total()).toFloat())

        val done = r.actions.filter { !it.soft && it.key != "note" && it.key != "scope" }.map { it.label }
        var msg = if (done.isEmpty()) "Nothing changed." else "Done: " + done.joinToString(", ") + (if (all) " (all clips)." else ".")
        val unique = notes.distinct()
        if (unique.isNotEmpty()) msg += " " + unique.joinToString(" ")
        if (r.unknown.isNotEmpty()) msg += " I did not understand: " + r.unknown.joinToString("; ") + "."
        promptNote = msg
        promptBackup = saved.copy(after = clips.toList())
    }

    fun undoPrompt() {
        val b = promptBackup ?: return
        promptBackup = null
        if (clips.toList() != b.after) {
            promptNote = "The clips changed since, so the prompt can not be undone."
            return
        }
        clips.clear()
        clips.addAll(b.clips)
        titles.clear()
        titles.addAll(b.titles)
        settings = b.settings
        useCaptions = b.captions
        voiceFx = b.voiceFx
        selClip = b.sel.coerceIn(0, max(0, clips.lastIndex))
        playMs = playMs.coerceAtMost(max(0L, total()).toFloat())
        promptNote = "Undone."
    }

    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var frame by mutableStateOf<ImageBitmap?>(null)
    var ovFrames by mutableStateOf<Map<Int, ImageBitmap>>(emptyMap())
    var settings by mutableStateOf(ExportSettings())

    // ---------- undo and redo ----------

    private val history = ArrayList<VideoState>()
    private var hIndex = -1
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun stateNow() = VideoState(clips.toList(), titles.toList(), overlays.toList(), voices.toList(), voiceFx, useCaptions, settings)

    /** Called a moment after the project stops changing. A drag of a slider becomes one step. */
    fun recordHistory(s: VideoState) {
        if (hIndex >= 0 && history[hIndex] == s) return
        while (history.size > hIndex + 1) history.removeAt(history.size - 1)
        history.add(s)
        hIndex = history.size - 1
        if (history.size > 60) {
            history.removeAt(0)
            hIndex--
        }
        canUndo = hIndex > 0
        canRedo = false
    }

    private fun restore(s: VideoState) {
        playing = false
        clips.clear()
        clips.addAll(s.clips)
        titles.clear()
        titles.addAll(s.titles)
        overlays.clear()
        overlays.addAll(s.overlays)
        voices.clear()
        voices.addAll(s.voices)
        voiceFx = s.voiceFx
        useCaptions = s.captions
        settings = s.settings
        selClip = selClip.coerceIn(0, max(0, clips.lastIndex))
        playMs = playMs.coerceAtMost(max(0L, total()).toFloat())
        canUndo = hIndex > 0
        canRedo = hIndex < history.size - 1
    }

    fun undo() {
        if (hIndex <= 0) return
        hIndex--
        restore(history[hIndex])
    }

    fun redo() {
        if (hIndex >= history.size - 1) return
        hIndex++
        restore(history[hIndex])
    }
    var cutOpts by mutableStateOf(CutoutOptions(quality = 0))

    fun total(): Long = totalOf(clips)

    /** Timeline time -> clip index, time inside the source video, time inside the clip. */
    fun locate(ms: Long): Loc? {
        if (clips.isEmpty()) return null
        val st = clipStarts(clips)
        for (i in clips.indices.reversed()) {
            if (ms >= st[i]) {
                val c = clips[i]
                val local = (ms - st[i]).coerceIn(0, clipDur(c))
                val off = (local * c.speed).toLong()
                val srcMs = when {
                    c.freeze -> c.inMs
                    c.reverse -> (c.outMs - off).coerceAtLeast(c.inMs)
                    else -> c.inMs + off
                }
                return Loc(i, srcMs, local)
            }
        }
        return Loc(0, clips[0].inMs, 0)
    }

    fun split() {
        val (i, t) = locate(playMs.toLong()) ?: return
        val c = clips[i]
        if (c.freeze) { status = "A frozen frame cannot be split. Change how long it is held instead."; return }
        if (t <= c.inMs + 200 || t >= c.outMs - 200) { status = "Move the playhead inside a clip to split it."; return }
        if (c.reverse) {
            // A reversed clip plays from its end to its start, so the part after the cut is the earlier part of the source.
            clips[i] = c.copy(inMs = t)
            clips.add(i + 1, c.copy(outMs = t))
        } else {
            clips[i] = c.copy(outMs = t)
            clips.add(i + 1, c.copy(inMs = t))
        }
        selClip = i + 1
    }

    /** Turns the selected clip around. Long clips are reversed in parts by the export, so there is no length limit. */
    fun toggleReverse() {
        val i = selClip
        val c = clips.getOrNull(i) ?: return
        if (c.freeze) { status = "A frozen frame has nothing to reverse."; return }
        clips[i] = c.copy(reverse = !c.reverse)
    }

    /** Holds the picture under the playhead for a while, splitting the clip around it. */
    fun freezeFrame(durMs: Long = 2000L) {
        val loc = locate(playMs.toLong()) ?: return
        val i = loc.i
        val c = clips[i]
        if (c.reverse) { status = "Freeze works on a clip that is not reversed."; return }
        if (c.freeze) { status = "This clip is already a frozen frame."; return }
        val t = loc.srcMs.coerceIn(c.inMs, c.outMs - 40L)
        val still = c.copy(inMs = t, outMs = t + durMs, speed = 1f, freeze = true, trans = 0, keys = emptyList(), stab = 0)
        when {
            t - c.inMs < 150L -> {
                clips.add(i, still.copy(trans = c.trans))
                clips[i + 1] = c.copy(trans = 0)
                selClip = i
            }
            c.outMs - t < 150L -> {
                clips.add(i + 1, still)
                selClip = i + 1
            }
            else -> {
                clips[i] = c.copy(outMs = t)
                clips.add(i + 1, still)
                clips.add(i + 2, c.copy(inMs = t, trans = 0, keys = emptyList()))
                selClip = i + 1
            }
        }
        status = "Froze a frame for ${durMs / 1000} seconds."
    }

    /** Splits the selected clip into ten parts that each play at their own speed, so the speed changes smoothly. */
    fun speedRamp(kind: Int) {
        if (clips.isEmpty()) return
        val i = selClip.coerceIn(0, clips.lastIndex)
        val c = clips[i]
        if (c.freeze) { status = "A frozen frame has no motion to ramp."; return }
        val srcLen = c.outMs - c.inMs
        if (srcLen < 2000L) { status = "Speed ramps need a clip of at least 2 seconds."; return }
        val curve = rampCurves[kind.coerceIn(0, rampCurves.lastIndex)]
        val n = curve.size
        val seg = srcLen / n
        val parts = ArrayList<Clip>()
        for (k in 0 until n) {
            // Parts are made in the order they play. A reversed clip plays its source from the end.
            val idx = if (c.reverse) n - 1 - k else k
            val a = c.inMs + seg * idx
            val b = if (idx == n - 1) c.outMs else a + seg
            parts.add(c.copy(inMs = a, outMs = b, speed = curve[k], trans = if (k == 0) c.trans else 0, keys = emptyList()))
        }
        clips.removeAt(i)
        clips.addAll(i, parts)
        selClip = i
        playMs = playMs.coerceAtMost(max(0L, total()).toFloat())
        status = "Speed ramp \u201C${rampNames[kind]}\u201D added. The clip is now ten parts; animation keyframes on it were cleared."
    }

    fun deleteClip() {
        if (clips.isEmpty()) return
        clips.removeAt(selClip.coerceIn(0, clips.lastIndex))
        selClip = selClip.coerceIn(0, max(0, clips.lastIndex))
        playMs = playMs.coerceAtMost(max(0L, total()).toFloat())
    }

    fun duplicateClip() {
        val c = clips.getOrNull(selClip) ?: return
        clips.add(selClip + 1, c)
        selClip += 1
    }

    fun move(d: Int) {
        val j = selClip + d
        if (selClip !in clips.indices || j !in clips.indices) return
        val c = clips.removeAt(selClip)
        clips.add(j, c)
        selClip = j
    }
}

private fun DrawScope.drawLabel(
    tm: TextMeasurer, text: String, xf: Float, yf: Float, sizeFrac: Float, color: Color, box: Boolean,
    alpha: Float = 1f, dx: Float = 0f, dy: Float = 0f, anchor: String? = null, cx: Float? = null, cy: Float? = null
) {
    val style = TextStyle(color = color.copy(alpha = color.alpha * alpha), fontSize = (sizeFrac * size.height).toSp(), fontWeight = FontWeight.Bold)
    val r = tm.measure(text, style)
    // A typed title is placed by the estimated width of its full text, the same way the export does it.
    val wide = if (anchor != null) (widestLineEm(anchor) * sizeFrac * size.height).toFloat() else r.size.width.toFloat()
    val tl = if (cx != null && cy != null) {
        Offset(cx * size.width - wide / 2f + dx, cy * size.height - r.size.height / 2f + dy)
    } else {
        Offset(xf * (size.width - wide) + dx, yf * (size.height - r.size.height) + dy)
    }
    if (box) drawRect(Color(0x99000000), tl, Size(r.size.width.toFloat(), r.size.height.toFloat()))
    drawText(r, topLeft = tl)
}

@Composable
fun VideoScreen(m: VideoModel, cap: CaptionsModel) {
    val tm = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(-1) }
    val add = rememberVideoPicker { s ->
        if (s.durationMs <= 0L) m.status = "Could not read that video."
        else {
            m.clips.add(Clip(s, 0, s.durationMs))
            if (m.clips.size == 1) m.selClip = 0
            m.status = "Added ${s.name}"
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { m.stateNow() }.collectLatest { snap ->
            delay(450)
            m.recordHistory(snap)
        }
    }
    LaunchedEffect(m.playMs, m.clips.toList(), m.overlays.toList()) {
        delay(150)
        val loc = m.locate(m.playMs.toLong())
        m.frame = if (loc != null) frameAt(m.clips[loc.i].src, loc.srcMs) else null
        val ms = m.playMs.toLong()
        val frames = mutableMapOf<Int, ImageBitmap>()
        m.overlays.forEachIndexed { k, o ->
            if (ms >= o.startMs && ms <= o.startMs + (o.outMs - o.inMs)) {
                frameAt(o.src, o.inMs + (ms - o.startMs))?.let { fr -> frames[k] = if (fullEngine && o.key > 0) keyPreview(fr, o.key, o.keySim) else fr }
            }
        }
        m.ovFrames = frames
    }

    LaunchedEffect(m.clips.toList()) {
        m.clips.toList().forEach { c ->
            val key = "${c.src.ref}@${c.inMs}"
            if (m.thumbs[key] == null) frameAt(c.src, c.inMs)?.let { m.thumbs[key] = it }
        }
    }

    LaunchedEffect(m.playing) {
        while (m.playing) {
            delay(250)
            val total = m.total().toFloat()
            if (m.playMs + 250f >= total) { m.playMs = total; m.playing = false } else m.playMs += 250f
        }
    }

    val export: () -> Unit = {
        scope.launch {
            m.busy = true; m.progress = 0f; m.status = "Exporting…"
            val project = VideoProject(
                m.clips.toList(), m.titles.toList(), if (m.useCaptions) cap.cues.toList() else emptyList(),
                m.overlays.toList(), m.voices.toList(), m.voiceFx, m.settings, cap.style
            )
            m.status = try { exportVideo(project) { m.progress = it } } catch (e: Throwable) { "Export failed: ${e.message}" }
            m.busy = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 820.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoHeader(m, add, export)
                    VideoStage(m, cap, tm, add, Modifier.weight(1f))
                    Transport(m)
                    Timeline(m, add) { tool = it }
                }
                VideoInspector(m, cap, scope, tool.coerceAtLeast(0), { tool = it }, export, Modifier.width(400.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoHeader(m, add, export)
                VideoStage(m, cap, tm, add, Modifier.weight(1f))
                Transport(m)
                Timeline(m, add) { tool = it }
                if (tool < 0) {
                    ToolDock(Modifier.offset(x = 0.dp)) {
                        val has = m.clips.getOrNull(m.selClip) != null
                        if (has) {
                            DockItem(Ico.Cut, "Split", { m.split() })
                            DockItem(Ico.Copy, "Duplicate", { m.duplicateClip() })
                            DockItem(Ico.Trash, "Delete", { m.deleteClip() }, tint = Danger)
                            DockGap()
                        }
                        videoTabs.forEachIndexed { i, (ico, name) -> DockItem(ico, name, { tool = i }) }
                    }
                } else {
                    ToolSheet(videoTabs[tool].second, { tool = -1 }, 250.dp) { VideoToolContent(m, cap, scope, tool, export) }
                }
            }
        }
    }
}

@Composable
private fun VideoHeader(m: VideoModel, add: () -> Unit, export: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Video", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 2.dp))
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = add, enabled = !m.busy) { Text("Add clip") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = export, enabled = m.clips.isNotEmpty() && !m.busy) { Text("Export") }
        }
        if (m.busy) LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
        if (m.status.isNotEmpty() && m.clips.isNotEmpty()) Label(m.status)
    }
}

@Composable
private fun VideoStage(m: VideoModel, cap: CaptionsModel, tm: TextMeasurer, add: () -> Unit, modifier: Modifier = Modifier) {
    val curLoc = m.locate(m.playMs.toLong())
    val cur = curLoc?.let { m.clips[it.i] }
    val anim = if (cur != null && curLoc != null) animAt(cur, curLoc.localMs) else null
    val frameShape = RoundedCornerShape(4.dp)
    val ratio = frameRatio(m.settings)
    BoxWithConstraints(modifier.fillMaxWidth().background(Color(0xFF0B0A09)), contentAlignment = Alignment.Center) {
        val fitW = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
        Box(
            Modifier.width(fitW).aspectRatio(ratio).clip(frameShape).background(Color.Black).then(if (m.clips.isEmpty()) Modifier.cropMarks() else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (m.clips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No video yet", style = MaterialTheme.typography.titleLarge)
                    Label("Add a clip to start cutting.")
                    Button(onClick = add) { Text("Add a video") }
                }
            }
            m.frame?.let { f ->
                if (fullEngine && m.settings.blurBg) Image(f, null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop, alpha = 0.75f)
                Image(
                    f, null,
                    Modifier.fillMaxSize().graphicsLayer {
                        val sc = anim?.scale ?: 1f
                        scaleX = sc * (if (cur?.mirror == true) -1f else 1f)
                        scaleY = sc * (if (cur?.flipV == true) -1f else 1f)
                        rotationZ = anim?.rot ?: 0f
                        translationX = (anim?.x ?: 0f) * size.width
                        translationY = (anim?.y ?: 0f) * size.height
                        alpha = anim?.opacity ?: 1f
                    },
                    contentScale = ContentScale.Fit,
                    colorFilter = cur?.let { ColorFilter.colorMatrix(colorMatrixOf(EditState(brightness = it.brightness, contrast = it.contrast, saturation = it.saturation, filter = it.filter))) }
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val ms = m.playMs.toLong()
                if (cur != null && curLoc != null) {
                    val d = clipDur(cur)
                    val tl = curLoc.localMs
                    val next = m.clips.getOrNull(curLoc.i + 1)
                    var k = 1f
                    var type = 0
                    if (cur.trans in 1..2 && tl < cur.transMs) { k = tl.toFloat() / cur.transMs; type = cur.trans }
                    if (next != null && next.trans in 1..2 && tl > d - next.transMs) {
                        val kk = (d - tl).toFloat() / next.transMs
                        if (kk < k) { k = kk; type = next.trans }
                    }
                    if (type > 0) drawRect(if (type == 2) Color.White else Color.Black, alpha = 1f - k.coerceIn(0f, 1f))
                }
                m.overlays.forEachIndexed { k, o ->
                    val f = m.ovFrames[k]
                    if (f != null) {
                        val w = size.width * o.scale
                        val h = w * f.height / f.width
                        val tp = if (o.track.isNotEmpty() && o.blend == 0) trackAt(o.track, ms) else null
                        val cx = if (tp != null) tp.first * size.width else size.width / 2 + o.x * size.width
                        val cy = if (tp != null) tp.second * size.height else size.height / 2 + o.y * size.height
                        if (fullEngine && o.mask > 0) {
                            // A masked overlay is cut to a centred square first, then to the shape.
                            val sq = minOf(f.width, f.height)
                            clipPath(maskPath(o.mask, cx - w / 2, cy - w / 2, w)) {
                                drawImage(
                                    f, srcOffset = IntOffset((f.width - sq) / 2, (f.height - sq) / 2), srcSize = IntSize(sq, sq),
                                    dstOffset = IntOffset((cx - w / 2).toInt(), (cy - w / 2).toInt()), dstSize = IntSize(w.toInt(), w.toInt()), alpha = o.opacity
                                )
                            }
                        } else {
                            drawImage(f, dstOffset = IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()), alpha = o.opacity)
                        }
                    }
                }
                m.titles.filter { ms in it.startMs..it.endMs }.forEach { t ->
                    val tp = if (t.track.isNotEmpty()) trackAt(t.track, ms) else null
                    val dur = t.endMs - t.startMs
                    val inD = minOf(450L, dur / 3).coerceAtLeast(100L)
                    val outD = minOf(300L, dur / 4).coerceAtLeast(100L)
                    val p = ((ms - t.startMs).toFloat() / inD).coerceIn(0f, 1f)
                    val e = 1f - (1f - p) * (1f - p) * (1f - p)
                    val a = p * ((t.endMs - ms).toFloat() / outD).coerceIn(0f, 1f)
                    fun put(text: String, sz: Float, al: Float, dx: Float, dy: Float, anchor: String? = null) =
                        drawLabel(tm, text, t.x, t.y, sz, t.color, false, alpha = al, dx = dx, dy = dy, anchor = anchor, cx = tp?.first, cy = tp?.second)
                    if (!fullEngine || t.anim == 0) put(t.text, t.size, 1f, 0f, 0f)
                    else when (t.anim) {
                        2 -> put(t.text, t.size, a, 0f, (1f - e) * size.height * 0.06f)
                        3 -> put(t.text, t.size, a, 0f, -(1f - e) * size.height * 0.06f)
                        4 -> put(t.text, t.size, a, -(1f - e) * size.width * 0.25f, 0f)
                        5 -> put(t.text, t.size * (0.5f + 0.5f * e), a, 0f, 0f)
                        6 -> {
                            val n = (((ms - t.startMs) / 80L).toInt() + 1).coerceIn(1, max(1, t.text.length))
                            put(t.text.take(n), t.size, 1f, 0f, 0f, anchor = t.text)
                        }
                        else -> put(t.text, t.size, a, 0f, 0f)
                    }
                }
                if (m.showCross) {
                    val cxp = m.trackX * size.width
                    val cyp = m.trackY * size.height
                    drawCircle(Color.White, 16f, Offset(cxp, cyp), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    drawLine(Color.White, Offset(cxp - 26f, cyp), Offset(cxp + 26f, cyp), 3f)
                    drawLine(Color.White, Offset(cxp, cyp - 26f), Offset(cxp, cyp + 26f), 3f)
                }
                if (m.useCaptions) {
                    val st = cap.style
                    cap.cues.firstOrNull { ms / 1000.0 in it.start..it.end }?.let {
                        drawLabel(tm, it.text, 0.5f, when (st.pos) { 0 -> 0.08f; 1 -> 0.5f; else -> 0.86f }, st.size, if (st.preset >= 2) Color(0xFFFFE600) else Color.White, st.preset == 0 && st.box)
                    }
                }
            }
        }

    }
}

private val videoTabs = listOf(Ico.Clip to "Edit", Ico.Sparkle to "Prompt", Ico.Text to "Text", Ico.Layers to "Overlay", Ico.Music to "Audio", Ico.Grid to "Templates", Ico.Export to "Export")

@Composable
private fun VideoInspector(
    m: VideoModel, cap: CaptionsModel, scope: kotlinx.coroutines.CoroutineScope,
    tab: Int, onTab: (Int) -> Unit, export: () -> Unit, modifier: Modifier
) {
    Column(modifier.glass(RoundedCornerShape(22.dp))) {
        IconTabs(videoTabs, tab, onTab, Modifier.fillMaxWidth().padding(6.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VideoToolContent(m, cap, scope, tab, export)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun Clip.withKey(k: Int, nk: Keyframe) = copy(keys = keys.toMutableList().also { it[k] = nk })
private fun getProp(k: Keyframe, p: Int) = when (p) { 0 -> k.scale; 1 -> k.x; 2 -> k.y; 3 -> k.rot; 4 -> k.opacity; else -> k.vol }
private fun setProp(k: Keyframe, p: Int, v: Float) = when (p) {
    0 -> k.copy(scale = v); 1 -> k.copy(x = v); 2 -> k.copy(y = v); 3 -> k.copy(rot = v); 4 -> k.copy(opacity = v); else -> k.copy(vol = v)
}

@Composable
private fun ClipPanel(m: VideoModel, scope: kotlinx.coroutines.CoroutineScope) {
    val i = m.selClip
    val c = m.clips.getOrNull(i)
    if (c == null) { Label("Add a video, then tap a clip on the timeline."); return }
    var bgSel by remember { mutableStateOf(0) }
    val durMax = max(1f, c.src.durationMs.toFloat())

    Label("Animation")
    Chips {
        listOf("Slow zoom in", "Slow zoom out", "Fade in", "Fade out", "Fade in and out", "Pop in", "Remove").forEachIndexed { n, name ->
            OutlinedButton(
                onClick = {
                    val d = clipDur(c)
                    val keys = when (n) {
                        0 -> ClipAnim.keys(d, 1, false, false, false)
                        1 -> ClipAnim.keys(d, -1, false, false, false)
                        2 -> ClipAnim.keys(d, 0, true, false, false)
                        3 -> ClipAnim.keys(d, 0, false, true, false)
                        4 -> ClipAnim.keys(d, 0, true, true, false)
                        5 -> ClipAnim.keys(d, 0, false, false, true)
                        else -> emptyList()
                    }
                    m.clips[i] = c.copy(keys = keys)
                },
                enabled = fullEngine
            ) { Text(name) }
        }
    }
    if (!fullEngine) Label("Animations need the full video engine.")

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { m.split() }) { Text("Split at playhead") }
        OutlinedButton(onClick = { m.duplicateClip() }) { Text("Duplicate") }
        OutlinedButton(onClick = { m.move(-1) }) { Text("Move left") }
        OutlinedButton(onClick = { m.move(1) }) { Text("Move right") }
        DangerButton(onClick = { m.deleteClip() }) { Text("Delete") }
    }
    if (c.freeze) {
        Label("This clip is a frozen frame.")
        Param("Hold for", (c.outMs - c.inMs).toFloat(), 500f..8000f, "${(c.outMs - c.inMs) / 100 / 10.0} s", { v -> m.clips[i] = c.copy(outMs = c.inMs + v.toLong()) })
    } else {
        Param("Trim start", c.inMs.toFloat(), 0f..durMax, mmss(c.inMs), { v -> m.clips[i] = c.copy(inMs = v.toLong().coerceAtMost(c.outMs - 200)) })
        Param("Trim end", c.outMs.toFloat(), 0f..durMax, mmss(c.outMs), { v -> m.clips[i] = c.copy(outMs = v.toLong().coerceAtLeast(c.inMs + 200)) })
        Label("Speed")
        Chips { speeds.forEach { s -> FilterChip(selected = c.speed == s, onClick = { m.clips[i] = c.copy(speed = s) }, label = { Text("${s}x") }) } }
    }

    Label("Speed ramp: the speed changes smoothly inside the clip")
    Chips { rampNames.forEachIndexed { k, name -> OutlinedButton(onClick = { m.speedRamp(k) }, enabled = !c.freeze) { Text(name) } } }
    Label("Freeze frame: hold the picture under the playhead for two seconds")
    OutlinedButton(onClick = { m.freezeFrame() }, enabled = fullEngine && !c.freeze && !c.reverse) { Text("Freeze frame at playhead") }
    if (fullEngine) {
        Label("Reverse and stabilise (applied when you export)")
        Chips {
            FilterChip(selected = c.reverse, onClick = { m.toggleReverse() }, label = { Text("Reverse") })
            listOf("Steady off", "Steady", "Steady strong").forEachIndexed { n, name ->
                FilterChip(selected = c.stab == n && !c.freeze, onClick = { if (!c.freeze) m.clips[i] = c.copy(stab = n) }, label = { Text(name) })
            }
        }
        Label("Steady removes small hand shake. It cannot fix a very shaky video. Long clips are reversed in parts, so the export may take longer.")
    } else {
        Label("Freeze frame, reverse and stabilise need the full video engine.")
    }
    if (fullEngine) Param("Clip volume", c.gain, 0f..2f, "${(c.gain * 100).roundToInt()}%", { v -> m.clips[i] = c.copy(gain = v) })

    Label("Filter")
    Chips { videoFilterNames.forEachIndexed { f, n -> FilterChip(selected = c.filter == f, onClick = { m.clips[i] = c.copy(filter = f) }, label = { Text(n) }) } }
    Param("Brightness", c.brightness, -0.4f..0.4f, "${(c.brightness * 100).roundToInt()}", { v -> m.clips[i] = c.copy(brightness = v) })
    Param("Contrast", c.contrast, 0.5f..1.5f, "${(c.contrast * 100).roundToInt()}%", { v -> m.clips[i] = c.copy(contrast = v) })
    Param("Saturation", c.saturation, 0f..2f, "${(c.saturation * 100).roundToInt()}%", { v -> m.clips[i] = c.copy(saturation = v) })
    Chips {
        FilterChip(selected = c.mirror, onClick = { m.clips[i] = c.copy(mirror = !c.mirror) }, label = { Text("Mirror") })
        FilterChip(selected = c.flipV, onClick = { m.clips[i] = c.copy(flipV = !c.flipV) }, label = { Text("Flip upside down") })
    }
    Param("Crop left and right", c.cropX, 0f..0.4f, "${(c.cropX * 100).roundToInt()}%", { v -> m.clips[i] = c.copy(cropX = v) })
    Param("Crop top and bottom", c.cropY, 0f..0.4f, "${(c.cropY * 100).roundToInt()}%", { v -> m.clips[i] = c.copy(cropY = v) })

    if (fullEngine) {
        Label("Effects (applied when you export)")
        Chips {
            fxNames.forEachIndexed { b, name ->
                val bit = 1 shl b
                FilterChip(selected = c.fx and bit != 0, onClick = { m.clips[i] = c.copy(fx = c.fx xor bit) }, label = { Text(name) })
            }
        }
        Label("Remove the background of this clip and fill it with a colour (slow)")
        Chips {
            matteQualityNames.take(2).forEachIndexed { q, n ->
                FilterChip(selected = m.cutOpts.quality == q, onClick = { m.cutOpts = m.cutOpts.copy(quality = q) }, label = { Text("$n · ${matteQualityNotes[q]}") })
            }
        }
        Chips {
            listOf("Green", "Blue", "White", "Black").forEachIndexed { idx, name ->
                FilterChip(selected = bgSel == idx, onClick = { bgSel = idx }, label = { Text(name) })
            }
        }
        Button(
            enabled = !m.busy,
            onClick = {
                scope.launch {
                    m.busy = true; m.progress = 0f
                    m.status = "Removing the background frame by frame. Keep the app open…"
                    val r = try { cutoutVideo(c.src, c.inMs, c.outMs, bgSel, m.cutOpts) { m.progress = it } } catch (e: Throwable) { null }
                    val now = m.clips.getOrNull(i)
                    if (r != null && now != null) {
                        m.clips[i] = now.copy(src = r, inMs = 0, outMs = r.durationMs)
                        m.status = "Background removed."
                    } else m.status = "Could not remove the background. Check that you were online the first time."
                    m.busy = false
                }
            }
        ) { Text("Remove background from clip") }
    }

    Label("Transition into this clip")
    Chips {
        transNames.take(if (fullEngine) transNames.size else 3).forEachIndexed { t, name ->
            FilterChip(selected = c.trans == t, onClick = { m.clips[i] = c.copy(trans = t) }, label = { Text(name) })
        }
    }
    if (c.trans > 0) Param("Transition length", c.transMs.toFloat(), 200f..1500f, "${c.transMs} ms", { v -> m.clips[i] = c.copy(transMs = v.toLong()) })

    KeyframeEditor(m, i, c)
}

@Composable
private fun KeyframeEditor(m: VideoModel, i: Int, c: Clip) {
    var prop by remember { mutableStateOf(0) }
    Label("Keyframes: animate scale, position, rotation, opacity and volume")
    Chips {
        easeNames.forEachIndexed { e, name ->
            FilterChip(selected = c.ease == e, onClick = { m.clips[i] = c.copy(ease = e) }, label = { Text(name) })
        }
    }
    Button(onClick = {
        val loc = m.locate(m.playMs.toLong())
        if (loc == null || loc.i != i) m.status = "Move the playhead onto this clip first."
        else {
            val key = animAt(c, loc.localMs).copy(tMs = loc.localMs)
            val list = c.keys.filter { kotlin.math.abs(it.tMs - key.tMs) > 80 } + key
            m.clips[i] = c.copy(keys = list.sortedBy { it.tMs })
        }
    }) { Text("Add keyframe at playhead") }
    if (!fullEngine) Label("Opacity and volume keyframes need the full engine.")
    if (c.keys.isEmpty()) return

    Chips { propNames.forEachIndexed { p, name -> FilterChip(selected = prop == p, onClick = { prop = p }, label = { Text(name) }) } }
    val playLocal = m.locate(m.playMs.toLong())?.takeIf { it.i == i }?.localMs
    val cNow by rememberUpdatedState(c)
    var drag by remember { mutableStateOf(-1) }
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).glass(RoundedCornerShape(16.dp), 0.7f).pointerInput(prop) {
            detectDragGestures(
                onDragStart = { pos ->
                    val cc = cNow
                    val dur = clipDur(cc).coerceAtLeast(1)
                    val (lo, hi) = propRanges[prop]
                    var best = -1
                    var bd = 60f
                    cc.keys.forEachIndexed { idx, k ->
                        val kx = k.tMs.toFloat() / dur * size.width
                        val ky = size.height - ((getProp(k, prop) - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
                        val dd = hypot(pos.x - kx, pos.y - ky)
                        if (dd < bd) { bd = dd; best = idx }
                    }
                    drag = best
                },
                onDrag = { change, _ ->
                    change.consume()
                    val cc = cNow
                    val d = drag
                    if (d in cc.keys.indices) {
                        val dur = clipDur(cc).coerceAtLeast(1)
                        val (lo, hi) = propRanges[prop]
                        val lower = if (d > 0) cc.keys[d - 1].tMs + 50 else 0L
                        val upper = if (d < cc.keys.lastIndex) cc.keys[d + 1].tMs - 50 else dur
                        val t = (change.position.x / size.width * dur).toLong().coerceIn(lower, maxOf(lower, upper))
                        val v = lo + (1f - change.position.y / size.height).coerceIn(0f, 1f) * (hi - lo)
                        m.clips[i] = cc.withKey(d, setProp(cc.keys[d], prop, v).copy(tMs = t))
                    }
                },
                onDragEnd = { drag = -1 },
                onDragCancel = { drag = -1 }
            )
        }
    ) {
        val dur = clipDur(c).coerceAtLeast(1)
        val (lo, hi) = propRanges[prop]
        fun px(t: Long) = t.toFloat() / dur * size.width
        fun py(v: Float) = size.height - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
        var prev: Offset? = null
        for (st in 0..60) {
            val t = dur * st / 60
            val o = Offset(px(t), py(getProp(animAt(c, t), prop)))
            prev?.let { drawLine(Accent, it, o, 3f) }
            prev = o
        }
        c.keys.forEach { drawCircle(Color.White, 9f, Offset(px(it.tMs), py(getProp(it, prop)))) }
        if (playLocal != null) drawLine(Color.White.copy(alpha = 0.5f), Offset(px(playLocal), 0f), Offset(px(playLocal), size.height), 2f)
    }
    Label("Drag the dots to change when a keyframe happens and its value.")
    c.keys.forEachIndexed { k, key ->
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.8f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Keyframe at ${mmss(key.tMs)}", color = TextHi, fontWeight = FontWeight.SemiBold)
            Param("Scale", key.scale, 0.3f..3f, "${(key.scale * 100).roundToInt()}%", { v -> m.clips[i] = c.withKey(k, key.copy(scale = v)) })
            Param("Position X", key.x, -1f..1f, "${(key.x * 100).roundToInt()}", { v -> m.clips[i] = c.withKey(k, key.copy(x = v)) })
            Param("Position Y", key.y, -1f..1f, "${(key.y * 100).roundToInt()}", { v -> m.clips[i] = c.withKey(k, key.copy(y = v)) })
            Param("Rotation", key.rot, -180f..180f, "${key.rot.roundToInt()}°", { v -> m.clips[i] = c.withKey(k, key.copy(rot = v)) })
            Param("Opacity", key.opacity, 0f..1f, "${(key.opacity * 100).roundToInt()}%", { v -> m.clips[i] = c.withKey(k, key.copy(opacity = v)) })
            Param("Volume", key.vol, 0f..2f, "${(key.vol * 100).roundToInt()}%", { v -> m.clips[i] = c.withKey(k, key.copy(vol = v)) })
            DangerButton(onClick = { m.clips[i] = c.copy(keys = c.keys.filterIndexed { x, _ -> x != k }) }) { Text("Delete keyframe") }
        }
    }
}

@Composable
private fun TextPanel(m: VideoModel, cap: CaptionsModel) {
    Button(onClick = { m.titles.add(Title("Your title", m.playMs.toLong(), m.playMs.toLong() + 3000, 0.5f, 0.5f, 0.07f, Color.White)) }) { Text("Add title at playhead") }
    Label("Title styles")
    Chips {
        listOf("Big title", "Lower third", "Caption", "Top tag").forEachIndexed { n, name ->
            OutlinedButton(onClick = {
                val at = m.playMs.toLong()
                val y = when (n) { 0 -> 0.5f; 1 -> 0.8f; 2 -> 0.9f; else -> 0.12f }
                val size = when (n) { 0 -> 0.13f; 1 -> 0.065f; 2 -> 0.045f; else -> 0.055f }
                val color = if (n == 3) Color(0xFFFFC145) else Color.White
                m.titles.add(Title("Your title", at, at + 3000L, 0.5f, y, size, color))
            }) { Text(name) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = m.useCaptions, onCheckedChange = { m.useCaptions = it })
        Spacer(Modifier.width(10.dp))
        Text("Burn in captions from the Captions tab (${cap.cues.size})")
    }
    if (m.useCaptions) CaptionStyleControls(cap)
    m.titles.forEachIndexed { k, t ->
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.8f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(value = t.text, onValueChange = { v -> m.titles[k] = t.copy(text = v) }, modifier = Modifier.fillMaxWidth())
            Param("Appears at", t.startMs.toFloat(), 0f..max(1f, m.total().toFloat()), mmss(t.startMs), { v ->
                val len = t.endMs - t.startMs
                m.titles[k] = t.copy(startMs = v.toLong(), endMs = v.toLong() + len)
            })
            Param("Stays for", (t.endMs - t.startMs).toFloat(), 500f..10000f, "${(t.endMs - t.startMs) / 1000}s", { v -> m.titles[k] = t.copy(endMs = t.startMs + v.toLong()) })
            Chips {
                listOf("Top" to 0.1f, "Middle" to 0.5f, "Bottom" to 0.88f).forEach { (n, y) ->
                    FilterChip(selected = t.y == y, onClick = { m.titles[k] = t.copy(y = y) }, label = { Text(n) })
                }
            }
            Param("Size", t.size, 0.03f..0.2f, "${(t.size * 100).roundToInt()}", { v -> m.titles[k] = t.copy(size = v) })
            if (fullEngine) {
                Label("Animation")
                Chips { titleAnimNames.forEachIndexed { a, name -> FilterChip(selected = t.anim == a, onClick = { m.titles[k] = t.copy(anim = a) }, label = { Text(name) }) } }
                TrackControls(m, t.startMs, t.endMs, t.track.isNotEmpty()) { r -> m.titles.getOrNull(k)?.let { m.titles[k] = it.copy(track = r ?: emptyList()) } }
            }
            ColorDots(t.color) { c -> m.titles[k] = t.copy(color = c) }
            DangerButton(onClick = { m.titles.removeAt(k) }) { Text("Delete title") }
        }
    }
}

@Composable
private fun OverlayPanel(m: VideoModel) {
    val add = rememberVideoPicker { src ->
        if (src.durationMs > 0L) m.overlays.add(Overlay(src, 0, minOf(src.durationMs, 10000L), m.playMs.toLong()))
        else m.status = "Could not read that video."
    }
    if (!fullEngine) {
        Label("Overlay tracks and picture-in-picture need the full engine, which this build does not include.")
        return
    }
    Button(onClick = add) { Text("Add overlay video at playhead") }
    m.overlays.forEachIndexed { k, o ->
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.8f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${o.src.name}: starts ${mmss(o.startMs)}, plays ${(o.outMs - o.inMs) / 1000}s", color = TextHi, fontWeight = FontWeight.SemiBold)
            Param("Start", o.startMs.toFloat(), 0f..max(1f, m.total().toFloat()), mmss(o.startMs), { v -> m.overlays[k] = o.copy(startMs = v.toLong()) })
            Param("Length", (o.outMs - o.inMs).toFloat(), 500f..max(600f, (o.src.durationMs - o.inMs).toFloat()), "${(o.outMs - o.inMs) / 1000}s", { v -> m.overlays[k] = o.copy(outMs = o.inMs + v.toLong()) })
            Param("Position X", o.x, -1f..1f, "${(o.x * 100).roundToInt()}", { v -> m.overlays[k] = o.copy(x = v) })
            Param("Position Y", o.y, -1f..1f, "${(o.y * 100).roundToInt()}", { v -> m.overlays[k] = o.copy(y = v) })
            Param("Size", o.scale, 0.1f..1f, "${(o.scale * 100).roundToInt()}%", { v -> m.overlays[k] = o.copy(scale = v) })
            Param("Opacity", o.opacity, 0f..1f, "${(o.opacity * 100).roundToInt()}%", { v -> m.overlays[k] = o.copy(opacity = v) })
            Param("Crop left and right", o.cropX, 0f..0.4f, "${(o.cropX * 100).roundToInt()}%", { v -> m.overlays[k] = o.copy(cropX = v) })
            Param("Crop top and bottom", o.cropY, 0f..0.4f, "${(o.cropY * 100).roundToInt()}%", { v -> m.overlays[k] = o.copy(cropY = v) })
            Chips {
                blendNames.forEachIndexed { b, name ->
                    FilterChip(selected = o.blend == b, onClick = { m.overlays[k] = o.copy(blend = b) }, label = { Text(name) })
                }
            }
            Label("Green screen: removes this colour from the overlay so the video below shows through. Works with every blend mode.")
            Chips { keyNames.forEachIndexed { n, name -> FilterChip(selected = o.key == n, onClick = { m.overlays[k] = o.copy(key = n) }, label = { Text(name) }) } }
            if (o.key > 0) Param("Key strength", o.keySim, 0.05f..0.6f, "${(o.keySim * 100).roundToInt()}%", { v -> m.overlays[k] = o.copy(keySim = v) })
            Label("Mask: cuts the overlay into a shape")
            Chips { maskNames.forEachIndexed { n, name -> FilterChip(selected = o.mask == n, onClick = { m.overlays[k] = o.copy(mask = n) }, label = { Text(name) }) } }
            if (o.key > 0) Label("The preview shows the key close to the export. The final result can differ a little at the edges.")
            if (o.blend == 0) TrackControls(m, o.startMs, o.startMs + (o.outMs - o.inMs), o.track.isNotEmpty()) { r -> m.overlays.getOrNull(k)?.let { m.overlays[k] = it.copy(track = r ?: emptyList()) } }
            else Label("Following an object needs the Normal blend.")
            DangerButton(onClick = { m.overlays.removeAt(k) }) { Text("Delete overlay") }
        }
    }
}

@Composable
private fun AudioPanel(m: VideoModel) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val addMusic = rememberAudioPicker { s ->
        if (s.durationMs > 0L) m.voices.add(Voice(s.name, m.playMs.toLong(), s.ref, s.durationMs, 0.8f))
        else m.status = "Could not read that audio file."
    }
    Label("Voice effect for the whole video")
    val shown = if (fullEngine) voiceFxNames.indices.toList() else listOf(0, 4, 5)
    Chips {
        shown.forEach { idx -> FilterChip(selected = m.voiceFx == idx, onClick = { m.voiceFx = idx }, label = { Text(voiceFxNames[idx]) }) }
    }
    if (!fullEngine) { Label("Music, voiceovers and more voice effects need the full engine."); return }

    Button(onClick = addMusic) { Text("Add music or sound at playhead") }
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Voiceover text") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(
        enabled = text.isNotBlank() && !m.busy,
        onClick = {
            scope.launch {
                m.status = "Making the voice…"
                val r = generateSpeech(text)
                if (r == null) m.status = "Could not make a voice on this device."
                else { m.voices.add(Voice(text, m.playMs.toLong(), r.first, r.second)); m.status = "Voiceover added." }
            }
        }
    ) { Text("Add voiceover at playhead") }
    m.voices.forEachIndexed { k, v ->
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.8f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${v.text.take(40)} · ${(v.durMs / 1000)}s", color = TextHi, fontWeight = FontWeight.SemiBold)
            Param("Starts at", v.startMs.toFloat(), 0f..max(1f, m.total().toFloat()), mmss(v.startMs), { x -> m.voices[k] = v.copy(startMs = x.toLong()) })
            Param("Volume", v.vol, 0f..2f, "${(v.vol * 100).roundToInt()}%", { x -> m.voices[k] = v.copy(vol = x) })
            DangerButton(onClick = { m.voices.removeAt(k) }) { Text("Delete") }
        }
    }

    Label("Beats and auto-cut: the sound is analysed on this device")
    Chips {
        m.voices.forEachIndexed { k, v ->
            OutlinedButton(enabled = !m.busy, onClick = { scope.launch { m.busy = true; m.status = "Listening…"; m.findBeats(k); m.status = m.beatInfo; m.busy = false } }) { Text("Beats: ${v.text.take(16)}") }
        }
        OutlinedButton(enabled = !m.busy && m.clips.isNotEmpty(), onClick = { scope.launch { m.busy = true; m.status = "Listening…"; m.findBeats(-1); m.status = m.beatInfo; m.busy = false } }) { Text("Beats: this clip's sound") }
    }
    if (m.beatInfo.isNotEmpty()) Label(m.beatInfo)
    if (m.beats.isNotEmpty()) {
        Label("Cut on every")
        Chips { listOf(1, 2, 4).forEach { n -> FilterChip(selected = m.beatEvery == n, onClick = { m.beatEvery = n }, label = { Text(if (n == 1) "beat" else "$n beats") }) } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { m.cutAtBeats() }) { Text("Cut clips at the beats") }
            OutlinedButton(onClick = { m.autoCutToBeats() }) { Text("Auto-cut to the beat") }
        }
        Label("Auto-cut makes short shots from all your clips, spread across the footage, with a new shot on each beat. Undo takes it back.")
    }
    Label("Remove the quiet parts of the selected clip (jump cuts for talking videos)")
    Chips {
        listOf("Gentle" to -45.0, "Normal" to -38.0, "Strong" to -32.0).forEach { (n, db) ->
            OutlinedButton(enabled = !m.busy && m.clips.isNotEmpty(), onClick = { scope.launch { m.busy = true; m.status = "Listening…"; m.removeSilences(db); m.busy = false } }) { Text(n) }
        }
    }
}

@Composable
private fun ExportPanel(m: VideoModel, export: () -> Unit) {
    val st = m.settings
    if (!fullEngine) Label("This build exports at 720p. Resolution, frame rate and quality need the full engine.")
    else {
        Label("Shape")
        Chips { aspectNames.forEachIndexed { a, n -> FilterChip(selected = st.aspect == a, onClick = { m.settings = st.copy(aspect = a) }, label = { Text(n) }) } }
        Chips { FilterChip(selected = st.blurBg, onClick = { m.settings = st.copy(blurBg = !st.blurBg) }, label = { Text("Blurred background") }) }
        Label("Fills the empty bars around the picture with a blurred, darker copy of it.")
        Label("Resolution")
        Chips { resNames.forEachIndexed { r, n -> FilterChip(selected = st.res == r, onClick = { m.settings = st.copy(res = r) }, label = { Text(n) }) } }
        Label("Frame rate")
        Chips { listOf(24, 30, 60).forEach { f -> FilterChip(selected = st.fps == f, onClick = { m.settings = st.copy(fps = f) }, label = { Text("$f fps") }) } }
        Label("Quality")
        Chips {
            listOf("Small" to 26, "Good" to 20, "High" to 16).forEach { (n, q) ->
                FilterChip(selected = st.crf == q, onClick = { m.settings = st.copy(crf = q) }, label = { Text(n) })
            }
        }
    }
    if (fullEngine && hasBasicEngine) {
        Label("Format")
        Chips {
            FilterChip(selected = !st.basic, onClick = { m.settings = st.copy(basic = false) }, label = { Text("MPEG-4, all features") })
            FilterChip(selected = st.basic, onClick = { m.settings = st.copy(basic = true) }, label = { Text("H.264, basic features") })
        }
        Label("H.264 uses your phone's own encoder. It cannot draw overlays, keyframes or most effects.")
    } else if (fullEngine) {
        Label("H.264 is used when Windows provides its encoder. Otherwise the file is MPEG-4.")
    }
    Button(onClick = export, enabled = m.clips.isNotEmpty() && !m.busy) { Text("Export MP4") }
}

@Composable
private fun TemplatesPanel(m: VideoModel) {
    if (m.clips.isEmpty()) {
        Label("Add a video first, then pick a template.")
        return
    }
    Label("A template sets the shape, the look, the transitions and a title in one tap. It replaces your titles and clip looks. Use Undo at the top to take it back.")
    if (!fullEngine) Label("Shape, transitions, animation and blurred background need the full video engine, so only the look and title are used here.")
    videoTemplates.forEach { t ->
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.8f).clickable(enabled = !m.busy) { t.apply(m); m.status = "Applied \u201C${t.name}\u201D." }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(t.name, style = MaterialTheme.typography.titleSmall)
            Label(t.desc)
        }
    }
}

@Composable
private fun TrackControls(m: VideoModel, startMs: Long, endMs: Long, tracked: Boolean, onTracked: (List<TrackPt>?) -> Unit) {
    val scope = rememberCoroutineScope()
    Label("Follow an object: put the cross on it at the playhead, then tap Track. Pick a spot with some detail, not a flat colour.")
    Chips { FilterChip(selected = m.showCross, onClick = { m.showCross = !m.showCross }, label = { Text("Show cross on preview") }) }
    Param("Cross left to right", m.trackX, 0f..1f, "${(m.trackX * 100).roundToInt()}%", { v -> m.trackX = v })
    Param("Cross top to bottom", m.trackY, 0f..1f, "${(m.trackY * 100).roundToInt()}%", { v -> m.trackY = v })
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !m.busy && m.clips.isNotEmpty(),
            onClick = {
                scope.launch {
                    m.busy = true; m.progress = 0f; m.status = "Following the object…"
                    val from = m.playMs.toLong().coerceIn(startMs, max(startMs, endMs))
                    val r = try { m.trackFrom(from, max(from + 300L, endMs)) } catch (e: Throwable) { null }
                    if (r != null) { onTracked(r); m.status = "Following the object (${r.size} points)." }
                    else m.status = "Could not follow that point. Try a spot with more detail."
                    m.busy = false
                }
            }
        ) { Text("Track from playhead") }
        if (tracked) OutlinedButton(onClick = { onTracked(null) }) { Text("Stop following") }
    }
    if (tracked) Label("Following an object. The position you set is ignored.")
}

@Composable
private fun VideoToolContent(m: VideoModel, cap: CaptionsModel, scope: kotlinx.coroutines.CoroutineScope, tool: Int, export: () -> Unit) {
    when (tool) {
        0 -> ClipPanel(m, scope)
        1 -> PromptPanel(m)
        2 -> TextPanel(m, cap)
        3 -> OverlayPanel(m)
        4 -> AudioPanel(m)
        5 -> TemplatesPanel(m)
        else -> ExportPanel(m, export)
    }
}

@Composable
private fun PromptPanel(m: VideoModel) {
    val scope = rememberCoroutineScope()
    if (m.clips.isEmpty()) {
        Label("Add a video first, then describe the edit you want.")
        return
    }
    PromptSheet(
        text = m.promptText, onText = { m.promptText = it }, hint = "For example: slow motion and warm cinematic",
        adds = videoAdds, looks = videoLooks, onLook = { m.promptText = it; m.runPrompt(false) },
        ideas = videoPromptIdeas, history = m.promptHistory.toList(), note = m.promptNote,
        help = videoHelp, canUndo = m.promptBackup != null, onUndo = { m.undoPrompt() }, auto = "video",
        onAuto = { scope.launch { m.busy = true; m.progress = 0f; m.status = "Looking at your video…"; m.smartPrompt(true); m.busy = false } }
    ) {
        Button(onClick = { m.runPrompt(false) }, enabled = m.promptText.isNotBlank()) { Text("Apply to this clip") }
        OutlinedButton(onClick = { m.runPrompt(true) }, enabled = m.promptText.isNotBlank()) { Text("Apply to all clips") }
    }
}

/** Timecode, play and undo, between the picture and the timeline. */
@Composable
private fun Transport(m: VideoModel) {
    val total = m.total()
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${mmss(m.playMs.toLong())} / ${mmss(total)}", color = TextLo,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (total > 0) Accent else Surface2)
                .clickable(enabled = total > 0) {
                    if (m.playing) m.playing = false
                    else { if (m.playMs >= total) m.playMs = 0f; m.playing = true }
                },
            contentAlignment = Alignment.Center
        ) { Glyph(if (m.playing) Ico.Pause else Ico.Play, Modifier.size(22.dp), if (total > 0) AccentInk else TextMute) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            IconBtn(Ico.Undo, { m.undo() }, m.canUndo && !m.busy)
            IconBtn(Ico.Redo, { m.redo() }, m.canRedo && !m.busy)
        }
    }
}

private const val DP_PER_SEC = 48f

private fun dpOf(ms: Long): Dp = (ms * DP_PER_SEC / 1000f).dp

/** A bar for a title, overlay or voice on its own row of the timeline. */
@Composable
private fun TrackBar(startMs: Long, durMs: Long, label: String, tone: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.offset(x = dpOf(startMs)).width(dpOf(durMs).coerceAtLeast(28.dp)).height(24.dp)
            .clip(shape).background(tone).border(1.dp, Line, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) { Text(label, Modifier.padding(horizontal = 6.dp), color = TextHi, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

/**
 * The timeline slides under a playhead that stays in the middle. Scrolling sets the time, and playing moves the timeline.
 * Titles, overlays and voices have their own rows above and below the clips; tap one to open its tool.
 */
@Composable
private fun Timeline(m: VideoModel, add: () -> Unit, openTool: (Int) -> Unit) {
    val density = LocalDensity.current
    val pxPerMs = with(density) { DP_PER_SEC.dp.toPx() } / 1000f
    val total = m.total()
    val scroll = rememberScrollState()
    val tm = rememberTextMeasurer()
    val starts = clipStarts(m.clips)

    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.collect { v ->
            if (scroll.isScrollInProgress) {
                m.playing = false
                m.playMs = (v / pxPerMs).coerceIn(0f, max(0L, m.total()).toFloat())
            }
        }
    }
    LaunchedEffect(m.playMs, scroll.maxValue) {
        if (!scroll.isScrollInProgress) {
            val target = (m.playMs * pxPerMs).roundToInt()
            if (abs(target - scroll.value) > 1) scroll.scrollTo(target)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 200.dp).background(Surface1)) {
        val half = maxWidth / 2
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Spacer(Modifier.width(half))
            Column(Modifier.width(dpOf(total).coerceAtLeast(1.dp) + 72.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                    val style = TextStyle(color = TextMute, fontSize = 10.sp)
                    var sec = 0
                    while (sec * 1000L <= total + 1000L) {
                        val x = sec * DP_PER_SEC.dp.toPx()
                        drawLine(Line, Offset(x, size.height - 5.dp.toPx()), Offset(x, size.height), 1f)
                        if (sec % 2 == 0) drawText(tm.measure(mmss(sec * 1000L), style), topLeft = Offset(x + 3.dp.toPx(), 0f))
                        sec++
                    }
                    m.beats.forEach { b ->
                        drawCircle(Accent, 2.dp.toPx(), Offset(b * pxPerMs, size.height - 2.5.dp.toPx()))
                    }
                }
                if (m.titles.isNotEmpty()) Box(Modifier.fillMaxWidth().height(24.dp)) {
                    m.titles.forEach { t -> TrackBar(t.startMs, t.endMs - t.startMs, t.text, Color(0xFF3A342B)) { openTool(2) } }
                }
                if (m.overlays.isNotEmpty()) Box(Modifier.fillMaxWidth().height(24.dp)) {
                    m.overlays.forEach { o -> TrackBar(o.startMs, o.outMs - o.inMs, o.src.name, Color(0xFF2B3733)) { openTool(3) } }
                }
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    m.clips.forEachIndexed { i, c ->
                        val on = i == m.selClip
                        val shape = RoundedCornerShape(4.dp)
                        val w = dpOf(clipDur(c)).coerceAtLeast(24.dp)
                        val thumb = m.thumbs["${c.src.ref}@${c.inMs}"]
                        Box(
                            Modifier.offset(x = dpOf(starts.getOrElse(i) { 0L })).width(w).fillMaxHeight().clip(shape).background(Surface3)
                                .border(if (on) 2.dp else 1.dp, if (on) TextHi else Line, shape).clickable { m.selClip = i }
                        ) {
                            if (thumb != null) Row(Modifier.fillMaxSize()) {
                                repeat(minOf(40, (w.value / 56f).toInt() + 1)) { Image(thumb, null, Modifier.width(56.dp).fillMaxHeight(), contentScale = ContentScale.Crop, alpha = 0.85f) }
                            }
                            val tag = buildString {
                                append(mmss(clipDur(c)))
                                if (c.speed != 1f) append("  ${c.speed}x")
                                if (c.reverse) append("  rev")
                                if (c.freeze) append("  hold")
                            }
                            Text(
                                tag, Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                                color = TextHi, style = MaterialTheme.typography.labelMedium, maxLines = 1
                            )
                        }
                    }
                    Box(
                        Modifier.offset(x = dpOf(total) + 8.dp).size(52.dp, 56.dp).clip(RoundedCornerShape(4.dp)).background(Surface3).clickable(onClick = add),
                        contentAlignment = Alignment.Center
                    ) { Glyph(Ico.Plus, Modifier.size(22.dp), TextHi) }
                }
                if (m.voices.isNotEmpty()) Box(Modifier.fillMaxWidth().height(24.dp)) {
                    m.voices.forEach { v -> TrackBar(v.startMs, v.durMs, v.text, Color(0xFF2E3140)) { openTool(4) } }
                }
            }
            Spacer(Modifier.width(half))
        }
        Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(TextHi))
    }
}
