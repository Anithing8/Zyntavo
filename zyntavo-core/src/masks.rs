//! Shapes an overlay video can be cut into. Every shape sits in a square from 0 to 1.
use std::f64::consts::PI;

pub const MASK_NAMES: [&str; 5] = ["None", "Circle", "Rounded", "Heart", "Star"];

fn heart_poly() -> Vec<(f32, f32)> {
    (0..72)
        .map(|i| {
            let t = i as f64 * 2.0 * PI / 72.0;
            let x = 16.0 * t.sin().powi(3);
            let y = 13.0 * t.cos() - 5.0 * (2.0 * t).cos() - 2.0 * (3.0 * t).cos() - (4.0 * t).cos();
            ((0.5 + x / 34.0) as f32, (0.5 - (y + 2.5) / 34.0) as f32)
        })
        .collect()
}

fn star_poly() -> Vec<(f32, f32)> {
    (0..10)
        .map(|i| {
            let r = if i % 2 == 0 { 0.49 } else { 0.2 };
            let a = -PI / 2.0 + i as f64 * PI / 5.0;
            ((0.5 + r * a.cos()) as f32, (0.54 + r * a.sin()) as f32)
        })
        .collect()
}

fn in_poly(poly: &[(f32, f32)], x: f32, y: f32) -> bool {
    let mut inside = false;
    let mut j = poly.len() - 1;
    for i in 0..poly.len() {
        let (xi, yi) = poly[i];
        let (xj, yj) = poly[j];
        if (yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi {
            inside = !inside;
        }
        j = i;
    }
    inside
}

/// A greyscale picture of the shape (255 inside, 0 outside, soft at the edge), as a binary PGM file's bytes.
pub fn pgm_bytes(shape: i32, size: usize) -> Vec<u8> {
    let heart = heart_poly();
    let star = star_poly();
    let inside = |x: f32, y: f32| -> bool {
        match shape {
            1 => (x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5) <= 0.25,
            2 => {
                let r = 0.22f32;
                let dx = ((x - 0.5).abs() - (0.5 - r)).max(0.0);
                let dy = ((y - 0.5).abs() - (0.5 - r)).max(0.0);
                dx * dx + dy * dy <= r * r
            }
            3 => in_poly(&heart, x, y),
            4 => in_poly(&star, x, y),
            _ => true,
        }
    };
    let ss = 3usize;
    let mut data = Vec::with_capacity(size * size);
    for y in 0..size {
        for x in 0..size {
            let mut hit = 0usize;
            for sy in 0..ss {
                for sx in 0..ss {
                    let fx = (x as f32 + (sx as f32 + 0.5) / ss as f32) / size as f32;
                    let fy = (y as f32 + (sy as f32 + 0.5) / ss as f32) / size as f32;
                    if inside(fx, fy) {
                        hit += 1;
                    }
                }
            }
            data.push((hit * 255 / (ss * ss)) as u8);
        }
    }
    let mut out = format!("P5\n{} {}\n255\n", size, size).into_bytes();
    out.extend_from_slice(&data);
    out
}
