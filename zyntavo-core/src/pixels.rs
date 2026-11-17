/// A picture as 8-bit RGBA, row by row.
#[derive(Clone, Debug)]
pub struct Rgba {
    pub w: usize,
    pub h: usize,
    pub px: Vec<u8>,
}

impl Rgba {
    pub fn from_raw(w: usize, h: usize, px: Vec<u8>) -> Option<Rgba> {
        if w == 0 || h == 0 || px.len() != w * h * 4 {
            return None;
        }
        Some(Rgba { w, h, px })
    }

    /// Scales to the given size. Shrinking averages the pixels each new pixel covers.
    pub fn resize(&self, nw: usize, nh: usize) -> Rgba {
        let nw = nw.max(1);
        let nh = nh.max(1);
        let mut out = vec![0u8; nw * nh * 4];
        for y in 0..nh {
            let y0 = y * self.h / nh;
            let y1 = ((((y + 1) * self.h) + nh - 1) / nh).min(self.h).max(y0 + 1);
            for x in 0..nw {
                let x0 = x * self.w / nw;
                let x1 = ((((x + 1) * self.w) + nw - 1) / nw).min(self.w).max(x0 + 1);
                let mut acc = [0u32; 4];
                let mut n = 0u32;
                for yy in y0..y1 {
                    for xx in x0..x1 {
                        let i = (yy * self.w + xx) * 4;
                        for c in 0..4 {
                            acc[c] += self.px[i + c] as u32;
                        }
                        n += 1;
                    }
                }
                let o = (y * nw + x) * 4;
                for c in 0..4 {
                    out[o + c] = (acc[c] / n) as u8;
                }
            }
        }
        Rgba { w: nw, h: nh, px: out }
    }

    /// Shrinks so the long side is at most `max_side`. Never enlarges.
    pub fn fit(&self, max_side: usize) -> Rgba {
        let m = self.w.max(self.h);
        if m <= max_side {
            return self.clone();
        }
        let k = max_side as f32 / m as f32;
        self.resize(((self.w as f32 * k) as usize).max(1), ((self.h as f32 * k) as usize).max(1))
    }

    /// Cuts the middle part that has the given width-to-height ratio.
    pub fn crop_to_ratio(&self, ratio: f32) -> Rgba {
        let cur = self.w as f32 / self.h as f32;
        let (nw, nh) = if cur > ratio {
            (((self.h as f32 * ratio) as usize).max(1), self.h)
        } else {
            (self.w, ((self.w as f32 / ratio) as usize).max(1))
        };
        let x0 = (self.w - nw) / 2;
        let y0 = (self.h - nh) / 2;
        let mut out = Vec::with_capacity(nw * nh * 4);
        for y in 0..nh {
            let start = ((y0 + y) * self.w + x0) * 4;
            out.extend_from_slice(&self.px[start..start + nw * 4]);
        }
        Rgba { w: nw, h: nh, px: out }
    }

    /// Turns the picture a quarter turn clockwise, `turns` times.
    pub fn rotate90(&self, turns: i32) -> Rgba {
        let mut cur = self.clone();
        for _ in 0..turns.rem_euclid(4) {
            let (w, h) = (cur.w, cur.h);
            let mut out = vec![0u8; w * h * 4];
            for y in 0..h {
                for x in 0..w {
                    let nx = h - 1 - y;
                    let ny = x;
                    let s = (y * w + x) * 4;
                    let d = (ny * h + nx) * 4;
                    out[d..d + 4].copy_from_slice(&cur.px[s..s + 4]);
                }
            }
            cur = Rgba { w: h, h: w, px: out };
        }
        cur
    }

    pub fn flip_h(&mut self) {
        for y in 0..self.h {
            for x in 0..self.w / 2 {
                let a = (y * self.w + x) * 4;
                let b = (y * self.w + (self.w - 1 - x)) * 4;
                for c in 0..4 {
                    self.px.swap(a + c, b + c);
                }
            }
        }
    }

    pub fn flip_v(&mut self) {
        for y in 0..self.h / 2 {
            for x in 0..self.w {
                let a = (y * self.w + x) * 4;
                let b = ((self.h - 1 - y) * self.w + x) * 4;
                for c in 0..4 {
                    self.px.swap(a + c, b + c);
                }
            }
        }
    }
}
