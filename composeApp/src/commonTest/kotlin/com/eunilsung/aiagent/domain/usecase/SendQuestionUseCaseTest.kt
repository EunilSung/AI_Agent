package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.repository.ConversationRepositoryImpl
import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.MessageStatus
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendStatus
import com.eunilsung.aiagent.domain.model.Source
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.domain.repository.MessengerRepository
import com.eunilsung.aiagent.testsupport.createTestDatabase
import com.eunilsung.aiagent.testsupport.sseAssistant
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 질문 보내기 흐름. 대화 기록은 진짜 저장소(인메모리 DB)를 쓰고, LLM 만 [FakeAssistant] 로 바꾼다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendQuestionUseCaseTest {

    private class FakeAssistant(private val answer: (List<ChatMessage>) -> Flow<AnswerEvent>) : AssistantRepository {
        val asked = mutableListOf<List<ChatMessage>>()
        override fun ask(history: List<ChatMessage>): Flow<AnswerEvent> {
            asked += history
            return answer(history)
        }

        override fun summarize(fileId: String, fileName: String): Flow<AnswerEvent> = error("요약은 쓰지 않는다")
    }

    /** Talk 대역 — 이서연은 test2 하나, 이서는 둘(이서연·이서윤)이다. 보낸 것을 [sent] 에 남긴다. */
    private class FakeMessenger(var failSend: Boolean = false) : MessengerRepository {
        val sent = mutableListOf<Triple<String, String, String>>()
        override suspend fun findPeople(query: String): List<Person> = PEOPLE.filter { query in it.name || query == it.id }
        override suspend fun send(person: Person, content: String, requestId: String): Boolean {
            if (failSend) return false
            sent += Triple(person.id, content, requestId)
            return true
        }
    }

    /** 저장소는 로그인 상태를 계속 지켜보므로 테스트가 끝날 때 함께 멈추는 [TestScope.backgroundScope] 에 띄운다. */
    private fun TestScope.conversations(): ConversationRepositoryImpl {
        val session = SessionStore(MapSettings()).apply { save("token", User("test1", "김민준")) }
        return ConversationRepositoryImpl(createTestDatabase(), session, ChatMapper(), backgroundScope)
    }

    @Test
    fun `답변을 다 받으면 질문과 답변이 저장되고 제목이 질문이 된다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val assistant = FakeAssistant {
            flow {
                emit(AnswerEvent.Retrieved(listOf(Source("01_휴가규정.md", 0.7)), null))
                emit(AnswerEvent.Delta("4시간"))
                emit(AnswerEvent.Delta("입니다."))
            }
        }
        val id = conversations.create().id

        assertNull(SendQuestionUseCase(conversations, assistant, FakeMessenger())(id, "반차는 몇 시간이야?"))

        val messages = conversations.messages(id)
        assertEquals(listOf(ChatRole.User, ChatRole.Assistant), messages.map { it.role })
        assertEquals("4시간입니다.", messages[1].content)
        assertEquals(MessageStatus.Done, messages[1].status)
        assertEquals("01_휴가규정.md", messages[1].sources.single().name)
        assertEquals("반차는 몇 시간이야?", conversations.conversations.value.single().title)
    }

    @Test
    fun `문서에서 찾지 못한 답변은 근거와 검색어를 지우고 저장한다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val assistant = FakeAssistant {
            flow {
                emit(AnswerEvent.Retrieved(listOf(Source("02_재택근무.md", 0.48)), "회사의 복장 규정이 있나요?"))
                emit(AnswerEvent.Delta("문서에서 찾을 수 없습니다."))
                emit(AnswerEvent.NotFound)
            }
        }
        val id = conversations.create().id

        SendQuestionUseCase(conversations, assistant, FakeMessenger())(id, "Is there a dress code?")

        val answer = conversations.messages(id)[1]
        assertEquals("문서에서 찾을 수 없습니다.", answer.content)
        assertEquals(MessageStatus.Done, answer.status)
        assertTrue(answer.sources.isEmpty())
        assertNull(answer.searchQuery)
    }

    @Test
    fun `후속 질문은 앞 대화를 함께 보내고 실패한 답변은 빼고 보낸다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        var fail = true
        val assistant = FakeAssistant { flow { if (fail) throw AnswerFailure.Unreachable else emit(AnswerEvent.Delta("네")) } }
        val id = conversations.create().id
        val send = SendQuestionUseCase(conversations, assistant, FakeMessenger())

        assertEquals(AnswerFailure.Unreachable, send(id, "첫 질문"))
        fail = false
        send(id, "두 번째 질문")

        assertEquals(listOf("첫 질문", "두 번째 질문"), assistant.asked.last().map { it.content })
        assertEquals(MessageStatus.Failed, conversations.messages(id)[1].status)
    }

    @Test
    fun `멈추면 받은 데까지 남기고 중지됨으로 저장한다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val firstChunk = CompletableDeferred<Unit>()
        val assistant = FakeAssistant {
            flow {
                emit(AnswerEvent.Delta("받은 데까지"))
                firstChunk.complete(Unit)
                awaitCancellation()
            }
        }
        val id = conversations.create().id

        val job = async { SendQuestionUseCase(conversations, assistant, FakeMessenger())(id, "길게 설명해 줘") }
        firstChunk.await()
        job.cancel()

        val reply = conversations.messages(id).last()
        assertEquals("받은 데까지", reply.content)
        assertEquals(MessageStatus.Stopped, reply.status)
    }

    @Test
    fun `서버가 끝을 알리기 전에 끊긴 답변은 받은 데까지 실패로 남기고 다음 질문의 기록에서 뺀다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val id = conversations.create().id
        val cut = sseAssistant(": connected\n\ndata: {\"delta\": \"반차는 4시간\"}\n\ndata: {\"delta\": \"이고\"}\n\n")

        assertEquals(AnswerFailure.Unreachable, SendQuestionUseCase(conversations, cut, FakeMessenger())(id, "반차는 몇 시간이야?"))

        val reply = conversations.messages(id).last()
        assertEquals("반차는 4시간이고", reply.content)
        assertEquals(MessageStatus.Failed, reply.status)

        val next = FakeAssistant { flow { emit(AnswerEvent.Delta("네")) } }
        SendQuestionUseCase(conversations, next, FakeMessenger())(id, "오전 반차도 같아?")
        assertEquals(listOf("반차는 몇 시간이야?", "오전 반차도 같아?"), next.asked.single().map { it.content })
    }

    @Test
    fun `서버가 끝을 알리면 다 받은 답변으로 저장한다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val id = conversations.create().id
        val complete = sseAssistant("data: {\"delta\": \"4시간입니다.\"}\n\nevent: done\ndata: {}\n\n")

        assertNull(SendQuestionUseCase(conversations, complete, FakeMessenger())(id, "반차는 몇 시간이야?"))

        val reply = conversations.messages(id).last()
        assertEquals("4시간입니다.", reply.content)
        assertEquals(MessageStatus.Done, reply.status)
    }

    @Test
    fun `질문하지 않은 빈 대화가 있으면 새 대화를 또 만들지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val create = CreateConversationUseCase(conversations)
        assertEquals(create().id, create().id)
        assertEquals(1, conversations.conversations.value.size)
    }

    @Test
    fun `보내 달라는 요청은 받는 사람을 찾아 확인 카드로 남기고 보내지는 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val messenger = FakeMessenger()
        val assistant = FakeAssistant { flow { emit(AnswerEvent.SendRequested("이서연", "내일 회의는 10시입니다.")) } }
        val id = conversations.create().id

        SendQuestionUseCase(conversations, assistant, messenger)(id, "이서연에게 회의 시간 알려줘")

        val request = conversations.messages(id).last().sendRequest!!
        assertEquals(listOf("test2"), request.candidates.map { it.id })
        assertEquals(SendStatus.Pending, request.status)
        assertTrue(messenger.sent.isEmpty())
    }

    @Test
    fun `확인하면 고른 사람에게 메시지 id 로 보내고 보낸 것으로 남긴다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val messenger = FakeMessenger()
        val assistant = FakeAssistant { flow { emit(AnswerEvent.SendRequested("이서", "내일 회의는 10시입니다.")) } }
        val id = conversations.create().id
        SendQuestionUseCase(conversations, assistant, messenger)(id, "이서에게 알려줘")
        val card = conversations.messages(id).last()
        assertEquals(2, card.sendRequest!!.candidates.size)

        assertTrue(ConfirmSendUseCase(conversations, messenger)(id, card.id, "test4"))

        assertEquals(listOf(Triple("test4", "내일 회의는 10시입니다.", card.id)), messenger.sent)
        val result = conversations.messages(id).last().sendRequest!!
        assertEquals(SendStatus.Sent, result.status)
        assertEquals("이서윤", result.sentTo?.name)
    }

    @Test
    fun `보내지 못하면 실패로 남기고 다시 보낼 수 있다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val messenger = FakeMessenger(failSend = true)
        val assistant = FakeAssistant { flow { emit(AnswerEvent.SendRequested("test2", "안녕하세요")) } }
        val id = conversations.create().id
        SendQuestionUseCase(conversations, assistant, messenger)(id, "test2 에게 인사해줘")
        val cardId = conversations.messages(id).last().id

        assertFalse(ConfirmSendUseCase(conversations, messenger)(id, cardId, "test2"))
        assertEquals(SendStatus.Failed, conversations.messages(id).last().sendRequest!!.status)

        messenger.failSend = false
        assertTrue(ConfirmSendUseCase(conversations, messenger)(id, cardId, "test2"))
        assertEquals(SendStatus.Sent, conversations.messages(id).last().sendRequest!!.status)
    }

    @Test
    fun `취소하면 보내지 않고 다시 확인해도 보내지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val conversations = conversations()
        val messenger = FakeMessenger()
        val assistant = FakeAssistant { flow { emit(AnswerEvent.SendRequested("이서연", "안녕하세요")) } }
        val id = conversations.create().id
        SendQuestionUseCase(conversations, assistant, messenger)(id, "이서연에게 인사해줘")
        val cardId = conversations.messages(id).last().id

        CancelSendUseCase(conversations)(id, cardId)

        assertFalse(ConfirmSendUseCase(conversations, messenger)(id, cardId, "test2"))
        assertEquals(SendStatus.Cancelled, conversations.messages(id).last().sendRequest!!.status)
        assertTrue(messenger.sent.isEmpty())
    }

    private companion object {
        val PEOPLE = listOf(Person("test2", "이서연", "개발1팀 대리"), Person("test4", "이서윤"), Person("test3", "박서준"))
    }
}
