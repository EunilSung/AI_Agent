package com.eunilsung.aiagent

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import cafe.adriel.voyager.navigator.Navigator
import com.eunilsung.aiagent.di.appModule
import com.eunilsung.aiagent.ui.main.MainScreen
import org.koin.core.context.startKoin
import org.koin.core.error.KoinApplicationAlreadyStartedException

/** 키보드가 올라오면 화면 전체를 밀지 않고, 입력창이 스스로 키보드만큼 올라간다(imePadding). Talk 와 같다. */
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController() = ComposeUIViewController(
    configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
        try {
            startKoin { modules(appModule, platformModule) }
        } catch (_: KoinApplicationAlreadyStartedException) {
        }
    }
) {
    Navigator(MainScreen)
}
