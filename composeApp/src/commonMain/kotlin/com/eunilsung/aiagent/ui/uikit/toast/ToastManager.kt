package com.eunilsung.aiagent.ui.uikit.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

interface ToastManager {
    fun show(message: String, icon: (@Composable () -> Unit)? = null)
}

val LocalToastManager = staticCompositionLocalOf<ToastManager> {
    object : ToastManager {
        override fun show(message: String, icon: (@Composable () -> Unit)?) { }
    }
}
