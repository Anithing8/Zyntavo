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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

data class Source(val ref: String, val name: String, val durationMs: Long, val hasAudio: Boolean)
data class Keyframe(val tMs: Long, val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f, val rot: Float = 0f, val opacity: Float = 1f, val vol: Float = 1f)

data class Clip(
    val src: Source, val inMs: Long, val outMs: Long, val speed: Float = 1f, val filter: Int = 0,
    val brightness: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f,
    val mirror: Boolean = false, val flipV: Boolean = false, val cropX: Float = 0f, val cropY: Float = 0f,
    val gain: Float = 1f, val fx: Int = 0,
    val trans: Int = 0, val transMs: Long = 600L,
    val ease: Int = 1, val keys: List<Keyframe> = emptyList()
)

data class Loc(val i: Int, val srcMs: Long, val localMs: Long)
data class Title(val text: String, val startMs: Long, val endMs: Long, val x: Float, val y: Float, val size: Float, val color: Color)

data class Overlay(
    val src: Source, val inMs: Long, val outMs: Long, val startMs: Long,
    val x: Float = 0.3f, val y: Float = -0.3f, val scale: Float = 0.35f, val opacity: Float = 1f,
    val blend: Int = 0, val cropX: Float = 0f, val cropY: Float = 0f
)

/** A voiceover or music file mixed into the sound. */
data class Voice(val text: String, val startMs: Long, val wav: String, val durMs: Long, val vol: Float = 1f)

data class ExportSettings(val res: Int = 1, val fps: Int = 30, val crf: Int = 20, val basic: Boolean = false, val aspect: Int = 0)

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
                return Loc(i, c.inMs + (local * c.speed).toLong(), local)
            }
        }
        return Loc(0, clips[0].inMs, 0)
    }

    fun split() {
        val (i, t) = locate(playMs.toLong()) ?: return
        val c = clips[i]
        if (t <= c.inMs + 200 || t >= c.outMs - 200) { status = "Move the playhead inside a clip to split it."; return }
        clips[i] = c.copy(outMs = t)
        clips.add(i + 1, c.copy(inMs = t))
        selClip = i + 1
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

private fun DrawScope.drawLabel(tm: TextMeasurer, text: String, xf: Float, yf: Float, sizeFrac: Float, color: Color, box: Boolean) {
    val style = TextStyle(color = color, fontSize = (sizeFrac * size.height).toSp(), fontWeight = FontWeight.Bold)
    val r = tm.measure(text, style)
    val tl = Offset(xf * (size.width - r.size.width), yf * (size.height - r.size.height))
    if (box) drawRect(Color(0x99000000), tl, Size(r.size.width.toFloat(), r.size.height.toFloat()))
    drawText(r, topLeft = tl)
}

@Composable
fun VideoScreen(m: VideoModel, cap: CaptionsModel) {
    val tm = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
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
                frameAt(o.src, o.inMs + (ms - o.startMs))?.let { frames[k] = it }
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
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VideoHeader(m, add, export)
                    VideoStage(m, cap, tm, add)
                }
                VideoInspector(m, cap, scope, tab, { tab = it }, export, Modifier.width(400.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoHeader(m, add, export)
                VideoStage(m, cap, tm, add)
                VideoInspector(m, cap, scope, tab, { tab = it }, export, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun VideoHeader(m: VideoModel, add: () -> Unit, export: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Video", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 2.dp))
            Spacer(Modifier.width(4.dp))
            IconBtn(Ico.Undo, { m.undo() }, m.canUndo && !m.busy)
            IconBtn(Ico.Redo, { m.redo() }, m.canRedo && !m.busy)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = add, enabled = !m.busy) { Text("Add video") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = export, enabled = m.clips.isNotEmpty() && !m.busy) { Text("Export") }
        }
        if (m.busy) LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
        if (m.status.isNotEmpty() && m.clips.isNotEmpty()) Label(m.status)
    }
}

@Composable
private fun VideoStage(m: VideoModel, cap: CaptionsModel, tm: TextMeasurer, add: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val curLoc = m.locate(m.playMs.toLong())
        val cur = curLoc?.let { m.clips[it.i] }
        val anim = if (cur != null && curLoc != null) animAt(cur, curLoc.localMs) else null
        val frameShape = RoundedCornerShape(18.dp)
        val ratio = frameRatio(m.settings)
        val stageShape = if (ratio >= 1.7f) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.align(Alignment.CenterHorizontally).heightIn(max = 360.dp).aspectRatio(ratio)
        Box(
            stageShape.clip(frameShape).background(Color.Black).border(1.dp, Line, frameShape),
            contentAlignment = Alignment.Center
        ) {
            if (m.clips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(Ico.Video, Modifier.size(34.dp), TextLo)
                    Label("Your video appears here")
                    Button(onClick = add) { Text("Add a video") }
                }
            }
            m.frame?.let { f ->
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
                        val cx = size.width / 2 + o.x * size.width
                        val cy = size.height / 2 + o.y * size.height
                        drawImage(f, dstOffset = IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()), alpha = o.opacity)
                    }
                }
                m.titles.filter { ms in it.startMs..it.endMs }.forEach { drawLabel(tm, it.text, it.x, it.y, it.size, it.color, false) }
                if (m.useCaptions) {
                    val st = cap.style
                    cap.cues.firstOrNull { ms / 1000.0 in it.start..it.end }?.let {
                        drawLabel(tm, it.text, 0.5f, when (st.pos) { 0 -> 0.08f; 1 -> 0.5f; else -> 0.86f }, st.size, if (st.preset >= 2) Color(0xFFFFE600) else Color.White, st.preset == 0 && st.box)
                    }
                }
            }
        }

        val total = m.total()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Accent)
                    .clickable(enabled = total > 0) {
                        if (m.playing) m.playing = false
                        else { if (m.playMs >= total) m.playMs = 0f; m.playing = true }
                    },
                contentAlignment = Alignment.Center
            ) { Glyph(if (m.playing) Ico.Pause else Ico.Play, Modifier.size(22.dp), Color.White) }
            Spacer(Modifier.width(12.dp))
            Text("${mmss(m.playMs.toLong())} / ${mmss(total)}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            IconBtn(Ico.Cut, { m.split() }, m.clips.isNotEmpty())
            IconBtn(Ico.Copy, { m.duplicateClip() }, m.clips.isNotEmpty())
            IconBtn(Ico.Trash, { m.deleteClip() }, m.clips.isNotEmpty(), Danger)
        }
        if (total > 0) Slider(value = m.playMs, onValueChange = { m.playing = false; m.playMs = it }, valueRange = 0f..max(1f, total.toFloat()))

        if (m.clips.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(64.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.clips.forEachIndexed { i, c ->
                    val on = i == m.selClip
                    val shape = RoundedCornerShape(10.dp)
                    val thumb = m.thumbs["${c.src.ref}@${c.inMs}"]
                    Box(
                        Modifier.width((clipDur(c) / 1000f * 12f).coerceAtLeast(76f).dp).fillMaxHeight().clip(shape).background(Surface2)
                            .border(if (on) 2.dp else 1.dp, if (on) Accent else Line, shape).clickable { m.selClip = i }
                    ) {
                        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.8f)
                        Text("${i + 1}", Modifier.align(Alignment.TopStart).padding(6.dp), style = MaterialTheme.typography.labelMedium)
                        Text(mmss(clipDur(c)), Modifier.align(Alignment.BottomEnd).padding(6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private val videoTabs = listOf(Ico.Clip to "Clip", Ico.Text to "Text", Ico.Layers to "Overlay", Ico.Music to "Audio", Ico.Export to "Export")

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
            when (tab) {
                0 -> ClipPanel(m, scope)
                1 -> TextPanel(m, cap)
                2 -> OverlayPanel(m)
                3 -> AudioPanel(m)
                else -> ExportPanel(m, export)
            }
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

    Label("Edit this clip with a prompt")
    OutlinedTextField(
        value = m.promptText, onValueChange = { v -> m.promptText = v },
        modifier = Modifier.fillMaxWidth(), label = { Text("For example: slow motion and warm cinematic") }
    )
    if (m.promptText.isNotBlank()) {
        val pr = Prompt.parse(m.promptText)
        val sh = pr.actions.filter { !it.soft && it.key != "note" }
        if (sh.isNotEmpty()) Label("I will: " + sh.joinToString(", ") { it.label })
        else if (pr.actions.isEmpty()) Label("I do not understand this yet.")
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { m.runPrompt(false) }, enabled = m.promptText.isNotBlank()) { Text("Apply to this clip") }
        OutlinedButton(onClick = { m.runPrompt(true) }, enabled = m.promptText.isNotBlank()) { Text("Apply to all clips") }
        if (m.promptBackup != null) OutlinedButton(onClick = { m.undoPrompt() }) { Text("Undo prompt") }
    }
    if (m.promptNote.isNotEmpty()) Label(m.promptNote)
    Label("Quick looks for this clip")
    Chips { videoLooks.forEach { (name, text) -> OutlinedButton(onClick = { m.promptText = text; m.runPrompt(false) }) { Text(name) } } }
    Chips { videoPromptIdeas.forEach { idea -> OutlinedButton(onClick = { m.promptText = idea }) { Text(idea) } } }
    PromptHelp(videoHelp) { m.promptText = it }

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
    Param("Trim start", c.inMs.toFloat(), 0f..durMax, mmss(c.inMs), { v -> m.clips[i] = c.copy(inMs = v.toLong().coerceAtMost(c.outMs - 200)) })
    Param("Trim end", c.outMs.toFloat(), 0f..durMax, mmss(c.outMs), { v -> m.clips[i] = c.copy(outMs = v.toLong().coerceAtLeast(c.inMs + 200)) })
    Label("Speed")
    Chips { speeds.forEach { s -> FilterChip(selected = c.speed == s, onClick = { m.clips[i] = c.copy(speed = s) }, label = { Text("${s}x") }) } }
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
            Text("Keyframe at ${mmss(key.tMs)}", color = Accent, fontWeight = FontWeight.SemiBold)
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
            Text("${o.src.name}: starts ${mmss(o.startMs)}, plays ${(o.outMs - o.inMs) / 1000}s", color = Accent, fontWeight = FontWeight.SemiBold)
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
            Text("${v.text.take(40)} · ${(v.durMs / 1000)}s", color = Accent, fontWeight = FontWeight.SemiBold)
            Param("Starts at", v.startMs.toFloat(), 0f..max(1f, m.total().toFloat()), mmss(v.startMs), { x -> m.voices[k] = v.copy(startMs = x.toLong()) })
            Param("Volume", v.vol, 0f..2f, "${(v.vol * 100).roundToInt()}%", { x -> m.voices[k] = v.copy(vol = x) })
            DangerButton(onClick = { m.voices.removeAt(k) }) { Text("Delete") }
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
