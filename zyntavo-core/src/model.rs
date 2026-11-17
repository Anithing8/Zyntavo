//! The video project: clips on a timeline, titles and the export settings.

#[derive(Clone, Debug, PartialEq)]
pub struct Source {
    pub path: String,
    pub name: String,
    pub duration_ms: u64,
    pub has_audio: bool,
}

#[derive(Clone, Debug)]
pub struct Clip {
    pub src: Source,
    pub in_ms: u64,
    pub out_ms: u64,
    pub speed: f32,
    /// An index into `edit::FILTER_NAMES`.
    pub filter: usize,
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
    pub mirror: bool,
    pub crop_x: f32,
    pub crop_y: f32,
    pub gain: f32,
    pub reverse: bool,
    /// A single picture held for the length of the clip.
    pub freeze: bool,
    /// 0 off, 1 normal, 2 strong steadying.
    pub stab: u8,
    pub fade_in: bool,
    pub fade_out: bool,
}

impl Clip {
    pub fn new(src: Source) -> Clip {
        let out = src.duration_ms;
        Clip {
            src, in_ms: 0, out_ms: out, speed: 1.0, filter: 0, brightness: 0.0, contrast: 1.0, saturation: 1.0,
            mirror: false, crop_x: 0.0, crop_y: 0.0, gain: 1.0, reverse: false, freeze: false, stab: 0,
            fade_in: false, fade_out: false,
        }
    }

    /// How long the clip plays on the timeline.
    pub fn dur_ms(&self) -> u64 {
        ((self.out_ms.saturating_sub(self.in_ms)) as f32 / self.speed.max(0.05)) as u64
    }
}

#[derive(Clone, Debug)]
pub struct Title {
    pub text: String,
    pub start_ms: u64,
    pub end_ms: u64,
    pub x: f32,
    pub y: f32,
    /// The font height as a share of the frame height.
    pub size: f32,
    pub color: [u8; 3],
    /// 0 none, 1 fade.
    pub anim: u8,
}

#[derive(Clone, Debug)]
pub struct ExportSettings {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub crf: u32,
    /// Fill the empty bars with a blurred, darker copy of the picture.
    pub blur_bg: bool,
}

pub const ASPECT_NAMES: [&str; 4] = ["16:9", "9:16", "1:1", "4:5"];

impl ExportSettings {
    /// A frame of the given short-side height (such as 720 or 1080) and shape (an index into `ASPECT_NAMES`).
    pub fn new(height: u32, aspect: usize, fps: u32) -> ExportSettings {
        let (w, h) = match aspect {
            1 => (height, height * 16 / 9),
            2 => (height, height),
            3 => (height, height * 5 / 4),
            _ => (height * 16 / 9, height),
        };
        ExportSettings { width: w / 2 * 2, height: h / 2 * 2, fps, crf: 20, blur_bg: false }
    }
}

#[derive(Clone, Debug)]
pub struct Project {
    pub clips: Vec<Clip>,
    pub titles: Vec<Title>,
    pub settings: ExportSettings,
}

impl Project {
    pub fn new() -> Project {
        Project { clips: Vec::new(), titles: Vec::new(), settings: ExportSettings::new(720, 0, 30) }
    }

    pub fn total_ms(&self) -> u64 {
        self.clips.iter().map(|c| c.dur_ms()).sum()
    }

    /// The start of each clip on the timeline.
    pub fn starts(&self) -> Vec<u64> {
        let mut t = 0;
        self.clips
            .iter()
            .map(|c| {
                let s = t;
                t += c.dur_ms();
                s
            })
            .collect()
    }

    /// Which clip is playing at a timeline time, and the matching time inside its source.
    pub fn locate(&self, ms: u64) -> Option<(usize, u64)> {
        let starts = self.starts();
        for (i, c) in self.clips.iter().enumerate() {
            let end = starts[i] + c.dur_ms();
            if ms < end || i == self.clips.len() - 1 {
                let local = ms.saturating_sub(starts[i]).min(c.dur_ms());
                let off = (local as f32 * c.speed) as u64;
                let src = if c.freeze {
                    c.in_ms
                } else if c.reverse {
                    c.out_ms.saturating_sub(off).max(c.in_ms)
                } else {
                    c.in_ms + off
                };
                return Some((i, src));
            }
        }
        None
    }

    /// Splits the clip at a timeline time. Returns false if the time is too close to an edge.
    pub fn split_at(&mut self, ms: u64) -> bool {
        let (i, t) = match self.locate(ms) {
            Some(v) => v,
            None => return false,
        };
        let c = self.clips[i].clone();
        if c.freeze || t <= c.in_ms + 150 || t + 150 >= c.out_ms {
            return false;
        }
        let (mut first, mut second) = (c.clone(), c);
        if first.reverse {
            // A reversed clip plays from its end, so the part after the cut is the earlier part of the source.
            first.in_ms = t;
            second.out_ms = t;
        } else {
            first.out_ms = t;
            second.in_ms = t;
        }
        self.clips[i] = first;
        self.clips.insert(i + 1, second);
        true
    }

    /// Splits on the given beat times (every `every`-th one). Returns how many cuts were made.
    pub fn cut_at_beats(&mut self, beats_ms: &[u64], every: usize) -> usize {
        let end = self.total_ms();
        let mut n = 0;
        for (i, b) in beats_ms.iter().enumerate() {
            if i % every.max(1) == 0 && *b > 0 && *b < end && self.split_at(*b) {
                n += 1;
            }
        }
        n
    }

    /// Cuts out quiet stretches (start, end in source milliseconds from the clip's in point) of one clip.
    pub fn remove_silences(&mut self, clip: usize, quiet: &[(u64, u64)]) -> u64 {
        let c = match self.clips.get(clip) {
            Some(c) => c.clone(),
            None => return 0,
        };
        let pad = 120;
        let cuts: Vec<(u64, u64)> = quiet.iter().filter(|(a, b)| b.saturating_sub(*a) > 2 * pad + 250).map(|(a, b)| (a + pad, b - pad)).collect();
        if cuts.is_empty() {
            return 0;
        }
        let mut parts: Vec<Clip> = Vec::new();
        let mut from = c.in_ms;
        for (a, b) in &cuts {
            let (x, y) = (c.in_ms + a, c.in_ms + b);
            if x > from + 200 {
                let mut p = c.clone();
                p.in_ms = from;
                p.out_ms = x;
                parts.push(p);
            }
            from = y;
        }
        if c.out_ms > from + 200 {
            let mut p = c.clone();
            p.in_ms = from;
            parts.push(p);
        }
        if parts.is_empty() {
            return 0;
        }
        let removed: u64 = cuts.iter().map(|(a, b)| b - a).sum();
        self.clips.remove(clip);
        for (k, p) in parts.into_iter().enumerate() {
            self.clips.insert(clip + k, p);
        }
        removed
    }
}
