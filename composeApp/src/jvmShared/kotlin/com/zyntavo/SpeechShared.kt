package com.zyntavo

import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads and unpacks a Vosk speech model once. Works offline afterwards. */
fun ensureVoskModel(baseDir: File, name: String, status: (String) -> Unit): File {
    val dir = File(baseDir, name)
    if (File(dir, "am/final.mdl").exists()) return dir
    baseDir.mkdirs()
    status("Downloading the speech model (about 40 MB, one time)…")
    val zip = File(baseDir, "$name.zip")
    URL("https://alphacephei.com/vosk/models/$name.zip").openStream().use { i ->
        zip.outputStream().use { o -> i.copyTo(o) }
    }
    status("Unpacking the model…")
    val root = baseDir.canonicalPath
    ZipInputStream(zip.inputStream()).use { z ->
        var e = z.nextEntry
        while (e != null) {
            val f = File(baseDir, e.name)
            if (!f.canonicalPath.startsWith(root)) throw SecurityException("Bad zip entry")
            if (e.isDirectory) f.mkdirs() else {
                f.parentFile.mkdirs()
                f.outputStream().use { z.copyTo(it) }
            }
            e = z.nextEntry
        }
    }
    zip.delete()
    return dir
}

/** Feeds 16 kHz mono 16-bit PCM into Vosk and collects word timings. */
class VoskSession(modelDir: File, private val onProgress: (Int) -> Unit) : AutoCloseable {
    private val model = Model(modelDir.absolutePath)
    private val rec = Recognizer(model, 16000f).also { it.setWords(true) }
    private val results = mutableListOf<String>()
    private var carry = -1
    private var bytes = 0L
    private var lastSec = -1

    fun feed(b: ByteArray, n: Int) {
        var data = b
        var len = n
        if (carry >= 0) {
            data = ByteArray(len + 1)
            data[0] = carry.toByte()
            System.arraycopy(b, 0, data, 1, len)
            len += 1
            carry = -1
        }
        if (len % 2 == 1) { carry = data[len - 1].toInt() and 0xFF; len -= 1 }
        if (len > 0 && rec.acceptWaveForm(data, len)) results.add(rec.result)
        bytes += len
        val sec = (bytes / 32000).toInt()
        if (sec != lastSec) { lastSec = sec; onProgress(sec) }
    }

    fun words(): List<Word> {
        results.add(rec.finalResult)
        return results.flatMap { parseWords(it) }
    }

    override fun close() { rec.close(); model.close() }
}
