@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zyntavo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import kotlin.math.min
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AppCtx {
    lateinit var ctx: Context

    /** The FFmpeg program built into the app by GitHub Actions, if it is there. */
    val ffmpegFile: File? by lazy {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (f.exists() && f.length() > 100_000) f else null
    }
}

/** With FFmpeg bundled the file is copied into the app, because FFmpeg cannot open content:// links. */
private fun importMedia(ctx: Context, uri: Uri, audio: Boolean): Source? {
    val r = MediaMetadataRetriever()
    return try {
        var ref = uri.toString()
        if (AppCtx.ffmpegFile != null) {
            val dir = File(ctx.cacheDir, "media").also { it.mkdirs() }
            val name = (uri.lastPathSegment ?: "media").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val f = File(dir, "${System.currentTimeMillis()}-$name")
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ref = f.absolutePath
        }
        if (ref.startsWith("/")) r.setDataSource(ref) else r.setDataSource(ctx, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val a = audio || r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        if (d <= 0L) null else Source(ref, uri.lastPathSegment ?: "media", d, a)
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}

@Composable
actual fun rememberVideoPicker(onPicked: (Source) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            Toast.makeText(ctx, "Opening video…", Toast.LENGTH_SHORT).show()
            val s = withContext(Dispatchers.IO) { importMedia(ctx, uri, false) }
            if (s != null) onPicked(s) else Toast.makeText(ctx, "Could not read that video.", Toast.LENGTH_SHORT).show()
        }
    }
    return { launcher.launch(arrayOf("video/*")) }
}

@Composable
actual fun rememberAudioPicker(onPicked: (Source) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val s = withContext(Dispatchers.IO) { importMedia(ctx, uri, true) }
            if (s != null) onPicked(s) else Toast.makeText(ctx, "Could not read that audio file.", Toast.LENGTH_SHORT).show()
        }
    }
    return { launcher.launch(arrayOf("audio/*")) }
}

actual suspend fun frameAt(src: Source, ms: Long): ImageBitmap? = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        if (src.ref.startsWith("/")) r.setDataSource(src.ref) else r.setDataSource(AppCtx.ctx, Uri.parse(src.ref))
        r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.asImageBitmap()
    } catch (e: Exception) { null } finally { r.release() }
}

private fun rgbRows(vararg r: Float): Effect {
    val m = floatArrayOf(r[0], r[3], r[6], 0f, r[1], r[4], r[7], 0f, r[2], r[5], r[8], 0f, 0f, 0f, 0f, 1f)
    return object : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = m
    }
}

private fun androidFilter(i: Int): List<Effect> = when (i) {
    1 -> listOf(RgbFilter.createGrayscaleFilter())
    2 -> listOf(rgbRows(.393f, .769f, .189f, .349f, .686f, .168f, .272f, .534f, .131f))
    3 -> listOf(rgbRows(1.1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, .9f))
    4 -> listOf(rgbRows(.9f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1.1f))
    5 -> {
        val s = 1.6f; val l = floatArrayOf(.2126f, .7152f, .0722f)
        val v = FloatArray(9) { k -> l[k % 3] * (1 - s) + if (k / 3 == k % 3) s else 0f }
        listOf(rgbRows(*v))
    }
    6 -> listOf(Contrast(-0.25f))
    7 -> listOf(RgbFilter.createGrayscaleFilter(), Contrast(0.4f))
    8 -> listOf(rgbRows(.5f, .5f, .1f, .3f, .6f, .1f, .2f, .4f, .3f), Contrast(-0.08f))
    9 -> listOf(rgbRows(1.12f, 0f, 0f, 0f, 1.08f, 0f, 0f, 0f, 1.1f), Contrast(0.12f))
    10 -> listOf(satRows(1.45f), Contrast(0.12f))
    11 -> listOf(rgbRows(1.15f, 0f, 0f, 0f, .95f, 0f, 0f, 0f, .8f))
    12 -> listOf(rgbRows(.9f, 0f, 0f, 0f, 1.1f, 0f, 0f, 0f, .9f))
    13 -> listOf(rgbRows(.85f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1.2f))
    14 -> listOf(satRows(.85f), Contrast(-0.15f))
    15 -> listOf(satRows(.7f), Contrast(0.35f))
    16 -> listOf(rgbRows(1.12f, 0f, -.04f, -.03f, 1f, .08f, -.1f, .12f, 1.12f))
    17 -> listOf(RgbFilter.createGrayscaleFilter(), rgbRows(.85f, 0f, 0f, 0f, .95f, 0f, 0f, 0f, 1.2f))
    18 -> listOf(rgbRows(1.1f, 0f, 0f, 0f, .95f, 0f, 0f, 0f, 1.02f))
    19 -> listOf(rgbRows(1.2f, 0f, 0f, 0f, 1.05f, 0f, 0f, 0f, .8f))
    20 -> listOf(rgbRows(.92f, 0f, 0f, 0f, .9f, 0f, 0f, 0f, .86f), Contrast(-0.12f))
    21 -> listOf(Contrast(-0.15f), Brightness(0.1f))
    22 -> listOf(Contrast(0.25f), Brightness(-0.08f))
    else -> emptyList()
}

private const val VH = 720

private fun satRows(sat: Float): Effect {
    val l = floatArrayOf(.2126f, .7152f, .0722f)
    return rgbRows(*FloatArray(9) { k -> l[k % 3] * (1 - sat) + if (k / 3 == k % 3) sat else 0f })
}

private fun animEffect(c: Clip): Effect = object : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val a = animAt(c, presentationTimeUs / 1000)
        val ar = 1280f / VH
        val m = Matrix()
        m.postScale(ar, 1f)
        m.postRotate(-a.rot)
        m.postScale(1f / ar, 1f)
        m.postScale(a.scale * (if (c.mirror) -1f else 1f), a.scale * (if (c.flipV) -1f else 1f))
        m.postTranslate(a.x * 2f, -a.y * 2f)
        return m
    }
}

/** Dip to black / white at the start and end of a clip. */
private class FadeEffect(val durMs: Long, val inMs: Long, val inType: Int, val outMs: Long, val outType: Int) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val t = presentationTimeUs / 1000.0
        var k = 1.0
        var type = 0
        if (inMs > 0 && t < inMs) { k = t / inMs; type = inType }
        if (outMs > 0 && t > durMs - outMs) {
            val kk = (durMs - t) / outMs
            if (kk < k) { k = kk; type = outType }
        }
        k = k.coerceIn(0.0, 1.0)
        val f = (if (type == 2) 1.0 + 9.0 * (1 - k) * (1 - k) else k).toFloat()
        return floatArrayOf(f, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, 1f)
    }
}

private class TimedText(
    val text: String, val a: Long, val b: Long, val xf: Float, val yf: Float,
    val sizePx: Int, val color: Int, val box: Boolean
) : TextOverlay() {
    override fun getText(presentationTimeUs: Long): SpannableString {
        val ms = presentationTimeUs / 1000
        val s = SpannableString(if (ms in a..b) text else " ")
        val f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(ForegroundColorSpan(color), 0, s.length, f)
        s.setSpan(AbsoluteSizeSpan(sizePx), 0, s.length, f)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, f)
        if (box) s.setSpan(BackgroundColorSpan(0xAA000000.toInt()), 0, s.length, f)
        return s
    }
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder()
            .setBackgroundFrameAnchor(xf * 2 - 1, 1 - yf * 2)
            .setOverlayFrameAnchor(xf * 2 - 1, 1 - yf * 2)
            .build()
}

private class CaptionOverlay(val cues: List<Cue>, val style: CaptionStyle) : TextOverlay() {
    override fun getText(presentationTimeUs: Long): SpannableString {
        val t = presentationTimeUs / 1_000_000.0
        val cue = cues.firstOrNull { t >= it.start && t <= it.end }
        val s = SpannableString(cue?.text ?: " ")
        val f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(ForegroundColorSpan((if (style.preset >= 2) 0xFFFFE600 else 0xFFFFFFFF).toInt()), 0, s.length, f)
        s.setSpan(AbsoluteSizeSpan((style.size * VH).toInt()), 0, s.length, f)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, f)
        if (cue != null && style.preset == 0 && style.box) s.setSpan(BackgroundColorSpan(0xAA000000.toInt()), 0, s.length, f)
        return s
    }
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val yf = when (style.pos) { 0 -> 0.08f; 1 -> 0.5f; else -> 0.86f }
        val y = 1f - yf * 2f
        return OverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).setOverlayFrameAnchor(0f, y).build()
    }
}

private fun buildComposition(p: VideoProject): Composition {
    val pitch = when (p.voiceFx) { 4 -> 1.35f; 5 -> 0.8f; else -> 1f }
    val items = p.clips.mapIndexed { idx, c ->
        val mi = MediaItem.Builder().setUri(Uri.parse(c.src.ref))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inMs).setEndPositionMs(c.outMs).build()
            ).build()
        val video = mutableListOf<Effect>()
        if (c.speed != 1f) video.add(SpeedChangeEffect(c.speed))
        if (c.cropX > 0f || c.cropY > 0f) video.add(Crop(-1f + 2f * c.cropX, 1f - 2f * c.cropX, -1f + 2f * c.cropY, 1f - 2f * c.cropY))
        video.add(Presentation.createForWidthAndHeight(1280, VH, Presentation.LAYOUT_SCALE_TO_FIT))
        if (c.brightness != 0f) video.add(Brightness(c.brightness))
        if (c.contrast != 1f) video.add(Contrast(c.contrast - 1f))
        if (c.saturation != 1f) video.add(satRows(c.saturation))
        video.addAll(androidFilter(c.filter))
        if (c.keys.isNotEmpty() || c.mirror || c.flipV) video.add(animEffect(c))
        val next = p.clips.getOrNull(idx + 1)
        val inMs = if (c.trans in 1..2) c.transMs else 0L
        val outMs = if (next != null && next.trans in 1..2) next.transMs else 0L
        if (inMs > 0 || outMs > 0) video.add(FadeEffect(clipDur(c), inMs, c.trans, outMs, next?.trans ?: 0))
        val audio = mutableListOf<AudioProcessor>()
        if (c.speed != 1f || pitch != 1f) audio.add(SonicAudioProcessor().apply { setSpeed(c.speed); setPitch(pitch) })
        EditedMediaItem.Builder(mi).setEffects(Effects(audio, video)).build()
    }
    val overlays = mutableListOf<TextureOverlay>()
    p.titles.forEach {
        overlays.add(TimedText(it.text, it.startMs, it.endMs, it.x, it.y, (it.size * VH).toInt(), it.color.toArgb(), false))
    }
    if (p.captions.isNotEmpty()) overlays.add(CaptionOverlay(p.captions, p.captionStyle))
    val videoEffects = mutableListOf<Effect>()
    if (overlays.isNotEmpty()) videoEffects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
    return Composition.Builder(ImmutableList.of(EditedMediaItemSequence(items)))
        .setEffects(Effects(emptyList(), videoEffects))
        .build()
}

private fun fontFile(): String {
    val known = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf", "/system/fonts/DroidSans.ttf")
    return known.firstOrNull { File(it).exists() }
        ?: File("/system/fonts").listFiles { f -> f.name.endsWith(".ttf") }?.firstOrNull()?.absolutePath
        ?: "/system/fonts/Roboto-Regular.ttf"
}

private fun ffEnv(ff: File) = FfmpegEnv(ff.absolutePath, fontFile(), false)

private suspend fun exportWithMedia3(p: VideoProject, outFile: File, onProgress: (Float) -> Unit) {
    val ctx = AppCtx.ctx
    withContext(Dispatchers.Main) {
        val composition = buildComposition(p)
        suspendCancellableCoroutine<Unit> { cont ->
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        cont.resumeWithException(exportException)
                    }
                }).build()
            transformer.start(composition, outFile.absolutePath)
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 300)
                }
            }
            handler.post(poll)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }
}

actual suspend fun exportVideo(p: VideoProject, onProgress: (Float) -> Unit): String {
    val ctx = AppCtx.ctx
    val outFile = File(ctx.cacheDir, "export-${System.currentTimeMillis()}.mp4")
    val ff = AppCtx.ffmpegFile
    var format = "H.264, device encoder"
    val needsShaping = p.captions.any { c -> c.text.any { it.code in 0x0900..0x097F } } ||
        p.titles.any { t -> t.text.any { it.code in 0x0900..0x097F } }
    // Effects that only the full engine can draw switch the export to it, even if the basic H.264 format was chosen.
    val needsFull = usesFullFeatures(p)
    if (ff != null && (!p.settings.basic || needsFull) && !needsShaping) {
        format = withContext(Dispatchers.IO) {
            val work = File(ctx.cacheDir, "work-${System.currentTimeMillis()}").also { it.mkdirs() }
            try {
                FFmpeg.export(ffEnv(ff), p, work, outFile, onProgress)
            } finally {
                work.deleteRecursively()
            }
        }
        if (p.settings.basic && needsFull) format += ", full engine used for your effects"
    } else {
        exportWithMedia3(p, outFile, onProgress)
        if (needsShaping && needsFull) format += ", Hindi text was used, so effects that need the full engine were skipped"
    }
    return withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Zyntavo")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the file in Movies")
        ctx.contentResolver.openOutputStream(uri)?.use { o -> outFile.inputStream().use { it.copyTo(o) } }
        outFile.delete()
        "Saved to Movies/Zyntavo ($format)"
    }
}

actual val fullEngine: Boolean
    get() = AppCtx.ffmpegFile != null

actual val hasBasicEngine: Boolean = true

actual suspend fun generateSpeech(text: String): Pair<String, Long>? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine<Pair<String, Long>?> { cont ->
        val ctx = AppCtx.ctx
        val file = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.wav")
        var tts: TextToSpeech? = null
        var finished = false
        fun finish(v: Pair<String, Long>?) {
            if (!finished) {
                finished = true
                tts?.shutdown()
                cont.resume(v)
            }
        }
        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) {
                finish(null)
            } else {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        val r = MediaMetadataRetriever()
                        val d = try {
                            r.setDataSource(file.absolutePath)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } catch (e: Exception) { 0L } finally { r.release() }
                        finish(file.absolutePath to d)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { finish(null) }
                })
                val rc = tts?.synthesizeToFile(text, null, file, "zyntavo")
                if (rc != TextToSpeech.SUCCESS) finish(null)
            }
        }
    }
}

actual suspend fun cutoutVideo(
    src: Source, inMs: Long, outMs: Long, bg: Int, opts: CutoutOptions, onProgress: (Float) -> Unit
): Source? = withContext(Dispatchers.IO) {
    val ff = AppCtx.ffmpegFile ?: return@withContext null
    val ctx = AppCtx.ctx
    val q = min(opts.quality, 1)
    val model = Matting.ensureModel(File(ctx.filesDir, "models"), q) { }
    val work = File(ctx.cacheDir, "cutout-${System.currentTimeMillis()}").also { it.mkdirs() }
    val dest = File(ctx.cacheDir, "cutouts").also { it.mkdirs() }
    val bgRgb = cutoutColors[bg.coerceIn(0, cutoutColors.size - 1)]
    try {
        Matting.Session(model, q).use { s ->
            FFmpeg.cutout(ffEnv(ff), src, inMs, outMs, dest, work, onProgress) { inF, outF ->
                val bmp = BitmapFactory.decodeFile(inF.absolutePath)
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val flat = Matting.flatten(px, Matting.refine(s.alpha(px, w, h), px, w, h, opts), bgRgb)
                FileOutputStream(outF).use { Bitmap.createBitmap(flat, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    } finally {
        work.deleteRecursively()
    }
}

actual suspend fun decodeAudioMono(ref: String, startMs: Long, durMs: Long): ShortArray? = withContext(Dispatchers.IO) {
    val ff = AppCtx.ffmpegFile ?: return@withContext null
    try { FFmpeg.pcm(ffEnv(ff).ffmpeg, ref, startMs, durMs) } catch (e: Exception) { null }
}
