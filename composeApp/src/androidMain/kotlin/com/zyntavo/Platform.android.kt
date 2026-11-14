package com.zyntavo

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberImagePicker(onPicked: (ImageBitmap) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val k = max(info.size.width, info.size.height) / 2000
                    if (k > 1) d.setTargetSampleSize(k)
                }
                onPicked(bmp.asImageBitmap())
            } catch (e: Exception) {
                Toast.makeText(ctx, "Could not open that image.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
actual fun rememberImageSaver(onResult: (String) -> Unit): (ImageBitmap) -> Unit {
    val ctx = LocalContext.current
    return { img ->
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "zyntavo-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zyntavo")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) onResult("Could not save the image.") else {
            ctx.contentResolver.openOutputStream(uri)?.use { img.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            onResult("Saved to Pictures/Zyntavo")
        }
    }
}

private val segmenter by lazy {
    SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
}

/** Google's built-in cutout, used if the better model cannot be downloaded. */
private suspend fun mlKitCut(image: ImageBitmap): ImageBitmap? =
    suspendCancellableCoroutine { cont ->
        segmenter.process(InputImage.fromBitmap(image.asAndroidBitmap(), 0))
            .addOnSuccessListener { cont.resume(it.foregroundBitmap?.asImageBitmap()) }
            .addOnFailureListener { cont.resume(null) }
    }

actual suspend fun removeBackground(image: ImageBitmap, opts: CutoutOptions, onStatus: (String) -> Unit): ImageBitmap? =
    withContext(Dispatchers.Default) {
        try {
            var bmp = image.asAndroidBitmap()
            val k = min(1f, 1600f / max(bmp.width, bmp.height))
            if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val model = Matting.ensureModel(File(AppCtx.ctx.filesDir, "models"), opts.quality, onStatus)
            onStatus("Cutting out the subject…")
            val out = Matting.Session(model, opts.quality).use { s ->
                Matting.compose(px, Matting.refine(s.alpha(px, w, h), px, w, h, opts))
            }
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        } catch (e: Throwable) {
            onStatus("Using the built-in cutout instead…")
            mlKitCut(image)
        }
    }

/** Google's on-device translator: small download, good quality. */
private suspend fun mlKitTranslate(lines: List<String>, toHindi: Boolean, onStatus: (String) -> Unit): List<String> {
    val options = TranslatorOptions.Builder()
        .setSourceLanguage(if (toHindi) TranslateLanguage.ENGLISH else TranslateLanguage.HINDI)
        .setTargetLanguage(if (toHindi) TranslateLanguage.HINDI else TranslateLanguage.ENGLISH)
        .build()
    val translator = Translation.getClient(options)
    try {
        onStatus("Preparing the translator (one-time download)…")
        suspendCancellableCoroutine<Unit> { c ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { c.resume(Unit) }
                .addOnFailureListener { c.resumeWithException(it) }
        }
        val out = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            onStatus("Translating… ${i + 1} of ${lines.size}")
            out.add(suspendCancellableCoroutine<String> { c ->
                translator.translate(line)
                    .addOnSuccessListener { c.resume(it) }
                    .addOnFailureListener { c.resumeWithException(it) }
            })
        }
        return out
    } finally {
        translator.close()
    }
}

actual suspend fun translateText(lines: List<String>, toHindi: Boolean, onStatus: (String) -> Unit): List<String>? =
    withContext(Dispatchers.Default) {
        try {
            mlKitTranslate(lines, toHindi, onStatus)
        } catch (e: Throwable) {
            onStatus("Using the backup translator…")
            try {
                Marian.translateAll(File(AppCtx.ctx.filesDir, "models"), lines, toHindi, onStatus)
            } catch (e2: Throwable) {
                onStatus("Translation failed: ${e2.message ?: "unknown error"}. Check your internet for the first run.")
                null
            }
        }
    }

actual suspend fun fixSpelling(words: List<Word>, deep: Boolean, onStatus: (String) -> Unit): SpellResult? =
    withContext(Dispatchers.Default) {
        try {
            SpellFix.run(File(AppCtx.ctx.filesDir, "models"), words, deep, onStatus)
        } catch (e: Throwable) {
            onStatus("Spelling check failed: ${e.message ?: "unknown error"}. Check your internet for the first run.")
            null
        }
    }

private fun prefsFile(): File = File(AppCtx.ctx.filesDir, "learned.txt")

actual fun readPrefs(): String = try {
    val f = prefsFile()
    if (f.exists()) f.readText(Charsets.UTF_8) else ""
} catch (e: Throwable) {
    ""
}

actual fun writePrefs(text: String) {
    try {
        prefsFile().writeText(text, Charsets.UTF_8)
    } catch (e: Throwable) {
    }
}
