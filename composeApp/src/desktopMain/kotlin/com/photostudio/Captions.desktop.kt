package com.photostudio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

internal fun ffmpegPath(): String {
    val win = System.getProperty("os.name").lowercase().contains("win")
    val dir = ProcessHandle.current().info().command().orElse(null)?.let { File(it).parentFile }
    val local = dir?.let { File(it, if (win) "ffmpeg.exe" else "ffmpeg") }
    return if (local != null && local.exists()) local.absolutePath else "ffmpeg"
}

@Composable
actual fun rememberCaptionGenerator(
    model: String,
    engine: Int,
    translate: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onDone: (List<Word>?) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        val d = FileDialog(null as Frame?, "Choose a video or audio file", FileDialog.LOAD)
        d.isVisible = true
        val name = d.file
        if (name != null) scope.launch {
            val file = File(d.directory, name)
            onBusy(true)
            val words = try {
                withContext(Dispatchers.Default) {
                    val base = File(appHome(), "models")
                    fun run(eng: Int): List<Word> =
                        CaptionSinks.open(eng, base, model, translate, onStatus).use { s ->
                            val p = ProcessBuilder(
                                ffmpegPath(), "-v", "error", "-i", file.absolutePath,
                                "-vn", "-ar", "16000", "-ac", "1", "-f", "s16le", "-"
                            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                            p.inputStream.use { ins ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    s.feed(buf, n)
                                }
                            }
                            s.finish()
                        }
                    try {
                        run(engine)
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Throwable) {
                        if (engine == 0 || translate) throw e
                        onStatus("The better recognizer failed (${e.message}). Using the standard one…")
                        run(0)
                    }
                }
            } catch (e: IOException) {
                onStatus("FFmpeg was not found. Install FFmpeg, or use the Windows build from GitHub Actions, which includes it.")
                null
            } catch (e: Throwable) {
                onStatus("Failed: ${e.message ?: "unknown error"}. For the first run, connect to the internet.")
                null
            }
            onBusy(false)
            onDone(words)
        }
    }
}

@Composable
actual fun rememberTextSaver(onResult: (String) -> Unit): (String, String) -> Unit = { suggested, content ->
    val d = FileDialog(null as Frame?, "Save captions", FileDialog.SAVE)
    d.file = suggested
    d.isVisible = true
    val name = d.file
    if (name != null) {
        File(d.directory, name).writeText(content)
        onResult("Saved $name")
    }
}
