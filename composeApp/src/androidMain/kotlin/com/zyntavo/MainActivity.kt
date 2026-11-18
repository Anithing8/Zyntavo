package com.zyntavo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.ctx = applicationContext
        // The system bars take the colours of the app, so there is no black strip above the header.
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF121110.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = 0xFF1A1917.toInt()
        setContent { App() }
    }
}
