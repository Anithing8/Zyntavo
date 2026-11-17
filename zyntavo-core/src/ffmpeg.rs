//! Runs FFmpeg as a separate program: reading file information, grabbing a picture or the sound, and exporting.
//! FFmpeg is not linked into Zyntavo. Use an LGPL build so the result can be shared under the LGPL's terms.
use crate::masks;
use crate::model::{Clip, Project, Source};
use crate::pixels::Rgba;
use std::io::{BufRead, BufReader, Read};
use std::path::PathBuf;
use std::process::{Command, Stdio};

pub struct Ffmpeg {
    pub bin: String,
}

fn num(v: f64) -> String {
    format!("{:.3}", v)
}

fn atempo(speed: f32) -> String {
    let mut s = speed.max(0.05) as f64;
    let mut parts: Vec<String> = Vec::new();
    while s > 2.0 {
        parts.push("atempo=2.0".into());
        s /= 2.0;
    }
    while s < 0.5 {
        parts.push("atempo=0.5".into());
        s *= 2.0;
    }
    parts.push(format!("atempo={:.4}", s));
    parts.join(",")
}

/// The filters for a clip's look. Written with filters that are in LGPL builds of FFmpeg.
fn look(c: &Clip) -> String {
    let mut f = String::new();
    if c.brightness != 0.0 || c.contrast != 1.0 {
        f += &format!(",lutyuv=y='clip((val-128)*{:.3}+128+{:.1},0,255)'", c.contrast, c.brightness * 255.0);
    }
    if c.saturation != 1.0 {
        f += &format!(",hue=s={:.3}", c.saturation);
    }
    f += match c.filter {
        1 => ",hue=s=0",
        2 => ",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131",
        3 => ",colorbalance=rs=.12:bs=-.12",
        4 => ",colorbalance=rs=-.12:bs=.12",
        5 => ",hue=s=1.4",
        6 => ",curves=all='0/0.08 1/0.92',hue=s=0.85",
        7 => ",hue=s=0,curves=all='0/0 0.35/0.25 0.65/0.75 1/1'",
        8 => ",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131,curves=all='0/0.06 1/0.94'",
        9 | 16 => ",colorbalance=rs=.1:bs=-.1:rh=.1:bh=-.1",
        10 => ",hue=s=1.5,curves=all='0/0 0.5/0.55 1/1'",
        15 => ",curves=all='0/0 0.3/0.2 0.7/0.8 1/1',hue=s=0.8",
        _ => "",
    };
    if c.mirror {
        f += ",hflip";
    }
    f
}

fn escape_font(path: &str) -> String {
    path.replace('\\', "/").replace(':', "\\:")
}

impl Ffmpeg {
    /// Finds FFmpeg: the ZYNTAVO_FFMPEG variable, then `ffmpeg` on the PATH.
    pub fn locate() -> Option<Ffmpeg> {
        let bin = std::env::var("ZYNTAVO_FFMPEG").unwrap_or_else(|_| "ffmpeg".to_string());
        let ok = Command::new(&bin).arg("-version").stdout(Stdio::null()).stderr(Stdio::null()).status().map(|s| s.success()).unwrap_or(false);
        if ok { Some(Ffmpeg { bin }) } else { None }
    }

    /// Reads the length and whether there is sound.
    pub fn probe(&self, path: &str) -> Result<Source, String> {
        let out = Command::new(&self.bin).args(["-hide_banner", "-i", path]).output().map_err(|e| e.to_string())?;
        let text = String::from_utf8_lossy(&out.stderr).to_string();
        let dur = text.split("Duration: ").nth(1).and_then(|s| s.split(',').next()).map(|s| s.trim().to_string()).ok_or("Could not read the length of that file.")?;
        let parts: Vec<f64> = dur.split(':').filter_map(|p| p.parse::<f64>().ok()).collect();
        if parts.len() != 3 {
            return Err("Could not read the length of that file.".into());
        }
        let ms = ((parts[0] * 3600.0 + parts[1] * 60.0 + parts[2]) * 1000.0) as u64;
        let name = PathBuf::from(path).file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| path.to_string());
        Ok(Source { path: path.to_string(), name, duration_ms: ms, has_audio: text.contains(" Audio:") })
    }

    /// One picture from a video, `width` pixels wide.
    pub fn frame_at(&self, path: &str, ms: u64, width: usize) -> Result<Rgba, String> {
        let out = Command::new(&self.bin)
            .args(["-v", "error", "-ss", num(ms as f64 / 1000.0).as_str(), "-i", path, "-frames:v", "1"])
            .args(["-vf", format!("scale={}:-2", width).as_str(), "-f", "rawvideo", "-pix_fmt", "rgba", "-"])
            .output()
            .map_err(|e| e.to_string())?;
        let h = out.stdout.len() / (width * 4);
        Rgba::from_raw(width, h, out.stdout[..width * h * 4].to_vec()).ok_or_else(|| "Could not read a picture from that video.".to_string())
    }

    /// Mono 16-bit samples at the rate the beat finder uses. At most ten minutes.
    pub fn pcm(&self, path: &str, start_ms: u64, dur_ms: u64) -> Result<Vec<i16>, String> {
        let dur = if dur_ms > 0 && dur_ms <= 600_000 { dur_ms } else { 600_000 };
        let out = Command::new(&self.bin)
            .args(["-v", "error", "-ss", num(start_ms as f64 / 1000.0).as_str(), "-i", path, "-t", num(dur as f64 / 1000.0).as_str()])
            .args(["-vn", "-ac", "1", "-ar", crate::beats::RATE.to_string().as_str(), "-f", "s16le", "-"])
            .output()
            .map_err(|e| e.to_string())?;
        Ok(out.stdout.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect())
    }

    /// Builds the filter graph for a project. Also returns the text files the titles need.
    pub fn build_graph(&self, p: &Project, font: &str) -> (String, Vec<(String, String)>) {
        let s = &p.settings;
        let (w, h, fps) = (s.width, s.height, s.fps);
        let mut g = String::new();
        let n = p.clips.len();
        for (i, c) in p.clips.iter().enumerate() {
            let (a, b, d) = (c.in_ms as f64 / 1000.0, c.out_ms as f64 / 1000.0, c.dur_ms() as f64 / 1000.0);
            if c.freeze {
                g += &format!("[{i}:v]trim=start={}:end={},setpts=PTS-STARTPTS,trim=end_frame=1,tpad=stop_mode=clone:stop_duration={},trim=end={},setpts=PTS-STARTPTS", num(a), num(a + 0.3), num(d + 1.0), num(d));
            } else {
                g += &format!("[{i}:v]trim=start={}:end={},setpts=(PTS-STARTPTS)/{:.4}", num(a), num(b), c.speed);
                if c.stab > 0 {
                    let r = if c.stab > 1 { 48 } else { 20 };
                    g += &format!(",deshake=rx={r}:ry={r}:edge=mirror");
                }
            }
            if c.crop_x > 0.0 || c.crop_y > 0.0 {
                g += &format!(",crop=iw*(1-2*{:.3}):ih*(1-2*{:.3}):iw*{:.3}:ih*{:.3}", c.crop_x, c.crop_y, c.crop_x, c.crop_y);
            }
            if s.blur_bg {
                let (bw, bh) = (((w / 6) / 2 * 2).max(16), ((h / 6) / 2 * 2).max(16));
                g += &format!(",split[sa{i}][sb{i}];[sa{i}]scale={bw}:{bh}:force_original_aspect_ratio=increase,crop={bw}:{bh},gblur=sigma=4:steps=2,scale={w}:{h}:flags=bicubic,lutyuv=y='val*0.85'[bk{i}];");
                g += &format!("[sb{i}]scale={w}:{h}:force_original_aspect_ratio=decrease[fg{i}];[bk{i}][fg{i}]overlay=x='(W-w)/2':y='(H-h)/2',setsar=1,fps={fps}");
            } else {
                g += &format!(",scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={fps}");
            }
            if c.reverse && !c.freeze {
                g += ",reverse,setpts=PTS-STARTPTS";
            }
            g += &look(c);
            if c.fade_in {
                g += &format!(",fade=t=in:st=0:d={}", num(d.min(0.6)));
            }
            if c.fade_out {
                g += &format!(",fade=t=out:st={}:d={}", num((d - 0.6).max(0.0)), num(d.min(0.6)));
            }
            g += &format!(",format=yuv420p[v{i}];");
            if c.src.has_audio && !c.freeze {
                g += &format!("[{i}:a]atrim=start={}:end={},asetpts=PTS-STARTPTS,{}{},volume={:.3},aformat=sample_rates=44100:channel_layouts=stereo[a{i}];",
                    num(a), num(b), if c.reverse { "areverse," } else { "" }, atempo(c.speed), c.gain);
            } else {
                g += &format!("anullsrc=r=44100:cl=stereo,atrim=duration={}[a{i}];", num(d));
            }
        }
        for i in 0..n {
            g += &format!("[v{i}][a{i}]");
        }
        g += &format!("concat=n={n}:v=1:a=1[vc][ac];");

        // Titles: drawtext reads each text from a file, so no character in the text needs escaping.
        let mut files: Vec<(String, String)> = Vec::new();
        let mut chain: Vec<String> = Vec::new();
        for (k, t) in p.titles.iter().enumerate() {
            let name = format!("t{k}.txt");
            files.push((name.clone(), t.text.clone()));
            let (st, en) = (t.start_ms as f64 / 1000.0, t.end_ms as f64 / 1000.0);
            let alpha = if t.anim == 1 {
                format!(":alpha='clip((t-{})/0.4,0,1)*clip(({}-t)/0.3,0,1)'", num(st), num(en))
            } else {
                String::new()
            };
            chain.push(format!(
                "drawtext=fontfile='{}':textfile={}:fontsize=h*{:.3}:fontcolor=0x{:02x}{:02x}{:02x}:x=(w-text_w)*{:.3}:y=(h-text_h)*{:.3}{}:borderw=2:bordercolor=black:enable='between(t,{},{})'",
                escape_font(font), name, t.size, t.color[0], t.color[1], t.color[2], t.x, t.y, alpha, num(st), num(en)
            ));
        }
        g += &format!("[vc]{}[vout]", if chain.is_empty() { "null".to_string() } else { chain.join(",") });
        (g, files)
    }

    /// Exports a project. `progress` is called with a number from 0 to 1. Returns the name of the encoder that worked.
    pub fn export(&self, p: &Project, out_path: &str, font: &str, progress: &mut dyn FnMut(f32)) -> Result<String, String> {
        if p.clips.is_empty() {
            return Err("Add a video first.".into());
        }
        let work = std::env::temp_dir().join(format!("zyntavo-{}", std::process::id()));
        std::fs::create_dir_all(&work).map_err(|e| e.to_string())?;
        let (graph, files) = self.build_graph(p, font);
        for (name, text) in &files {
            std::fs::write(work.join(name), text).map_err(|e| e.to_string())?;
        }
        let total = p.total_ms().max(1) as f32;
        // The encoders to try, best first. LGPL builds of FFmpeg have no libx264.
        let encoders: Vec<(&str, Vec<&str>)> = vec![
            ("libx264", vec!["-crf", "20", "-preset", "medium"]),
            ("libopenh264", vec!["-b:v", "8M"]),
            ("h264_mf", vec!["-b:v", "8M"]),
            ("mpeg4", vec!["-q:v", "3"]),
        ];
        let mut last_error = String::new();
        for (enc, extra) in encoders.iter() {
            let mut cmd = Command::new(&self.bin);
            cmd.current_dir(&work).args(["-y", "-v", "error", "-progress", "pipe:1", "-nostats"]);
            for c in &p.clips {
                cmd.args(["-i", c.src.path.as_str()]);
            }
            cmd.args(["-filter_complex", graph.as_str(), "-map", "[vout]", "-map", "[ac]", "-c:v", *enc]);
            cmd.args(extra).args(["-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", out_path]);
            cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
            let mut child = cmd.spawn().map_err(|e| e.to_string())?;
            let mut err_pipe = child.stderr.take();
            let err_thread = std::thread::spawn(move || {
                let mut s = String::new();
                if let Some(e) = err_pipe.as_mut() {
                    let _ = e.read_to_string(&mut s);
                }
                s
            });
            if let Some(o) = child.stdout.take() {
                for line in BufReader::new(o).lines().map_while(Result::ok) {
                    if let Some(v) = line.strip_prefix("out_time_us=") {
                        if let Ok(us) = v.trim().parse::<f64>() {
                            progress(((us / 1000.0) as f32 / total).min(1.0));
                        }
                    }
                }
            }
            let status = child.wait().map_err(|e| e.to_string())?;
            let err_text = err_thread.join().unwrap_or_default();
            if status.success() {
                let _ = std::fs::remove_dir_all(&work);
                progress(1.0);
                return Ok(enc.to_string());
            }
            last_error = err_text;
            if !(last_error.contains("Unknown encoder") || last_error.contains("Encoder not found") || last_error.contains("not found")) {
                break;
            }
        }
        let _ = std::fs::remove_dir_all(&work);
        let tail: String = last_error.lines().rev().take(6).collect::<Vec<_>>().into_iter().rev().collect::<Vec<_>>().join("\n");
        Err(format!("FFmpeg could not export this project.\n{}", tail))
    }

    /// Writes the mask pictures an overlay would need. Kept for the overlay export that comes next.
    pub fn write_mask(&self, dir: &std::path::Path, shape: i32) -> Result<PathBuf, String> {
        let path = dir.join(format!("mask{shape}.pgm"));
        std::fs::write(&path, masks::pgm_bytes(shape, 384)).map_err(|e| e.to_string())?;
        Ok(path)
    }
}
