//! Follows a point through a video by block matching: a small square around the point is compared with every
//! position nearby in the next picture, and the position that differs least wins.
use crate::model::Clip;
use crate::pixels::Rgba;

/// Where a tracked object was on the timeline: x and y from 0 to 1 across the frame.
#[derive(Clone, Copy, Debug)]
pub struct TrackPt {
    pub t_ms: u64,
    pub x: f32,
    pub y: f32,
}

/// The position at a time, holding the first and last point outside the track.
pub fn track_at(pts: &[TrackPt], ms: u64) -> Option<(f32, f32)> {
    let first = pts.first()?;
    let last = pts.last()?;
    if ms <= first.t_ms {
        return Some((first.x, first.y));
    }
    if ms >= last.t_ms {
        return Some((last.x, last.y));
    }
    let i = pts.iter().rposition(|p| p.t_ms <= ms)?;
    let (a, b) = (pts[i], pts[i + 1]);
    let p = (ms - a.t_ms) as f32 / (b.t_ms - a.t_ms).max(1) as f32;
    Some((a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p))
}

pub struct Gray {
    pub w: usize,
    pub h: usize,
    pub v: Vec<i32>,
}

pub fn gray(img: &Rgba) -> Gray {
    let w = 160usize;
    let h = (img.h * 160 / img.w.max(1)).max(8);
    let s = img.resize(w, h);
    let mut v = vec![0i32; w * h];
    for i in 0..w * h {
        let p = i * 4;
        v[i] = (s.px[p] as i32 * 77 + s.px[p + 1] as i32 * 150 + s.px[p + 2] as i32 * 29) >> 8;
    }
    Gray { w, h, v }
}

/// Finds where the square around (px, py) in `a` went in `b`.
pub fn search(a: &Gray, px: i32, py: i32, b: &Gray) -> (i32, i32) {
    let (half, range) = (10i32, 14i32);
    let mut best = i64::MAX;
    let (mut bx, mut by) = (px, py);
    let clampa = |v: i32, max: usize| v.clamp(0, max as i32 - 1) as usize;
    for dy in -range..=range {
        for dx in -range..=range {
            let mut sad = 0i64;
            for j in -half..=half {
                let ya = clampa(py + j, a.h);
                let yb = clampa(py + dy + j, b.h);
                for i in -half..=half {
                    let xa = clampa(px + i, a.w);
                    let xb = clampa(px + dx + i, b.w);
                    sad += (a.v[ya * a.w + xa] - b.v[yb * b.w + xb]).abs() as i64;
                }
            }
            sad += (dx * dx + dy * dy) as i64;
            if sad < best {
                best = sad;
                bx = px + dx;
                by = py + dy;
            }
        }
    }
    (bx.clamp(0, b.w as i32 - 1), by.clamp(0, b.h as i32 - 1))
}

/// Where the video picture sits inside the output frame: left, top, width, height, each from 0 to 1.
fn fit(frame_aspect: f32, out_aspect: f32) -> [f32; 4] {
    if frame_aspect >= out_aspect {
        let h = out_aspect / frame_aspect;
        [0.0, (1.0 - h) / 2.0, 1.0, h]
    } else {
        let w = frame_aspect / out_aspect;
        [(1.0 - w) / 2.0, 0.0, w, 1.0]
    }
}

fn cropped_aspect(c: &Clip, fa: f32) -> f32 {
    fa * (1.0 - 2.0 * c.crop_x) / (1.0 - 2.0 * c.crop_y)
}

/// A point in the source picture to the same point in the output frame, after crop, fit and mirror.
pub fn src_to_out(c: &Clip, fa: f32, oa: f32, sx: f32, sy: f32) -> (f32, f32) {
    let x = (sx - c.crop_x) / (1.0 - 2.0 * c.crop_x);
    let y = (sy - c.crop_y) / (1.0 - 2.0 * c.crop_y);
    let r = fit(cropped_aspect(c, fa), oa);
    let mut ox = r[0] + x * r[2];
    let oy = r[1] + y * r[3];
    if c.mirror {
        ox = 1.0 - ox;
    }
    (ox, oy)
}

/// The other way round.
pub fn out_to_src(c: &Clip, fa: f32, oa: f32, ox0: f32, oy: f32) -> (f32, f32) {
    let ox = if c.mirror { 1.0 - ox0 } else { ox0 };
    let r = fit(cropped_aspect(c, fa), oa);
    let x = ((ox - r[0]) / r[2]).clamp(0.0, 1.0);
    let y = ((oy - r[1]) / r[3]).clamp(0.0, 1.0);
    (c.crop_x + x * (1.0 - 2.0 * c.crop_x), c.crop_y + y * (1.0 - 2.0 * c.crop_y))
}
