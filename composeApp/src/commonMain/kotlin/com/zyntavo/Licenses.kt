package com.zyntavo

/** Edit this to the page where you publish your downloads and the matching FFmpeg source. */
const val SOURCE_URL = "https://github.com/Anithing8/Zyntavo/releases"

val licenseNotice = """
Zyntavo is built with open-source software.

FFmpeg (https://ffmpeg.org) is used under the GNU LGPL, version 2.1 or later. This app runs an unmodified FFmpeg that was built without any GPL or non-free parts. You can replace it with your own LGPL build. The exact source code and build settings are published at $SOURCE_URL. FFmpeg is a trademark of Fabrice Bellard.

FreeType is used under the FreeType License.
ONNX Runtime is used under the MIT licence.
Vosk is used under the Apache 2.0 licence, and so are its small speech models.
AndroidX Media3, Kotlin and Compose Multiplatform are used under the Apache 2.0 licence.
Google ML Kit is used under the Google ML Kit terms.
Captions: OpenAI Whisper (MIT licence), as ONNX files from the Hugging Face user Xenova; check the licence shown on each model page before you sell the app.
Hindi translation: Helsinki-NLP opus-mt-en-hi (CC BY 4.0; ONNX files by Xenova) and, on Android, Google ML Kit Translation.
Spelling: Google BERT (Apache 2.0), as ONNX files from the Hugging Face user Xenova.
Cutout models: U2-Net and U2-Net-P (Apache 2.0) and Silueta (MIT).

Video formats such as H.264, AAC and MPEG-4 may need patent licences in some countries. Zyntavo uses the encoder built into your device where it can.
""".trimIndent()
