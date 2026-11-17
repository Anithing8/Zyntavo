use crate::pixels::Rgba;

pub const FILTER_NAMES: [&str; 23] = [
    "None", "B&W", "Sepia", "Warm", "Cool", "Vivid", "Fade", "Noir", "Vintage", "Cinema", "Pop", "Sunset", "Forest",
    "Ocean", "Matte", "Dramatic", "Teal & Orange", "Mono Blue", "Rose", "Golden hour", "Faded film", "High key", "Low key",
];

/// Everything a photo edit can change. The default changes nothing.
#[derive(Clone, Debug, PartialEq)]
pub struct EditState {
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
    pub exposure: f32,
    pub warmth: f32,
    pub fade: f32,
    pub vignette: f32,
    pub sharpen: f32,
    pub blur: f32,
    pub highlights: f32,
    pub shadows: f32,
    pub grain: f32,
    pub filter: usize,
    /// Quarter turns clockwise.
    pub rotation: i32,
    pub flip_h: bool,
    pub flip_v: bool,
    /// Width divided by height to crop to, or None to keep the shape.
    pub ratio: Option<f32>,
}

impl Default for EditState {
    fn default() -> Self {
        EditState {
            brightness: 0.0, contrast: 1.0, saturation: 1.0, exposure: 0.0, warmth: 0.0, fade: 0.0, vignette: 0.0,
            sharpen: 0.0, blur: 0.0, highlights: 0.0, shadows: 0.0, grain: 0.0, filter: 0, rotation: 0,
            flip_h: false, flip_v: false, ratio: None,
        }
    }
}

fn luma(c: [f32; 3]) -> f32 {
    0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}

fn saturate(c: [f32; 3], k: f32) -> [f32; 3] {
    let l = luma(c);
    [l + (c[0] - l) * k, l + (c[1] - l) * k, l + (c[2] - l) * k]
}

fn contrast(c: [f32; 3], k: f32) -> [f32; 3] {
    [(c[0] - 0.5) * k + 0.5, (c[1] - 0.5) * k + 0.5, (c[2] - 0.5) * k + 0.5]
}

fn mix(a: [f32; 3], b: [f32; 3], t: f32) -> [f32; 3] {
    [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]
}

fn tint(c: [f32; 3], m: [f32; 3], add: [f32; 3]) -> [f32; 3] {
    [c[0] * m[0] + add[0], c[1] * m[1] + add[1], c[2] * m[2] + add[2]]
}

/// One of the named looks, applied to a single pixel with channels from 0 to 1.
pub fn filter_px(idx: usize, c: [f32; 3]) -> [f32; 3] {
    let l = luma(c);
    match idx {
        1 => [l, l, l],
        2 => [
            0.393 * c[0] + 0.769 * c[1] + 0.189 * c[2],
            0.349 * c[0] + 0.686 * c[1] + 0.168 * c[2],
            0.272 * c[0] + 0.534 * c[1] + 0.131 * c[2],
        ],
        3 => tint(c, [1.06, 1.0, 0.9], [0.02, 0.0, 0.0]),
        4 => tint(c, [0.92, 1.0, 1.08], [0.0, 0.0, 0.02]),
        5 => saturate(contrast(c, 1.08), 1.35),
        6 => saturate(mix(c, [0.55, 0.55, 0.55], 0.2), 0.85),
        7 => {
            let v = (l - 0.5) * 1.35 + 0.5;
            [v, v, v]
        }
        8 => mix(filter_px(2, c), mix(c, [0.55, 0.5, 0.45], 0.25), 0.5),
        9 | 16 => {
            let k = if idx == 16 { 0.2 } else { 0.12 };
            contrast([c[0] + (l - 0.5) * k, c[1], c[2] - (l - 0.5) * k], 1.1)
        }
        10 => saturate(contrast(c, 1.12), 1.5),
        11 => saturate(tint(c, [1.1, 0.95, 0.85], [0.03, 0.0, 0.0]), 1.1),
        12 => tint(c, [0.92, 1.06, 0.9], [0.0, 0.0, 0.0]),
        13 => tint(c, [0.88, 1.0, 1.1], [0.0, 0.0, 0.0]),
        14 => tint(contrast(c, 0.85), [1.0, 1.0, 1.0], [0.05, 0.05, 0.05]),
        15 => saturate(contrast(c, 1.3), 0.8),
        17 => [l * 0.8, l * 0.95, l * 1.1],
        18 => tint(c, [1.08, 0.95, 1.02], [0.02, 0.0, 0.0]),
        19 => tint(c, [1.1, 1.02, 0.8], [0.03, 0.0, 0.0]),
        20 => tint(mix(c, [0.55, 0.55, 0.55], 0.25), [1.05, 1.0, 0.92], [0.02, 0.0, 0.0]),
        21 => tint(contrast(c, 0.9), [1.0, 1.0, 1.0], [0.1, 0.1, 0.1]),
        22 => tint(contrast(c, 1.2), [1.0, 1.0, 1.0], [-0.08, -0.08, -0.08]),
        _ => c,
    }
}

fn clamp01(v: f32) -> f32 {
    v.max(0.0).min(1.0)
}

fn tone(c: f32, adj: f32) -> f32 {
    if adj > 0.0 { c + adj * (1.0 - c) } else { c * (1.0 + adj) }
}

/// A box blur of the colour channels, along rows and then columns. Alpha is kept.
fn box_blur(src: &[u8], w: usize, h: usize, r: usize) -> Vec<u8> {
    if r < 1 {
        return src.to_vec();
    }
    let mut tmp = vec![0u8; src.len()];
    let mut out = vec![0u8; src.len()];
    blur_pass(src, &mut tmp, w, h, r, true);
    blur_pass(&tmp, &mut out, w, h, r, false);
    out
}

fn blur_pass(src: &[u8], dst: &mut [u8], w: usize, h: usize, r: usize, horizontal: bool) {
    let (lines, len) = if horizontal { (h, w) } else { (w, h) };
    let at = |line: usize, i: usize| -> usize {
        if horizontal { (line * w + i) * 4 } else { (i * w + line) * 4 }
    };
    let div = (2 * r + 1) as i32;
    for line in 0..lines {
        let mut sum = [0i32; 3];
        for k in -(r as i64)..=(r as i64) {
            let i = k.clamp(0, len as i64 - 1) as usize;
            let p = at(line, i);
            for c in 0..3 {
                sum[c] += src[p + c] as i32;
            }
        }
        for i in 0..len {
            let p = at(line, i);
            for c in 0..3 {
                dst[p + c] = (sum[c] / div) as u8;
            }
            dst[p + 3] = src[p + 3];
            let add = at(line, (i + r + 1).min(len - 1));
            let rem = at(line, i.saturating_sub(r));
            for c in 0..3 {
                sum[c] += src[add + c] as i32 - src[rem + c] as i32;
            }
        }
    }
}

/// Applies an edit to a picture and returns the result. The original is not changed.
pub fn render(src: &Rgba, s: &EditState) -> Rgba {
    let mut img = match s.ratio {
        Some(r) if r > 0.05 => src.crop_to_ratio(r),
        _ => src.clone(),
    };
    if s.rotation.rem_euclid(4) != 0 {
        img = img.rotate90(s.rotation);
    }
    if s.flip_h {
        img.flip_h();
    }
    if s.flip_v {
        img.flip_v();
    }
    let (w, h) = (img.w, img.h);
    let exp = 2f32.powf(s.exposure);

    // Colour: exposure, brightness, contrast, colour strength, warmth, fade and the look.
    let need_tone = s.shadows != 0.0 || s.highlights != 0.0;
    for i in 0..w * h {
        let p = i * 4;
        let mut c = [img.px[p] as f32 / 255.0, img.px[p + 1] as f32 / 255.0, img.px[p + 2] as f32 / 255.0];
        for v in c.iter_mut() {
            *v = *v * exp + s.brightness;
        }
        c = contrast(c, s.contrast);
        c = saturate(c, s.saturation);
        c[0] += s.warmth * 0.1;
        c[2] -= s.warmth * 0.1;
        if s.fade > 0.0 {
            for v in c.iter_mut() {
                *v = *v * (1.0 - s.fade * 0.3) + s.fade * 0.12;
            }
        }
        c = [clamp01(c[0]), clamp01(c[1]), clamp01(c[2])];
        if s.filter > 0 {
            c = filter_px(s.filter, c);
        }
        if need_tone {
            let l = luma(c);
            let adj = s.shadows * 0.5 * (1.0 - l) * (1.0 - l) + s.highlights * 0.5 * l * l;
            c = [tone(c[0], adj), tone(c[1], adj), tone(c[2], adj)];
        }
        for k in 0..3 {
            img.px[p + k] = (clamp01(c[k]) * 255.0) as u8;
        }
    }

    let scale = (w.max(h) as f32 / 2048.0).max(0.25);
    if s.blur > 0.0 {
        let r = ((s.blur * 14.0 * scale) as usize).max(1);
        img.px = box_blur(&box_blur(&img.px, w, h, r), w, h, r);
    }
    if s.sharpen > 0.0 {
        let soft = box_blur(&img.px, w, h, 2);
        let amount = s.sharpen * 1.6;
        for i in 0..w * h {
            let p = i * 4;
            for c in 0..3 {
                let v = img.px[p + c] as f32;
                let o = v + amount * (v - soft[p + c] as f32);
                img.px[p + c] = o.max(0.0).min(255.0) as u8;
            }
        }
    }
    if s.vignette > 0.0 {
        let (cx, cy) = (w as f32 / 2.0, h as f32 / 2.0);
        for y in 0..h {
            for x in 0..w {
                let dx = (x as f32 - cx) / cx;
                let dy = (y as f32 - cy) / cy;
                let d = ((dx * dx + dy * dy).sqrt() / 1.414).min(1.0);
                let t = ((d - 0.4) / 0.6).max(0.0).min(1.0);
                let f = 1.0 - s.vignette * t * t * (3.0 - 2.0 * t);
                let p = (y * w + x) * 4;
                for c in 0..3 {
                    img.px[p + c] = (img.px[p + c] as f32 * f) as u8;
                }
            }
        }
    }
    if s.grain > 0.0 {
        let amt = s.grain * 40.0;
        let mut seed: u32 = 12345;
        for i in 0..w * h {
            seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
            let n = (((seed >> 16) & 255) as f32 - 128.0) / 128.0 * amt;
            let p = i * 4;
            for c in 0..3 {
                img.px[p + c] = (img.px[p + c] as f32 + n).max(0.0).min(255.0) as u8;
            }
        }
    }
    img
}
