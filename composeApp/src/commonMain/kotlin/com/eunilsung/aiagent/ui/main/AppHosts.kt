package com.eunilsung.aiagent.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import com.eunilsung.aiagent.ui.uikit.dialog.DialogHost
import com.eunilsung.aiagent.ui.uikit.toast.ToastHost

/**
 * 앱 전역 호스트 — 토스트·다이얼로그와 우측 네비게이터. Talk 의 AppHosts 에서 이 앱이 쓰는 것만 남겼다.
 *
 * 위쪽 안전 영역은 여기서 비우고, 아래쪽(내비게이션 바·키보드)은 하단 탭과 입력창이 각자 비운다.
 */
@Composable
fun AppHosts(
    rightNavigator: Navigator,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRightNavigator provides rightNavigator) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                ToastHost {
                    DialogHost {
                        content()
                    }
                }
            }
        }
    }
}
