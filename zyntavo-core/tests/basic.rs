use zyntavo_core::analysis;
use zyntavo_core::beats;
use zyntavo_core::edit::{self, EditState};
use zyntavo_core::masks;
use zyntavo_core::model::*;
use zyntavo_core::pixels::Rgba;

fn flat(w: usize, h: usize, v: u8) -> Rgba {
    let mut px = Vec::with_capacity(w * h * 4);
    for _ in 0..w * h {
        px.extend_from_slice(&[v, v, v, 255]);
    }
    Rgba::from_raw(w, h, px).unwrap()
}

fn src(ms: u64) -> Source {
    Source { path: "a.mp4".into(), name: "a.mp4".into(), duration_ms: ms, has_audio: true }
}

#[test]
fn goal_is_read_from_loose_words() {
    let g = analysis::detect("edit it and make it such that people are attracted to it").expect("a goal");
    assert!(g.attention);
    assert!(analysis::detect("rotate left").is_none());
    let g = analysis::detect("cinematic reel for instagram, make it pop").expect("a goal");
    assert_eq!(g.mood.as_deref(), Some("cinematic"));
}

#[test]
fn dark_picture_gets_brighter() {
    let img = flat(200, 120, 30);
    let st = analysis::analyse(&img);
    let t = analysis::tune(&st, &analysis::general());
    assert!(t.brightness > 0.0);
    assert!(t.shadows > 0.0);
}

#[test]
fn default_edit_changes_nothing() {
    let img = flat(40, 30, 120);
    let out = edit::render(&img, &EditState::default());
    assert_eq!(out.px, img.px);
}

#[test]
fn crop_and_rotate_change_the_shape() {
    let img = flat(40, 30, 120);
    let mut s = EditState::default();
    s.ratio = Some(1.0);
    let out = edit::render(&img, &s);
    assert_eq!((out.w, out.h), (30, 30));
    s.rotation = 1;
    s.ratio = None;
    let out = edit::render(&img, &s);
    assert_eq!((out.w, out.h), (30, 40));
}

#[test]
fn split_keeps_the_total_length() {
    let mut p = Project::new();
    p.clips.push(Clip::new(src(10_000)));
    let before = p.total_ms();
    assert!(p.split_at(4_000));
    assert_eq!(p.clips.len(), 2);
    assert_eq!(p.total_ms(), before);
    assert!(!p.split_at(4_050));
}

#[test]
fn reverse_clip_locates_from_the_end() {
    let mut p = Project::new();
    let mut c = Clip::new(src(10_000));
    c.reverse = true;
    p.clips.push(c);
    let (_, at_start) = p.locate(0).unwrap();
    assert_eq!(at_start, 10_000);
}

#[test]
fn silence_removal_shortens_the_clip() {
    let mut p = Project::new();
    p.clips.push(Clip::new(src(10_000)));
    let removed = p.remove_silences(0, &[(2_000, 5_000)]);
    assert!(removed > 0);
    assert!(p.total_ms() < 10_000);
    assert_eq!(p.clips.len(), 2);
}

#[test]
fn finds_the_tempo_of_a_click_track() {
    let rate = beats::RATE;
    let secs = 20;
    let mut s = vec![0i16; rate * secs];
    for (i, v) in s.iter_mut().enumerate() {
        let t = i as f64 / rate as f64;
        let phase = t % 0.5;
        if phase < 0.03 {
            let decay = 1.0 - phase / 0.03;
            *v = ((2.0 * std::f64::consts::PI * 1000.0 * t).sin() * 20000.0 * decay) as i16;
        }
    }
    let r = beats::detect(&s).expect("a beat");
    assert!(r.bpm > 114.0 && r.bpm < 126.0, "bpm was {}", r.bpm);
    assert!(r.beats_ms.len() > 30);
}

#[test]
fn quiet_parts_are_found() {
    let rate = beats::RATE;
    let mut s = vec![0i16; rate * 4];
    for i in 0..rate {
        s[i] = (8000.0 * (i as f64 * 0.3).sin()) as i16;
    }
    let q = beats::silences(&s, -38.0, 600);
    assert_eq!(q.len(), 1);
    assert!(q[0].0 >= 900 && q[0].0 <= 1100);
}

#[test]
fn mask_files_have_the_right_size() {
    let b = masks::pgm_bytes(1, 64);
    assert!(b.starts_with(b"P5\n64 64\n255\n"));
    assert_eq!(b.len(), "P5\n64 64\n255\n".len() + 64 * 64);
}
