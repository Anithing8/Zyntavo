package com.photostudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val toolItems = listOf(Ico.Adjust to "Adjust", Ico.Filter to "Filters", Ico.Crop to "Crop", Ico.Text to "Text", Ico.Cutout to "Cutout", Ico.Sparkle to "Prompt")
private val navItems = listOf(Ico.Photo to "Photo", Ico.Captions to "Captions", Ico.Video to "Video")
private val ratios: List<Pair<String, Float?>> = listOf(
    "Original" to null, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "2:3" to 2f / 3f,
    "3:2" to 1.5f, "16:9" to 16f / 9f, "9:16" to 9f / 16f, "21:9" to 21f / 9f, "5:4" to 1.25f, "4:3" to 4f / 3f, "3:1" to 3f
)
private val stickers = listOf(
    "\uD83D\uDE00", "\uD83D\uDD25", "\u2764\uFE0F", "\u2B50", "\uD83D\uDC4D", "\uD83C\uDF89", "\uD83D\uDE0E", "\u2728",
    "\uD83D\uDE0D", "\uD83D\uDE02", "\uD83E\uDD29", "\uD83D\uDCAF", "\uD83C\uDF1F", "\uD83C\uDF08", "\u2600\uFE0F", "\uD83C\uDF38",
    "\uD83D\uDC51", "\uD83D\uDC96", "\uD83C\uDF40", "\uD83C\uDF54"
)

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.White,
            background = Ink, surface = Surface1, surfaceVariant = Surface2,
            onSurface = TextHi, onSurfaceVariant = TextLo, outline = Line
        ),
        typography = zyntavoTypography
    ) {
        // Without this, any Text that has no colour of its own is drawn in black.
        CompositionLocalProvider(LocalContentColor provides TextHi) {
            GlassBackground { AppShell() }
        }
    }
}

@Composable
private fun AppShell() {
    val ed = remember { Editor() }
    val cap = remember { CaptionsModel() }
    val vid = remember { VideoModel() }
    var screen by remember { mutableStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            containerColor = Surface1,
            shape = RoundedCornerShape(24.dp),
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZyntavoMark(Modifier.size(46.dp))
                    Column {
                        Text("Zyntavo", style = MaterialTheme.typography.titleLarge)
                        Text("Photo, video and subtitle editor \u00B7 version 3.1", color = TextLo, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    licenseNotice.split("\n\n").forEach { Text(it, color = TextLo, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 820.dp
        val body: @Composable () -> Unit = {
            when (screen) {
                0 -> Studio(ed)
                1 -> CaptionsScreen(cap)
                else -> VideoScreen(vid, cap)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavRail(screen, { screen = it }, { showAbout = true })
                Box(Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 14.dp, bottom = 12.dp)) { body() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ZyntavoMark(Modifier.size(24.dp))
                    Text("Zyntavo", style = MaterialTheme.typography.titleMedium)
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp)) { body() }
                BottomNav(screen, { screen = it }, { showAbout = true })
            }
        }
    }
}

@Composable
private fun NavItem(ico: Ico, name: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Glyph(ico, Modifier.size(24.dp), if (on) Accent else TextLo)
        Spacer(Modifier.height(3.dp))
        Text(name, color = if (on) Accent else TextLo, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun NavRail(selected: Int, onSelect: (Int) -> Unit, onAbout: () -> Unit) {
    Column(
        Modifier.width(92.dp).fillMaxHeight().padding(vertical = 16.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ZyntavoMark(Modifier.size(38.dp))
        Text("Zyntavo", color = TextHi, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        navItems.forEachIndexed { i, (ico, name) -> NavItem(ico, name, i == selected, { onSelect(i) }, Modifier.fillMaxWidth()) }
        Spacer(Modifier.weight(1f))
        NavItem(Ico.Info, "About", false, onAbout, Modifier.fillMaxWidth())
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit, onAbout: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Surface1)
            .drawBehind { drawLine(Line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        navItems.forEachIndexed { i, (ico, name) -> NavItem(ico, name, i == selected, { onSelect(i) }, Modifier.weight(1f)) }
        NavItem(Ico.Info, "About", false, onAbout, Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------------------------
// Photo
// ---------------------------------------------------------------------------------------------

@Composable
fun Studio(ed: Editor) {
    val scope = rememberCoroutineScope()
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    var tool by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var cutOpts by remember { mutableStateOf(CutoutOptions()) }
    val pick = rememberImagePicker { ed.load(it); tool = 0; msg = "" }
    val save = rememberImageSaver { msg = it }
    val hasImage = ed.shown != null

    val doCutout: () -> Unit = {
        val src = ed.original
        if (src != null && !busy) scope.launch {
            busy = true
            msg = "Preparing…"
            var failed = false
            val r = try {
                removeBackground(src, cutOpts) { msg = it }
            } catch (e: Throwable) {
                failed = true
                msg = "Cutout failed: ${e.message ?: "unknown error"}. Check your connection for the first run."
                null
            }
            busy = false
            if (r != null) {
                ed.cutout = r
                ed.set { it.copy(cutout = true) }
                msg = "Background removed."
            } else if (!failed) msg = "Could not remove the background from this photo."
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 700.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Photo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = pick) { Text("Open") }
                IconBtn(Ico.Undo, { ed.undo() }, ed.canUndo)
                IconBtn(Ico.Redo, { ed.redo() }, ed.canRedo)
                Spacer(Modifier.width(6.dp))
                Button(enabled = hasImage, onClick = { ed.shown?.let { save(renderExport(it, ed.s, tm, density)) } }) { Text("Export") }
            }
            if (msg.isNotEmpty()) Label(msg)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)

            if (!hasImage) {
                Box(Modifier.weight(1f).fillMaxWidth().glass(RoundedCornerShape(24.dp), 0.8f)) {
                    EmptyState(Ico.Photo, "Edit a photo", "Adjust the light, crop, add text, or cut out the background.", "Open a photo", pick)
                }
            } else if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Preview(ed, tm, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(360.dp).fillMaxHeight().glass(RoundedCornerShape(22.dp))) {
                        IconTabs(toolItems, tool, { tool = it }, Modifier.fillMaxWidth().padding(8.dp))
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) { ToolControls(ed, tool, busy, doCutout, cutOpts) { cutOpts = it } }
                    }
                }
            } else {
                Preview(ed, tm, Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp))) {
                    IconTabs(toolItems, tool, { tool = it }, Modifier.fillMaxWidth().padding(6.dp))
                    Column(
                        Modifier.fillMaxWidth().height(230.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) { ToolControls(ed, tool, busy, doCutout, cutOpts) { cutOpts = it } }
                }
            }
        }
    }
}

private fun Modifier.checker(): Modifier = drawBehind {
    val s = 14.dp.toPx()
    drawRect(Color(0xFF2A2D35))
    var y = 0f; var row = 0
    while (y < size.height) {
        var x = 0f; var col = 0
        while (x < size.width) {
            if ((row + col) % 2 == 0) drawRect(Color(0xFF353943), Offset(x, y), Size(s, s))
            x += s; col++
        }
        y += s; row++
    }
}

@Composable
fun Preview(ed: Editor, tm: TextMeasurer, modifier: Modifier) {
    val img = ed.shown
    Box(modifier.glass(RoundedCornerShape(24.dp), 0.8f).padding(14.dp), contentAlignment = Alignment.Center) {
        if (img != null) {
            val o = outSize(img, ed.s)
            val ratio = o.width.toFloat() / o.height
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val w: Dp
                val h: Dp
                if (ratio > maxWidth / maxHeight) { w = maxWidth; h = maxWidth / ratio } else { h = maxHeight; w = maxHeight * ratio }
                Canvas(
                    Modifier.size(w, h).clip(RoundedCornerShape(8.dp)).checker()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p -> ed.pickText(p.x / size.width, p.y / size.height) },
                                onDrag = { change, d ->
                                    change.consume()
                                    ed.dragText(d.x / size.width, d.y / size.height)
                                },
                                onDragEnd = { ed.end() },
                                onDragCancel = { ed.end() }
                            )
                        }
                ) { drawEdits(img, ed.previewState(), tm, if (ed.showOriginal) null else ed.selected) }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Surface1.copy(alpha = 0.88f))
                    .clickable { ed.showOriginal = !ed.showOriginal }.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (ed.showOriginal) "Original (tap to go back)" else "Compare", color = if (ed.showOriginal) Accent else TextHi, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ToolControls(ed: Editor, tool: Int, busy: Boolean, onCutout: () -> Unit, opts: CutoutOptions, onOpts: (CutoutOptions) -> Unit) {
    val s = ed.s
    when (tool) {
        0 -> {
            Param("Brightness", s.brightness, -0.4f..0.4f, "${(s.brightness * 100).roundToInt()}", { v -> ed.live { it.copy(brightness = v) } }, { ed.end() })
            Param("Contrast", s.contrast, 0.5f..1.5f, "${(s.contrast * 100).roundToInt()}%", { v -> ed.live { it.copy(contrast = v) } }, { ed.end() })
            Param("Saturation", s.saturation, 0f..2f, "${(s.saturation * 100).roundToInt()}%", { v -> ed.live { it.copy(saturation = v) } }, { ed.end() })
            Param("Warmth", s.warmth, -1f..1f, "${(s.warmth * 100).roundToInt()}", { v -> ed.live { it.copy(warmth = v) } }, { ed.end() })
            Param("Exposure", s.exposure, -1f..1f, "${(s.exposure * 100).roundToInt()}", { v -> ed.live { it.copy(exposure = v) } }, { ed.end() })
            Param("Tint", s.tint, -1f..1f, "${(s.tint * 100).roundToInt()}", { v -> ed.live { it.copy(tint = v) } }, { ed.end() })
            Param("Hue", s.hue, -180f..180f, "${s.hue.roundToInt()}°", { v -> ed.live { it.copy(hue = v) } }, { ed.end() })
            Param("Fade", s.fade, 0f..1f, "${(s.fade * 100).roundToInt()}%", { v -> ed.live { it.copy(fade = v) } }, { ed.end() })
            Param("Vignette", s.vignette, 0f..1f, "${(s.vignette * 100).roundToInt()}%", { v -> ed.live { it.copy(vignette = v) } }, { ed.end() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ed.autoEnhance() }) { Text("Auto enhance") }
                OutlinedButton(onClick = { ed.set { it.copy(brightness = 0f, contrast = 1f, saturation = 1f, warmth = 0f, vignette = 0f, exposure = 0f, tint = 0f, hue = 0f, fade = 0f) } }) { Text("Reset") }
            }
        }
        1 -> Chips {
            photoFilterNames.forEachIndexed { i, n ->
                FilterChip(selected = s.filter == i, onClick = { ed.set { it.copy(filter = i) } }, label = { Text(n) })
            }
        }
        2 -> {
            Label("Aspect ratio")
            Chips {
                ratios.forEach { (n, r) ->
                    FilterChip(selected = s.ratio == r, onClick = { ed.set { it.copy(ratio = r) } }, label = { Text(n) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ed.set { it.copy(rotation = (it.rotation + 90) % 360) } }) { Text("Rotate 90°") }
                OutlinedButton(onClick = { ed.set { it.copy(flip = !it.flip) } }) { Text("Flip") }
                OutlinedButton(onClick = { ed.set { it.copy(flipV = !it.flipV) } }) { Text("Flip up/down") }
            }
            Param("Frame", s.border, 0f..0.15f, "${(s.border * 100).roundToInt()}%", { v -> ed.live { it.copy(border = v) } }, { ed.end() })
            if (s.border > 0f) ColorDots(s.borderColor) { c -> ed.set { it.copy(borderColor = c) } }
        }
        3 -> {
            Button(onClick = { ed.addText("Your text") }) { Text("Add text") }
            Label("Stickers")
            Chips { stickers.forEach { e -> OutlinedButton(onClick = { ed.addText(e) }) { Text(e) } } }
            val sel = s.texts.firstOrNull { it.id == ed.selected }
            if (sel != null) {
                OutlinedTextField(
                    value = sel.text, onValueChange = { v -> ed.updateSel(true) { it.copy(text = v) } },
                    label = { Text("Text") }, modifier = Modifier.fillMaxWidth()
                )
                Param("Size", sel.size, 0.02f..0.25f, "${(sel.size * 100).roundToInt()}", { v -> ed.updateSel { it.copy(size = v) } }, { ed.end() })
                ColorDots(sel.color) { c -> ed.updateSel(true) { it.copy(color = c) } }
                DangerButton(onClick = { ed.deleteSel() }) { Text("Delete") }
            } else Label("Tap a text on the photo to edit it. Drag to move it.")
        }
        5 -> {
            Label("Describe the edit you want in your own words. It works on your device, with no internet.")
            OutlinedTextField(
                value = ed.promptText, onValueChange = { v -> ed.promptText = v },
                modifier = Modifier.fillMaxWidth(), label = { Text("For example: warmer, more contrast and a vignette") }
            )
            if (ed.promptText.isNotBlank()) {
                val r = Prompt.parse(ed.promptText)
                val shownActions = r.actions.filter { !it.soft && it.key != "note" }
                if (shownActions.isEmpty() && r.actions.isEmpty()) Label("I do not understand this yet.")
                else if (shownActions.isNotEmpty()) Label("I will: " + shownActions.joinToString(", ") { it.label })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ed.runPrompt() }, enabled = ed.promptText.isNotBlank()) { Text("Apply") }
                OutlinedButton(onClick = { ed.promptText = ""; ed.promptNote = "" }) { Text("Clear") }
            }
            if (ed.promptNote.isNotEmpty()) Label(ed.promptNote)
            Label("Quick looks")
            Chips { photoLooks.forEach { (name, text) -> OutlinedButton(onClick = { ed.promptText = text; ed.runPrompt() }) { Text(name) } } }
            Label("Ideas")
            Chips { promptIdeas.forEach { idea -> OutlinedButton(onClick = { ed.promptText = idea }) { Text(idea) } } }
            if (ed.promptHistory.isNotEmpty()) {
                Label("Recent")
                Chips { ed.promptHistory.forEach { h -> OutlinedButton(onClick = { ed.promptText = h }) { Text(h) } } }
            }
            PromptHelp(photoHelp) { ed.promptText = it }
            Label("Tip: say how much, for example \u201Cslightly warmer\u201D, \u201Cvery dark\u201D or \u201Cbrightness 30%\u201D. Use Undo above to take a prompt back.")
        }
        else -> {
            Label("Quality (each model downloads once)")
            Chips {
                matteQualityNames.forEachIndexed { q, n ->
                    FilterChip(selected = opts.quality == q, onClick = { onOpts(opts.copy(quality = q)) }, label = { Text("$n · ${matteQualityNotes[q]}") })
                }
            }
            Param("Edge cleanup", opts.cleanup, 0f..1f, "${(opts.cleanup * 100).roundToInt()}%", { onOpts(opts.copy(cleanup = it)) })
            Param("Feather", opts.feather.toFloat(), 0f..4f, "${opts.feather} px", { onOpts(opts.copy(feather = it.roundToInt())) })
            Button(onClick = onCutout, enabled = !busy) { Text(if (ed.cutout == null) "Remove background" else "Redo with these settings") }
            if (ed.cutout != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.cutout, onCheckedChange = { v -> ed.set { it.copy(cutout = v) } })
                    Spacer(Modifier.width(10.dp)); Text("Show cutout")
                }
            }
            Label("Background")
            OutlinedButton(onClick = { ed.set { it.copy(bg = null) } }) { Text("Transparent") }
            ColorDots(s.bg) { c -> ed.set { it.copy(bg = c) } }
        }
    }
}
