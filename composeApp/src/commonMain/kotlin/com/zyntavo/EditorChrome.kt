package com.zyntavo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The editor layout shared by Photo and Video: the picture on top, a row of tools at the bottom,
// and a sheet that replaces the row when a tool is open. This is the usual arrangement of a phone editor.
// ---------------------------------------------------------------------------------------------

/** One tool in the bottom row: an icon with a label. */
@Composable
fun DockItem(ico: Ico, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = TextHi) {
    Column(
        Modifier.width(68.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Glyph(ico, Modifier.size(24.dp), tint)
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (tint == TextHi) TextLo else tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A thin divider between groups of tools in the bottom row. */
@Composable
fun DockGap() {
    Box(Modifier.padding(vertical = 14.dp, horizontal = 4.dp).width(1.dp).height(36.dp).background(Line))
}

/** The bottom row of tools. It scrolls sideways. */
@Composable
fun ToolDock(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Surface1)
            .drawBehind { drawLine(Line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) }
            .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

/** A tool's controls, with its name and a tick to close it. */
@Composable
fun ToolSheet(title: String, onDone: () -> Unit, height: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Surface1).drawBehind { drawLine(Line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) }) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconBtn(Ico.Check, onDone)
        }
        Column(
            Modifier.fillMaxWidth().height(height).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The prompt panel, the same for photos and video.
// ---------------------------------------------------------------------------------------------

/** Adds a phrase to a prompt, with a comma between phrases. */
fun addToPrompt(text: String, add: String): String {
    val t = text.trim().trimEnd(',').trim()
    if (t.isEmpty()) return add
    if (t.contains(add, ignoreCase = true)) return text
    return "$t, $add"
}

/** A small read-only tag, used to show what the prompt was understood as. */
@Composable
private fun Tag(text: String, ok: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.clip(shape).border(1.dp, if (ok) LineStrong else Danger.copy(alpha = 0.6f), shape).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (ok) Glyph(Ico.Check, Modifier.size(14.dp), TextLo)
        Text(text, color = if (ok) TextHi else Danger, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Write what you want, see how it was understood while you type, then apply it.
 * [adds] are short phrases that are added to the text when tapped, so a prompt can be built from taps.
 * [looks] are whole edits that are applied at once. [actions] holds the apply buttons.
 */
@Composable
fun PromptSheet(
    text: String, onText: (String) -> Unit, hint: String,
    adds: List<String>, looks: List<Pair<String, String>>, onLook: (String) -> Unit,
    ideas: List<String>, history: List<String>, note: String,
    help: List<HelpGroup>, canUndo: Boolean, onUndo: () -> Unit,
    auto: String, onAuto: () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text, onValueChange = onText, modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 4, placeholder = { Text(hint, color = TextMute) }
        )
        if (text.isNotEmpty()) IconBtn(Ico.Close, { onText("") }, tint = TextLo, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp))
    }

    val goal = if (text.isNotBlank()) Auto.detect(text) else null
    var explicit = false
    if (text.isNotBlank()) {
        val r = Prompt.parse(text)
        val shown = r.actions.filter { !it.soft && it.key != "note" }
        explicit = shown.isNotEmpty()
        if (goal != null || shown.isNotEmpty() || r.unknown.isNotEmpty()) {
            Label("Understood")
            Chips {
                if (goal != null) Tag("Goal: ${goal.label}", true)
                shown.forEach { Tag(it.label, true) }
                if (goal == null) r.unknown.forEach { Tag(it, false) }
            }
            if (goal != null) Label("I will look at your $auto, decide the edits myself, and also do anything exact you wrote.")
            else if (r.unknown.isNotEmpty()) Label("Words in red are skipped. Try other words, or tap Auto edit and let me decide.")
        } else {
            Label("I am not sure what that means. Tap Auto edit and I will look at your $auto and choose what helps most.")
        }
    } else {
        Label("Describe the edit, tap the phrases below, or tap Auto edit to let me look at your $auto and decide. Everything runs on your device.")
    }

    // When the text states a goal, deciding is the main action. Otherwise applying the exact words is.
    val autoMain = goal != null && !explicit
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (autoMain) Button(onClick = onAuto) { Text("Auto edit") }
        else {
            actions()
            OutlinedButton(onClick = onAuto) { Text("Auto edit") }
        }
    }
    if (canUndo) OutlinedButton(onClick = onUndo) { Text("Undo last prompt") }
    if (note.isNotEmpty()) Label(note)

    if (adds.isNotEmpty()) {
        Label("Tap to add")
        Chips { adds.forEach { a -> FilterChip(selected = false, onClick = { onText(addToPrompt(text, a)) }, label = { Text(a) }) } }
    }
    if (looks.isNotEmpty()) {
        Label("Looks (applied at once)")
        Chips { looks.forEach { (name, t) -> OutlinedButton(onClick = { onLook(t) }) { Text(name) } } }
    }
    if (history.isNotEmpty()) {
        Label("Recent")
        Chips { history.forEach { h -> OutlinedButton(onClick = { onText(h) }) { Text(h) } } }
    }
    if (ideas.isNotEmpty()) {
        Label("Examples")
        Chips { ideas.forEach { i -> OutlinedButton(onClick = { onText(i) }) { Text(i) } } }
    }
    if (help.isNotEmpty()) PromptHelp(help) { onText(it) }
}

val photoAdds = listOf("warmer", "cooler", "brighter", "darker", "more contrast", "more saturated", "black and white", "vignette", "sharpen", "film grain", "square", "blur the background")
val videoAdds = listOf("slow motion", "speed up", "warm", "cinematic", "black and white", "vintage", "fade in and out", "slow zoom in", "mirror", "reverse", "stabilize", "freeze frame", "blurred background")
