# Third-party software in Zyntavo

Zyntavo is not itself open source unless you choose to make it so. It uses the following parts.
Keep this file, and the in-app About screen, with every release.

## FFmpeg (LGPL v2.1 or later)
- Windows: an LGPL build from https://github.com/BtbN/FFmpeg-Builds (a file named `...-lgpl...`).
- Android: built by `.github/workflows/build.yml` from the official FFmpeg source with
  FreeType only. No `--enable-gpl`, no `--enable-nonfree`, no x264 or x265.
- It runs as a separate program, unmodified. You may replace `ffmpeg.exe` (Windows) or
  `libffmpeg.so` (Android) with another LGPL build.
- The workflow publishes the exact source and the configure options as the `ffmpeg-source`
  artifact. Put that file next to every release you publish (for example on a GitHub Release),
  and set `SOURCE_URL` in `Licenses.kt` to that page.
- The app refuses to export if the FFmpeg it finds reports `--enable-gpl`, `--enable-nonfree`,
  `libx264` or `libx265`.

## Other libraries
| Part | Licence |
|---|---|
| FreeType (text drawing in Android FFmpeg) | FreeType License |
| ONNX Runtime | MIT |
| Vosk and its small speech models | Apache 2.0 |
| AndroidX Media3, Kotlin, Compose Multiplatform | Apache 2.0 |
| Google ML Kit | Google ML Kit terms |
| OpenAI Whisper (tiny.en, base.en) caption models, ONNX files by Xenova | MIT (verify on each model page) |
| Helsinki-NLP opus-mt-en-hi (Hindi translation), ONNX files by Xenova | CC BY 4.0, credit required |
| Google ML Kit Translation (Android) | Google ML Kit terms |
| Google BERT (spelling check), ONNX files by Xenova | Apache 2.0 |
| U2-Net, U2-Net-P cutout models | Apache 2.0 |
| Silueta cutout model | MIT |

## Patents
Copyright licences and patent licences are different things. H.264, AAC and MPEG-4 can need
patent licences in some countries. Zyntavo uses encoders provided by the device or by Windows
where possible. This is not legal advice. Ask a lawyer before selling the app.
