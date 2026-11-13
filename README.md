# Zyntavo

<img src="branding/zyntavo-logo.svg" alt="Zyntavo logo" width="96" align="right">

A photo, video and subtitle editor for Windows and Android. It works on your own device: editing, subtitles and the
written-prompt editing do not need an internet connection (speech and translation models are downloaded once).

The Kotlin source still lives in the package `com.photostudio`. That is an internal name only and is not shown to anyone.

## What it can do

### Photos
- Adjust: brightness, contrast, saturation, warmth, tint, hue, exposure, fade, vignette, **Auto enhance**
- 27 filters, including duotones and invert
- Crop to a shape (1:1, 4:5, 3:4, 2:3, 3:2, 16:9, 9:16, 21:9, 5:4, 4:3, 3:1), rotate, flip both ways, border
- Text and 20 stickers, background cutout
- Undo and redo, **Compare with the original**, **edit with a written prompt**, one-tap Quick looks

### Video
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

## Compared with CapCut

Zyntavo does not have every CapCut feature, and it is not a copy of it. Things that are **not** here yet:

- Reverse, freeze frame and speed curves
- Green screen (chroma key), masks, motion tracking, stabilisation
- Beat sync, auto-cut, templates, and stock music, video or effects libraries
- Generative AI features, AI avatars, voice cloning
- Animated text and a large text style library, stickers on video
- Background blur canvas for tall video
- Cloud projects, sharing and collaboration, keyboard shortcuts

## Building

Push to GitHub and the workflow in `.github/workflows/build.yml` builds the Android app and the Windows app.
Windows: `./gradlew :composeApp:createDistributable`. Android: `./gradlew :composeApp:assembleDebug`.

Downloaded models and what the app learns are kept in `.zyntavo` in your user folder (a `.sheen` folder from an older
version is moved there automatically). On Android the Android application id changed to `app.zyntavo.editor`, so it
installs as a separate app from older builds.

## About the name

"Zyntavo" is an invented word. A web search found no product, company or app with this name when it was chosen, but a
web search is not a trademark search. Before publishing or selling the app, check the name in the trademark registers of
the countries you use it in (for example USPTO, WIPO Global Brand Database, India IP Public Search), and check the
domain, the Play Store and GitHub. Nobody can promise from here that a name is free of legal problems.

Third-party software and models are listed in `THIRD_PARTY_NOTICES.md`.
