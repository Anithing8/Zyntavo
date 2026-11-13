package com.photostudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** The folder where the app keeps downloaded models and what it learned. A folder from an older version is moved over. */
internal fun appHome(): File {
    val home = System.getProperty("user.home")
    val fresh = File(home, ".zyntavo")
    val old = File(home, ".sheen")
    if (!fresh.exists() && old.exists()) {
        try { old.renameTo(fresh) } catch (e: Throwable) { }
    }
    return if (!fresh.exists() && old.exists()) old else fresh
}

internal val modelDir = File(appHome(), "models")

private fun toArgb(img: java.awt.Image, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    return out
}

@Composable
actual fun rememberImagePicker(onPicked: (ImageBitmap) -> Unit): () -> Unit = {
    val d = FileDialog(null as Frame?, "Open image", FileDialog.LOAD)
    d.isVisible = true
    val name = d.file
    if (name != null) {
        ImageIO.read(File(d.directory, name))?.let { src ->
            val k = min(1.0, 2400.0 / max(src.width, src.height))
            onPicked(toArgb(src, max(1, (src.width * k).toInt()), max(1, (src.height * k).toInt())).toComposeImageBitmap())
        }
    }
}

@Composable
actual fun rememberImageSaver(onResult: (String) -> Unit): (ImageBitmap) -> Unit = { img ->
    val d = FileDialog(null as Frame?, "Save image", FileDialog.SAVE)
    d.file = "edited.png"
    d.isVisible = true
    val name = d.file
    if (name != null) {
        val f = File(d.directory, if (name.endsWith(".png", true)) name else "$name.png")
        val src = img.toAwtImage()
        ImageIO.write(toArgb(src, src.width, src.height), "png", f)
        onResult("Saved ${f.absolutePath}")
    }
}

actual suspend fun removeBackground(image: ImageBitmap, opts: CutoutOptions, onStatus: (String) -> Unit): ImageBitmap? =
    withContext(Dispatchers.Default) {
        val src = image.toAwtImage()
        val k = min(1.0, 2400.0 / max(src.width, src.height))
        val w = max(1, (src.width * k).toInt())
        val h = max(1, (src.height * k).toInt())
        val px = toArgb(src, w, h).getRGB(0, 0, w, h, null, 0, w)
        val model = Matting.ensureModel(modelDir, opts.quality, onStatus)
        onStatus("Cutting out the subject…")
        val out = Matting.Session(model, opts.quality).use { s ->
            Matting.compose(px, Matting.refine(s.alpha(px, w, h), px, w, h, opts))
        }
        val res = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        res.setRGB(0, 0, w, h, out, 0, w)
        res.toComposeImageBitmap()
    }

actual suspend fun translateText(lines: List<String>, toHindi: Boolean, onStatus: (String) -> Unit): List<String>? =
    withContext(Dispatchers.Default) {
        try {
            Marian.translateAll(modelDir, lines, toHindi, onStatus)
        } catch (e: Throwable) {
            onStatus("Translation failed: ${e.message ?: "unknown error"}. Check your internet for the first run.")
            null
        }
    }

actual suspend fun fixSpelling(words: List<Word>, deep: Boolean, onStatus: (String) -> Unit): SpellResult? =
    withContext(Dispatchers.Default) {
        try {
            SpellFix.run(modelDir, words, deep, onStatus)
        } catch (e: Throwable) {
            onStatus("Spelling check failed: ${e.message ?: "unknown error"}. Check your internet for the first run.")
            null
        }
    }

private fun prefsFile(): File = File(appHome(), "learned.txt")

actual fun readPrefs(): String = try {
    val f = prefsFile()
    if (f.exists()) f.readText(Charsets.UTF_8) else ""
} catch (e: Throwable) {
    ""
}

actual fun writePrefs(text: String) {
    try {
        val f = prefsFile()
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
    } catch (e: Throwable) {
    }
}
