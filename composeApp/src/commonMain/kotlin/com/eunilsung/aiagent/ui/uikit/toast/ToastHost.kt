package com.eunilsung.aiagent.ui.uikit.toast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun ToastHost(content: @Composable () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    var icon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    /** 같은 문구를 연달아 띄워도 새 토스트로 인식되도록 호출마다 증가시킨다. */
    var showToken by remember { mutableStateOf(0) }

    val manager = remember {
        object : ToastManager {
            override fun show(m: String, i: (@Composable () -> Unit)?) {
                message = m
                icon = i
                showToken++
            }
        }
    }

    CompositionLocalProvider(LocalToastManager provides manager) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            ToastMessage(
                message = message.orEmpty(),
                isVisible = message != null,
                onDismiss = {
                    message = null
                    icon = null
                },
                left = icon,
                restartKey = showToken,
            )
        }
    }
}
