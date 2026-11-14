package com.zyntavo

import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where FFmpeg lives and how text is drawn. fontFile must already be escaped for a filter graph. */
class FfmpegEnv(val ffmpeg: String, val fontFile: String, val useSubtitles: Boolean)

/** Builds and runs the FFmpeg command for a project. Used by Windows and by Android when FFmpeg is bundled. */
object FFmpeg {
    fun n(d: Double): String = String.format(Locale.ROOT, "%.3f", d)
    private fun f(v: Float): String = n(v.toDouble())

    fun probe(ffmpeg: String, path: String): Pair<Long, Boolean> {
        val p = ProcessBuilder(ffmpeg, "-hide_banner", "-i", path).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val m = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text)
        val ms = m?.let { ((it.groupValues[1].toLong() * 3600 + it.groupValues[2].toLong() * 60 + it.groupValues[3].toDouble()) * 1000).toLong() } ?: 0L
        return ms to Regex("Stream #.*Audio:").containsMatchIn(text)
    }

    /** Mono 16-bit samples of a stretch of a file, at the rate the beat finder uses. At most ten minutes. */
    fun pcm(ffmpeg: String, ref: String, startMs: Long, durMs: Long): ShortArray? {
        val cmd = mutableListOf(ffmpeg, "-v", "error")
        if (startMs > 0) cmd += listOf("-ss", n(startMs / 1000.0))
        cmd += listOf("-i", ref)
        val dur = if (durMs in 1..600000) durMs else 600000L
        cmd += listOf("-t", n(dur / 1000.0), "-vn", "-ac", "1", "-ar", "${Beats.RATE}", "-f", "s16le", "-")
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        if (bytes.size < 2) return null
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) out[i] = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 255)).toShort()
        return out
    }

    /** Refuses a GPL or non-free FFmpeg, so the app stays free to ship closed-source and commercially. */
    fun checkLicense(ffmpeg: String): String? {
        val p = ProcessBuilder(ffmpeg, "-hide_banner", "-version").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val bad = listOf("--enable-gpl", "--enable-nonfree", "libx264", "libx265")
        val hit = bad.firstOrNull { text.contains(it) } ?: return null
        return "This FFmpeg is a GPL or non-free build ($hit). Zyntavo only exports with an LGPL build so the app can stay " +
            "closed-source and commercial. Use the LGPL FFmpeg that the project builds or downloads."
    }

    /** Video encoders to try, best first. None of them is GPL: the system encoder, OpenH264, then plain MPEG-4. */
    private fun encoderCandidates(ffmpeg: String, tier: Int, res: Int, fps: Int): List<Pair<String, List<String>>> {
        val listed = try {
            val p = ProcessBuilder(ffmpeg, "-hide_banner", "-encoders").redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText()
            p.waitFor()
            t
        } catch (e: Exception) { "" }
        val mbit = intArrayOf(3, 6, 12)[tier.coerceIn(0, 2)]
        val scale = when (res) { 0 -> 0.5; 2 -> 2.2; else -> 1.0 }
        val kbps = (mbit * 1000 * scale * (fps / 30.0)).toInt()
        val out = mutableListOf<Pair<String, List<String>>>()
        if (listed.contains(" h264_mf ")) out += "H.264 (system encoder)" to listOf("-c:v", "h264_mf", "-b:v", "${kbps}k", "-pix_fmt", "yuv420p")
        if (listed.contains(" libopenh264 ")) out += "H.264 (OpenH264)" to listOf("-c:v", "libopenh264", "-b:v", "${kbps}k", "-pix_fmt", "yuv420p")
        out += "MPEG-4" to listOf("-c:v", "mpeg4", "-q:v", "${intArrayOf(6, 3, 2)[tier.coerceIn(0, 2)]}", "-pix_fmt", "yuv420p")
        return out
    }

    private fun run(cmd: List<String>): Int {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor()
    }

    /** Brightness, contrast and saturation using only filters that are part of the LGPL build (eq is GPL-only). */
    private fun grade(brightness: Float, contrast: Float, saturation: Float): String {
        val sb = StringBuilder()
        if (brightness != 0f || contrast != 1f) {
            sb.append(",lutyuv=y='clip((val/maxval-0.5)*${f(contrast)}+0.5+(${f(brightness)}),0,1)*maxval'")
        }
        if (saturation != 1f) sb.append(",hue=s=${f(saturation)}")
        return sb.toString()
    }

    private fun filterOf(i: Int) = when (i) {
        1 -> ",hue=s=0"
        2 -> ",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131:0"
        3 -> grade(0f, 1f, 1.1f) + ",colorbalance=rm=.08:bm=-.08"
        4 -> ",colorbalance=rm=-.08:bm=.1"
        5 -> grade(0f, 1.1f, 1.6f)
        6 -> grade(0.06f, 0.85f, 1f)
        7 -> ",hue=s=0" + grade(0f, 1.4f, 1f)
        8 -> ",colorchannelmixer=.5:.5:.1:0:.3:.6:.1:0:.2:.4:.3:0" + grade(0.04f, 0.92f, 1f)
        9 -> grade(0f, 1.15f, 1.1f) + ",colorbalance=rs=-.06:bs=.08:rh=.06:bh=-.05"
        10 -> grade(0.02f, 1.12f, 1.45f)
        11 -> ",colorchannelmixer=1.15:0:0:0:0:.95:0:0:0:0:.8:0"
        12 -> ",colorchannelmixer=.9:0:0:0:0:1.1:0:0:0:0:.9:0"
        13 -> ",colorchannelmixer=.85:0:0:0:0:1:0:0:0:0:1.2:0"
        14 -> grade(0.03f, 0.85f, 0.85f)
        15 -> grade(-0.03f, 1.35f, 0.7f)
        16 -> ",colorbalance=rs=-.08:bs=.1:rh=.1:bh=-.08"
        17 -> ",hue=s=0,colorbalance=rs=-.12:bs=.15:rm=-.08:bm=.1"
        18 -> ",colorbalance=rm=.08:bm=.04"
        19 -> ",colorchannelmixer=1.2:0:0:0:0:1.05:0:0:0:0:.8:0"
        20 -> grade(0.07f, 0.88f, 0.9f)
        21 -> grade(0.1f, 0.85f, 0.95f)
        22 -> grade(-0.08f, 1.25f, 1f)
        else -> ""
    }

    private fun fxOf(mask: Int, w: Int, h: Int): String {
        val sb = StringBuilder()
        if (mask and 1 != 0) sb.append(",gblur=sigma=6")
        if (mask and 2 != 0) sb.append(",unsharp=5:5:1.0:5:5:0.0")
        if (mask and 4 != 0) sb.append(",vignette=PI/4")
        if (mask and 8 != 0) sb.append(",noise=alls=14:allf=t")
        if (mask and 16 != 0) sb.append(",atadenoise")
        if (mask and 32 != 0) sb.append(",scale=trunc(iw/20/2)*2:trunc(ih/20/2)*2,scale=$w:$h:flags=neighbor")
        if (mask and 64 != 0) sb.append(",drawbox=x=0:y=0:w=iw:h=ih*0.11:color=black:t=fill,drawbox=x=0:y=ih*0.89:w=iw:h=ih*0.11:color=black:t=fill")
        if (mask and 128 != 0) sb.append(",negate")
        return sb.toString()
    }

    private fun atempo(speed: Float): String {
        var s = speed.toDouble()
        val sb = StringBuilder()
        while (s > 2.0) { sb.append("atempo=2.0,"); s /= 2.0 }
        while (s < 0.5) { sb.append("atempo=0.5,"); s *= 2.0 }
        if (s != 1.0) sb.append("atempo=${n(s)},")
        return sb.toString()
    }

    private fun voiceFx(i: Int) = when (i) {
        1 -> "highpass=f=80,afftdn=nf=-25,loudnorm=I=-16:TP=-1.5:LRA=11"
        2 -> "highpass=f=300,lowpass=f=3400"
        3 -> "afftfilt=real='hypot(re,im)*sin(0)':imag='hypot(re,im)*cos(0)':win_size=512:overlap=0.75"
        4 -> "asetrate=44100*1.35,aresample=44100,atempo=0.7407"
        5 -> "asetrate=44100*0.8,aresample=44100,atempo=1.25"
        else -> ""
    }

    /** Piecewise animation as an FFmpeg expression of time (tv, in seconds inside the clip). */
    private fun kfExpr(k: List<Keyframe>, ease: Int, tv: String = "t", pick: (Keyframe) -> Float): String {
        var e = f(pick(k.last()))
        for (i in k.size - 2 downTo 0) {
            val a = k[i]
            val b = k[i + 1]
            val ta = a.tMs / 1000.0
            val tb = b.tMs / 1000.0
            val p = "clip(($tv-${n(ta)})/${n(tb - ta)},0,1)"
            val ep = when (ease) { 0 -> p; 1 -> "($p)*($p)*(3-2*($p))"; 2 -> "($p)*($p)"; else -> "(1-(1-($p))*(1-($p)))" }
            val va = f(pick(a))
            val vb = f(pick(b))
            e = "if(lt($tv,${n(tb)}),$va+($vb-($va))*$ep,$e)"
        }
        return e
    }

    /** How many letters of text this big fit across the frame: about 0.55 of the text height per letter, in 90% of the width. */
    private fun fitChars(w: Int, h: Int, size: Float, most: Int): Int = ((w * 0.9) / (h * size * 0.55)).toInt().coerceIn(6, most)

    private fun wrap(text: String, width: Int = 34): String {
        if ('\n' in text) return text
        val sb = StringBuilder()
        var line = 0
        for (word in text.split(' ')) {
            if (line > 0 && line + 1 + word.length > width) { sb.append('\n'); line = 0 } else if (line > 0) { sb.append(' '); line += 1 }
            sb.append(word)
            line += word.length
        }
        return sb.toString()
    }

    /** A position that follows a tracked object: straight lines between the points, written as an expression of time t. */
    private fun trackExpr(pts: List<TrackPt>, pick: (TrackPt) -> Float): String {
        var e = "(${f(pick(pts.last()))})"
        for (i in pts.size - 2 downTo 0) {
            val a = pts[i]
            val b = pts[i + 1]
            val ta = a.tMs / 1000.0
            val tb = b.tMs / 1000.0
            val va = f(pick(a))
            val vb = f(pick(b))
            e = "if(lt(t,${n(tb)}),($va)+(($vb)-($va))*(t-${n(ta)})/${n(max(0.001, tb - ta))},$e)"
        }
        return "if(lt(t,${n(pts.first().tMs / 1000.0)}),(${f(pick(pts.first()))}),$e)"
    }

    /** A reversed clip that is too long to hold in memory is cut into parts, each reversed on its own and played last part first. */
    private fun expandReversed(clips: List<Clip>, limitMs: Long): List<Clip> {
        val out = ArrayList<Clip>()
        for (c in clips) {
            if (!c.reverse || c.freeze || clipDur(c) <= limitMs) { out.add(c); continue }
            val partSrc = max(500L, (limitMs * 0.8 * c.speed).toLong())
            val parts = ArrayList<Pair<Long, Long>>()
            var a = c.inMs
            while (a < c.outMs) {
                val b = min(a + partSrc, c.outMs)
                parts.add(a to b)
                a = b
            }
            if (parts.size > 1 && parts.last().second - parts.last().first < 200L) {
                val last = parts.removeAt(parts.size - 1)
                parts[parts.size - 1] = parts.last().first to last.second
            }
            parts.reversed().forEachIndexed { k, (x, y) ->
                out.add(c.copy(inMs = x, outMs = y, trans = if (k == 0) c.trans else 0, keys = emptyList()))
            }
        }
        return out
    }

    private val keyHexes = listOf("", "0x00B140", "0x0047BB")

    /** Draws a shape mask as a small greyscale picture. FFmpeg scales it to the overlay and uses it as the alpha. */
    private fun writeMaskPgm(file: File, shape: Int) {
        if (file.exists()) return
        val size = 384
        val ss = 3
        val data = ByteArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var hit = 0
            for (sy in 0 until ss) for (sx in 0 until ss) {
                if (maskInside(shape, (x + (sx + 0.5f) / ss) / size, (y + (sy + 0.5f) / ss) / size)) hit++
            }
            data[y * size + x] = (hit * 255 / (ss * ss)).toByte()
        }
        file.outputStream().use {
            it.write("P5\n$size $size\n255\n".toByteArray(Charsets.US_ASCII))
            it.write(data)
        }
    }

    /** The drawtext filters for one title. Animated titles fade, slide or grow in with expressions of time; a typed title is a row of filters. */
    private fun titleFilters(env: FfmpegEnv, work: File, k: Int, t: Title, W: Int, H: Int): List<String> {
        val hex = (t.color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
        val txt = wrap(t.text, fitChars(W, H, t.size, 40))
        File(work, "t$k.txt").writeText(txt)
        val st = t.startMs / 1000.0
        val en = t.endMs / 1000.0
        val dur = (en - st).coerceAtLeast(0.2)
        val inD = min(0.45, dur / 3)
        val outD = min(0.3, dur / 4)
        val pIn = "clip((t-${n(st)})/${n(inD)},0,1)"
        val ease = "(1-pow(1-$pIn,3))"
        val alpha = "$pIn*clip((${n(en)}-t)/${n(outD)},0,1)"
        val size = "h*${f(t.size)}"
        val tracked = t.track.size >= 2
        val xBase = if (tracked) "(${trackExpr(t.track) { it.x }})*w-text_w/2" else "(w-text_w)*${f(t.x)}"
        val yBase = if (tracked) "(${trackExpr(t.track) { it.y }})*h-text_h/2" else "(h-text_h)*${f(t.y)}"
        fun one(file: String, x: String, y: String, fontSize: String, a: String?, window: String): String =
            "drawtext=fontfile='${env.fontFile}':textfile=$file:fontsize='$fontSize':fontcolor=0x$hex:x='$x':y='$y'" +
                (if (a != null) ":alpha='$a'" else "") + ":borderw=2:bordercolor=black:enable='between(t,$window)'"
        val whole = "${n(st)},${n(en)}"
        return when (t.anim) {
            1 -> listOf(one("t$k.txt", xBase, yBase, size, alpha, whole))
            2 -> listOf(one("t$k.txt", xBase, "$yBase+(1-$ease)*h*0.06", size, alpha, whole))
            3 -> listOf(one("t$k.txt", xBase, "$yBase-(1-$ease)*h*0.06", size, alpha, whole))
            4 -> listOf(one("t$k.txt", "$xBase-(1-$ease)*w*0.25", yBase, size, alpha, whole))
            5 -> listOf(one("t$k.txt", xBase, yBase, "$size*(0.5+0.5*$ease)", alpha, whole))
            6 -> {
                // Letters appear one after another. Each step is the text so far, drawn from the same left edge.
                val len = txt.length
                val steps = min(max(len, 1), 60)
                val step = min(0.08, dur * 0.6 / steps)
                val estW = widestLineEm(txt) * t.size * H
                val xAnchor = if (tracked) "(${trackExpr(t.track) { it.x }})*w-${n(estW)}/2" else "(w-${n(estW)})*${f(t.x)}"
                val out = ArrayList<String>()
                for (idx in 1..steps) {
                    val cnt = (len * idx + steps - 1) / steps
                    val part = txt.take(cnt).trimEnd()
                    if (part.isBlank()) continue
                    val name = "tw${k}_$idx.txt"
                    File(work, name).writeText(part)
                    val from = st + (idx - 1) * step
                    val to = if (idx == steps) en else st + idx * step
                    out += one(name, xAnchor, yBase, size, null, "${n(from)},${n(to)}")
                }
                out
            }
            else -> listOf(one("t$k.txt", xBase, yBase, size, null, whole))
        }
    }

    /** Returns a short name of the video format that was written. */
    fun export(env: FfmpegEnv, source: VideoProject, work: File, out: File, onProgress: (Float) -> Unit): String {
        checkLicense(env.ffmpeg)?.let { throw RuntimeException(it) }
        val p = source.copy(clips = expandReversed(source.clips, reverseLimitMs(source.settings)))
        val (W, H) = frameSize(p.settings.res, p.settings.aspect)
        val fps = p.settings.fps
        val side = (ceil(hypot(W.toDouble(), H.toDouble()) / 2.0) * 2).toInt()
        val g = StringBuilder()
        val cmd = mutableListOf(env.ffmpeg, "-y", "-v", "error", "-progress", "pipe:1", "-nostats")
        val durs = p.clips.map { (it.outMs - it.inMs) / 1000.0 / it.speed }

        p.clips.forEachIndexed { i, c ->
            cmd += listOf("-i", c.src.ref)
            val s = n(c.inMs / 1000.0)
            val e = n(c.outMs / 1000.0)
            val d = durs[i]
            val next = p.clips.getOrNull(i + 1)
            val fi = if (c.trans in 1..2) min(c.transMs / 1000.0, d / 2) else 0.0
            val fo = if (next != null && next.trans in 1..2) min(next.transMs / 1000.0, d / 2) else 0.0
            val fadeV = StringBuilder()
            if (fi > 0) fadeV.append(",fade=t=in:st=0:d=${n(fi)}:color=${if (c.trans == 2) "white" else "black"}")
            if (fo > 0) fadeV.append(",fade=t=out:st=${n(d - fo)}:d=${n(fo)}:color=${if (next!!.trans == 2) "white" else "black"}")
            val fadeA = StringBuilder()
            if (fi > 0) fadeA.append("afade=t=in:st=0:d=${n(fi)},")
            if (fo > 0) fadeA.append("afade=t=out:st=${n(d - fo)}:d=${n(fo)},")
            val gainExpr = if (c.gain != 1f) "volume=${f(c.gain)}," else ""
            val volExpr = if (c.keys.any { it.vol != 1f }) "volume=volume='${kfExpr(c.keys, c.ease) { it.vol }}':eval=frame," else ""

            if (c.freeze) {
                // One picture, held: take the first frame at the chosen time and repeat it.
                val s0 = c.inMs / 1000.0
                g.append("[$i:v]trim=start=${n(s0)}:end=${n(s0 + 0.3)},setpts=PTS-STARTPTS,trim=end_frame=1,tpad=stop_mode=clone:stop_duration=${n(d + 1.0)},trim=end=${n(d)},setpts=PTS-STARTPTS")
            } else {
                g.append("[$i:v]trim=start=$s:end=$e,setpts=(PTS-STARTPTS)/${f(c.speed)}")
                if (c.stab > 0) {
                    val r = if (c.stab > 1) 48 else 20
                    g.append(",deshake=rx=$r:ry=$r:edge=mirror")
                }
            }
            if (c.cropX > 0f || c.cropY > 0f) {
                val cx = f(c.cropX)
                val cy = f(c.cropY)
                g.append(",crop=iw*(1-2*$cx):ih*(1-2*$cy):iw*$cx:ih*$cy")
            }
            if (p.settings.blurBg) {
                // A blurred, darker copy of the picture fills the frame; the sharp picture sits on top of it.
                val bw = max(16, W / 6 / 2 * 2)
                val bh = max(16, H / 6 / 2 * 2)
                g.append(",split[sa$i][sb$i];")
                g.append("[sa$i]scale=$bw:$bh:force_original_aspect_ratio=increase,crop=$bw:$bh,gblur=sigma=4:steps=2,scale=$W:$H:flags=bicubic${grade(-0.1f, 1f, 1f)}[bk$i];")
                g.append("[sb$i]scale=$W:$H:force_original_aspect_ratio=decrease[fg$i];")
                g.append("[bk$i][fg$i]overlay=x='(W-w)/2':y='(H-h)/2'")
                g.append(",setsar=1,fps=$fps")
            } else {
                g.append(",scale=$W:$H:force_original_aspect_ratio=decrease,pad=$W:$H:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$fps")
            }
            if (c.reverse && !c.freeze) g.append(",reverse,setpts=PTS-STARTPTS")
            g.append(grade(c.brightness, c.contrast, c.saturation))
            g.append(filterOf(c.filter))
            g.append(fxOf(c.fx, W, H))
            if (c.mirror) g.append(",hflip")
            if (c.flipV) g.append(",vflip")
            if (c.keys.isNotEmpty()) {
                val rot = kfExpr(c.keys, c.ease) { it.rot }
                val zoom = kfExpr(c.keys, c.ease) { it.scale }
                val px = kfExpr(c.keys, c.ease) { it.x }
                val py = kfExpr(c.keys, c.ease) { it.y }
                g.append(",format=rgba,rotate=a='($rot)*PI/180':ow=$side:oh=$side:c=black@0")
                g.append(",scale=w='trunc($side*($zoom)/2)*2':h='trunc($side*($zoom)/2)*2':eval=frame")
                if (c.keys.any { it.opacity != 1f }) {
                    val op = kfExpr(c.keys, c.ease, "T") { it.opacity }
                    g.append(",format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*($op)'")
                }
                g.append("[z$i];")
                g.append("color=c=black:s=${W}x$H:r=$fps:d=${n(d + 1)}[bg$i];")
                g.append("[bg$i][z$i]overlay=x='(W-w)/2+($px)*W':y='(H-h)/2+($py)*H':eval=frame:shortest=1:format=auto$fadeV,format=yuv420p[v$i];")
            } else {
                g.append("$fadeV,format=yuv420p[v$i];")
            }
            if (c.src.hasAudio && !c.freeze) {
                g.append("[$i:a]atrim=start=$s:end=$e,asetpts=PTS-STARTPTS,${if (c.reverse) "areverse," else ""}${atempo(c.speed)}$gainExpr$volExpr${fadeA}aformat=sample_rates=44100:channel_layouts=stereo[a$i];")
            } else {
                g.append("anullsrc=r=44100:cl=stereo,atrim=duration=${n(d)}[a$i];")
            }
        }

        var cv = "v0"
        var ca = "a0"
        var acc = durs[0]
        for (k in 1 until p.clips.size) {
            val ov = overlapMs(p.clips, k) / 1000.0
            if (ov > 0) {
                g.append("[$cv][v$k]xfade=transition=${xfadeNames[p.clips[k].trans - 3]}:duration=${n(ov)}:offset=${n(acc - ov)}[xv$k];")
                g.append("[$ca][a$k]acrossfade=d=${n(ov)}[xa$k];")
                acc += durs[k] - ov
            } else {
                g.append("[$cv][v$k]concat=n=2:v=1:a=0[xv$k];[$ca][a$k]concat=n=2:v=0:a=1[xa$k];")
                acc += durs[k]
            }
            cv = "xv$k"
            ca = "xa$k"
        }

        var base = cv
        var nIn = p.clips.size
        val aud = mutableListOf(ca)
        p.overlays.forEachIndexed { j, o ->
            val ix = nIn++
            cmd += listOf("-i", o.src.ref)
            val s = n(o.inMs / 1000.0)
            val e = n(o.outMs / 1000.0)
            val st = o.startMs / 1000.0
            val en = st + (o.outMs - o.inMs) / 1000.0
            val cx = f(o.cropX)
            val cy = f(o.cropY)
            val crop = if (o.cropX > 0f || o.cropY > 0f) ",crop=iw*(1-2*$cx):ih*(1-2*$cy):iw*$cx:ih*$cy" else ""
            val ox = f(o.x)
            val oy = f(o.y)
            val masked = o.mask > 0
            val keyed = o.key > 0
            val side = (((W * o.scale) / 2f).toInt() * 2).coerceAtLeast(16)
            g.append("[$ix:v]trim=start=$s:end=$e,setpts=PTS-STARTPTS$crop")
            if (masked) g.append(",scale=$side:$side:force_original_aspect_ratio=increase,crop=$side:$side")
            else g.append(",scale=w='trunc($W*${f(o.scale)}/2)*2':h=-2")
            g.append(",fps=$fps")
            if (keyed) g.append(",chromakey=color=${keyHexes[o.key.coerceIn(1, 2)]}:similarity=${f(o.keySim)}:blend=0.08")
            if (masked) {
                val mf = File(work, "mask${o.mask}.pgm")
                writeMaskPgm(mf, o.mask)
                val mi = nIn++
                cmd += listOf("-loop", "1", "-framerate", "$fps", "-t", n((o.outMs - o.inMs) / 1000.0 + 0.5), "-i", mf.absolutePath)
                g.append(",format=rgba[om$j];[$mi:v]scale=$side:$side,format=gray[mk$j];[om$j][mk$j]alphamerge")
            }
            if (o.blend == 0) {
                g.append(",format=rgba,colorchannelmixer=aa=${f(o.opacity)},setpts=PTS+${n(st)}/TB[ol$j];")
                val tracked = o.track.size >= 2
                val px = if (tracked) "(W-w)/2+((${trackExpr(o.track) { it.x }})-0.5)*W" else "(W-w)/2+$ox*W"
                val py = if (tracked) "(H-h)/2+((${trackExpr(o.track) { it.y }})-0.5)*H" else "(H-h)/2+$oy*H"
                g.append("[$base][ol$j]overlay=x='$px':y='$py':eof_action=pass:enable='between(t,${n(st)},${n(en)})':format=auto[ob$j];")
            } else if (!keyed && !masked) {
                val pad = if (o.blend == 4 || o.blend == 5) "white" else "black"
                g.append(",format=yuv420p,pad=$W:$H:x='(ow-iw)/2+$ox*ow':y='(oh-ih)/2+$oy*oh':color=$pad,")
                g.append("tpad=start_mode=add:start_duration=${n(st)}:color=$pad,tpad=stop_mode=add:stop_duration=600:color=$pad[ol$j];")
                g.append("[$base][ol$j]blend=all_mode=${blendModes[o.blend]}:all_opacity=${f(o.opacity)}:shortest=1[ob$j];")
            } else {
                // A blend mode with a key or a mask: blend the whole frame, then let the overlay's own transparency decide where the blend shows.
                g.append(",format=rgba,pad=$W:$H:x='(ow-iw)/2+$ox*ow':y='(oh-ih)/2+$oy*oh':color=black@0,")
                g.append("tpad=start_mode=add:start_duration=${n(st)}:color=black@0,tpad=stop_mode=add:stop_duration=600:color=black@0,split[lc$j][la$j];")
                g.append("[la$j]alphaextract[am$j];[lc$j]format=yuv420p[lf$j];")
                g.append("[$base]format=yuv420p,split[bs$j][bt$j];")
                g.append("[bs$j][lf$j]blend=all_mode=${blendModes[o.blend]}:all_opacity=${f(o.opacity)}:shortest=1[bl$j];")
                g.append("[bt$j][bl$j][am$j]maskedmerge=shortest=1[ob$j];")
            }
            base = "ob$j"
            if (o.src.hasAudio) {
                g.append("[$ix:a]atrim=start=$s:end=$e,asetpts=PTS-STARTPTS,aformat=sample_rates=44100:channel_layouts=stereo,adelay=${o.startMs}|${o.startMs}[oa$j];")
                aud += "oa$j"
            }
        }
        p.voices.forEachIndexed { k, v ->
            val ix = nIn++
            cmd += listOf("-i", v.wav)
            g.append("[$ix:a]aformat=sample_rates=44100:channel_layouts=stereo,adelay=${v.startMs}|${v.startMs},volume=${f(v.vol)}[va$k];")
            aud += "va$k"
        }
        val mixed = if (aud.size > 1) {
            g.append(aud.joinToString("") { "[$it]" } + "amix=inputs=${aud.size}:duration=first:normalize=0[am];")
            "am"
        } else aud[0]
        val fx = voiceFx(p.voiceFx)
        g.append("[$mixed]${if (fx.isEmpty()) "anull" else fx}[aout];")

        val chain = mutableListOf<String>()
        p.titles.forEachIndexed { k, t -> chain += titleFilters(env, work, k, t, W, H) }
        if (p.captions.isNotEmpty()) {
            val st = p.captionStyle
            if (env.useSubtitles) {
                File(work, "subs.ass").writeText(toAss(p.captions, st, W, H))
                chain += "subtitles=subs.ass"
            } else {
                val yf = when (st.pos) { 0 -> 0.08f; 1 -> 0.5f; else -> 0.86f }
                val look = if (st.preset == 0 && st.box) ":box=1:boxcolor=black@0.55:boxborderw=14" else ":borderw=3:bordercolor=black"
                val color = if (st.preset >= 2) "0xFFE600" else "white"
                p.captions.forEachIndexed { k, c ->
                    File(work, "c$k.txt").writeText(wrap(c.text, fitChars(W, H, st.size, 34)))
                    chain += "drawtext=fontfile='${env.fontFile}':textfile=c$k.txt:fontsize=h*${f(st.size)}:fontcolor=$color" +
                        ":x=(w-text_w)/2:y=(h-text_h)*${f(yf)}$look:enable='between(t,${n(c.start)},${n(c.end)})'"
                }
            }
        }
        g.append("[$base]${if (chain.isEmpty()) "null" else chain.joinToString(",")}[vout]")

        val common = listOf("-filter_complex", g.toString(), "-map", "[vout]", "-map", "[aout]")
        val tail = listOf("-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.absolutePath)
        val tier = when { p.settings.crf >= 24 -> 0; p.settings.crf <= 16 -> 2; else -> 1 }
        val total = max(1.0, p.totalMs / 1000.0)
        var lastError = ""
        for ((label, codec) in encoderCandidates(env.ffmpeg, tier, p.settings.res, fps)) {
            val log = File(work, "log.txt")
            val proc = ProcessBuilder(cmd + common + codec + tail).directory(work).redirectError(log).start()
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                    line.substringAfter('=').toLongOrNull()?.let { onProgress((it / 1e6 / total).toFloat().coerceIn(0f, 1f)) }
                }
            }
            if (proc.waitFor() == 0 && out.exists()) return label
            lastError = log.readText().takeLast(500)
            out.delete()
        }
        throw RuntimeException(lastError.ifBlank { "FFmpeg failed to run." })
    }

    /** Cuts the subject out of every frame (15 per second) and builds a new clip with a flat background. */
    fun cutout(
        env: FfmpegEnv, src: Source, inMs: Long, outMs: Long, destDir: File, work: File,
        onProgress: (Float) -> Unit, perFrame: (File, File) -> Unit
    ): Source? {
        checkLicense(env.ffmpeg)?.let { throw RuntimeException(it) }
        val inDir = File(work, "in").also { it.mkdirs() }
        val outDir = File(work, "out").also { it.mkdirs() }
        val seconds = (outMs - inMs) / 1000.0
        val code = run(
            listOf(
                env.ffmpeg, "-y", "-v", "error", "-ss", n(inMs / 1000.0), "-t", n(seconds), "-i", src.ref,
                "-vf", "fps=15,scale=960:-2", File(inDir, "%05d.png").absolutePath
            )
        )
        if (code != 0) return null
        val frames = inDir.listFiles()?.sortedBy { it.name }
        if (frames == null || frames.isEmpty()) return null
        frames.forEachIndexed { i, fr ->
            perFrame(fr, File(outDir, fr.name))
            onProgress(0.9f * (i + 1) / frames.size)
        }
        destDir.mkdirs()
        val outFile = File(destDir, "cutout-${System.currentTimeMillis()}.mp4")
        val cmd = mutableListOf(env.ffmpeg, "-y", "-v", "error", "-framerate", "15", "-i", File(outDir, "%05d.png").absolutePath)
        if (src.hasAudio) cmd += listOf("-ss", n(inMs / 1000.0), "-t", n(seconds), "-i", src.ref)
        cmd += listOf("-map", "0:v")
        if (src.hasAudio) cmd += listOf("-map", "1:a")
        val tail = listOf("-c:a", "aac", "-shortest", outFile.absolutePath)
        var ok = false
        for ((_, codec) in encoderCandidates(env.ffmpeg, 1, 0, 15)) {
            if (run(cmd + codec + tail) == 0 && outFile.exists()) { ok = true; break }
            outFile.delete()
        }
        if (!ok) return null
        onProgress(1f)
        val info = probe(env.ffmpeg, outFile.absolutePath)
        return Source(outFile.absolutePath, outFile.name, info.first, info.second)
    }
}
