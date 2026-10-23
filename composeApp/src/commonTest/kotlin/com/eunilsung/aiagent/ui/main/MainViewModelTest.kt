package com.eunilsung.aiagent.ui.main

import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.repository.ConversationRepositoryImpl
import com.eunilsung.aiagent.data.repository.SummaryRequestRepositoryImpl
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.model.User
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
import kotlin.test.assertTrue

/**
 * 앱 전체가 Talk 요약 링크를 받아 화면에 알리는 일. 링크는 플랫폼 진입점(IncomingLinks) 대신 테스트가 채널로 넣는다.
 * 로그인을 지켜보다가 로그인하면 기다리던 요약을 이어 가는지까지 본다.
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
    )

    private fun TestScope.env(loggedInAs: String? = null): Env {
        val session = SessionStore(MapSettings())
        if (loggedInAs != null) session.save("token-$loggedInAs", User(loggedInAs, FakeAuth.NAMES[loggedInAs] ?: loggedInAs))
        val auth = FakeAuth(session)
        val conversations = ConversationRepositoryImpl(createTestDatabase(), session, ChatMapper(), backgroundScope)
        val links = Channel<String>(Channel.UNLIMITED)
        val requests = SummaryRequestRepositoryImpl(links.receiveAsFlow())
        val create = CreateConversationUseCase(conversations)
        val viewModel = MainViewModel(
            MainUseCases(
                observeSession = ObserveSessionUseCase(auth),
                observeSummaryLinks = ObserveSummaryLinksUseCase(requests),
                handleSummaryLink = HandleSummaryLinkUseCase(auth, requests, create),
                resumeSummaryAfterLogin = ResumeSummaryAfterLoginUseCase(requests, create),
            )
        )
        val events = mutableListOf<MainEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        return Env(auth, requests, links, viewModel, events)
    }

    private fun link(userId: String) =
        "aiagent://summarize?fileId=file-1&fileName=a.txt&userId=$userId&userName=${FakeAuth.NAMES[userId] ?: userId}"

    @Test
    fun `같은 계정의 링크면 요약을 건 대화를 연다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        env.links.send(link("test1"))

        val open = assertIs<MainEvent.OpenChat>(env.events.single())
        assertEquals(SummaryRequest("file-1", "a.txt", "test1", "김민준"), env.requests.takeHeld(open.conversationId).first())
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `다른 계정의 링크면 로그아웃하고 안내한 뒤 그 계정으로 로그인하면 요약 대화를 연다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        env.links.send(link("test2"))

        assertTrue(MainEvent.LoggedOut in env.events)
        assertTrue(MainEvent.SummaryLoginRequired("test2", "이서연", loggedOut = true) in env.events)
        assertEquals(2, env.events.size)
        assertEquals(false, env.viewModel.uiState.value.isLoggedIn)

        env.auth.login("test2", "1234")

        assertIs<MainEvent.OpenChat>(env.events.last())
        assertEquals(3, env.events.size)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `로그인 전에 온 링크를 다른 계정으로 로그인해 받으면 요약을 취소했다고 알린다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()

        env.links.send(link("test2"))
        assertEquals(listOf<MainEvent>(MainEvent.SummaryLoginRequired("test2", "이서연", loggedOut = false)), env.events)

        env.auth.login("test3", "1234")

        assertEquals(MainEvent.SummaryCancelled, env.events.last())
        assertEquals(2, env.events.size)
        env.viewModel.viewModelScope.cancel()
    }

    @Test
    fun `요약 링크를 기다리지 않을 때의 로그인과 잘못된 링크는 아무것도 알리지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()

        env.auth.login("test1", "1234")
        env.links.send("aiagent://summarize?fileId=a%20b&fileName=a.txt&userId=test1")

        assertTrue(env.events.isEmpty())
        assertEquals(0, env.auth.logoutCount)
        env.viewModel.viewModelScope.cancel()
    }
}
