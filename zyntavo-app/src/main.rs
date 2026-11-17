//! Zyntavo desktop app: Photo and Video editors with an auto editor. Captions are not in this version.
use eframe::egui;
use std::sync::{Arc, Mutex};
use zyntavo_core::analysis;
use zyntavo_core::beats;
use zyntavo_core::edit::{self, EditState};
use zyntavo_core::ffmpeg::Ffmpeg;
use zyntavo_core::model::*;
use zyntavo_core::pixels::Rgba;

// The look: a warm, neutral darkroom with one amber accent.
const INK: egui::Color32 = egui::Color32::from_rgb(0x12, 0x11, 0x10);
const SURFACE: egui::Color32 = egui::Color32::from_rgb(0x1A, 0x19, 0x17);
const SURFACE2: egui::Color32 = egui::Color32::from_rgb(0x23, 0x21, 0x20);
const LINE: egui::Color32 = egui::Color32::from_rgb(0x38, 0x34, 0x2F);
const TEXT: egui::Color32 = egui::Color32::from_rgb(0xEF, 0xEA, 0xE0);
const TEXT_LO: egui::Color32 = egui::Color32::from_rgb(0x9C, 0x95, 0x8A);
const ACCENT: egui::Color32 = egui::Color32::from_rgb(0xF0, 0xB0, 0x3A);
const ACCENT_INK: egui::Color32 = egui::Color32::from_rgb(0x1A, 0x13, 0x05);

fn apply_theme(ctx: &egui::Context) {
    let mut v = egui::Visuals::dark();
    v.panel_fill = INK;
    v.window_fill = SURFACE;
    v.extreme_bg_color = SURFACE2;
    v.faint_bg_color = SURFACE;
    v.override_text_color = Some(TEXT);
    v.selection.bg_fill = ACCENT;
    v.selection.stroke.color = ACCENT_INK;
    v.hyperlink_color = TEXT;
    let r = egui::Rounding::same(6.0);
    v.widgets.noninteractive.rounding = r;
    v.widgets.inactive.rounding = r;
    v.widgets.hovered.rounding = r;
    v.widgets.active.rounding = r;
    v.widgets.noninteractive.bg_stroke.color = LINE;
    v.widgets.inactive.bg_fill = SURFACE2;
    v.widgets.inactive.weak_bg_fill = SURFACE2;
    v.widgets.hovered.bg_fill = egui::Color32::from_rgb(0x2F, 0x2C, 0x29);
    v.widgets.hovered.weak_bg_fill = egui::Color32::from_rgb(0x2F, 0x2C, 0x29);
    v.widgets.active.bg_fill = ACCENT;
    v.widgets.active.weak_bg_fill = ACCENT;
    ctx.set_visuals(v);
}

fn font_path() -> String {
    if let Ok(p) = std::env::var("ZYNTAVO_FONT") {
        return p;
    }
    if cfg!(target_os = "windows") {
        "C:/Windows/Fonts/arial.ttf".to_string()
    } else if cfg!(target_os = "macos") {
        "/System/Library/Fonts/Supplemental/Arial.ttf".to_string()
    } else {
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf".to_string()
    }
}

fn heading(ui: &mut egui::Ui, text: &str) {
    ui.label(egui::RichText::new(text).size(26.0).strong());
}

fn primary(ui: &mut egui::Ui, text: &str) -> egui::Response {
    ui.add(egui::Button::new(egui::RichText::new(text).color(ACCENT_INK).strong()).fill(ACCENT))
}

fn slider(ui: &mut egui::Ui, label: &str, v: &mut f32, range: std::ops::RangeInclusive<f32>, dirty: &mut bool) {
    if ui.add(egui::Slider::new(v, range).text(label)).changed() {
        *dirty = true;
    }
}

fn to_texture(ctx: &egui::Context, name: &str, img: &Rgba) -> egui::TextureHandle {
    let ci = egui::ColorImage::from_rgba_unmultiplied([img.w, img.h], &img.px);
    ctx.load_texture(name, ci, egui::TextureOptions::LINEAR)
}

fn show_fitted(ui: &mut egui::Ui, tex: &egui::TextureHandle) {
    let avail = ui.available_size();
    let ts = tex.size_vec2();
    let k = (avail.x / ts.x).min(avail.y / ts.y).min(4.0).max(0.01);
    ui.centered_and_justified(|ui| {
        ui.image(egui::load::SizedTexture::new(tex.id(), ts * k));
    });
}

#[derive(PartialEq, Clone, Copy)]
enum Tab {
    Photo,
    Video,
    Captions,
    About,
}

#[derive(Default)]
struct PhotoState {
    original: Option<Rgba>,
    preview_src: Option<Rgba>,
    state: EditState,
    tex: Option<egui::TextureHandle>,
    dirty: bool,
    prompt: String,
    note: String,
}

#[derive(Default)]
struct ExportJob {
    running: bool,
    progress: f32,
    message: String,
}

struct VideoState {
    ff: Option<Ffmpeg>,
    project: Project,
    selected: usize,
    play_ms: u64,
    tex: Option<egui::TextureHandle>,
    tex_key: Option<(usize, u64, u32)>,
    status: String,
    job: Arc<Mutex<ExportJob>>,
    beats: Vec<u64>,
    beat_every: usize,
    prompt: String,
    note: String,
    aspect: usize,
    res: u32,
}

struct ZyntavoApp {
    tab: Tab,
    photo: PhotoState,
    video: VideoState,
}

impl Default for ZyntavoApp {
    fn default() -> Self {
        let ff = Ffmpeg::locate();
        let status = if ff.is_some() { String::new() } else { "FFmpeg was not found. Install an LGPL build of FFmpeg, or set ZYNTAVO_FFMPEG to its path.".to_string() };
        ZyntavoApp {
            tab: Tab::Photo,
            photo: PhotoState::default(),
            video: VideoState {
                ff, project: Project::new(), selected: 0, play_ms: 0, tex: None, tex_key: None, status,
                job: Arc::new(Mutex::new(ExportJob::default())), beats: Vec::new(), beat_every: 1,
                prompt: String::new(), note: String::new(), aspect: 0, res: 720,
            },
        }
    }
}

impl ZyntavoApp {
    // ------------------------------------------------------------------ photo

    fn open_photo(&mut self) {
        let Some(path) = rfd::FileDialog::new().add_filter("Pictures", &["png", "jpg", "jpeg"]).pick_file() else { return };
        match image::open(&path) {
            Ok(img) => {
                let rgba = img.to_rgba8();
                let (w, h) = rgba.dimensions();
                if let Some(r) = Rgba::from_raw(w as usize, h as usize, rgba.into_raw()) {
                    let p = &mut self.photo;
                    p.preview_src = Some(r.fit(1280));
                    p.original = Some(r);
                    p.state = EditState::default();
                    p.note.clear();
                    p.dirty = true;
                }
            }
            Err(e) => self.photo.note = format!("Could not open that picture: {e}"),
        }
    }

    fn export_photo(&mut self) {
        let Some(orig) = &self.photo.original else { return };
        let Some(path) = rfd::FileDialog::new().add_filter("PNG", &["png"]).add_filter("JPEG", &["jpg", "jpeg"]).save_file() else { return };
        let out = edit::render(orig, &self.photo.state);
        let Some(buf) = image::RgbaImage::from_raw(out.w as u32, out.h as u32, out.px) else { return };
        let is_jpeg = path.extension().map(|e| e.to_string_lossy().to_lowercase()).map(|e| e == "jpg" || e == "jpeg").unwrap_or(false);
        let res = if is_jpeg { image::DynamicImage::ImageRgba8(buf).to_rgb8().save(&path) } else { buf.save(&path) };
        self.photo.note = match res {
            Ok(_) => "Saved.".to_string(),
            Err(e) => format!("Could not save: {e}"),
        };
    }

    fn photo_auto(&mut self) {
        let Some(orig) = &self.photo.original else { return };
        let text = self.photo.prompt.clone();
        let goal = analysis::detect(&text).unwrap_or_else(analysis::general);
        let tune = analysis::tune(&analysis::analyse(orig), &goal);
        tune.apply_to(&mut self.photo.state);
        self.photo.note = format!("Goal: {}\n{}\nUse Reset to take it back.", goal.label, tune.notes.join("\n"));
        self.photo.dirty = true;
    }

    fn photo_ui(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("photo_top").show(ctx, |ui| {
            ui.horizontal(|ui| {
                heading(ui, "Photo");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if self.photo.original.is_some() && primary(ui, "Export").clicked() {
                        self.export_photo();
                    }
                    if ui.button("Open").clicked() {
                        self.open_photo();
                    }
                });
            });
        });
        egui::SidePanel::right("photo_tools").min_width(300.0).show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
                let p = &mut self.photo;
                if p.original.is_none() {
                    ui.label("Open a picture to start.");
                    return;
                }
                ui.label(egui::RichText::new("Auto edit").strong());
                ui.add(egui::TextEdit::multiline(&mut p.prompt).desired_rows(2).hint_text("For example: make it so people notice it, for Instagram"));
                let mut run_auto = false;
                if primary(ui, "Auto edit").clicked() {
                    run_auto = true;
                }
                if !p.note.is_empty() {
                    ui.label(egui::RichText::new(&p.note).color(TEXT_LO));
                }
                ui.separator();
                let s = &mut p.state;
                let d = &mut p.dirty;
                slider(ui, "Brightness", &mut s.brightness, -0.4..=0.4, d);
                slider(ui, "Contrast", &mut s.contrast, 0.5..=1.5, d);
                slider(ui, "Saturation", &mut s.saturation, 0.0..=2.0, d);
                slider(ui, "Exposure", &mut s.exposure, -1.0..=1.0, d);
                slider(ui, "Warmth", &mut s.warmth, -1.0..=1.0, d);
                slider(ui, "Shadows", &mut s.shadows, -1.0..=1.0, d);
                slider(ui, "Highlights", &mut s.highlights, -1.0..=1.0, d);
                slider(ui, "Fade", &mut s.fade, 0.0..=1.0, d);
                slider(ui, "Vignette", &mut s.vignette, 0.0..=1.0, d);
                slider(ui, "Sharpen", &mut s.sharpen, 0.0..=1.0, d);
                slider(ui, "Blur", &mut s.blur, 0.0..=1.0, d);
                slider(ui, "Film grain", &mut s.grain, 0.0..=1.0, d);
                ui.horizontal(|ui| {
                    ui.label("Look");
                    egui::ComboBox::from_id_salt("photo_filter").selected_text(edit::FILTER_NAMES[s.filter]).show_ui(ui, |ui| {
                        for (i, n) in edit::FILTER_NAMES.iter().enumerate() {
                            if ui.selectable_value(&mut s.filter, i, *n).changed() {
                                *d = true;
                            }
                        }
                    });
                });
                ui.horizontal(|ui| {
                    ui.label("Shape");
                    let shapes: [(&str, Option<f32>); 5] = [("Original", None), ("1:1", Some(1.0)), ("4:5", Some(0.8)), ("9:16", Some(9.0 / 16.0)), ("16:9", Some(16.0 / 9.0))];
                    for (name, r) in shapes.iter() {
                        if ui.selectable_label(s.ratio == *r, *name).clicked() {
                            s.ratio = *r;
                            *d = true;
                        }
                    }
                });
                ui.horizontal(|ui| {
                    if ui.button("Rotate").clicked() { s.rotation += 1; *d = true; }
                    if ui.button("Flip").clicked() { s.flip_h = !s.flip_h; *d = true; }
                    if ui.button("Reset").clicked() { *s = EditState::default(); *d = true; }
                });
                if run_auto {
                    self.photo_auto();
                }
            });
        });
        egui::CentralPanel::default().show(ctx, |ui| {
            let p = &mut self.photo;
            if p.dirty {
                if let Some(src) = &p.preview_src {
                    let out = edit::render(src, &p.state);
                    p.tex = Some(to_texture(ctx, "photo", &out));
                }
                p.dirty = false;
            }
            if let Some(t) = &p.tex {
                show_fitted(ui, t);
                return;
            }
            ui.vertical_centered(|ui| {
                ui.add_space(ui.available_height() / 3.0);
                heading(ui, "Edit a photo");
                ui.label(egui::RichText::new("Adjust the light, crop, sharpen, or let Auto edit decide.").color(TEXT_LO));
                ui.add_space(12.0);
                if primary(ui, "Open a photo").clicked() {
                    self.open_photo();
                }
            });
        });
    }

    // ------------------------------------------------------------------ video

    fn add_clips(&mut self) {
        let Some(paths) = rfd::FileDialog::new().add_filter("Video", &["mp4", "mov", "mkv", "webm", "avi", "m4v"]).pick_files() else { return };
        let v = &mut self.video;
        let Some(ff) = &v.ff else { return };
        for p in paths {
            match ff.probe(&p.to_string_lossy()) {
                Ok(src) => v.project.clips.push(Clip::new(src)),
                Err(e) => v.status = e,
            }
        }
        v.tex_key = None;
    }

    fn refresh_frame(&mut self, ctx: &egui::Context) {
        let v = &mut self.video;
        let Some(ff) = &v.ff else { return };
        let Some((i, src_ms)) = v.project.locate(v.play_ms) else { return };
        let key = (i, src_ms / 100, v.project.clips[i].filter as u32);
        if v.tex_key == Some(key) {
            return;
        }
        if let Ok(img) = ff.frame_at(&v.project.clips[i].src.path, src_ms, 640) {
            v.tex = Some(to_texture(ctx, "frame", &img));
            v.tex_key = Some(key);
        }
    }

    fn start_export(&mut self) {
        let v = &mut self.video;
        let Some(ff) = &v.ff else { return };
        let Some(out) = rfd::FileDialog::new().add_filter("MP4", &["mp4"]).save_file() else { return };
        let job = v.job.clone();
        {
            let mut j = job.lock().unwrap();
            j.running = true;
            j.progress = 0.0;
            j.message.clear();
        }
        let project = v.project.clone();
        let bin = ff.bin.clone();
        let out = out.to_string_lossy().to_string();
        std::thread::spawn(move || {
            let ffm = Ffmpeg { bin };
            let job2 = job.clone();
            let mut report = move |x: f32| {
                job2.lock().unwrap().progress = x;
            };
            let res = ffm.export(&project, &out, &font_path(), &mut report);
            let mut j = job.lock().unwrap();
            j.running = false;
            j.message = match res {
                Ok(enc) => format!("Saved (encoder: {enc})."),
                Err(e) => e,
            };
        });
    }

    fn find_beats(&mut self) {
        let v = &mut self.video;
        let Some(ff) = &v.ff else { return };
        let Some(c) = v.project.clips.get(v.selected) else { return };
        if !c.src.has_audio {
            v.status = "This clip has no sound to listen to.".into();
            return;
        }
        let start = v.project.starts()[v.selected];
        match ff.pcm(&c.src.path, c.in_ms, c.out_ms - c.in_ms) {
            Ok(pcm) => match beats::detect(&pcm) {
                Some(r) => {
                    let speed = c.speed.max(0.05);
                    v.beats = r.beats_ms.iter().map(|b| start + (*b as f32 / speed) as u64).collect();
                    v.status = format!("{} BPM, {} beats found.", r.bpm.round(), v.beats.len());
                }
                None => v.status = "No clear beat found.".into(),
            },
            Err(e) => v.status = e,
        }
    }

    fn video_auto(&mut self) {
        let text = self.video.prompt.clone();
        let v = &mut self.video;
        let Some(ff) = &v.ff else { return };
        if v.project.clips.is_empty() {
            v.note = "Add a video first.".into();
            return;
        }
        let goal = analysis::detect(&text).unwrap_or_else(analysis::general);
        let mut notes = vec![format!("Goal: {}", goal.label)];
        let mut first = true;
        for c in v.project.clips.iter_mut() {
            let mut stats = Vec::new();
            for f in [0.2f64, 0.5, 0.8] {
                let ms = c.in_ms + ((c.out_ms - c.in_ms) as f64 * f) as u64;
                if let Ok(img) = ff.frame_at(&c.src.path, ms, 320) {
                    stats.push(analysis::analyse(&img));
                }
            }
            if let Some(st) = analysis::merge(&stats) {
                let t = analysis::tune(&st, &goal);
                c.brightness = t.brightness;
                c.contrast = t.contrast;
                c.saturation = t.saturation;
                if let Some(f) = t.filter {
                    c.filter = f;
                }
                if first {
                    notes.extend(t.notes.iter().filter(|n| !n.starts_with("Cropped")).cloned());
                    first = false;
                }
            }
            if goal.shaky {
                c.stab = 1;
            }
        }
        if goal.shaky {
            notes.push("Turned on stabilising for every clip.".into());
        }
        let n = v.project.clips.len();
        v.project.clips[0].fade_in = true;
        v.project.clips[n - 1].fade_out = true;
        notes.push("Added a fade at the start and end.".into());
        if let Some(p) = goal.platform.as_deref() {
            let a = match p { "instagram" => 3, "short" => 1, _ => 0 };
            let blur = a != 0;
            v.project.settings = ExportSettings::new(v.res, a, v.project.settings.fps);
            v.project.settings.blur_bg = blur;
            v.aspect = a;
            notes.push(format!("Set the frame to {}{}.", ASPECT_NAMES[a], if blur { ", with a blurred background" } else { "" }));
        }
        if goal.talking {
            let mut removed = 0u64;
            for i in (0..v.project.clips.len()).rev() {
                let c = v.project.clips[i].clone();
                if !c.src.has_audio {
                    continue;
                }
                if let Ok(pcm) = ff.pcm(&c.src.path, c.in_ms, c.out_ms - c.in_ms) {
                    let quiet = beats::silences(&pcm, -38.0, 600);
                    removed += v.project.remove_silences(i, &quiet);
                }
            }
            notes.push(if removed > 0 { format!("Cut out {:.1} seconds of silence.", removed as f64 / 1000.0) } else { "I did not find long quiet parts to cut.".into() });
        }
        v.note = notes.join("\n");
        v.tex_key = None;
    }

    fn video_ui(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("video_top").show(ctx, |ui| {
            ui.horizontal(|ui| {
                heading(ui, "Video");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let running = self.video.job.lock().unwrap().running;
                    if !self.video.project.clips.is_empty() && !running && primary(ui, "Export").clicked() {
                        self.start_export();
                    }
                    if self.video.ff.is_some() && ui.button("Add clip").clicked() {
                        self.add_clips();
                    }
                });
            });
        });
        let (running, progress, message) = {
            let j = self.video.job.lock().unwrap();
            (j.running, j.progress, j.message.clone())
        };
        if running {
            ctx.request_repaint_after(std::time::Duration::from_millis(200));
        }
        egui::TopBottomPanel::bottom("video_timeline").min_height(140.0).show(ctx, |ui| {
            if running {
                ui.add(egui::ProgressBar::new(progress).text("Exporting…").fill(ACCENT));
            } else if !message.is_empty() {
                ui.label(egui::RichText::new(&message).color(TEXT_LO));
            }
            let v = &mut self.video;
            let total = v.project.total_ms();
            ui.horizontal(|ui| {
                ui.label(format!("{} / {}", mmss(v.play_ms), mmss(total)));
                let mut pos = v.play_ms as f32;
                if ui.add(egui::Slider::new(&mut pos, 0.0..=total.max(1) as f32).show_value(false)).changed() {
                    v.play_ms = pos as u64;
                }
            });
            // The timeline: each clip is a block as wide as it plays.
            let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 56.0), egui::Sense::click());
            let painter = ui.painter_at(rect);
            painter.rect_filled(rect, 4.0, SURFACE);
            if total > 0 {
                let starts = v.project.starts();
                for (i, c) in v.project.clips.iter().enumerate() {
                    let x0 = rect.left() + rect.width() * starts[i] as f32 / total as f32;
                    let x1 = rect.left() + rect.width() * (starts[i] + c.dur_ms()) as f32 / total as f32;
                    let r = egui::Rect::from_min_max(egui::pos2(x0 + 1.0, rect.top() + 4.0), egui::pos2((x1 - 1.0).max(x0 + 2.0), rect.bottom() - 4.0));
                    painter.rect_filled(r, 4.0, egui::Color32::from_rgb(0x2F, 0x2C, 0x29));
                    if i == v.selected {
                        painter.rect_stroke(r, 4.0, egui::Stroke::new(2.0, TEXT));
                    }
                    painter.text(r.left_center() + egui::vec2(6.0, 0.0), egui::Align2::LEFT_CENTER, &c.src.name, egui::FontId::proportional(12.0), TEXT);
                }
                for b in &v.beats {
                    let x = rect.left() + rect.width() * *b as f32 / total as f32;
                    painter.circle_filled(egui::pos2(x, rect.bottom() - 3.0), 2.0, ACCENT);
                }
                let px = rect.left() + rect.width() * v.play_ms as f32 / total as f32;
                painter.line_segment([egui::pos2(px, rect.top()), egui::pos2(px, rect.bottom())], egui::Stroke::new(2.0, TEXT));
                if resp.clicked() {
                    if let Some(pos) = resp.interact_pointer_pos() {
                        let ms = ((pos.x - rect.left()) / rect.width() * total as f32) as u64;
                        v.play_ms = ms.min(total);
                        if let Some((i, _)) = v.project.locate(v.play_ms) {
                            v.selected = i;
                        }
                    }
                }
            }
        });
        egui::SidePanel::right("video_tools").min_width(320.0).show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| self.video_tools(ui));
        });
        self.refresh_frame(ctx);
        egui::CentralPanel::default().show(ctx, |ui| {
            let v = &self.video;
            match &v.tex {
                Some(t) => show_fitted(ui, t),
                None => {
                    ui.vertical_centered(|ui| {
                        ui.add_space(ui.available_height() / 3.0);
                        heading(ui, "No video yet");
                        ui.label(egui::RichText::new(if v.ff.is_some() { "Add a clip to start cutting." } else { v.status.as_str() }).color(TEXT_LO));
                    });
                }
            }
        });
    }

    fn video_tools(&mut self, ui: &mut egui::Ui) {
        let v = &mut self.video;
        if v.ff.is_none() {
            ui.label(&v.status);
            return;
        }
        if !v.status.is_empty() {
            ui.label(egui::RichText::new(&v.status).color(TEXT_LO));
        }
        ui.label(egui::RichText::new("Auto edit").strong());
        ui.add(egui::TextEdit::multiline(&mut v.prompt).desired_rows(2).hint_text("For example: for a reel, remove the silences, steady"));
        let mut run_auto = false;
        if primary(ui, "Auto edit").clicked() {
            run_auto = true;
        }
        if !v.note.is_empty() {
            ui.label(egui::RichText::new(&v.note).color(TEXT_LO));
        }
        ui.separator();
        if let Some(c) = v.project.clips.get_mut(v.selected) {
            ui.label(egui::RichText::new(format!("Clip: {}", c.src.name)).strong());
            let dur = c.src.duration_ms;
            ui.horizontal(|ui| {
                ui.label("In");
                ui.add(egui::DragValue::new(&mut c.in_ms).range(0..=dur).suffix(" ms"));
                ui.label("Out");
                ui.add(egui::DragValue::new(&mut c.out_ms).range(0..=dur).suffix(" ms"));
            });
            if c.out_ms < c.in_ms + 200 {
                c.out_ms = (c.in_ms + 200).min(dur);
            }
            ui.horizontal(|ui| {
                ui.label("Speed");
                for sp in [0.25f32, 0.5, 1.0, 1.5, 2.0, 4.0] {
                    if ui.selectable_label(c.speed == sp, format!("{sp}x")).clicked() {
                        c.speed = sp;
                    }
                }
            });
            ui.horizontal(|ui| {
                ui.checkbox(&mut c.reverse, "Reverse");
                ui.checkbox(&mut c.freeze, "Freeze");
                ui.checkbox(&mut c.mirror, "Mirror");
            });
            ui.horizontal(|ui| {
                ui.label("Steady");
                for (n, name) in ["Off", "Normal", "Strong"].iter().enumerate() {
                    if ui.selectable_label(c.stab as usize == n, *name).clicked() {
                        c.stab = n as u8;
                    }
                }
            });
            ui.horizontal(|ui| {
                ui.checkbox(&mut c.fade_in, "Fade in");
                ui.checkbox(&mut c.fade_out, "Fade out");
            });
            ui.add(egui::Slider::new(&mut c.brightness, -0.4..=0.4).text("Brightness"));
            ui.add(egui::Slider::new(&mut c.contrast, 0.5..=1.5).text("Contrast"));
            ui.add(egui::Slider::new(&mut c.saturation, 0.0..=2.0).text("Saturation"));
            ui.add(egui::Slider::new(&mut c.gain, 0.0..=2.0).text("Volume"));
            egui::ComboBox::from_id_salt("clip_filter").selected_text(edit::FILTER_NAMES[c.filter]).show_ui(ui, |ui| {
                for (i, n) in edit::FILTER_NAMES.iter().enumerate() {
                    ui.selectable_value(&mut c.filter, i, *n);
                }
            });
            ui.horizontal(|ui| {
                if ui.button("Split at playhead").clicked() {
                    let at = v.play_ms;
                    v.project.split_at(at);
                }
                if ui.button("Duplicate").clicked() {
                    let copy = v.project.clips[v.selected].clone();
                    v.project.clips.insert(v.selected + 1, copy);
                }
                if ui.button("Delete").clicked() {
                    v.project.clips.remove(v.selected);
                    v.selected = v.selected.saturating_sub(1);
                }
            });
            v.tex_key = None;
        } else {
            ui.label("Add a clip, then click it on the timeline.");
        }
        ui.separator();
        ui.label(egui::RichText::new("Frame").strong());
        ui.horizontal(|ui| {
            let mut changed = false;
            for (i, n) in ASPECT_NAMES.iter().enumerate() {
                if ui.selectable_label(v.aspect == i, *n).clicked() {
                    v.aspect = i;
                    changed = true;
                }
            }
            for r in [480u32, 720, 1080] {
                if ui.selectable_label(v.res == r, format!("{r}p")).clicked() {
                    v.res = r;
                    changed = true;
                }
            }
            if changed {
                let blur = v.project.settings.blur_bg;
                v.project.settings = ExportSettings::new(v.res, v.aspect, v.project.settings.fps);
                v.project.settings.blur_bg = blur;
            }
        });
        ui.checkbox(&mut v.project.settings.blur_bg, "Blurred background");
        ui.separator();
        ui.label(egui::RichText::new("Beats").strong());
        ui.horizontal(|ui| {
            if ui.button("Find beats in this clip").clicked() {
                self.find_beats();
            }
        });
        let v = &mut self.video;
        if !v.beats.is_empty() {
            ui.horizontal(|ui| {
                ui.label("Cut on every");
                for n in [1usize, 2, 4] {
                    if ui.selectable_label(v.beat_every == n, n.to_string()).clicked() {
                        v.beat_every = n;
                    }
                }
                if ui.button("Cut").clicked() {
                    let b = v.beats.clone();
                    let n = v.project.cut_at_beats(&b, v.beat_every);
                    v.status = format!("Cut the clips at {n} beats.");
                    v.tex_key = None;
                }
            });
        }
        ui.separator();
        ui.label(egui::RichText::new("Titles").strong());
        let mut remove: Option<usize> = None;
        for (k, t) in v.project.titles.iter_mut().enumerate() {
            ui.horizontal(|ui| {
                ui.text_edit_singleline(&mut t.text);
                if ui.button("×").clicked() {
                    remove = Some(k);
                }
            });
            ui.horizontal(|ui| {
                ui.add(egui::DragValue::new(&mut t.start_ms).suffix(" ms"));
                ui.add(egui::DragValue::new(&mut t.end_ms).suffix(" ms"));
                ui.add(egui::Slider::new(&mut t.size, 0.03..=0.2).show_value(false));
                let mut fade = t.anim == 1;
                if ui.checkbox(&mut fade, "Fade").changed() {
                    t.anim = if fade { 1 } else { 0 };
                }
            });
        }
        if let Some(k) = remove {
            v.project.titles.remove(k);
        }
        if ui.button("Add title").clicked() {
            let at = v.play_ms;
            v.project.titles.push(Title { text: "Title".into(), start_ms: at, end_ms: at + 3000, x: 0.5, y: 0.8, size: 0.07, color: [255, 255, 255], anim: 1 });
        }
        if run_auto {
            self.video_auto();
        }
    }
}

fn mmss(ms: u64) -> String {
    let s = ms / 1000;
    format!("{}:{:02}", s / 60, s % 60)
}

impl eframe::App for ZyntavoApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::TopBottomPanel::top("tabs").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.label(egui::RichText::new("Zyntavo").size(20.0).strong());
                ui.add_space(16.0);
                for (t, name) in [(Tab::Photo, "Photo"), (Tab::Video, "Video"), (Tab::Captions, "Captions"), (Tab::About, "About")] {
                    if ui.selectable_label(self.tab == t, name).clicked() {
                        self.tab = t;
                    }
                }
            });
        });
        match self.tab {
            Tab::Photo => self.photo_ui(ctx),
            Tab::Video => self.video_ui(ctx),
            Tab::Captions => {
                egui::CentralPanel::default().show(ctx, |ui| {
                    heading(ui, "Captions");
                    ui.label(egui::RichText::new("Captions are not in the Rust version yet. They need a speech model that runs on the device; the Kotlin version has them.").color(TEXT_LO));
                });
            }
            Tab::About => {
                egui::CentralPanel::default().show(ctx, |ui| {
                    heading(ui, "Zyntavo");
                    ui.label(egui::RichText::new("Photo, video and subtitle editor. Rust version 0.1").color(TEXT_LO));
                    ui.add_space(8.0);
                    ui.label("Built with open-source software.");
                    ui.label("egui and eframe, MIT or Apache 2.0. image and rfd, MIT or Apache 2.0.");
                    ui.label("FFmpeg is run as a separate program. Use an LGPL build. FFmpeg is a trademark of Fabrice Bellard.");
                });
            }
        }
    }
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_inner_size([1200.0, 780.0]).with_title("Zyntavo"),
        ..Default::default()
    };
    eframe::run_native(
        "Zyntavo",
        options,
        Box::new(|cc| {
            apply_theme(&cc.egui_ctx);
            Ok(Box::new(ZyntavoApp::default()))
        }),
    )
}
