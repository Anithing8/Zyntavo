package com.photostudio

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

    /** Returns a short name of the video format that was written. */
    fun export(env: FfmpegEnv, p: VideoProject, work: File, out: File, onProgress: (Float) -> Unit): String {
        checkLicense(env.ffmpeg)?.let { throw RuntimeException(it) }
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

            g.append("[$i:v]trim=start=$s:end=$e,setpts=(PTS-STARTPTS)/${f(c.speed)}")
            if (c.cropX > 0f || c.cropY > 0f) {
                val cx = f(c.cropX)
                val cy = f(c.cropY)
                g.append(",crop=iw*(1-2*$cx):ih*(1-2*$cy):iw*$cx:ih*$cy")
            }
            g.append(",scale=$W:$H:force_original_aspect_ratio=decrease,pad=$W:$H:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$fps")
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
            if (c.src.hasAudio) {
                g.append("[$i:a]atrim=start=$s:end=$e,asetpts=PTS-STARTPTS,${atempo(c.speed)}$gainExpr$volExpr${fadeA}aformat=sample_rates=44100:channel_layouts=stereo[a$i];")
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
            g.append("[$ix:v]trim=start=$s:end=$e,setpts=PTS-STARTPTS$crop,scale=w='trunc($W*${f(o.scale)}/2)*2':h=-2,fps=$fps")
            if (o.blend == 0) {
                g.append(",format=rgba,colorchannelmixer=aa=${f(o.opacity)},setpts=PTS+${n(st)}/TB[ol$j];")
                g.append("[$base][ol$j]overlay=x='(W-w)/2+$ox*W':y='(H-h)/2+$oy*H':eof_action=pass:enable='between(t,${n(st)},${n(en)})':format=auto[ob$j];")
            } else {
                val pad = if (o.blend == 4 || o.blend == 5) "white" else "black"
                g.append(",format=yuv420p,pad=$W:$H:x='(ow-iw)/2+$ox*ow':y='(oh-ih)/2+$oy*oh':color=$pad,")
                g.append("tpad=start_mode=add:start_duration=${n(st)}:color=$pad,tpad=stop_mode=add:stop_duration=600:color=$pad[ol$j];")
                g.append("[$base][ol$j]blend=all_mode=${blendModes[o.blend]}:all_opacity=${f(o.opacity)}:shortest=1[ob$j];")
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
        p.titles.forEachIndexed { k, t ->
            File(work, "t$k.txt").writeText(wrap(t.text, fitChars(W, H, t.size, 40)))
            val hex = (t.color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
            chain += "drawtext=fontfile='${env.fontFile}':textfile=t$k.txt:fontsize=h*${f(t.size)}:fontcolor=0x$hex" +
                ":x=(w-text_w)*${f(t.x)}:y=(h-text_h)*${f(t.y)}:borderw=2:bordercolor=black" +
                ":enable='between(t,${n(t.startMs / 1000.0)},${n(t.endMs / 1000.0)})'"
        }
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
