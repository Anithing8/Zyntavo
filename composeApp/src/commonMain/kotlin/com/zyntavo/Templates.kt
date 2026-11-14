package com.zyntavo

import androidx.compose.ui.graphics.Color

/** A one-tap starting point for a photo. It sets the shape, the look and the finishing. */
class PhotoTemplate(val name: String, val apply: (EditState) -> EditState)

/** Keeps the person's text, cutout, turn and flip, and starts everything else fresh. */
private fun fresh(it: EditState) = EditState(texts = it.texts, cutout = it.cutout, bg = it.bg, rotation = it.rotation, flip = it.flip, flipV = it.flipV)

val photoTemplates = listOf(
    PhotoTemplate("Clean post") { fresh(it).copy(ratio = 0.8f, brightness = 0.03f, contrast = 1.08f, saturation = 1.1f, sharpen = 0.35f, highlights = -0.2f, shadows = 0.2f) },
    PhotoTemplate("Polaroid") { fresh(it).copy(ratio = 1f, border = 0.07f, borderColor = Color.White, fade = 0.25f, warmth = 0.15f, vignette = 0.15f, grain = 0.2f) },
    PhotoTemplate("Cinematic") { fresh(it).copy(ratio = 21f / 9f, filter = 16, vignette = 0.35f, grain = 0.25f, contrast = 1.1f) },
    PhotoTemplate("Retro film") { fresh(it).copy(filter = 20, grain = 0.5f, vignette = 0.3f, warmth = 0.2f, fade = 0.2f) },
    PhotoTemplate("Moody B&W") { fresh(it).copy(filter = 7, grain = 0.35f, vignette = 0.3f, contrast = 1.1f) },
    PhotoTemplate("Bright and airy") { fresh(it).copy(filter = 21, exposure = 0.15f, warmth = 0.1f, shadows = 0.35f, highlights = -0.15f) },
    PhotoTemplate("Story") { fresh(it).copy(ratio = 9f / 16f, filter = 19, vignette = 0.2f, sharpen = 0.3f) },
    PhotoTemplate("Soft dream") { fresh(it).copy(filter = 18, fade = 0.2f, brightness = 0.04f, saturation = 0.9f, grain = 0.1f) }
)

/** A one-tap starting point for a video: shape, look, transitions and a title. It can be undone with Undo. */
class VideoTemplate(val name: String, val desc: String, val apply: (VideoModel) -> Unit)

private fun VideoModel.styleAll(f: (Clip) -> Clip) {
    for (i in clips.indices) clips[i] = f(clips[i])
}

private fun VideoModel.fadeAll(zoom: Int) {
    if (!fullEngine) return
    for (i in clips.indices) {
        val c = clips[i]
        clips[i] = c.copy(keys = ClipAnim.keys(clipDur(c), zoom, i == 0, i == clips.lastIndex, false))
    }
}

private fun VideoModel.joinAll(trans: Int) {
    if (!fullEngine) return
    for (i in 1 until clips.size) clips[i] = clips[i].copy(trans = trans, transMs = 600L)
}

private fun VideoModel.setTitle(text: String, x: Float, y: Float, size: Float, color: Color, anim: Int) {
    titles.clear()
    titles.add(Title(text, 300L, 3300L, x, y, size, color, if (fullEngine) anim else 0))
}

val videoTemplates = listOf(
    VideoTemplate("Vlog", "Tall and warm, blurred sides, lower-third title") { m ->
        if (fullEngine) m.settings = m.settings.copy(aspect = 1, blurBg = true)
        m.styleAll { it.copy(filter = 3, saturation = 1.1f, brightness = 0.03f) }
        m.fadeAll(0)
        m.setTitle("My vlog", 0.5f, 0.8f, 0.065f, Color.White, 2)
    },
    VideoTemplate("Cinematic", "Wide, cinema bars, slow zoom and a fading title") { m ->
        if (fullEngine) m.settings = m.settings.copy(aspect = 0)
        m.styleAll { it.copy(filter = 9, contrast = 1.1f, fx = it.fx or 64) }
        m.fadeAll(1)
        m.setTitle("TITLE", 0.5f, 0.5f, 0.1f, Color.White, 1)
    },
    VideoTemplate("Travel reel", "Portrait, vivid, crossfades and a typed title") { m ->
        if (fullEngine) m.settings = m.settings.copy(aspect = 3, blurBg = true)
        m.styleAll { it.copy(filter = 10) }
        m.joinAll(3)
        m.setTitle("Travel diary", 0.5f, 0.12f, 0.06f, Color(0xFFFFC145), 6)
    },
    VideoTemplate("Retro", "Vintage colour, grain, dark corners, pop-in tag") { m ->
        m.styleAll { it.copy(filter = 8, fx = it.fx or 4 or 8) }
        m.setTitle("REC", 0.08f, 0.08f, 0.05f, Color(0xFFFF4D4D), 5)
    },
    VideoTemplate("Punchy short", "Tall, vivid, sharp, with a speed ramp on the first clip") { m ->
        if (fullEngine) m.settings = m.settings.copy(aspect = 1, blurBg = true)
        m.styleAll { it.copy(filter = 5, fx = it.fx or 2) }
        if (m.clips.isNotEmpty()) {
            m.selClip = 0
            m.speedRamp(2)
        }
        m.setTitle("WAIT FOR IT", 0.5f, 0.25f, 0.08f, Color.White, 5)
    },
    VideoTemplate("Product promo", "Square, bright and sharp, sliding title and cuts") { m ->
        if (fullEngine) m.settings = m.settings.copy(aspect = 2)
        m.styleAll { it.copy(filter = 21, fx = it.fx or 2) }
        m.joinAll(8)
        m.setTitle("New", 0.5f, 0.88f, 0.08f, Color.White, 4)
    },
    VideoTemplate("Moody portrait", "Portrait, black and white, grain, fading title") { m ->
        if (fullEngine) m.settings = m.settings.copy(aspect = 3, blurBg = true)
        m.styleAll { it.copy(filter = 7, contrast = 1.15f, brightness = -0.03f, fx = it.fx or 4 or 8) }
        m.fadeAll(0)
        m.setTitle("Untitled", 0.5f, 0.85f, 0.06f, Color.White, 1)
    }
)
