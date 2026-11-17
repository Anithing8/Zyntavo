# Zyntavo (Rust)

A photo and video editor with an auto editor, written in Rust. This is version 0.1 of the Rust port.

## What is in it

**`zyntavo-core`** (no dependencies, standard library only)
- Photo edits: exposure, brightness, contrast, colour strength, warmth, shadows, highlights, fade, vignette, sharpen, blur, film grain, 23 looks, crop shapes, rotate, flip.
- The auto editor: reads a goal from loose wording ("make it so people notice it", "for Instagram", "cinematic and moody"), measures the picture, and chooses the edits.
- Beat finding and silence finding, with its own FFT.
- Point tracking by block matching.
- Shape masks (circle, rounded, heart, star).
- The video project (clips, titles, split, cut on beats, remove silences) and the FFmpeg export builder
  (trim, speed, reverse, freeze frame, stabilise, mirror, blurred background, looks, fades, titles with a fade).

**`zyntavo-app`** (egui, desktop)
- Photo: open, edit with sliders, Auto edit, export PNG or JPEG.
- Video: add clips, timeline, per-clip controls, titles, frame shape, beats and cut on beats, Auto edit, export MP4.

## Build

```
cargo test -p zyntavo-core
cargo run --release -p zyntavo-app
```

Video needs FFmpeg as a separate program on your PATH (or set `ZYNTAVO_FFMPEG`). Use an LGPL build. Set `ZYNTAVO_FONT` to a .ttf file for titles if the default font path is wrong for your system.
On Linux you need the GTK 3 and xkbcommon development packages.

## Not in the Rust version yet

- **Captions** (speech to text). The Kotlin version has them. A Rust version needs a speech model run through ONNX Runtime or whisper.cpp.
- **Android and iOS.** This is a desktop app. The Kotlin app is the phone version.
- **Cutout and portrait blur** (needs a segmentation model), overlays with green screen and masks in the export (the mask and key code is in the core, the export graph for overlays is not yet), transitions between clips, speed ramps, animated titles other than fade, templates, and the prompt box (only Auto edit is ported).
- Hindi and other scripts that need text shaping in titles.

## Status

This code was written without access to a Rust compiler, so it has not been built or run. Expect to fix a few compile errors on the first build.
`cargo test -p zyntavo-core` runs checks for the model, the auto editor, beat finding and the photo pipeline.

## Licences

MIT or Apache 2.0 for the dependencies (egui, eframe, image, rfd). FFmpeg is run as a separate program and is not linked.
