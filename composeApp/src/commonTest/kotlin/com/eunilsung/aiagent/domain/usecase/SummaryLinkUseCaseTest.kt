package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.repository.ConversationRepositoryImpl
import com.eunilsung.aiagent.data.repository.SummaryRequestRepositoryImpl
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.SummaryLinkResult
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.testsupport.FakeAuth
import com.eunilsung.aiagent.testsupport.createTestDatabase
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Talk 요약 링크 처리 — 같은 계정·다른 계정(묻고 고른 대로)·로그인 안 됨, 그리고 로그인 뒤 같은/다른 계정.
 * 로그인은 [FakeAuth], 대화 기록은 진짜 저장소(인메모리 DB)를 쓴다. 시간은 [clock] 으로 넘겨, 3초 안에 다시 온 링크를
 * 버리는 규칙이 다른 시나리오를 막지 않게 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryLinkUseCaseTest {

    private class Env(
        val session: SessionStore,
        val auth: FakeAuth,
        val conversations: ConversationRepositoryImpl,
        val requests: SummaryRequestRepositoryImpl,
    ) {
        val clock = TestTimeSource()
        private val create = CreateConversationUseCase(conversations)
        private val handleLink = HandleSummaryLinkUseCase(auth, requests, create, clock)
        val confirmSwitch = ConfirmAccountSwitchUseCase(auth, requests, create)
        val resume = ResumeSummaryAfterLoginUseCase(requests, create)

        /** 링크 하나를 받는다. 앞 링크와 겹치지 않게 시간을 [HandleSummaryLinkUseCase.REPEAT_WINDOW] 만큼 먼저 흘린다. */
        suspend fun handle(link: String): SummaryLinkResult {
            clock += HandleSummaryLinkUseCase.REPEAT_WINDOW
            return handleLink(link)
        }

        /** 시간을 흘리지 않고 바로 다음 링크를 받는다. */
        suspend fun handleRightAway(link: String): SummaryLinkResult = handleLink(link)
    }

    /** 대화 저장소가 로그인을 지켜보는 일은 [conversationScope] 에서 돈다. 기본은 테스트가 끝날 때 함께 멈추는 곳이다. */
    private fun TestScope.env(loggedInAs: String? = null, conversationScope: CoroutineScope = backgroundScope): Env {
        val session = SessionStore(MapSettings())
        if (loggedInAs != null) session.save("token-$loggedInAs", User(loggedInAs, FakeAuth.NAMES[loggedInAs] ?: loggedInAs))
        return Env(
            session = session,
            auth = FakeAuth(session),
            conversations = ConversationRepositoryImpl(createTestDatabase(), session, ChatMapper(), conversationScope),
            requests = SummaryRequestRepositoryImpl(emptyFlow()),
        )
    }

    private fun link(userId: String, fileId: String = "file-1", fileName: String = "%EB%B3%B4%EA%B3%A0%EC%84%9C.pdf") =
        "aiagent://summarize?fileId=$fileId&fileName=$fileName&userId=$userId&userName=${FakeAuth.NAMES[userId] ?: userId}"

    private fun request(userId: String, fileId: String = "file-1") =
        SummaryRequest(fileId, "보고서.pdf", userId, FakeAuth.NAMES[userId] ?: userId)

    @Test
    fun `같은 계정이면 빈 대화에 요약을 걸고 그 대화를 연다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        val empty = env.conversations.create()

        val result = env.handle(link("test1"))

        assertEquals(SummaryLinkResult.Open(empty.id), result)
        assertEquals(request("test1"), env.requests.takeHeld(empty.id).first())
        assertEquals(0, env.auth.logoutCount)
        assertEquals(1, env.conversations.conversations.value.size)
    }

    @Test
    fun `같은 계정인데 빈 대화가 없으면 새 대화를 만든다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        val used = env.conversations.create()
        env.conversations.upsertMessage(ChatMessage("q", used.id, ChatRole.User, "반차는 몇 시간이야?", createdAt = 1))

        val result = assertIs<SummaryLinkResult.Open>(env.handle(link("test1")))

        assertTrue(result.conversationId != used.id)
        assertEquals(2, env.conversations.conversations.value.size)
    }

    @Test
    fun `로그인 바로 뒤라 대화를 아직 읽지 않았어도 그 사람의 빈 대화를 찾아 쓴다`() = runTest(UnconfinedTestDispatcher()) {
        val database = createTestDatabase()
        val session = SessionStore(MapSettings()).apply { save("token-test1", User("test1", "김민준")) }
        val emptyId = ConversationRepositoryImpl(database, session, ChatMapper(), backgroundScope).create().id

        val stalled = CoroutineScope(StandardTestDispatcher(testScheduler))
        val conversations = ConversationRepositoryImpl(database, session, ChatMapper(), stalled)
        val requests = SummaryRequestRepositoryImpl(emptyFlow())
        val handle = HandleSummaryLinkUseCase(FakeAuth(session), requests, CreateConversationUseCase(conversations))

        assertEquals(SummaryLinkResult.Open(emptyId), handle(link("test1")))
        stalled.cancel()
    }

    @Test
    fun `다른 계정이면 로그아웃하지 않고 계정을 바꿀지 묻는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        val result = env.handle(link("test2"))

        assertEquals(SummaryLinkResult.SwitchAccountRequested(request("test2"), currentUserId = "test1"), result)
        assertEquals(0, env.auth.logoutCount, "묻기 전에 로그아웃했다 — 링크 하나로 남의 로그인을 끊을 수 있다")
        assertEquals("test1", env.session.user.value?.id)
        assertNull(env.requests.takeAwaitingLogin(), "묻기 전에 로그인 대기를 걸었다")
    }

    @Test
    fun `계정을 바꾸기로 하면 로그아웃하고 그 계정의 로그인을 기다린다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        val asked = assertIs<SummaryLinkResult.SwitchAccountRequested>(env.handle(link("test2")))

        val result = env.confirmSwitch(asked.request)

        assertEquals(SummaryLinkResult.LoginRequired(request("test2"), loggedOut = true), result)
        assertEquals(1, env.auth.logoutCount)
        assertNull(env.session.user.value)
        assertEquals(request("test2"), env.requests.takeAwaitingLogin())
    }

    @Test
    fun `묻는 사이 이미 그 계정이 되었으면 로그아웃하지 않고 요약 대화를 연다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        val asked = assertIs<SummaryLinkResult.SwitchAccountRequested>(env.handle(link("test2")))
        env.auth.login("test2", "1234")

        assertIs<SummaryLinkResult.Open>(env.confirmSwitch(asked.request))
        assertEquals(0, env.auth.logoutCount)
    }

    @Test
    fun `앞 링크에서 3초 안에 다시 온 링크는 버린다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        assertIs<SummaryLinkResult.SwitchAccountRequested>(env.handle(link("test2")))

        assertEquals(SummaryLinkResult.Ignored, env.handleRightAway(link("test3")))
        env.clock += HandleSummaryLinkUseCase.REPEAT_WINDOW - 1.milliseconds
        assertEquals(SummaryLinkResult.Ignored, env.handleRightAway(link("test1")), "3초가 지나기 전이다")

        env.clock += 1.milliseconds
        assertIs<SummaryLinkResult.Open>(env.handleRightAway(link("test1")), "3초가 지나면 다시 받는다")
        assertEquals(0, env.auth.logoutCount)
    }

    @Test
    fun `값이 이상한 링크는 다음 링크를 막지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        assertEquals(SummaryLinkResult.Ignored, env.handle("aiagent://summarize?fileId=a%20b&fileName=a.txt&userId=test1"))

        assertIs<SummaryLinkResult.Open>(env.handleRightAway(link("test1")))
    }

    @Test
    fun `로그인하지 않았으면 로그아웃 없이 로그인을 기다린다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()

        val result = env.handle(link("test2"))

        assertEquals(SummaryLinkResult.LoginRequired(request("test2"), loggedOut = false), result)
        assertEquals(0, env.auth.logoutCount)
    }

    @Test
    fun `기다리던 계정으로 로그인하면 그 사람의 대화에 요약을 걸고 연다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        env.conversations.create()
        env.confirmSwitch(assertIs<SummaryLinkResult.SwitchAccountRequested>(env.handle(link("test2"))).request)

        env.auth.login("test2", "1234")
        val result = assertIs<SummaryLinkResult.Open>(env.resume(User("test2", "이서연")))

        assertEquals(listOf(result.conversationId), env.conversations.conversations.value.map { it.id })
        assertEquals(request("test2"), env.requests.takeHeld(result.conversationId).first())
        assertEquals(SummaryLinkResult.Ignored, env.resume(User("test2", "이서연")), "한 번 이어 간 요청은 다시 나오지 않는다")
    }

    @Test
    fun `다른 계정으로 로그인하면 기다리던 요약을 버린다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")
        env.confirmSwitch(assertIs<SummaryLinkResult.SwitchAccountRequested>(env.handle(link("test2"))).request)

        env.auth.login("test3", "1234")

        assertEquals(SummaryLinkResult.Cancelled, env.resume(User("test3", "박서준")))
        assertTrue(env.conversations.conversations.value.isEmpty())
        assertNull(env.requests.takeAwaitingLogin())
    }

    @Test
    fun `기다리는 동안 새 요청이 오면 앞의 요청은 버리고 새 요청으로 이어 간다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        env.handle(link("test2", fileId = "old"))
        env.handle(link("test3", fileId = "new"))

        env.auth.login("test3", "1234")
        val result = assertIs<SummaryLinkResult.Open>(env.resume(User("test3", "박서준")))

        assertEquals(request("test3", fileId = "new"), env.requests.takeHeld(result.conversationId).first())
    }

    @Test
    fun `요약 링크가 아니거나 값이 이상하면 로그인을 건드리지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env(loggedInAs = "test1")

        assertEquals(SummaryLinkResult.Ignored, env.handle("aiagent://summarize?fileId=..%2Fx&fileName=a.txt&userId=test2"))
        assertEquals(SummaryLinkResult.Ignored, env.handle("https://example.com"))

        assertEquals(0, env.auth.logoutCount)
        assertEquals("test1", env.session.user.value?.id)
        assertNull(env.requests.takeAwaitingLogin())
    }

    @Test
    fun `같은 파일을 다시 걸어도 대화방이 새 요청으로 알아챈다`() = runTest(UnconfinedTestDispatcher()) {
        val requests = SummaryRequestRepositoryImpl(emptyFlow())
        val taken = mutableListOf<SummaryRequest>()
        backgroundScope.launch { requests.takeHeld("c1").toList(taken) }

        requests.hold("c1", request("test1"))
        requests.hold("c2", request("test1", fileId = "other"))
        requests.hold("c1", request("test1"))

        assertEquals(listOf(request("test1"), request("test1")), taken)
    }
}
