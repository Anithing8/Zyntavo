# Zyntavo

<img src="branding/zyntavo-logo.svg" alt="Zyntavo logo" width="96" align="right">

A photo, video and subtitle editor for Windows and Android. It works on your own device: editing, subtitles and the
written-prompt editing do not need an internet connection (speech and translation models are downloaded once).

The Kotlin source still lives in the package `com.zyntavo`. That is an internal name only and is not shown to anyone.

## What it can do

### Photos
- Sharpen, blur, highlights, shadows, film grain, straighten, and portrait blur (after removing the background)
- Photo templates: Clean post, Polaroid, Cinematic, Retro film, Moody B&W, Bright and airy, Story, Soft dream
- Adjust: brightness, contrast, saturation, warmth, tint, hue, exposure, fade, vignette, **Auto enhance**
- 27 filters, including duotones and invert
- Crop to a shape (1:1, 4:5, 3:4, 2:3, 3:2, 16:9, 9:16, 21:9, 5:4, 4:3, 3:1), rotate, flip both ways, border
- Text and 20 stickers, background cutout
- Undo and redo, **Compare with the original**, **edit with a written prompt**, one-tap Quick looks

### Video
- Reverse (any length), freeze frame, and speed ramps (Montage, Hero, Bullet, Speed up, Slow down)
- Motion tracking: titles and overlays can follow an object
- Beat finding, cut on the beat, auto-cut to the beat, and removing silences
- Stabilise (Steady and Steady strong)
- Green screen (chroma key) and shape masks (circle, rounded, heart, star) on overlay videos
- Blurred background for tall and square video
- Animated titles: fade, slide, pop and typewriter
- Video templates: Vlog, Cinematic, Travel reel, Retro, Punchy short, Product promo, Moody portrait
- Several clips on a timeline: add, trim, split, duplicate, delete, move
- Speed from 0.25x to 4x, mirror, flip, crop, volume
- 23 filters, and 8 effects: blur, sharpen, vignette, grain, denoise, pixelate, cinema bars, negative
- Keyframes for size, rotation, position, opacity and volume
- **Animation presets**: slow zoom in or out, fade in or out, pop in
- Transitions between clips, picture-in-picture overlays with blend modes
- Titles (with style presets), burned-in subtitles, text-to-speech voice-over, voice effects
- Export as wide (16:9), **tall (9:16)**, **square (1:1)** or **portrait (4:5)** video, at 480p, 720p or 1080p, 24, 30 or 60 fps, three quality levels. Titles and subtitles fit the shape you choose
- **Undo and redo**, **edit with a written prompt**, one-tap Quick looks

### Subtitles
- Speech to text on the device, in many languages
- Translation, spell fixing that learns from your corrections, find and replace, a list of your own names and words
- Styles and positions, export as .srt, .vtt or styled .ass files

## Writing a prompt

Open the **Prompt** tab (photos) or the box at the top of the clip panel (video) and write what you want. Open
**What can I say?** for examples. A few:

| Say | What happens |
| --- | --- |
| `warmer, more contrast and a slight vignette` | three adjustments, the last one gently |
| `cinematic` / `vintage` / `moody` / `cyberpunk` | a whole look |
| `split at 5 seconds` · `cut the first 2 seconds` | edits the timeline |
| `the first clip slow motion and the second clip fast` | each part goes to its own clip |
| `speed up the last 3 seconds` | splits the clip and changes only that part |
| `slow zoom in with fade in` | animation |
| `add a dissolve transition for 1 second` | transition |
| `add title "My trip" at the bottom for 4 seconds` | title with position and timing |
| `1080p 60 fps high quality` | export settings |
| `make it vertical` · `square video` · `4:5` | the shape of the exported video |

It is a rule-based reader, not an AI model. It knows many ways of saying the same thing and fixes small typos, but it
only controls what the editor already has, and it works on the whole picture or clip, not on one object in it.

## Not here yet

- Two-pass stabilisation
- Stock music, video or effects libraries
- Generative features, avatars, voice cloning
- A large text style library and stickers on video
- Cloud projects, sharing and collaboration, keyboard shortcuts

## Notes on the newer video tools

These run in the full video engine. On a phone, a project that uses them is exported with the full engine even if
the basic H.264 format is selected (the result is labelled). The one exception is Hindi text, which needs the basic
engine for correct letter shaping; effects that need the full engine are skipped in that case and the export says so.

- **Reverse** has no length limit. A long clip is reversed in parts that fit in memory and joined in reverse order.
- **Stabilise** uses a single-pass filter that removes small shake only.
- **Green screen** and **masks** work with every blend mode. The preview shows the key close to the export; the edges can differ a little.
- **Motion tracking** follows a point you choose by comparing small patches of brightness from one frame to the next.
  It works best on a spot with detail and steady lighting, and can lose an object that moves very fast or leaves the frame.
- **Beats** and **silence removal** analyse the sound on your device with a small FFT written for this app. Beat finding
  assumes a steady tempo.
- **Typewriter titles** place the text from an estimate of its width, in the preview and in the export alike.

## Building

The workflow in `.github/workflows/build.yml` builds the Android app and the Windows app.
Windows: `./gradlew :composeApp:createDistributable`. Android: `./gradlew :composeApp:assembleDebug`.

Downloaded models and what the app learns are kept in the `.zyntavo` folder in your user folder. The Android
application id is `app.zyntavo.editor`.

Third-party software and models are listed in `THIRD_PARTY_NOTICES.md`.
