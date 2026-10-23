package com.eunilsung.aiagent.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** Android — 내비게이션 바 인셋. */
@Composable
actual fun keyboardBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
