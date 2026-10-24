package com.eunilsung.aiagent.ui.setting

import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.data.local.AccessKeyStore
import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.repository.ServerSettingsRepositoryImpl
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AuthRepository
import com.eunilsung.aiagent.domain.usecase.ChangeServerUseCase
import com.eunilsung.aiagent.domain.usecase.GetDefaultServerUrlUseCase
import com.eunilsung.aiagent.domain.usecase.GetServiceStatusUseCase
import com.eunilsung.aiagent.domain.usecase.LogoutUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveServerUrlUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveSessionUseCase
import com.eunilsung.aiagent.domain.usecase.SetServerUrlUseCase
import com.eunilsung.aiagent.domain.usecase.SettingUseCases
import com.eunilsung.aiagent.testsupport.FakeAuth
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 설정 화면의 서버 주소. 로그인한 채로 다른 서버로 바꾸면 먼저 묻고, 바꾸기 전에 앞 서버에서 로그아웃한다 — Talk 토큰은 만료가 없는
 * 전체 권한 토큰이라 새 서버로 싣지 않는다. 주소·접속 키 없이 만든 공개 릴리스는 기본 주소(에뮬레이터·시뮬레이터용 평문 주소)로
 * 되돌리지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingViewModelTest {

    /**
     * ViewModel 의 일은 실제 시간으로 돈다 — 연결 확인은 BridgeClient 의 10초 제한 안에서 MockEngine 의 다른 스레드로 가므로,
     * 가상 시간 디스패처에서는 응답보다 제한이 먼저 끝난다.
     */
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Env(
        val auth: FakeAuth,
        val session: SessionStore,
        val urlStore: BridgeUrlStore,
        val viewModel: SettingViewModel,
        val events: MutableList<SettingEvent>,
        /** 로그아웃할 때 붙어 있던 서버 주소 — 로그아웃은 앞 서버에 알린다. */
        val urlsAtLogout: MutableList<String>,
        /** 연결 상태(`/v1/health`)를 본 횟수. 응답은 MockEngine 의 다른 스레드에서 오므로 [awaitHealthChecks] 로 기다린다. */
        val healthChecks: StateFlow<Int>,
    ) {
        suspend fun awaitHealthChecks(count: Int) = withContext(Dispatchers.Default) {
            withTimeout(5_000) { healthChecks.first { it >= count } }
        }

        suspend fun awaitStatus() = withContext(Dispatchers.Default) {
            withTimeout(5_000) { viewModel.uiState.first { it.status != null } }
        }
    }

    /** test1 로 로그인한 기기. [isDefaultUsable] 이 false 면 공개 릴리스처럼 https://bridge.example.com 을 정해 둔 상태다. */
    private fun TestScope.env(isDefaultUsable: Boolean = true): Env {
        val settings = MapSettings()
        val session = SessionStore(settings).apply { save("token-test1", User("test1", "김민준")) }
        val fake = FakeAuth(session)
        val urlStore = BridgeUrlStore(settings, isDefaultUsable).apply { if (!isDefaultUsable) set(CHOSEN) }
        val urlsAtLogout = mutableListOf<String>()
        val auth: AuthRepository = object : AuthRepository by fake {
            override suspend fun logout() {
                urlsAtLogout += urlStore.url.value
                fake.logout()
            }
        }
        val keys = AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet())
        val health = MutableStateFlow(0)
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/v1/health") health.update { it + 1 }
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val bridge = BridgeClient(HttpClient(engine), urlStore, keys)
        val servers = ServerSettingsRepositoryImpl(urlStore, keys, bridge)
        val viewModel = SettingViewModel(
            SettingUseCases(
                observeSession = ObserveSessionUseCase(auth),
                observeServerUrl = ObserveServerUrlUseCase(servers),
                getDefaultServerUrl = GetDefaultServerUrlUseCase(servers),
                changeServer = ChangeServerUseCase(SetServerUrlUseCase(servers), servers, auth),
                getServiceStatus = GetServiceStatusUseCase(servers),
                logout = LogoutUseCase(auth),
            )
        )
        val events = mutableListOf<SettingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        return Env(fake, session, urlStore, viewModel, events, urlsAtLogout, health)
    }

    @Test
    fun `다른 서버로 바꾸면 먼저 묻고 확인하면 앞 서버에서 로그아웃한 뒤 바꾼다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val before = env.urlStore.url.value
        env.viewModel.onAction(SettingActions.OnServerInputChange(OTHER))

        env.viewModel.onAction(SettingActions.OnServerSave)
        assertEquals(listOf<SettingEvent>(SettingEvent.ConfirmServerChange), env.events)
        assertEquals(before, env.urlStore.url.value, "묻기 전에 바꿨다")
        assertEquals(0, env.auth.logoutCount)

        env.viewModel.onAction(SettingActions.OnServerChangeConfirmed)

        assertEquals(OTHER, env.urlStore.url.value)
        assertEquals(listOf(before), env.urlsAtLogout, "새 서버로 바꾼 뒤에 로그아웃했다 — 토큰이 새 서버로 간다")
        assertNull(env.viewModel.uiState.value.me)
        assertEquals(SettingEvent.ServerSaved, env.events.last())
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `쓸 수 없는 주소는 묻지 않고 알리며 로그아웃하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val before = env.urlStore.url.value
        env.viewModel.onAction(SettingActions.OnServerInputChange("http://192.168.0.10:8095"))

        env.viewModel.onAction(SettingActions.OnServerSave)
        env.viewModel.onAction(SettingActions.OnServerChangeConfirmed)

        assertEquals(listOf<SettingEvent>(SettingEvent.ServerInvalid), env.events)
        assertEquals(before, env.urlStore.url.value)
        assertEquals(0, env.auth.logoutCount)
        assertNotNull(env.viewModel.uiState.value.me)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `같은 주소로 저장하면 묻지도 로그아웃하지도 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.viewModel.onAction(SettingActions.OnServerInputChange(env.urlStore.url.value + "/"))

        env.viewModel.onAction(SettingActions.OnServerSave)

        assertTrue(env.events.isEmpty(), "같은 주소인데 ${env.events}")
        assertEquals(0, env.auth.logoutCount)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `기본 주소를 쓰는 빌드는 기본값으로 되돌릴 때도 묻고 로그아웃한다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.viewModel.onAction(SettingActions.OnServerInputChange(OTHER))
        env.viewModel.onAction(SettingActions.OnServerSave)
        env.viewModel.onAction(SettingActions.OnServerChangeConfirmed)
        assertTrue(env.viewModel.uiState.value.canResetServer)
        env.session.save("token-test1", User("test1", "김민준"))
        env.events.clear()

        env.viewModel.onAction(SettingActions.OnServerReset)
        assertEquals(listOf<SettingEvent>(SettingEvent.ConfirmServerChange), env.events)
        assertEquals(OTHER, env.urlStore.url.value, "묻기 전에 되돌렸다")
        env.viewModel.onAction(SettingActions.OnServerChangeConfirmed)

        assertEquals(Config.Bridge.DEFAULT_BASE_URL, env.urlStore.url.value)
        assertEquals(OTHER, env.urlsAtLogout.last(), "되돌리기 전에 앞 서버에서 로그아웃하지 않았다")
        assertNull(env.viewModel.uiState.value.me)
        assertFalse(env.viewModel.uiState.value.canResetServer, "이미 기본 주소인데 [기본값] 을 보인다")
        env.viewModel.viewModelScope.cancel()
    }

    /**
     * 이 ViewModel 은 로그아웃해도 남는다. 서버를 바꾸면 보던 상태를 버리고 진행 중인 확인도 멈춘다 — 앞 서버의 늦은 응답이
     * 새 서버의 상태로 보이지 않게 한다. 다시 로그인해 설정 화면을 열면(OnShown) 그때 다시 본다.
     * 응답은 MockEngine 의 다른 스레드에서 오므로, 화면 동작을 먼저 하고 그 뒤에 기다린다.
     */
    @Test
    fun `서버를 바꾸면 보던 연결 상태를 버리고 다시 열 때 다시 본다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.viewModel.onAction(SettingActions.OnShown)
        env.viewModel.onAction(SettingActions.OnServerInputChange(OTHER))
        env.viewModel.onAction(SettingActions.OnServerSave)
        env.viewModel.onAction(SettingActions.OnServerChangeConfirmed)
        assertEquals(OTHER, env.urlStore.url.value)
        assertNull(env.session.user.value)

        withContext(Dispatchers.Default) { delay(500) }
        assertNull(env.viewModel.uiState.value.status, "앞 서버의 늦은 응답이 상태로 남았다")
        val before = env.healthChecks.value
        assertTrue(before <= 1, "로그아웃한 채로 새 서버의 상태를 봤다(확인 $before 번)")

        env.session.save("token-test1", User("test1", "김민준"))
        env.viewModel.onAction(SettingActions.OnShown)

        env.awaitHealthChecks(before + 1)
        env.awaitStatus()
        env.viewModel.viewModelScope.cancel()
    }

    /** 로그아웃한 사이 로그인 화면에서 주소를 바꾸고 다시 로그인하면, 설정 탭을 열 때 주소 칸도 새 주소로 맞춘다. */
    @Test
    fun `설정 탭을 열면 주소 칸을 지금 주소로 맞춘다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.viewModel.onAction(SettingActions.OnShown)
        env.viewModel.onAction(SettingActions.OnLogout)
        env.urlStore.set(OTHER)
        env.session.save("token-test1", User("test1", "김민준"))
        assertTrue(env.viewModel.uiState.value.canSaveServer, "열기 전에는 앞 주소가 칸에 남아 있다")

        env.viewModel.onAction(SettingActions.OnShown)

        val state = env.viewModel.uiState.value
        assertEquals(OTHER, state.serverUrl)
        assertEquals(OTHER, state.serverInput, "주소 칸에 앞 서버 주소가 남았다")
        assertFalse(state.canSaveServer, "같은 주소인데 [저장] 이 켜져 있다")
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `공개 릴리스는 기본값을 숨기고 되돌리지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(isDefaultUsable = false)
        assertNull(env.viewModel.uiState.value.defaultUrl)
        assertFalse(env.viewModel.uiState.value.canResetServer)

        env.viewModel.onAction(SettingActions.OnServerReset)
        env.viewModel.onAction(SettingActions.OnServerChangeConfirmed)

        assertEquals(listOf<SettingEvent>(SettingEvent.ServerInvalid), env.events)
        assertEquals(CHOSEN, env.urlStore.url.value, "평문 기본 주소로 되돌렸다")
        assertEquals(0, env.auth.logoutCount)
        env.viewModel.viewModelScope.cancel()
    }

    private companion object {
        const val CHOSEN = "https://bridge.example.com"
        const val OTHER = "https://other.example.com"
    }
}
