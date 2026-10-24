package com.eunilsung.aiagent.ui.login

import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.data.local.AccessKeyStore
import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.repository.ServerSettingsRepositoryImpl
import com.eunilsung.aiagent.domain.model.BridgeRefusal
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.repository.AuthRepository
import com.eunilsung.aiagent.domain.usecase.GetLastLoginIdUseCase
import com.eunilsung.aiagent.domain.usecase.HasAccessKeyUseCase
import com.eunilsung.aiagent.domain.usecase.IsServerChosenUseCase
import com.eunilsung.aiagent.domain.usecase.LoginUseCase
import com.eunilsung.aiagent.domain.usecase.LoginUseCases
import com.eunilsung.aiagent.domain.usecase.ObserveServerUrlUseCase
import com.eunilsung.aiagent.domain.usecase.SetAccessKeyUseCase
import com.eunilsung.aiagent.domain.usecase.SetServerUrlUseCase
import com.eunilsung.aiagent.domain.util.BridgeAddress
import com.eunilsung.aiagent.testsupport.FakeAuth
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
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
 *
 * 공개 릴리스 앱은 서버 주소와 접속 키 없이 빌드한다. 처음 켜면 기본 주소(에뮬레이터·시뮬레이터용 평문 주소)로 아무것도 보내지
 * 않고 서버 주소 칸을 맨 위에 연다. 지금 서버 주소로 보낼 키가 없으면 접속 키 칸을 열고, 넣은 키를 그 주소의 출처에 묶어 저장한 뒤
 * 로그인한다. 넣은 키는 결과와 상관없이 화면 상태에서 지운다. 주소가 바뀌면 넣던 키와 거절 표시를 지워 앞 서버의 키가 새 서버로
 * 가지 않게 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Env(val session: SessionStore, val keys: AccessKeyStore, val viewModel: LoginViewModel)

    /**
     * 마지막으로 [lastLoginId] 로 로그인했다가 로그아웃한 기기. [buildKey] 가 있으면 빌드 기본 주소로 보낼 키가 있는 개발용 빌드
     * (기본 주소를 그대로 쓴다), 비었으면 공개 릴리스 빌드(서버 주소를 넣어야 한다)다. [loginResult] 를 주면 로그인이 그 결과로 끝난다.
     * [loginResults] 를 주면 로그인할 때마다 차례로 그 결과다(마지막 것이 되풀이된다). [loginGate] 를 주면 그것이 끝날 때까지 응답을 기다린다.
     */
    private fun env(
        lastLoginId: String = "test1",
        buildKey: String = BUILD_KEY,
        loginResult: LoginResult? = null,
        loginResults: List<LoginResult> = listOfNotNull(loginResult),
        loginGate: CompletableDeferred<Unit>? = null,
    ): Env {
        val settings = MapSettings()
        val session = SessionStore(settings).apply { this.lastLoginId = lastLoginId }
        val fake = FakeAuth(session)
        val pending = ArrayDeque(loginResults)
        val auth: AuthRepository = object : AuthRepository by fake {
            override suspend fun login(id: String, password: String): LoginResult {
                loginGate?.await()
                val next = if (pending.size > 1) pending.removeFirst() else pending.firstOrNull()
                return next ?: fake.login(id, password)
            }
        }
        val urlStore = BridgeUrlStore(settings, isDefaultUsable = buildKey.isNotEmpty())
        val keys = AccessKeyStore(settings, buildKey = buildKey, buildKeyHosts = Config.Bridge.ACCESS_KEY_HOSTS)
        val serverSettings = ServerSettingsRepositoryImpl(
            urlStore,
            keys,
            BridgeClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }), urlStore, keys),
        )
        val viewModel = LoginViewModel(
            LoginUseCases(
                login = LoginUseCase(auth),
                getLastLoginId = GetLastLoginIdUseCase(auth),
                observeServerUrl = ObserveServerUrlUseCase(serverSettings),
                setServerUrl = SetServerUrlUseCase(serverSettings),
                isServerChosen = IsServerChosenUseCase(serverSettings),
                hasAccessKey = HasAccessKeyUseCase(serverSettings),
                setAccessKey = SetAccessKeyUseCase(serverSettings),
            )
        )
        return Env(session, keys, viewModel)
    }

    /** 로그인 화면에서 서버 주소를 저장한다(아래 서버 줄을 눌러 고치거나, 맨 위 서버 주소 칸에 넣은 뒤 저장). */
    private fun LoginViewModel.chooseServer(url: String) {
        if (uiState.value.serverInput == null) onAction(LoginActions.OnToggleServerEdit)
        onAction(LoginActions.OnServerInputChange(url))
        onAction(LoginActions.OnServerSave)
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

    @Test
    fun `빌드 기본 주소로 보낼 키가 있으면 접속 키 칸을 열지 않는다`() {
        val env = env()

        val state = env.viewModel.uiState.value
        assertFalse(state.isAccessKeyFieldShown)
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        assertTrue(env.viewModel.uiState.value.canSubmit)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `공개 릴리스 앱은 서버 주소와 접속 키를 넣어야 로그인하고 키를 그 주소에 묶는다`() {
        val env = env(buildKey = "")
        val first = env.viewModel.uiState.value
        assertTrue(first.isServerRequired, "주소를 정하지 않았는데 서버 주소 칸을 열지 않았다")
        assertEquals("", first.serverInput)
        assertTrue(first.isAccessKeyFieldShown)

        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(" $USER_KEY\n"))
        assertFalse(env.viewModel.uiState.value.canSubmit, "서버 주소를 넣기 전에 로그인할 수 있다")

        env.viewModel.onAction(LoginActions.OnServerInputChange(" https://bridge.example.com/ "))
        assertTrue(env.viewModel.uiState.value.canSubmit)
        env.viewModel.onAction(LoginActions.OnLoginClick)

        assertEquals(USER_KEY, env.keys.keyFor(ORIGIN), "넣은 키를 넣은 서버 주소에 묶지 않았다")
        assertEquals("test1", env.session.user.value?.id)
        val state = env.viewModel.uiState.value
        assertEquals("https://bridge.example.com", state.serverUrl)
        assertFalse(state.isServerRequired)
        assertNull(state.serverInput)
        assertEquals("", state.accessKey, "로그인한 뒤에도 넣은 키가 화면 상태에 남았다")
        assertFalse(state.isAccessKeyFieldShown)
        env.viewModel.viewModelScope.cancel()
    }

    /**
     * 기본 주소는 실기기에서 같은 망의 아무 기기에나 닿을 수 있는 평문 주소다. 주소를 넣지 않았거나 쓸 수 없는 주소면
     * 키도 비밀번호도 보내지 않는다.
     */
    @Test
    fun `서버 주소를 정하기 전에는 키를 저장하지도 로그인하지도 않는다`() {
        val env = env(buildKey = "")
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))

        env.viewModel.onAction(LoginActions.OnLoginClick)
        assertNull(env.session.user.value, "주소 없이 로그인했다")
        assertFalse(env.keys.hasAnyKey, "주소 없이 키를 저장했다")

        env.viewModel.onAction(LoginActions.OnToggleServerEdit)
        assertEquals("", env.viewModel.uiState.value.serverInput, "주소를 정하기 전에 서버 칸을 닫았다")

        env.viewModel.onAction(LoginActions.OnServerInputChange("http://192.168.0.10:8095"))
        env.viewModel.onAction(LoginActions.OnLoginClick)
        val refused = env.viewModel.uiState.value
        assertTrue(refused.isServerInvalid)
        assertTrue(refused.isServerRequired)
        assertNull(env.session.user.value, "막힌 주소로 로그인했다")
        assertFalse(env.keys.hasAnyKey, "막힌 주소에 키를 묶었다")

        env.viewModel.chooseServer("")
        assertTrue(env.viewModel.uiState.value.isServerInvalid, "빈 주소를 평문 기본 주소로 받았다")
        assertTrue(env.viewModel.uiState.value.isServerRequired)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `형식이 맞지 않는 키는 저장하지 않고 로그인하지 않는다`() {
        val env = env(buildKey = "")
        env.viewModel.chooseServer("https://bridge.example.com")
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnAccessKeyChange("too-short"))

        env.viewModel.onAction(LoginActions.OnLoginClick)

        assertTrue(env.viewModel.uiState.value.isAccessKeyInvalid)
        assertEquals("too-short", env.viewModel.uiState.value.accessKey, "고칠 수 있게 넣은 값을 남긴다")
        assertNull(env.session.user.value, "형식이 틀린 키로 로그인했다")
        assertFalse(env.keys.hasAnyKey)

        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        assertFalse(env.viewModel.uiState.value.isAccessKeyInvalid, "고치기 시작했는데 안내가 남아 있다")
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `서버가 빌드 키를 받지 않으면 칸을 연 채 다시 넣게 한다`() {
        val env = env(loginResult = LoginResult.Refused(BridgeRefusal.ClientKeyInvalid))
        assertFalse(env.viewModel.uiState.value.isAccessKeyFieldShown, "처음에는 빌드 키가 있어 칸이 닫혀 있다")
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))

        env.viewModel.onAction(LoginActions.OnLoginClick)

        val state = env.viewModel.uiState.value
        assertTrue(state.isAccessKeyRejected)
        assertTrue(state.isAccessKeyFieldShown)
        assertFalse(state.canSubmit, "새 키를 넣기 전에 다시 로그인할 수 있다")
        assertEquals(LoginStatus.Error(LoginResult.Refused(BridgeRefusal.ClientKeyInvalid)), state.status)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `넣은 키를 서버가 받지 않으면 칸을 비워 같은 키로 다시 누르지 못하게 한다`() {
        val env = env(buildKey = "", loginResult = LoginResult.Refused(BridgeRefusal.ClientKeyInvalid))
        env.viewModel.chooseServer("https://bridge.example.com")
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))

        env.viewModel.onAction(LoginActions.OnLoginClick)

        val state = env.viewModel.uiState.value
        assertTrue(state.isAccessKeyFieldShown)
        assertEquals("", state.accessKey, "거절된 키가 화면 상태에 남았다")
        assertFalse(state.canSubmit)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `서버 주소를 다른 호스트로 바꾸면 그 서버의 키를 다시 넣게 한다`() {
        val env = env(buildKey = "")
        env.viewModel.chooseServer("https://bridge.example.com")
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        env.viewModel.onAction(LoginActions.OnLoginClick)
        assertFalse(env.viewModel.uiState.value.isAccessKeyFieldShown)

        env.viewModel.chooseServer("https://other.example.com")

        assertTrue(env.viewModel.uiState.value.isAccessKeyFieldShown, "다른 서버로 바꿨는데 키 칸이 닫혀 있다")
        env.viewModel.viewModelScope.cancel()
    }

    /** 서버 A 의 키를 넣고 실패한 뒤 주소를 B 로 바꾸게 속여도, 한 번 더 누르는 것만으로 A 의 키가 B 로 가지 않는다. */
    @Test
    fun `로그인에 실패한 뒤 서버를 바꾸면 앞서 넣은 키가 새 서버로 가지 않는다`() {
        val env = env(buildKey = "", loginResult = LoginResult.InvalidCredentials)
        env.viewModel.chooseServer("https://bridge.example.com")
        env.viewModel.onAction(LoginActions.OnPwChange("wrong"))
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        env.viewModel.onAction(LoginActions.OnLoginClick)
        assertEquals("", env.viewModel.uiState.value.accessKey, "실패한 뒤에도 키가 화면 상태에 남았다")

        env.viewModel.chooseServer("https://other.example.com")
        assertFalse(env.viewModel.uiState.value.canSubmit, "키를 다시 넣기 전에 새 서버로 로그인할 수 있다")
        env.viewModel.onAction(LoginActions.OnLoginClick)

        assertNull(env.keys.keyFor(OTHER_ORIGIN), "앞 서버의 키를 새 서버에 묶었다")
        assertEquals(USER_KEY, env.keys.keyFor(ORIGIN))
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `넣던 키는 서버 주소를 바꾸면 지운다`() {
        val env = env(buildKey = "")
        env.viewModel.chooseServer("https://bridge.example.com")
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        env.viewModel.onAction(LoginActions.OnToggleAccessKeyVisible)

        env.viewModel.chooseServer("https://other.example.com")

        val state = env.viewModel.uiState.value
        assertEquals("", state.accessKey)
        assertFalse(state.isAccessKeyVisible)
        env.viewModel.viewModelScope.cancel()
    }

    /** 고치던 주소를 저장하지 않고 로그인을 누르면, 그 주소를 먼저 저장하고 함께 넣은 키를 그 주소에 묶는다. */
    @Test
    fun `열린 주소 칸은 로그인할 때 먼저 저장하고 키를 그 주소에 묶는다`() {
        val env = env(buildKey = "")
        env.viewModel.chooseServer("https://bridge.example.com")
        env.viewModel.onAction(LoginActions.OnToggleServerEdit)
        env.viewModel.onAction(LoginActions.OnServerInputChange("https://other.example.com"))
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))

        env.viewModel.onAction(LoginActions.OnLoginClick)

        assertEquals(USER_KEY, env.keys.keyFor(OTHER_ORIGIN))
        assertNull(env.keys.keyFor(ORIGIN), "고치기 전 주소에 키를 묶었다")
        assertEquals("https://other.example.com", env.viewModel.uiState.value.serverUrl)
        assertEquals("test1", env.session.user.value?.id)
        env.viewModel.viewModelScope.cancel()
    }

    /** 키가 있는 서버에서 키가 없는 서버로 고치고 바로 누르면, 키 칸을 열고 멈춘다 — 키 없이 보내 거절당하지 않는다. */
    @Test
    fun `로그인할 때 저장한 주소에 키가 없으면 키 칸을 열고 멈춘다`() {
        val env = env()
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnToggleServerEdit)
        env.viewModel.onAction(LoginActions.OnServerInputChange("https://other.example.com"))

        env.viewModel.onAction(LoginActions.OnLoginClick)

        val state = env.viewModel.uiState.value
        assertEquals("https://other.example.com", state.serverUrl)
        assertTrue(state.isAccessKeyFieldShown)
        assertFalse(state.canSubmit)
        assertNull(env.session.user.value, "키 없는 서버로 로그인을 보냈다")
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `키를 거절당한 뒤 키가 가는 주소로 바꾸면 접속 키 칸을 닫는다`() {
        val env = env(loginResult = LoginResult.Refused(BridgeRefusal.ClientKeyInvalid))
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnLoginClick)
        assertTrue(env.viewModel.uiState.value.isAccessKeyFieldShown)

        env.viewModel.chooseServer("http://127.0.0.1:8095")

        assertFalse(env.viewModel.uiState.value.isAccessKeyFieldShown, "빌드 키가 가는 주소인데 키 칸이 남아 있다")
        assertTrue(env.viewModel.uiState.value.canSubmit)
        env.viewModel.viewModelScope.cancel()
    }

    /** 브릿지의 키가 바뀌었을 때 거절을 기다리지 않고 [접속 키 바꾸기] 로 새 키를 넣는다. */
    @Test
    fun `접속 키 바꾸기를 누르면 칸을 열고 넣은 키를 지금 주소에 저장한다`() {
        val env = env()
        assertFalse(env.viewModel.uiState.value.isAccessKeyFieldShown)

        env.viewModel.onAction(LoginActions.OnToggleAccessKeyEdit)
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        assertTrue(env.viewModel.uiState.value.isAccessKeyFieldShown)
        assertFalse(env.viewModel.uiState.value.canSubmit, "키를 넣기 전에 로그인할 수 있다")

        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        env.viewModel.onAction(LoginActions.OnLoginClick)

        val origin = BridgeAddress.originOf(env.viewModel.uiState.value.serverUrl)!!
        assertEquals(USER_KEY, env.keys.keyFor(origin))
        assertFalse(env.viewModel.uiState.value.isAccessKeyFieldShown)
        assertEquals("test1", env.session.user.value?.id)
        env.viewModel.viewModelScope.cancel()
    }

    /** 브릿지는 키를 먼저 본다 — 비밀번호가 틀렸다는 답은 새 키를 받았다는 뜻이라, 맞는 키를 또 넣으라고 하지 않는다. */
    @Test
    fun `키를 거절당한 뒤 새 키를 넣고 비밀번호가 틀리면 키 칸을 닫는다`() {
        val env = env(loginResults = listOf(LoginResult.Refused(BridgeRefusal.ClientKeyInvalid), LoginResult.InvalidCredentials))
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnLoginClick)
        assertTrue(env.viewModel.uiState.value.isAccessKeyFieldShown)

        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        env.viewModel.onAction(LoginActions.OnPwChange("wrong"))
        env.viewModel.onAction(LoginActions.OnLoginClick)

        val state = env.viewModel.uiState.value
        assertEquals(LoginStatus.Error(LoginResult.InvalidCredentials), state.status)
        assertFalse(state.isAccessKeyRejected)
        assertFalse(state.isAccessKeyFieldShown, "받아 준 키를 또 넣으라고 한다")
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        assertTrue(env.viewModel.uiState.value.canSubmit, "비밀번호만 고쳐 다시 누를 수 없다")
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `접속 키 바꾸기를 다시 누르면 칸을 닫고 지금 키로 로그인한다`() {
        val env = env()
        env.viewModel.onAction(LoginActions.OnToggleAccessKeyEdit)
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        assertTrue(env.viewModel.uiState.value.canToggleAccessKeyEdit, "연 칸을 닫을 수 없다")

        env.viewModel.onAction(LoginActions.OnToggleAccessKeyEdit)

        val state = env.viewModel.uiState.value
        assertFalse(state.isAccessKeyFieldShown)
        assertEquals("", state.accessKey)
        assertTrue(state.canSubmit)
        env.viewModel.onAction(LoginActions.OnLoginClick)
        assertEquals("test1", env.session.user.value?.id)
        assertEquals(BUILD_KEY, env.keys.keyFor(BridgeAddress.originOf(state.serverUrl)!!), "닫은 칸의 키를 저장했다")
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `빈 칸이나 거절로 열린 접속 키 칸은 닫을 수 없다`() {
        val missing = env(buildKey = "")
        missing.viewModel.chooseServer("https://bridge.example.com")
        assertFalse(missing.viewModel.uiState.value.canToggleAccessKeyEdit)

        val rejected = env(loginResult = LoginResult.Refused(BridgeRefusal.ClientKeyInvalid))
        rejected.viewModel.onAction(LoginActions.OnPwChange("1234"))
        rejected.viewModel.onAction(LoginActions.OnLoginClick)
        assertFalse(rejected.viewModel.uiState.value.canToggleAccessKeyEdit)
        missing.viewModel.viewModelScope.cancel()
        rejected.viewModel.viewModelScope.cancel()
    }

    /** 이미 서버 A 로 나간 로그인이 끝나기 전에 주소를 B 로 바꾸면, A 가 준 토큰이 B 로 실린다. 기다리는 동안은 바꾸지 못한다. */
    @Test
    fun `로그인 응답을 기다리는 동안에는 서버 주소를 바꾸지 못한다`() {
        val gate = CompletableDeferred<Unit>()
        val env = env(loginGate = gate)
        val before = env.viewModel.uiState.value.serverUrl
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnLoginClick)
        assertTrue(env.viewModel.uiState.value.isLoggingIn)

        env.viewModel.onAction(LoginActions.OnToggleServerEdit)
        env.viewModel.onAction(LoginActions.OnServerInputChange("https://other.example.com"))
        env.viewModel.onAction(LoginActions.OnServerSave)
        assertNull(env.viewModel.uiState.value.serverInput)

        gate.complete(Unit)
        assertEquals(before, env.viewModel.uiState.value.serverUrl, "로그인하는 동안 주소를 바꿨다")
        assertEquals("test1", env.session.user.value?.id)
        env.viewModel.viewModelScope.cancel()
    }

    /** 로그인을 누르면 넣은 키는 이미 저장된다. 응답을 기다리는 동안 [그대로 쓰기] 를 보이면 지킬 수 없는 약속이라 숨긴다. */
    @Test
    fun `로그인 응답을 기다리는 동안에는 접속 키 칸을 닫지 못한다`() {
        val gate = CompletableDeferred<Unit>()
        val env = env(loginGate = gate)
        env.viewModel.onAction(LoginActions.OnToggleAccessKeyEdit)
        env.viewModel.onAction(LoginActions.OnAccessKeyChange(USER_KEY))
        env.viewModel.onAction(LoginActions.OnPwChange("1234"))
        env.viewModel.onAction(LoginActions.OnLoginClick)

        assertFalse(env.viewModel.uiState.value.canToggleAccessKeyEdit)
        env.viewModel.onAction(LoginActions.OnToggleAccessKeyEdit)
        assertTrue(env.viewModel.uiState.value.isAccessKeyEditing, "로그인하는 동안 칸을 닫았다")

        gate.complete(Unit)
        val origin = BridgeAddress.originOf(env.viewModel.uiState.value.serverUrl)!!
        assertEquals(USER_KEY, env.keys.keyFor(origin))
        assertFalse(env.viewModel.uiState.value.isAccessKeyFieldShown)
        env.viewModel.viewModelScope.cancel()
    }

    private companion object {
        const val BUILD_KEY = "build-time-key-not-real-0123456789"
        const val USER_KEY = "user-entered-key-not-real-0123456789"
        const val ORIGIN = "https://bridge.example.com:443"
        const val OTHER_ORIGIN = "https://other.example.com:443"
    }
}
