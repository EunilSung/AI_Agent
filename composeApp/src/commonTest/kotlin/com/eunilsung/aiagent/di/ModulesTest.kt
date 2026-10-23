package com.eunilsung.aiagent.di

import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.db.AppDatabase
import com.eunilsung.aiagent.testsupport.createTestDatabase
import com.eunilsung.aiagent.ui.chat.ChatViewModel
import com.eunilsung.aiagent.ui.conversationlist.ConversationListViewModel
import com.eunilsung.aiagent.ui.login.LoginViewModel
import com.eunilsung.aiagent.ui.main.MainViewModel
import com.eunilsung.aiagent.ui.setting.SettingViewModel
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * 앱의 Koin 구성으로 화면 ViewModel 을 모두 만들 수 있는지 — 빠진 등록이 있으면 앱이 켜지자마자 죽는다.
 * 기기 저장소·DB·통신만 테스트용으로 바꾼다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModulesTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `앱 구성으로 화면 ViewModel 을 모두 만든다`() {
        val testModule = module {
            single<Settings> { MapSettings() }
            single<AppDatabase> { createTestDatabase() }
            single { HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }) }
        }
        val koin = koinApplication {
            allowOverride(true)
            modules(appModule, testModule)
        }.koin

        val viewModels = listOf(
            koin.get<MainViewModel>(),
            koin.get<LoginViewModel>(),
            koin.get<ConversationListViewModel>(),
            koin.get<ChatViewModel> { parametersOf("conversation-1") },
            koin.get<SettingViewModel>(),
        )

        viewModels.forEach { it.viewModelScope.cancel() }
        koin.close()
    }
}
