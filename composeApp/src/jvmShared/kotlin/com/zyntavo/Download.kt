package com.zyntavo

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a file for the speech and cutout models. Compared with a plain stream it:
 * gives up on a stalled connection instead of waiting forever, tries again (and carries on from where it
 * stopped), shows how much has arrived, and only keeps the file when every byte is there.
 */
fun downloadFile(url: String, to: File, label: String = to.name, status: ((String) -> Unit)? = null) {
    to.parentFile?.mkdirs()
    val part = File(to.path + ".part")
    var lastError: Exception? = null
    for (attempt in 1..4) {
        try {
            val have = if (part.exists()) part.length() else 0L
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Zyntavo")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416 && have > 0) {
                // The partial file is already complete, or it is bad. Start again if it is bad.
                c.disconnect()
                if (!part.renameTo(to)) throw IOException("Could not save $label")
                return
            }
            if (code != 200 && code != 206) {
                c.disconnect()
                throw IOException("The server answered $code for $label")
            }
            val resumed = code == 206 && have > 0
            val body = c.contentLengthLong
            val total = if (body > 0) body + (if (resumed) have else 0L) else -1L
            var done = if (resumed) have else 0L
            var lastShown = -1
            c.inputStream.use { ins ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (status != null) {
                            val pct = if (total > 0) (done * 100 / total).toInt() else (done / 1_000_000).toInt()
                            if (pct != lastShown) {
                                lastShown = pct
                                status(if (total > 0) "Downloading $label… $pct% of ${total / 1_000_000} MB (one time)" else "Downloading $label… ${done / 1_000_000} MB (one time)")
                            }
                        }
                    }
                }
            }
            c.disconnect()
            if (total > 0 && part.length() != total) throw IOException("The download of $label stopped early")
            if (to.exists()) to.delete()
            if (!part.renameTo(to)) throw IOException("Could not save $label")
            return
        } catch (e: Exception) {
            lastError = e
            status?.invoke("Download of $label stopped (${e.message ?: e.javaClass.simpleName}). Trying again ($attempt of 4)…")
            try { Thread.sleep(1500L * attempt) } catch (e2: InterruptedException) { }
        }
    }
    throw IOException("Could not download $label. Check your internet connection and try again. (${lastError?.message ?: "unknown error"})")
}
