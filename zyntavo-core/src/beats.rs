//! Finds the beat of music and the quiet parts of speech, from plain samples (mono, 11025 Hz), with its own small FFT.
use std::f64::consts::PI;

pub const RATE: usize = 11025;
const N: usize = 1024;
const HOP: usize = 256;

pub struct BeatResult {
    pub bpm: f64,
    pub beats_ms: Vec<u64>,
}

fn fft(re: &mut [f64], im: &mut [f64]) {
    let n = re.len();
    let mut j = 0usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2usize;
    while len <= n {
        let ang = -2.0 * PI / len as f64;
        let (wi, wr) = ang.sin_cos();
        let mut i = 0usize;
        while i < n {
            let (mut cr, mut ci) = (1.0f64, 0.0f64);
            for k in 0..len / 2 {
                let a = i + k;
                let b = i + k + len / 2;
                let (ur, ui) = (re[a], im[a]);
                let xr = re[b] * cr - im[b] * ci;
                let xi = re[b] * ci + im[b] * cr;
                re[a] = ur + xr;
                im[a] = ui + xi;
                re[b] = ur - xr;
                im[b] = ui - xi;
                let nr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = nr;
            }
            i += len;
        }
        len <<= 1;
    }
}

/// How much new sound starts in each step of about 23 ms (spectral flux), with the slow level removed.
fn onset_envelope(s: &[i16]) -> Vec<f32> {
    if s.len() < N {
        return Vec::new();
    }
    let frames = (s.len() - N) / HOP + 1;
    let win: Vec<f64> = (0..N).map(|i| 0.5 - 0.5 * (2.0 * PI * i as f64 / (N - 1) as f64).cos()).collect();
    let mut re = vec![0f64; N];
    let mut im = vec![0f64; N];
    let mut prev = vec![0f64; N / 2];
    let mut env = vec![0f32; frames];
    for f in 0..frames {
        for i in 0..N {
            re[i] = s[f * HOP + i] as f64 / 32768.0 * win[i];
            im[i] = 0.0;
        }
        fft(&mut re, &mut im);
        let mut flux = 0.0;
        for k in 4..460 {
            let mag = (1.0 + 10.0 * (re[k] * re[k] + im[k] * im[k]).sqrt()).ln();
            let d = mag - prev[k];
            if d > 0.0 {
                flux += d;
            }
            prev[k] = mag;
        }
        env[f] = flux as f32;
    }
    let w = 21usize;
    let mut out = vec![0f32; frames];
    for i in 0..frames {
        let lo = i.saturating_sub(w);
        let hi = (i + w).min(frames - 1);
        let sum: f32 = env[lo..=hi].iter().sum();
        out[i] = (env[i] - sum / (hi - lo + 1) as f32).max(0.0);
    }
    out
}

/// Finds the tempo and the time of every beat. None if the sound is too short or has no clear beat.
pub fn detect(s: &[i16]) -> Option<BeatResult> {
    let env = onset_envelope(s);
    if env.len() < 200 {
        return None;
    }
    let hop_sec = HOP as f64 / RATE as f64;
    let min_lag = (60.0 / (180.0 * hop_sec)) as usize;
    let max_lag = (60.0 / (70.0 * hop_sec)) as usize + 1;
    let mut ac = vec![0f64; max_lag + 2];
    for lag in min_lag.saturating_sub(1).max(1)..=max_lag + 1 {
        let mut sum = 0.0;
        for i in 0..env.len() - lag {
            sum += env[i] as f64 * env[i + lag] as f64;
        }
        ac[lag] = sum / (env.len() - lag) as f64;
    }
    let (mut best_lag, mut best_score) = (min_lag, -1.0f64);
    for lag in min_lag..=max_lag {
        let bpm = 60.0 / (lag as f64 * hop_sec);
        let z = (bpm / 120.0).ln() / 0.6;
        let score = ac[lag] * (-0.5 * z * z).exp();
        if score > best_score {
            best_score = score;
            best_lag = lag;
        }
    }
    if best_score <= 0.0 {
        return None;
    }
    let (a, b, c) = (ac[best_lag - 1], ac[best_lag], ac[best_lag + 1]);
    let denom = a - 2.0 * b + c;
    let lag_f = if denom.abs() > 1e-9 { best_lag as f64 + 0.5 * (a - c) / denom } else { best_lag as f64 };
    let period = lag_f.max(min_lag as f64).min(max_lag as f64);
    let bpm = 60.0 / (period * hop_sec);

    // The beat grid: the start offset that puts the most onset energy on the beats.
    let (mut best_phase, mut best_sum) = (0.0f64, -1.0f64);
    let mut ph = 0.0f64;
    while ph < period {
        let (mut sum, mut pos) = (0.0f64, ph);
        while pos < (env.len() - 1) as f64 {
            let ix = (pos.round() as usize).min(env.len() - 1);
            let m = env[ix].max(env[ix.saturating_sub(1)]).max(env[(ix + 1).min(env.len() - 1)]);
            sum += m as f64;
            pos += period;
        }
        if sum > best_sum {
            best_sum = sum;
            best_phase = ph;
        }
        ph += 0.5;
    }
    let mut beats: Vec<u64> = Vec::new();
    let mut pos = best_phase;
    while pos < (env.len() - 1) as f64 {
        let c0 = pos.round() as usize;
        let reach = ((period * 0.12) as usize).max(1);
        let mut bi = c0.min(env.len() - 1);
        for k in c0.saturating_sub(reach)..=(c0 + reach).min(env.len() - 1) {
            if env[k] > env[bi] {
                bi = k;
            }
        }
        beats.push((bi as f64 * hop_sec * 1000.0) as u64);
        pos += period;
    }
    beats.sort();
    beats.dedup();
    Some(BeatResult { bpm, beats_ms: beats })
}

/// Quiet stretches of speech: (start, end) in milliseconds that stay below `threshold_db` for at least `min_ms`.
pub fn silences(s: &[i16], threshold_db: f64, min_ms: u64) -> Vec<(u64, u64)> {
    let win = RATE / 50;
    let windows = s.len() / win;
    let mut out = Vec::new();
    let mut start: Option<usize> = None;
    for w in 0..=windows {
        let mut quiet = false;
        if w < windows {
            let mut sum = 0.0;
            for i in 0..win {
                let v = s[w * win + i] as f64 / 32768.0;
                sum += v * v;
            }
            let db = 20.0 * ((sum / win as f64).sqrt() + 1e-9).log10();
            quiet = db < threshold_db;
        }
        if quiet && start.is_none() {
            start = Some(w);
        }
        if !quiet {
            if let Some(st) = start.take() {
                let (from, to) = (st as u64 * 20, w as u64 * 20);
                if to - from >= min_ms {
                    out.push((from, to));
                }
            }
        }
    }
    out
}
