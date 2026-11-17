//! The auto editor. It reads a request for what the person wants, looks at the actual picture, and decides the edits.
//! It is rules and arithmetic, not a language model, so it knows a fixed set of goals, moods and places.
use crate::edit::{EditState, FILTER_NAMES};
use crate::pixels::Rgba;

/// What a picture looks like in numbers, each from 0 to 1.
#[derive(Clone, Copy, Debug)]
pub struct FrameStats {
    pub luma: f32,
    pub contrast: f32,
    pub sat: f32,
    /// Blue minus red. Above 0 is cool, below 0 is warm.
    pub cast: f32,
    pub dark: f32,
    pub bright: f32,
    pub sharp: f32,
}

pub fn analyse(img: &Rgba) -> FrameStats {
    let w = 160usize;
    let h = (img.h * 160 / img.w.max(1)).max(8);
    let small = img.resize(w, h);
    let mut l = vec![0f32; w * h];
    let (mut sum, mut sum_sq, mut sat_sum, mut r_sum, mut b_sum) = (0f64, 0f64, 0f64, 0f64, 0f64);
    let (mut dark, mut bright, mut n) = (0usize, 0usize, 0usize);
    for i in 0..w * h {
        let p = i * 4;
        let r = small.px[p] as f32 / 255.0;
        let g = small.px[p + 1] as f32 / 255.0;
        let b = small.px[p + 2] as f32 / 255.0;
        let y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        l[i] = y;
        if small.px[p + 3] < 16 {
            continue;
        }
        let mx = r.max(g).max(b);
        let mn = r.min(g).min(b);
        sum += y as f64;
        sum_sq += (y * y) as f64;
        if mx > 0.0 {
            sat_sum += ((mx - mn) / mx) as f64;
        }
        r_sum += r as f64;
        b_sum += b as f64;
        if y < 0.06 {
            dark += 1;
        }
        if y > 0.94 {
            bright += 1;
        }
        n += 1;
    }
    if n < 50 {
        return FrameStats { luma: 0.5, contrast: 0.2, sat: 0.35, cast: 0.0, dark: 0.0, bright: 0.0, sharp: 0.04 };
    }
    let nf = n as f64;
    let mean = sum / nf;
    let std = (sum_sq / nf - mean * mean).max(0.0).sqrt();
    let (mut lap, mut cnt) = (0f64, 0usize);
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let c = y * w + x;
            lap += (4.0 * l[c] - l[c - 1] - l[c + 1] - l[c - w] - l[c + w]).abs() as f64;
            cnt += 1;
        }
    }
    FrameStats {
        luma: mean as f32,
        contrast: std as f32,
        sat: (sat_sum / nf) as f32,
        cast: ((b_sum - r_sum) / nf) as f32,
        dark: dark as f32 / n as f32,
        bright: bright as f32 / n as f32,
        sharp: if cnt > 0 { (lap / cnt as f64) as f32 } else { 0.04 },
    }
}

/// The average of the numbers of several frames, for judging a whole clip.
pub fn merge(list: &[FrameStats]) -> Option<FrameStats> {
    if list.is_empty() {
        return None;
    }
    let n = list.len() as f32;
    let avg = |f: fn(&FrameStats) -> f32| list.iter().map(f).sum::<f32>() / n;
    Some(FrameStats {
        luma: avg(|s| s.luma), contrast: avg(|s| s.contrast), sat: avg(|s| s.sat), cast: avg(|s| s.cast),
        dark: avg(|s| s.dark), bright: avg(|s| s.bright), sharp: avg(|s| s.sharp),
    })
}

/// What the person is after, read from their words.
#[derive(Clone, Debug, Default)]
pub struct Goal {
    pub label: String,
    pub attention: bool,
    pub polish: bool,
    pub energetic: bool,
    pub mood: Option<String>,
    pub platform: Option<String>,
    pub subject: Option<String>,
    pub talking: bool,
    pub shaky: bool,
    pub beat: bool,
}

fn has(t: &str, keys: &[&str]) -> bool {
    keys.iter().any(|k| t.contains(k))
}

/// The goal used when the person gives no words.
pub fn general() -> Goal {
    Goal { label: "a better look".to_string(), polish: true, ..Default::default() }
}

/// Reads a goal from loose wording. Returns None if the words do not ask for an overall result.
pub fn detect(text: &str) -> Option<Goal> {
    let t = format!(" {} ", text.to_lowercase().replace('-', " ").replace('\'', ""));
    let attention = has(&t, &[
        "attract", "eye catch", "eyecatch", "catchy", "stand out", "standout", "stunning", "wow", "viral", "engag", "scroll",
        "hook", "grab", "striking", "appeal", "trending", "make it pop", "popular", "noticed", "notice it", "impress",
        "irresist", "gorgeous", "amazing", "awesome", "likes", "followers", "interest", "people love", "people like",
    ]);
    let polish = has(&t, &[
        "professional", "polish", "better", "improve", "enhance", "fix it", "look good", "looks good", "look great", "nice",
        "beautiful", "aesthetic", "pretty", "the best", "high quality", "perfect", "edit it", "edit this", "edit my", "edit the",
        "do something", "surprise me", "your choice", "on your own", "by yourself", "by itself", "automatic", "auto edit",
        "make it look", "good looking", "fancy", "clean up",
    ]);
    let energetic = has(&t, &["energetic", "energy", "hype", "fast pace", "fast paced", "punchy", "exciting", "dynamic", "upbeat", "lively", "party", "action", "sporty"]);
    let mood = if has(&t, &["dramatic", "moody", "intense", "mysterious", "dark mood"]) { Some("dramatic") }
        else if has(&t, &["calm", "peaceful", "gentle", "relax", "cozy", "serene", "minimal", "soft look"]) { Some("calm") }
        else if has(&t, &["happy", "cheerful", "sunny", "joy", "vibrant", "colorful", "colourful", "summer", "fun"]) { Some("happy") }
        else if has(&t, &["romantic", "love", "dreamy", "wedding"]) { Some("romantic") }
        else if has(&t, &["vintage", "retro", "nostalgic", "old school", "90s", "80s"]) { Some("retro") }
        else if has(&t, &["cinematic", "movie", "film look"]) { Some("cinematic") }
        else if has(&t, &["luxury", "premium", "elegant", "classy", "expensive", "fashion"]) { Some("luxury") }
        else if has(&t, &["golden", "sunset", "warm tone"]) { Some("golden") }
        else { None };
    let platform = if has(&t, &["instagram", "insta ", "insta,", "ig post"]) { Some("instagram") }
        else if has(&t, &["reel", "tiktok", "tik tok", "shorts", "story", "stories", "status", "snapchat"]) { Some("short") }
        else if has(&t, &["youtube", "thumbnail", "widescreen"]) { Some("youtube") }
        else { None };
    let subject = if has(&t, &["portrait", "selfie", "face", "people", "person", "girl", "boy", "model", "couple"]) { Some("portrait") }
        else if has(&t, &["food", "dish", "meal", "pizza", "burger", "cake", "coffee", "restaurant", "recipe"]) { Some("food") }
        else if has(&t, &["product", "shop", "sale", "brand", "advert", "store"]) { Some("product") }
        else if has(&t, &["landscape", "nature", "travel", "mountain", "beach", "sky", "scenery", "trip", "city"]) { Some("landscape") }
        else if has(&t, &["night", "neon"]) { Some("night") }
        else { None };
    let talking = has(&t, &["talking", "speech", "interview", "podcast", "vlog", "lecture", "tutorial", "dead air", "pauses", "silence", "tighten"]);
    let shaky = has(&t, &["shaky", "shake", "stabil", "steady", "smooth", "handheld", "jitter"]);
    let beat = has(&t, &["beat", "rhythm", "to the music", "sync", "bass"]);
    if !(attention || polish || energetic || platform.is_some() || talking || shaky || beat) {
        return None;
    }
    let mut parts: Vec<String> = Vec::new();
    if attention { parts.push("eye-catching".into()); }
    if polish { parts.push("polished".into()); }
    if energetic { parts.push("energetic".into()); }
    if let Some(m) = mood { parts.push(format!("{} mood", m)); }
    match platform {
        Some("instagram") => parts.push("for Instagram".into()),
        Some("short") => parts.push("for short video".into()),
        Some("youtube") => parts.push("for YouTube".into()),
        _ => {}
    }
    if let Some(s) = subject { parts.push(s.to_string()); }
    if talking { parts.push("tighter talking".into()); }
    if shaky { parts.push("steadier".into()); }
    if beat { parts.push("on the beat".into()); }
    Some(Goal {
        label: parts.join(", "), attention, polish, energetic,
        mood: mood.map(|s| s.to_string()), platform: platform.map(|s| s.to_string()), subject: subject.map(|s| s.to_string()),
        talking, shaky, beat,
    })
}

/// The edits chosen for one picture, with a plain-words list of what was done and why.
#[derive(Clone, Debug)]
pub struct Tune {
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
    pub shadows: f32,
    pub highlights: f32,
    pub warmth: f32,
    pub sharpen: f32,
    pub vignette: f32,
    pub fade: f32,
    pub grain: f32,
    pub filter: Option<usize>,
    pub ratio: Option<f32>,
    pub notes: Vec<String>,
}

impl Tune {
    pub fn apply_to(&self, s: &mut EditState) {
        s.brightness = self.brightness;
        s.contrast = self.contrast;
        s.saturation = self.saturation;
        s.shadows = self.shadows;
        s.highlights = self.highlights;
        s.warmth = self.warmth;
        s.sharpen = self.sharpen;
        s.vignette = self.vignette;
        s.fade = self.fade;
        s.grain = self.grain;
        if let Some(f) = self.filter {
            s.filter = f;
        }
        if self.ratio.is_some() {
            s.ratio = self.ratio;
        }
    }
}

/// Chooses brightness, contrast, colour and finishing from how the picture looks and what the person wants.
pub fn tune(st: &FrameStats, g: &Goal) -> Tune {
    let mut notes: Vec<String> = Vec::new();
    let mood = g.mood.as_deref();
    let subject = g.subject.as_deref();
    let target_l = match mood {
        Some("dramatic") => 0.38, Some("luxury") => 0.40, Some("happy") => 0.55, Some("calm") => 0.52,
        _ => if matches!(subject, Some("food") | Some("product")) { 0.53 } else { 0.48 },
    };
    let target_c = match mood {
        Some("dramatic") => 0.27, Some("calm") => 0.19, Some("luxury") => 0.25,
        _ => if g.attention { 0.25 } else { 0.22 },
    };
    let target_s = match mood {
        Some("calm") => 0.26, Some("luxury") => 0.28, Some("dramatic") => 0.34, Some("happy") => 0.48,
        _ => match subject {
            Some("food") => 0.50, Some("portrait") => 0.34,
            _ => if g.attention { 0.42 } else { 0.38 },
        },
    };

    let dl = target_l - st.luma;
    let brightness = if dl.abs() > 0.04 { (dl * 0.9).clamp(-0.22, 0.22) } else { 0.0 };
    if brightness > 0.04 { notes.push("Brightened it, because it looked dark.".into()); }
    else if brightness < -0.04 { notes.push("Darkened it a little, because it looked washed out.".into()); }

    let dc = target_c - st.contrast;
    let contrast = if dc.abs() > 0.02 { 1.0 + (dc * 1.5).clamp(-0.12, 0.3) } else { 1.0 };
    if contrast > 1.04 { notes.push("Added contrast so the picture stands out.".into()); }
    else if contrast < 0.96 { notes.push("Softened the contrast.".into()); }

    let ds = target_s - st.sat;
    let saturation = if ds.abs() > 0.03 { 1.0 + (ds * 1.3).clamp(-0.3, 0.4) } else { 1.0 };
    if saturation > 1.05 { notes.push("Made the colours stronger.".into()); }
    else if saturation < 0.95 { notes.push("Toned the colours down.".into()); }

    let shadows = if st.dark > 0.1 || st.luma < 0.34 { (0.25 + st.dark).min(0.55) } else { 0.0 };
    if shadows > 0.0 { notes.push("Lifted the dark areas so the details show.".into()); }
    let highlights = if st.bright > 0.05 { (-0.15 - st.bright * 2.0).max(-0.5) } else { 0.0 };
    if highlights < 0.0 { notes.push("Pulled back the bright spots.".into()); }

    let mut warmth = 0.0f32;
    if st.cast.abs() > 0.1 && !matches!(subject, Some("landscape") | Some("night")) {
        warmth += (st.cast * 1.6).clamp(-0.3, 0.3);
        notes.push("Corrected a colour cast.".into());
    }
    let mood_warm = match mood {
        Some("happy") => 0.08, Some("romantic") => 0.10, Some("golden") => 0.15, Some("calm") => -0.06,
        _ => match subject { Some("food") => 0.10, Some("portrait") => 0.05, _ => 0.0 },
    };
    if mood_warm > 0.0 { notes.push("Warmed the tones.".into()); }
    else if mood_warm < 0.0 { notes.push("Cooled the tones slightly.".into()); }
    warmth = (warmth + mood_warm).clamp(-0.35, 0.35);

    let sharpen = if st.sharp < 0.02 { 0.6 }
        else if st.sharp < 0.035 { 0.4 }
        else if g.attention || matches!(subject, Some("product") | Some("food")) { 0.3 }
        else { 0.2 };
    notes.push(if st.sharp < 0.035 { "Sharpened it, because it looked soft.".into() } else { "Added a little sharpness.".into() });

    let vignette = match mood {
        Some("dramatic") => 0.35, Some("luxury") => 0.25,
        Some("cinematic") | Some("romantic") => 0.22,
        _ => if g.attention || subject == Some("portrait") { 0.22 } else { 0.0 },
    };
    if vignette > 0.0 { notes.push("Darkened the corners to draw the eye to the centre.".into()); }
    let fade = match mood { Some("retro") => 0.25, Some("calm") => 0.12, _ => 0.0 };
    let grain = match mood { Some("retro") => 0.35, Some("cinematic") => 0.12, _ => 0.0 };
    if grain > 0.0 { notes.push("Added a touch of film grain.".into()); }

    let filter = match mood {
        Some("cinematic") => Some(16), Some("retro") => Some(20), Some("romantic") => Some(18),
        Some("dramatic") => Some(15), Some("luxury") => Some(14), Some("golden") => Some(19), _ => None,
    };
    if let (Some(f), Some(m)) = (filter, mood) {
        notes.push(format!("Used the {} look for the {} mood.", FILTER_NAMES[f], m));
    }
    let ratio = match g.platform.as_deref() {
        Some("instagram") => { notes.push("Cropped to 4:5 for Instagram.".into()); Some(0.8) }
        Some("short") => { notes.push("Cropped to 9:16 for short video.".into()); Some(9.0 / 16.0) }
        Some("youtube") => { notes.push("Cropped to 16:9 for YouTube.".into()); Some(16.0 / 9.0) }
        _ => None,
    };
    Tune { brightness, contrast, saturation, shadows, highlights, warmth, sharpen, vignette, fade, grain, filter, ratio, notes }
}
