package com.eunilsung.aiagent.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow

/** iOS 창의 하단 safe-area(홈 인디케이터) inset — 키보드 높이 보정용. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun keyboardBottomInset(): Dp {
    val bottom = remember {
        val app = UIApplication.sharedApplication
        val window = app.keyWindow
            ?: (app.windows.firstOrNull() as? UIWindow)
        window?.safeAreaInsets?.useContents { bottom } ?: 0.0
    }
    return bottom.dp
}
