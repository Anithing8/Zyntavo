package com.zyntavo

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** A group of example sentences for the "What can I say?" panel. */
class HelpGroup(val title: String, val examples: List<String>)

val photoHelp = listOf(
    HelpGroup("Light and colour", listOf("brighter", "slightly darker", "more contrast", "less colour", "warmer", "cooler", "fix the colours")),
    HelpGroup("Looks", listOf("cinematic", "vintage", "moody", "dreamy", "cyberpunk", "black and white", "teal and orange", "golden hour")),
    HelpGroup("Effects", listOf("add a vignette", "remove the vignette", "faded look", "invert")),
    HelpGroup("Shape and turning", listOf("make it square", "9:16", "rotate left", "flip upside down", "add a white border")),
    HelpGroup("Text and stickers", listOf("add text \"Hello\" at the top in red, big", "add a heart sticker")),
    HelpGroup("Start over", listOf("reset everything"))
)

val videoHelp = listOf(
    HelpGroup("Cutting", listOf("split at 5 seconds", "cut the first 2 seconds", "cut the last 3 seconds", "keep only the first 10 seconds", "delete the second clip", "duplicate this clip", "move this clip left")),
    HelpGroup("Speed", listOf("slow motion", "2x speed", "timelapse", "speed up the last 3 seconds")),
    HelpGroup("Look", listOf("warm and cinematic", "black and white", "add film grain and a vignette", "mirror this clip", "zoom in a little")),
    HelpGroup("Animation and transitions", listOf("slow zoom in", "fade in and out", "pop in", "add a dissolve transition", "wipe left transition", "no transition")),
    HelpGroup("Titles", listOf("add title \"My trip\" at the bottom for 4 seconds")),
    HelpGroup("Sound", listOf("mute", "reduce volume", "robot voice", "deep voice")),
    HelpGroup("Shape of the video", listOf("make it vertical", "square video", "4:5", "16:9")),
    HelpGroup("Captions and export", listOf("turn off the captions", "1080p 60 fps high quality", "export in 720p")),
    HelpGroup("All clips", listOf("make all clips black and white"))
)

/** A collapsible list of things that can be written, as buttons that fill the prompt box. */
@Composable
fun PromptHelp(groups: List<HelpGroup>, onPick: (String) -> Unit) {
    Section("What can I say?", startOpen = false) {
        Label("Tap an example to put it in the box, then change it as you like.")
        groups.forEach { g ->
            Label(g.title)
            Chips { g.examples.forEach { ex -> OutlinedButton(onClick = { onPick(ex) }) { Text(ex) } } }
        }
    }
}
