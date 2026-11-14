package com.zyntavo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// Design tokens. One accent, flat dark surfaces, thin borders, one spacing grid.
// ---------------------------------------------------------------------------------------------

val Accent = Color(0xFF8A7CFF)
val AccentWarm = Color(0xFFFF7A59)
val Ink = Color(0xFF0D1016)
val Surface1 = Color(0xFF151921)
val Surface2 = Color(0xFF1C212B)
val Surface3 = Color(0xFF252B38)
val Line = Color(0xFF2B3141)
val TextHi = Color(0xFFF2F4F8)
val TextLo = Color(0xFF9BA4B4)
val Danger = Color(0xFFFF6B6B)
val palette = listOf(Color.White, Color.Black, Color(0xFFFF4D4D), Color(0xFFFFC145), Color(0xFF3DDC84), Color(0xFF00D4C8), Color(0xFF4D7CFF), Color(0xFFD14DFF))

val zyntavoTypography = Typography(
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
)

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Ink).drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF121722), Ink)))
            val r = size.minDimension * 0.9f
            val c = Offset(size.width * 0.1f, 0f)
            drawCircle(Brush.radialGradient(listOf(Color(0x265B4BFF), Color.Transparent), center = c, radius = r), radius = r, center = c)
            val c2 = Offset(size.width * 1.0f, size.height)
            drawCircle(Brush.radialGradient(listOf(Color(0x14FF7A59), Color.Transparent), center = c2, radius = r), radius = r, center = c2)
        },
        content = content
    )
}

/** A flat surface with a thin border and a faint light edge on top. strength: below 1 darker, above 1 lighter. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(20.dp), strength: Float = 1f): Modifier {
    val fill = when {
        strength < 0.9f -> Color(0xFF11141B)
        strength > 1.6f -> Surface3
        else -> Surface1
    }
    return this.clip(shape).background(fill)
        .drawBehind { drawLine(Color.White.copy(alpha = 0.07f), Offset(14f, 0.5f), Offset(size.width - 14f, 0.5f), 1f) }
        .border(1.dp, Line, shape)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A titled group of controls that can be folded away. */
@Composable
fun Section(title: String, startOpen: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Glyph(if (open) Ico.ChevronUp else Ico.ChevronDown, Modifier.size(20.dp), TextLo)
        }
        if (open) content()
    }
}

@Composable
fun Label(text: String) {
    Text(text, color = TextLo, style = MaterialTheme.typography.labelLarge)
}

// ---------------------------------------------------------------------------------------------
// Icons, drawn with plain shapes so there is nothing to download or license.
// ---------------------------------------------------------------------------------------------

enum class Ico { Photo, Captions, Video, Info, Undo, Redo, Open, Export, Adjust, Filter, Crop, Text, Cutout, Plus, Play, Pause, Trash, Copy, Layers, Music, Clip, Cut, Check, Sparkle, ChevronDown, ChevronUp }

@Composable
fun Glyph(ico: Ico, modifier: Modifier = Modifier.size(22.dp), tint: Color = Color.White) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val sw = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, o(x1, y1), o(x2, y2), 1.9f * u, StrokeCap.Round)
        fun rrect(x1: Float, y1: Float, x2: Float, y2: Float, r: Float) =
            drawRoundRect(tint, o(x1, y1), Size((x2 - x1) * u, (y2 - y1) * u), CornerRadius(r * u), style = sw)
        fun poly(vararg p: Float, close: Boolean = false) {
            val path = Path()
            path.moveTo(p[0] * u, p[1] * u)
            var i = 2
            while (i + 1 < p.size) { path.lineTo(p[i] * u, p[i + 1] * u); i += 2 }
            if (close) path.close()
            drawPath(path, tint, style = sw)
        }
        fun fillPoly(vararg p: Float) {
            val path = Path()
            path.moveTo(p[0] * u, p[1] * u)
            var i = 2
            while (i + 1 < p.size) { path.lineTo(p[i] * u, p[i + 1] * u); i += 2 }
            path.close()
            drawPath(path, tint)
        }
        fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, o(x, y))
        fun ring(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, o(x, y), style = sw)
        when (ico) {
            Ico.Photo -> { rrect(3f, 4f, 21f, 20f, 3f); ring(8.5f, 9.5f, 1.6f); poly(4f, 18f, 10f, 12f, 14f, 16f, 17f, 13f, 21f, 17f) }
            Ico.Captions -> { rrect(3f, 5f, 21f, 19f, 3f); line(7f, 10f, 10.5f, 10f); line(13f, 10f, 17f, 10f); line(7f, 14f, 13f, 14f) }
            Ico.Video -> { rrect(3f, 5f, 21f, 19f, 3f); fillPoly(10f, 9f, 10f, 15f, 15.5f, 12f) }
            Ico.Info -> { ring(12f, 12f, 9f); line(12f, 11f, 12f, 17f); dot(12f, 7.8f, 1.1f) }
            Ico.Undo -> { poly(9f, 6f, 4f, 11f, 9f, 16f); poly(4f, 11f, 14f, 11f, 19f, 13f, 19f, 18f) }
            Ico.Redo -> { poly(15f, 6f, 20f, 11f, 15f, 16f); poly(20f, 11f, 10f, 11f, 5f, 13f, 5f, 18f) }
            Ico.Open -> poly(3f, 7f, 10f, 7f, 12f, 9f, 21f, 9f, 21f, 19f, 3f, 19f, close = true)
            Ico.Export -> { line(12f, 15f, 12f, 4f); poly(8f, 8f, 12f, 4f, 16f, 8f); poly(5f, 13f, 5f, 20f, 19f, 20f, 19f, 13f) }
            Ico.Adjust -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f); dot(9f, 7f, 2.3f); dot(15f, 12f, 2.3f); dot(8f, 17f, 2.3f) }
            Ico.Filter -> { ring(9.5f, 12f, 5.5f); ring(14.5f, 12f, 5.5f) }
            Ico.Crop -> { poly(7f, 3f, 7f, 17f, 21f, 17f); poly(3f, 7f, 17f, 7f, 17f, 21f) }
            Ico.Text -> { line(5f, 6f, 19f, 6f); line(12f, 6f, 12f, 19f); line(9f, 19f, 15f, 19f) }
            Ico.Cutout -> { ring(12f, 8f, 3.4f); poly(5f, 20f, 6.5f, 15f, 12f, 13f, 17.5f, 15f, 19f, 20f) }
            Ico.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ico.Play -> fillPoly(8f, 5f, 8f, 19f, 19f, 12f)
            Ico.Pause -> { drawRect(tint, o(7f, 5f), Size(3.6f * u, 14f * u)); drawRect(tint, o(13.4f, 5f), Size(3.6f * u, 14f * u)) }
            Ico.Trash -> { line(5f, 7f, 19f, 7f); poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); poly(7f, 7f, 8f, 20f, 16f, 20f, 17f, 7f) }
            Ico.Copy -> { rrect(8f, 8f, 20f, 20f, 2.5f); poly(16f, 8f, 16f, 5f, 4f, 5f, 4f, 16f, 8f, 16f) }
            Ico.Layers -> { poly(12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f, close = true); poly(3f, 13f, 12f, 18f, 21f, 13f) }
            Ico.Music -> { dot(8f, 17f, 2.6f); dot(16f, 15f, 2.6f); line(10.6f, 17f, 10.6f, 6f); line(18.6f, 15f, 18.6f, 4f); line(10.6f, 6f, 18.6f, 4f) }
            Ico.Clip -> { rrect(3f, 6f, 21f, 18f, 2.5f); line(8f, 6f, 8f, 18f); line(16f, 6f, 16f, 18f) }
            Ico.Cut -> { ring(7f, 17f, 2.8f); ring(17f, 17f, 2.8f); line(8.8f, 15f, 17f, 4f); line(15.2f, 15f, 7f, 4f) }
            Ico.Check -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Ico.ChevronDown -> poly(6f, 9f, 12f, 15f, 18f, 9f)
            Ico.ChevronUp -> poly(6f, 15f, 12f, 9f, 18f, 15f)
            Ico.Sparkle -> poly(12f, 3f, 14f, 10f, 21f, 12f, 14f, 14f, 12f, 21f, 10f, 14f, 3f, 12f, 10f, 10f, close = true)
        }
    }
}

@Composable
fun IconBtn(ico: Ico, onClick: () -> Unit, enabled: Boolean = true, tint: Color = TextHi, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).alpha(if (enabled) 1f else 0.35f).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Glyph(ico, Modifier.size(22.dp), tint) }
}

@Composable
fun EmptyState(ico: Ico, title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.26f), AccentWarm.copy(alpha = 0.12f))))
                .border(1.dp, Accent.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Glyph(ico, Modifier.size(40.dp), Accent) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, color = TextLo, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction, modifier = Modifier.height(52.dp)) {
            Glyph(Ico.Plus, Modifier.size(20.dp), Color.White)
            Spacer(Modifier.width(8.dp))
            Text(action)
        }
    }
}

/** A numbered line for short how-it-works lists. */
@Composable
fun StepRow(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text("$n", color = Accent, style = MaterialTheme.typography.labelLarge)
        }
        Text(text, color = TextLo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------------------------
// Tabs and chips
// ---------------------------------------------------------------------------------------------

/** Icon with a label underneath, for tools and tabs. All tabs share the width so no label is cut off. */
@Composable
fun IconTabs(items: List<Pair<Ico, String>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, (ico, name) ->
            val on = i == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (on) Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Glyph(ico, Modifier.size(22.dp), if (on) Accent else TextLo)
                Spacer(Modifier.height(3.dp))
                Text(name, color = if (on) Accent else TextLo, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(Surface2).border(1.dp, Line, shape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, name ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) Accent else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(name, maxLines = 1, textAlign = TextAlign.Center, color = if (on) Color.White else TextLo, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun Chips(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
fun ColorDots(selected: Color?, onPick: (Color) -> Unit) = Chips {
    palette.forEach { c ->
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(c)
                .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Accent else Color.White.copy(alpha = 0.35f), CircleShape)
                .clickable { onPick(c) }
        )
    }
}

/** A slider with its name and current value on one line. */
@Composable
fun Param(label: String, value: Float, range: ClosedFloatingPointRange<Float>, show: String, onChange: (Float) -> Unit, onEnd: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(label)
            Spacer(Modifier.weight(1f))
            Text(show, color = TextHi, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value, onChange, valueRange = range, onValueChangeFinished = onEnd)
    }
}

// The functions below share their names with Material 3 on purpose: inside this package they
// take priority, so every screen picks up the same look without changing its call sites.

@Composable
private fun Pill(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, fill: Color, border: Color, text: Color, primary: Boolean, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    // A disabled button is a quiet grey tile, not a faded copy of the active one.
    val brush = if (enabled && primary) Brush.linearGradient(listOf(Color(0xFF7B6CFF), Color(0xFF5E4FE8))) else SolidColor(if (enabled) fill else Surface2)
    val edge = if (enabled) (if (primary) Color(0xFF9A8EFF) else border) else Line
    val ink = if (enabled) text else TextLo.copy(alpha = 0.65f)
    Row(
        modifier.heightIn(min = 44.dp).clip(shape).background(brush, shape).border(1.dp, edge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Pill(onClick, modifier, enabled, Accent, Accent, Color.White, true, content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Pill(onClick, modifier, enabled, Color.White.copy(alpha = 0.06f), Line, TextHi, false, content)

@Composable
fun DangerButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Pill(onClick, modifier, enabled, Danger.copy(alpha = 0.10f), Danger.copy(alpha = 0.40f), Danger, false, content)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides TextHi) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (selected) Accent else Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) Color.White else TextLo) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

@Composable
fun Slider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, onValueChangeFinished: (() -> Unit)? = null
) {
    val range = if (valueRange.endInclusive > valueRange.start) valueRange else valueRange.start..(valueRange.start + 1f)
    androidx.compose.material3.Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = range,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Surface3)
    )
}

@Composable
fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: (@Composable () -> Unit)? = null) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, label = label,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Line,
            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Accent,
            focusedLabelColor = Accent, unfocusedLabelColor = TextLo,
            focusedContainerColor = Surface2, unfocusedContainerColor = Surface2
        )
    )
}

/** The Zyntavo mark: a gradient tile with a Z whose diagonal is cut, like a clip being split. */
@Composable
fun ZyntavoMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF5B4BFF), Color(0xFFB03DFF), Color(0xFFFF7A59)), start = Offset(0f, 0f), end = Offset(s, s)),
            cornerRadius = CornerRadius(s * 0.28f)
        )
        val w = s * 0.09f
        fun at(x: Float, y: Float) = Offset(s * x, s * y)
        drawLine(Color.White, at(0.29f, 0.31f), at(0.71f, 0.31f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(Color.White, at(0.29f, 0.69f), at(0.71f, 0.69f), strokeWidth = w, cap = StrokeCap.Round)
        // The diagonal is drawn in two pieces with a flat cut between them.
        fun along(t: Float) = at(0.71f + (0.29f - 0.71f) * t, 0.31f + (0.69f - 0.31f) * t)
        drawLine(Color.White, along(0f), along(0.38f), strokeWidth = w, cap = StrokeCap.Butt)
        drawCircle(Color.White, w / 2f, along(0f))
        drawLine(Color.White, along(0.58f), along(1f), strokeWidth = w, cap = StrokeCap.Butt)
        drawCircle(Color.White, w / 2f, along(1f))
    }
}
