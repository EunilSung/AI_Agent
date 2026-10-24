package com.eunilsung.aiagent.ui.chat

import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.repository.ConversationRepositoryImpl
import com.eunilsung.aiagent.data.repository.SummaryRequestRepositoryImpl
import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.MessageStatus
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendResult
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.MessengerRepository
import com.eunilsung.aiagent.domain.usecase.CancelSendUseCase
import com.eunilsung.aiagent.domain.usecase.ChatUseCases
import com.eunilsung.aiagent.domain.usecase.ConfirmSendUseCase
import com.eunilsung.aiagent.domain.usecase.LogoutUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveConversationUseCase
import com.eunilsung.aiagent.domain.usecase.ObserveMessagesUseCase
import com.eunilsung.aiagent.domain.usecase.SendQuestionUseCase
import com.eunilsung.aiagent.domain.usecase.SummarizeFileUseCase
import com.eunilsung.aiagent.domain.usecase.TakeHeldSummariesUseCase
import com.eunilsung.aiagent.testsupport.FakeAuth
import com.eunilsung.aiagent.testsupport.FakeSummaryAssistant
import com.eunilsung.aiagent.testsupport.createTestDatabase
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
import kotlin.test.assertFalse

/**
 * 대화방이 Talk 요약 링크로 걸린 요약을 꺼내 시작하는 일. 화면 대신 테스트가 [ChatEvent.SummaryRequested] 를 받아
 * 질문 문구를 만들어 [ChatActions.OnSummarize] 로 돌려준다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSummaryTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val request = SummaryRequest("file-1", "보고서.pdf", "test1", "김민준")

    private object NoMessenger : MessengerRepository {
        override suspend fun findPeople(query: String): List<Person> = emptyList()
        override suspend fun send(person: Person, content: String, requestId: String): SendResult = SendResult.Failed()
    }

    private class Env(
        val conversations: ConversationRepositoryImpl,
        val requests: SummaryRequestRepositoryImpl,
        val useCases: ChatUseCases,
    )

    private fun TestScope.env(answer: (String, String) -> Flow<AnswerEvent>): Env {
        val session = SessionStore(MapSettings()).apply { save("token-test1", User("test1", "김민준")) }
        val conversations = ConversationRepositoryImpl(createTestDatabase(), session, ChatMapper(), backgroundScope)
        val requests = SummaryRequestRepositoryImpl(emptyFlow())
        val assistant = FakeSummaryAssistant(answer)
        val useCases = ChatUseCases(
            observeConversation = ObserveConversationUseCase(conversations),
            observeMessages = ObserveMessagesUseCase(conversations),
            sendQuestion = SendQuestionUseCase(conversations, assistant, NoMessenger),
            confirmSend = ConfirmSendUseCase(conversations, NoMessenger),
            cancelSend = CancelSendUseCase(conversations),
            logout = LogoutUseCase(FakeAuth(session)),
            takeHeldSummaries = TakeHeldSummariesUseCase(requests),
            summarizeFile = SummarizeFileUseCase(conversations, assistant),
        )
        return Env(conversations, requests, useCases)
    }

    /** 화면이 하는 일 — 요약이 걸렸다는 알림을 받으면 질문 문구를 만들어 돌려준다. 그 밖의 알림은 [others] 에 남긴다. */
    private fun TestScope.actLikeScreen(viewModel: ChatViewModel, others: MutableList<ChatEvent> = mutableListOf()) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is ChatEvent.SummaryRequested) {
                    viewModel.onAction(ChatActions.OnSummarize(event.request, "${event.request.fileName} 요약해 줘"))
                } else {
                    others += event
                }
            }
        }
    }

    @Test
    fun `대화방이 열리면 걸려 있던 요약을 꺼내 질문과 요약으로 남긴다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env { _, _ -> flow { emit(AnswerEvent.Delta("한 줄 요약")) } }
        val id = env.conversations.create().id
        env.requests.hold(id, request)

        val viewModel = ChatViewModel(id, env.useCases)
        actLikeScreen(viewModel)

        val messages = viewModel.uiState.value.messages
        assertEquals(listOf("보고서.pdf 요약해 줘", "한 줄 요약"), messages.map { it.content })
        assertEquals(MessageStatus.Done, messages.last().status)
        assertFalse(viewModel.uiState.value.isAnswering)
        assertEquals("보고서.pdf 요약해 줘", viewModel.uiState.value.title)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `이미 있던 빈 대화의 대화방도 나중에 걸린 요약을 꺼낸다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env { _, _ -> flow { emit(AnswerEvent.Delta("요약")) } }
        val id = env.conversations.create().id
        val viewModel = ChatViewModel(id, env.useCases)
        actLikeScreen(viewModel)
        assertEquals(emptyList(), viewModel.uiState.value.messages)

        env.requests.hold(id, request)

        assertEquals(listOf("보고서.pdf 요약해 줘", "요약"), viewModel.uiState.value.messages.map { it.content })
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `다른 대화에 걸린 요약은 꺼내지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env { _, _ -> flow { emit(AnswerEvent.Delta("요약")) } }
        val mine = env.conversations.create().id
        val viewModel = ChatViewModel(mine, env.useCases)
        actLikeScreen(viewModel)

        env.requests.hold("other", request)

        assertEquals(emptyList(), viewModel.uiState.value.messages)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `요약하지 못하면 이유를 알린다`() = runTest(UnconfinedTestDispatcher()) {
        val env = env { _, _ -> flow { throw AnswerFailure.FileNotFound } }
        val id = env.conversations.create().id
        env.requests.hold(id, request)
        val viewModel = ChatViewModel(id, env.useCases)
        val others = mutableListOf<ChatEvent>()

        actLikeScreen(viewModel, others)

        assertEquals(listOf<ChatEvent>(ChatEvent.AnswerFailed(AnswerFailure.FileNotFound)), others)
        assertEquals(MessageStatus.Failed, viewModel.uiState.value.messages.last().status)
        viewModel.viewModelScope.cancel()
    }
}
