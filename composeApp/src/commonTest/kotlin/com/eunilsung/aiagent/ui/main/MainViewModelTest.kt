package com.eunilsung.aiagent.ui.main

import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.repository.ConversationRepositoryImpl
import com.eunilsung.aiagent.data.repository.SummaryRequestRepositoryImpl
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.usecase.ConfirmAccountSwitchUseCase
import com.eunilsung.aiagent.domain.usecase.CreateConversationUseCase
import com.eunilsung.aiagent.domain.usecase.HandleSummaryLinkUseCase
import com.eunilsung.aiagent.domain.usecase.MainUseCases
import com.eunilsung.aiagent.domain.usecase.ObserveSessionUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveSummaryLinksUseCase
import com.eunilsung.aiagent.domain.usecase.ResumeSummaryAfterLoginUseCase
import com.eunilsung.aiagent.testsupport.FakeAuth
import com.eunilsung.aiagent.testsupport.createTestDatabase
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * 앱 전체가 Talk 요약 링크를 받아 화면에 알리는 일. 링크는 플랫폼 진입점(IncomingLinks) 대신 테스트가 채널로 넣는다.
 * 다른 계정의 링크는 묻고 고른 대로만 로그아웃하는지, 로그인을 지켜보다가 로그인하면 기다리던 요약을 이어 가는지까지 본다.
 * 시간은 [Env.clock] 으로 넘긴다 — 3초 안에 다시 온 링크는 버리기 때문이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Env(
        val auth: FakeAuth,
        val requests: SummaryRequestRepositoryImpl,
        val links: Channel<String>,
        val viewModel: MainViewModel,
        val events: MutableList<MainEvent>,
        val clock: TestTimeSource,
    ) {
        /** 링크를 연다. 앞 링크와 겹치지 않게 시간을 먼저 흘린다. */
        suspend fun open(link: String) {
            clock += HandleSummaryLinkUseCase.REPEAT_WINDOW
            links.send(link)
        }
    }

    private fun TestScope.env(loggedInAs: String? = null): Env {
        val session = SessionStore(MapSettings())
        if (loggedInAs != null) session.save("token-$loggedInAs", User(loggedInAs, FakeAuth.NAMES[loggedInAs] ?: loggedInAs))
        val auth = FakeAuth(session)
        val conversations = ConversationRepositoryImpl(createTestDatabase(), session, ChatMapper(), backgroundScope)
        val links = Channel<String>(Channel.UNLIMITED)
        val requests = SummaryRequestRepositoryImpl(links.receiveAsFlow())
        val create = CreateConversationUseCase(conversations)
        val clock = TestTimeSource()
        val viewModel = MainViewModel(
            MainUseCases(
                observeSession = ObserveSessionUseCase(auth),
                observeSummaryLinks = ObserveSummaryLinksUseCase(requests),
                handleSummaryLink = HandleSummaryLinkUseCase(auth, requests, create, clock),
                confirmAccountSwitch = ConfirmAccountSwitchUseCase(auth, requests, create),
                resumeSummaryAfterLogin = ResumeSummaryAfterLoginUseCase(requests, create),
            )
        )
        val events = mutableListOf<MainEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        return Env(auth, requests, links, viewModel, events, clock)
    }

    private fun link(userId: String) =
        "aiagent://summarize?fileId=file-1&fileName=a.txt&userId=$userId&userName=${FakeAuth.NAMES[userId] ?: userId}"

    @Test
    fun `같은 계정의 링크면 요약을 건 대화를 연다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        env.open(link("test1"))

        val open = assertIs<MainEvent.OpenChat>(env.events.single())
        assertEquals(SummaryRequest("file-1", "a.txt", "test1", "김민준"), env.requests.takeHeld(open.conversationId).first())
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `다른 계정의 링크면 먼저 묻고 로그아웃을 고르면 로그아웃한 뒤 그 계정으로 로그인하면 요약 대화를 연다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        env.open(link("test2"))

        assertEquals(listOf<MainEvent>(MainEvent.ConfirmAccountSwitch(currentUserId = "test1", requestedUserId = "test2")), env.events)
        assertEquals(0, env.auth.logoutCount, "묻기만 했는데 로그아웃했다")
        assertEquals(true, env.viewModel.uiState.value.isLoggedIn)

        env.viewModel.onAction(MainActions.OnAccountSwitchConfirm)

        assertTrue(MainEvent.LoggedOut in env.events)
        assertTrue(MainEvent.SummaryLoginRequired("test2", loggedOut = true) in env.events)
        assertEquals(3, env.events.size)
        assertEquals(false, env.viewModel.uiState.value.isLoggedIn)

        env.auth.login("test2", "1234")

        assertIs<MainEvent.OpenChat>(env.events.last())
        assertEquals(4, env.events.size)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `계정을 바꿀지 물었을 때 취소하면 아무것도 바뀌지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        env.open(link("test2"))

        env.viewModel.onAction(MainActions.OnAccountSwitchCancel)
        env.viewModel.onAction(MainActions.OnAccountSwitchConfirm)

        assertEquals(listOf<MainEvent>(MainEvent.ConfirmAccountSwitch("test1", "test2")), env.events, "취소한 뒤의 확인이 로그아웃시켰다")
        assertEquals(0, env.auth.logoutCount)
        assertEquals("test1", env.viewModel.uiState.value.me?.id)
        assertNull(env.requests.takeAwaitingLogin(), "취소한 요청이 로그인을 기다린다 — 나중에 test2 로 로그인하면 요약이 열린다")

        env.auth.logout()
        env.auth.login("test2", "1234")
        assertTrue(env.events.none { it is MainEvent.OpenChat }, "취소한 요약이 나중에 열렸다")
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `링크를 연달아 열어도 3초 안의 것은 버려 물음이 한 번만 뜬다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        env.open(link("test2"))
        repeat(5) { env.links.send(link("test2")) }

        assertEquals(listOf<MainEvent>(MainEvent.ConfirmAccountSwitch("test1", "test2")), env.events)
        assertEquals(0, env.auth.logoutCount)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `묻는 사이 다른 링크를 처리했으면 앞의 물음에 답해도 로그아웃하지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        env.open(link("test2"))
        env.open(link("test1"))

        env.viewModel.onAction(MainActions.OnAccountSwitchConfirm)

        assertEquals(0, env.auth.logoutCount)
        assertIs<MainEvent.OpenChat>(env.events.last())
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `로그인 전에 온 링크를 다른 계정으로 로그인해 받으면 요약을 취소했다고 알린다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()

        env.open(link("test2"))
        assertEquals(listOf<MainEvent>(MainEvent.SummaryLoginRequired("test2", loggedOut = false)), env.events)

        env.auth.login("test3", "1234")

        assertEquals(MainEvent.SummaryCancelled, env.events.last())
        assertEquals(2, env.events.size)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `요약 링크를 기다리지 않을 때의 로그인과 잘못된 링크는 아무것도 알리지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()

        env.auth.login("test1", "1234")
        env.open("aiagent://summarize?fileId=a%20b&fileName=a.txt&userId=test1")

        assertTrue(env.events.isEmpty())
        assertEquals(0, env.auth.logoutCount)
        env.viewModel.viewModelScope.cancel()
    }
}
