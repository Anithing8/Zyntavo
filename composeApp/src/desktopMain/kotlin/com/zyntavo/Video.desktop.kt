package com.zyntavo

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.min

private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

private fun winEnv(): FfmpegEnv {
    val os = System.getProperty("os.name").lowercase()
    val font = when {
        "win" in os -> "C\\:/Windows/Fonts/arial.ttf"
        "mac" in os -> "/System/Library/Fonts/Supplemental/Arial.ttf"
        else -> "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    }
    return FfmpegEnv(ffmpegPath(), font, true)
}

internal fun probe(path: String): Pair<Long, Boolean> = FFmpeg.probe(ffmpegPath(), path)

private fun pickFile(title: String): File? {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.isVisible = true
    val name = d.file ?: return null
    return File(d.directory, name)
}

@Composable
actual fun rememberVideoPicker(onPicked: (Source) -> Unit): () -> Unit = {
    val f = pickFile("Choose a video")
    if (f != null) {
        try {
            val (dur, audio) = probe(f.absolutePath)
            onPicked(Source(f.absolutePath, f.name, dur, audio))
        } catch (e: Exception) { }
    }
}

@Composable
actual fun rememberAudioPicker(onPicked: (Source) -> Unit): () -> Unit = {
    val f = pickFile("Choose a music or sound file")
    if (f != null) {
        try {
            val (dur, _) = probe(f.absolutePath)
            onPicked(Source(f.absolutePath, f.name, dur, true))
        } catch (e: Exception) { }
    }
}

actual suspend fun frameAt(src: Source, ms: Long): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val p = ProcessBuilder(
            ffmpegPath(), "-v", "error", "-ss", String.format(Locale.ROOT, "%.3f", ms / 1000.0), "-i", src.ref,
            "-frames:v", "1", "-vf", "scale=-2:540", "-f", "image2pipe", "-vcodec", "png", "-"
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        ImageIO.read(ByteArrayInputStream(bytes))?.let { img ->
            val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose()
            out.toComposeImageBitmap()
        }
    } catch (e: Exception) { null }
}

actual val fullEngine: Boolean = true

actual val hasBasicEngine: Boolean = false

actual suspend fun generateSpeech(text: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
    if (!isWindows()) return@withContext null
    try {
        val txt = File.createTempFile("zyntavo-voice", ".txt").also { it.writeText(text) }
        val wav = File.createTempFile("zyntavo-voice", ".wav")
        val wp = wav.absolutePath.replace("'", "''")
        val tp = txt.absolutePath.replace("'", "''")
        val script = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "\$s.SetOutputToWaveFile('$wp'); \$s.Speak([IO.File]::ReadAllText('$tp')); \$s.Dispose()"
        val pr = ProcessBuilder("powershell", "-NoProfile", "-Command", script).redirectErrorStream(true).start()
        pr.inputStream.readBytes()
        val ok = pr.waitFor() == 0 && wav.length() > 100
        txt.delete()
        if (ok) wav.absolutePath to probe(wav.absolutePath).first else null
    } catch (e: Exception) { null }
}

actual suspend fun exportVideo(p: VideoProject, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
    val work = Files.createTempDirectory("zyntavo-export").toFile()
    val outDir = File(System.getProperty("user.home"), "Videos/Zyntavo").also { it.mkdirs() }
    val out = File(outDir, "export-${System.currentTimeMillis()}.mp4")
    val format = try {
        FFmpeg.export(winEnv(), p, work, out, onProgress)
    } finally {
        work.deleteRecursively()
    }
    "Saved to ${out.absolutePath} ($format)"
}

actual suspend fun cutoutVideo(
    src: Source, inMs: Long, outMs: Long, bg: Int, opts: CutoutOptions, onProgress: (Float) -> Unit
): Source? = withContext(Dispatchers.IO) {
    val q = min(opts.quality, 1)
    val model = Matting.ensureModel(modelDir, q) { }
    val work = Files.createTempDirectory("zyntavo-cutout").toFile()
    val dest = File(System.getProperty("user.home"), "Videos/Zyntavo").also { it.mkdirs() }
    val bgRgb = cutoutColors[bg.coerceIn(0, cutoutColors.size - 1)]
    try {
        Matting.Session(model, q).use { s ->
            FFmpeg.cutout(winEnv(), src, inMs, outMs, dest, work, onProgress) { inF, outF ->
                val img = ImageIO.read(inF)
                val w = img.width
                val h = img.height
                val px = img.getRGB(0, 0, w, h, null, 0, w)
                val flat = Matting.flatten(px, Matting.refine(s.alpha(px, w, h), px, w, h, opts), bgRgb)
                val o = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                o.setRGB(0, 0, w, h, flat, 0, w)
                ImageIO.write(o, "png", outF)
            }
        }
    } finally {
        work.deleteRecursively()
    }
}

actual suspend fun decodeAudioMono(ref: String, startMs: Long, durMs: Long): ShortArray? = withContext(Dispatchers.IO) {
    try { FFmpeg.pcm(ffmpegPath(), ref, startMs, durMs) } catch (e: Exception) { null }
}
