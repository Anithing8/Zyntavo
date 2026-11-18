package com.zyntavo

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Zyntavo",
        icon = BitmapPainter(useResource("zyntavo.png", ::loadImageBitmap)),
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) { App() }
}
