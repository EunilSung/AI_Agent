package com.eunilsung.aiagent.ui.login

import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.repository.ServerSettingsRepositoryImpl
import com.eunilsung.aiagent.domain.usecase.GetLastLoginIdUseCase
import com.eunilsung.aiagent.domain.usecase.LoginUseCase
import com.eunilsung.aiagent.domain.usecase.LoginUseCases
import com.eunilsung.aiagent.domain.usecase.ObserveServerUrlUseCase
import com.eunilsung.aiagent.domain.usecase.SetServerUrlUseCase
import com.eunilsung.aiagent.testsupport.FakeAuth
import com.russhwolf.settings.MapSettings
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 로그인 화면. Talk 요약 링크가 다른 계정을 요청하면 MainScreen 이 [LoginActions.OnAccountRequested] 를 보낸다 —
 * 아이디 칸이 그 계정으로 바뀌고 비밀번호는 비워져, 사용자는 비밀번호만 넣어 그 계정으로 로그인한다.
 * 로그인은 [FakeAuth] 대역이 진짜 [SessionStore] 를 바꾼다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Env(val session: SessionStore, val viewModel: LoginViewModel)

    /** 마지막으로 [lastLoginId] 로 로그인했다가 로그아웃한 기기. */
    private fun env(lastLoginId: String = "test1"): Env {
        val settings = MapSettings()
        val session = SessionStore(settings).apply { this.lastLoginId = lastLoginId }
        val auth = FakeAuth(session)
        val urlStore = BridgeUrlStore(settings)
        val serverSettings = ServerSettingsRepositoryImpl(
            urlStore,
            BridgeClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }), urlStore),
        )
        val viewModel = LoginViewModel(
            LoginUseCases(
                login = LoginUseCase(auth),
                getLastLoginId = GetLastLoginIdUseCase(auth),
                observeServerUrl = ObserveServerUrlUseCase(serverSettings),
                setServerUrl = SetServerUrlUseCase(serverSettings),
            )
        )
        return Env(session, viewModel)
    }

    @Test
    fun `요약 링크가 다른 계정을 요청하면 아이디를 바꾸고 넣던 비밀번호를 비우고 가린다`() {
        val env = env()
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnTogglePasswordVisible)
        assertEquals("test1", env.viewModel.uiState.value.id)
        assertTrue(env.viewModel.uiState.value.isPasswordVisible)

        env.viewModel.onAction(LoginActions.OnAccountRequested(" test2 "))

        val state = env.viewModel.uiState.value
        assertEquals("test2", state.id)
        assertEquals("", state.pw)
        assertFalse(state.isPasswordVisible)
        assertFalse(state.canSubmit, "비밀번호를 다시 넣기 전에는 로그인할 수 없다")
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `아이디를 건드리지 않고 비밀번호만 넣으면 요청한 계정으로 로그인한다`() {
        val env = env()

        env.viewModel.onAction(LoginActions.OnAccountRequested("test2"))
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnLoginClick)

        assertEquals("test2", env.session.user.value?.id)
        assertEquals(LoginStatus.Idle, env.viewModel.uiState.value.status)
        assertEquals("", env.viewModel.uiState.value.pw)
        env.viewModel.viewModelScope.cancel()
    }

    /** 로그인 전에 고치는 서버 주소도 https 이거나 에뮬레이터·시뮬레이터 주소여야 한다. 아니면 입력칸을 연 채 안내한다. */
    @Test
    fun `같은 망의 http 주소는 저장하지 않고 입력칸을 연 채 안내한다`() {
        val env = env()
        val before = env.viewModel.uiState.value.serverUrl
        env.viewModel.onAction(LoginActions.OnToggleServerEdit)
        env.viewModel.onAction(LoginActions.OnServerInputChange("http://192.168.0.10:8095"))

        env.viewModel.onAction(LoginActions.OnServerSave)

        val refused = env.viewModel.uiState.value
        assertTrue(refused.isServerInvalid)
        assertEquals("http://192.168.0.10:8095", refused.serverInput, "고칠 수 있게 입력을 남긴다")
        assertEquals(before, refused.serverUrl, "막힌 주소로 바꿨다")

        env.viewModel.onAction(LoginActions.OnServerInputChange("https://bridge.example.com"))
        assertFalse(env.viewModel.uiState.value.isServerInvalid, "고치기 시작했는데 안내가 남아 있다")
        env.viewModel.onAction(LoginActions.OnServerSave)

        val saved = env.viewModel.uiState.value
        assertNull(saved.serverInput)
        assertFalse(saved.isServerInvalid)
        assertEquals("https://bridge.example.com", saved.serverUrl)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `로그인 화면이 떠 있는 동안 요청이 다시 와도 아이디를 다시 넣고 비밀번호를 비운다`() {
        val env = env(lastLoginId = "")
        assertNull(env.session.user.value)

        env.viewModel.onAction(LoginActions.OnAccountRequested("test2"))
        env.viewModel.onAction(LoginActions.OnIdChange("test3"))
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnAccountRequested("test2"))

        assertEquals("test2", env.viewModel.uiState.value.id)
        assertEquals("", env.viewModel.uiState.value.pw)
        env.viewModel.viewModelScope.cancel()
    }
}
