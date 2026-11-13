package com.photostudio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

@Composable
actual fun rememberCaptionGenerator(
    model: String,
    engine: Int,
    translate: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onDone: (List<Word>?) -> Unit
): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            onBusy(true)
            val words = try {
                withContext(Dispatchers.Default) {
                    val base = File(ctx.filesDir, "models")
                    fun run(eng: Int): List<Word> =
                        CaptionSinks.open(eng, base, model, translate, onStatus).use { s ->
                            decodeAudio(ctx, uri) { b, n -> s.feed(b, n) }
                            s.finish()
                        }
                    try {
                        run(engine)
                    } catch (e: Throwable) {
                        if (engine == 0 || translate) throw e
                        onStatus("The better recognizer failed (${e.message}). Using the standard one…")
                        run(0)
                    }
                }
            } catch (e: Throwable) {
                onStatus("Failed: ${e.message ?: "unknown error"}. For the first run, connect to the internet.")
                null
            }
            onBusy(false)
            onDone(words)
        }
    }
    return { launcher.launch(arrayOf("video/*", "audio/*")) }
}

@Composable
actual fun rememberTextSaver(onResult: (String) -> Unit): (String, String) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) }
            onResult("Saved.")
        }
    }
    return { name, content -> pending = content; launcher.launch(name) }
}

/** Decodes any audio track to 16 kHz mono 16-bit PCM and streams it to [sink]. */
private fun decodeAudio(ctx: Context, uri: Uri, sink: (ByteArray, Int) -> Unit) {
    val ex = MediaExtractor()
    ex.setDataSource(ctx, uri, null)
    var track = -1
    for (i in 0 until ex.trackCount) {
        if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
    }
    if (track < 0) { ex.release(); throw IllegalStateException("This file has no audio") }
    val fmt = ex.getTrackFormat(track)
    ex.selectTrack(track)
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(fmt, null, null, 0)
    codec.start()
    var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val info = MediaCodec.BufferInfo()
    var inDone = false
    var outDone = false
    val out = ByteArray(8192)
    var outN = 0
    var g = 0L
    var prev = 0f
    var nextOut = 0.0
    try {
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val ob = codec.getOutputBuffer(o)!!
                ob.position(info.offset)
                ob.limit(info.offset + info.size)
                val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                val step = rate / 16000.0
                while (sb.remaining() >= ch) {
                    var sum = 0
                    for (c in 0 until ch) sum += sb.get()
                    val v = sum.toFloat() / ch
                    while (nextOut <= g) {
                        val s = if (g == 0L) v else prev + (v - prev) * (nextOut - (g - 1)).toFloat()
                        val sv = s.toInt().coerceIn(-32768, 32767)
                        out[outN++] = (sv and 0xFF).toByte()
                        out[outN++] = ((sv shr 8) and 0xFF).toByte()
                        if (outN == out.size) { sink(out, outN); outN = 0 }
                        nextOut += step
                    }
                    prev = v
                    g++
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        if (outN > 0) sink(out, outN)
    } finally {
        codec.stop(); codec.release(); ex.release()
    }
}
