package com.photostudio

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** How the cutout is made. quality: 0 fast, 1 better, 2 best. */
data class CutoutOptions(val quality: Int = 1, val cleanup: Float = 0.5f, val feather: Int = 1)

val matteQualityNames = listOf("Fast", "Better", "Best")
val matteQualityNotes = listOf("5 MB", "43 MB", "176 MB")

/** Returns a function that opens the system image picker. */
@Composable
expect fun rememberImagePicker(onPicked: (ImageBitmap) -> Unit): () -> Unit

/** Returns a function that saves a PNG and reports a message. */
@Composable
expect fun rememberImageSaver(onResult: (String) -> Unit): (ImageBitmap) -> Unit

/** On-device background removal. Returns null on failure. */
expect suspend fun removeBackground(image: ImageBitmap, opts: CutoutOptions, onStatus: (String) -> Unit): ImageBitmap?

/** Picks a video/audio file, downloads the speech model if needed, and transcribes it on the device. */
@Composable
expect fun rememberCaptionGenerator(
    model: String,
    engine: Int,
    translate: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onDone: (List<Word>?) -> Unit
): () -> Unit

/** Returns a function that asks where to save a text file and writes it. */
@Composable
expect fun rememberTextSaver(onResult: (String) -> Unit): (String, String) -> Unit

/** Picks a video file and reads its length and whether it has sound. */
@Composable
expect fun rememberVideoPicker(onPicked: (Source) -> Unit): () -> Unit

/** Picks a music or sound file. */
@Composable
expect fun rememberAudioPicker(onPicked: (Source) -> Unit): () -> Unit

/** One picture from a video at the given time, for the preview. */
expect suspend fun frameAt(src: Source, ms: Long): ImageBitmap?

/** Renders the project to an MP4 and returns a message saying where it was saved. */
expect suspend fun exportVideo(p: VideoProject, onProgress: (Float) -> Unit): String

/** True where the full FFmpeg engine is available. Otherwise the lighter Media3 engine is used. */
expect val fullEngine: Boolean

/** Text-to-speech to a WAV file. Returns the file path and its length, or null if unavailable. */
expect suspend fun generateSpeech(text: String): Pair<String, Long>?

/** Cuts the background out of every frame of a clip part and returns a new video. */
expect suspend fun cutoutVideo(src: Source, inMs: Long, outMs: Long, bg: Int, opts: CutoutOptions, onProgress: (Float) -> Unit): Source?

/** True where a lighter built-in H.264 engine exists besides FFmpeg (the phone build). */
expect val hasBasicEngine: Boolean

/** Translates sentences between English and Hindi on the device. Returns null if it could not. */
expect suspend fun translateText(lines: List<String>, toHindi: Boolean, onStatus: (String) -> Unit): List<String>?

/** Fixes spelling in English subtitle words with a small language model on the device. Returns null if it could not. */
expect suspend fun fixSpelling(words: List<Word>, deep: Boolean, onStatus: (String) -> Unit): SpellResult?

/** Reads the small file where the app keeps what it learned from the person's edits (empty if there is none). */
expect fun readPrefs(): String

/** Replaces the contents of that file. */
expect fun writePrefs(text: String)
