package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.repository.ConversationRepositoryImpl
import com.eunilsung.aiagent.db.AppDatabase
import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.MessageStatus
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendResult
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.domain.repository.MessengerRepository
import com.eunilsung.aiagent.testsupport.FakeSummaryAssistant
import com.eunilsung.aiagent.testsupport.createTestDatabase
import com.eunilsung.aiagent.testsupport.sseAssistant
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Talk 파일 요약. 대화 기록은 진짜 저장소(인메모리 DB)를 쓰고, 요약 서버만 [FakeSummaryAssistant] 로 바꾼다. */
@OptIn(ExperimentalCoroutinesApi::class)
class SummarizeFileUseCaseTest {

    private val request = SummaryRequest("file-1", "보고서.pdf", "test1", "김민준")
    private val question = "보고서.pdf 요약해 줘"

    private class Store(val database: AppDatabase, val session: SessionStore, val conversations: ConversationRepositoryImpl)

    private fun TestScope.store(): Store {
        val database = createTestDatabase()
        val session = SessionStore(MapSettings()).apply { save("token", User("test1", "김민준")) }
        return Store(database, session, ConversationRepositoryImpl(database, session, ChatMapper(), backgroundScope))
    }

    /** 같은 DB 를 새로 열어 읽는다 — 화면에만 있던 것은 빠지고 저장된 것만 남는다. */
    private suspend fun TestScope.reopened(store: Store, conversationId: String): List<ChatMessage> =
        ConversationRepositoryImpl(store.database, store.session, ChatMapper(), backgroundScope).messages(conversationId)

    @Test
    fun `요약을 다 받으면 질문과 요약이 저장되고 진행은 받는 동안 화면에만 있다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val conversations = store.conversations
        val id = conversations.create().id
        val whileReading = mutableListOf<ChatMessage>()
        val assistant = FakeSummaryAssistant { _, _ ->
            flow {
                emit(AnswerEvent.Progress(1, 2))
                whileReading += conversations.messages(id).last()
                emit(AnswerEvent.Progress(2, 2))
                whileReading += conversations.messages(id).last()
                emit(AnswerEvent.Delta("한 줄 요약"))
                emit(AnswerEvent.Delta("\n### 주요 내용"))
            }
        }

        assertNull(SummarizeFileUseCase(conversations, assistant)(id, request, question))

        assertEquals(listOf("file-1" to "보고서.pdf"), assistant.summarized)
        assertEquals(listOf(AnswerEvent.Progress(1, 2), AnswerEvent.Progress(2, 2)), whileReading.map { it.progress })
        assertEquals(listOf(MessageStatus.Streaming, MessageStatus.Streaming), whileReading.map { it.status })

        val messages = conversations.messages(id)
        assertEquals(listOf(ChatRole.User, ChatRole.Assistant), messages.map { it.role })
        assertEquals(question, messages[0].content)
        assertEquals("한 줄 요약\n### 주요 내용", messages[1].content)
        assertEquals(MessageStatus.Done, messages[1].status)
        assertNull(messages[1].progress)
        assertEquals(question, conversations.conversations.value.single().title)

        val saved = reopened(store, id)
        assertEquals(listOf(question, "한 줄 요약\n### 주요 내용"), saved.map { it.content })
        assertEquals(MessageStatus.Done, saved[1].status)
    }

    /**
     * 파일은 남이 올린 것이라 요약에 숨긴 지시가 섞여 나올 수 있다. 요약 질문과 답변은 요약으로 표시해 저장하고, 다음 질문의
     * 대화 기록에도 그 표시를 실어 보낸다 — RAG 서버가 지시가 아닌 자료로 감싼다. 사용자가 직접 쓴 질문은 표시하지 않는다.
     */
    @Test
    fun `요약 질문과 답변은 요약으로 표시해 저장하고 다음 질문의 대화 기록에도 싣는다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val id = store.conversations.create().id
        val assistant = FakeSummaryAssistant { _, _ -> flow { emit(AnswerEvent.Delta("요약에 숨긴 지시")) } }
        SummarizeFileUseCase(store.conversations, assistant)(id, request, question)

        assertEquals(listOf(true, true), store.conversations.messages(id).map { it.summary })
        assertEquals(listOf(true, true), reopened(store, id).map { it.summary }, "앱을 다시 켜면 요약 표시가 사라진다")

        val asked = mutableListOf<List<ChatMessage>>()
        val followUp = object : AssistantRepository {
            override fun ask(history: List<ChatMessage>) = flow<AnswerEvent> { asked += history; emit(AnswerEvent.Delta("답")) }
            override fun summarize(fileId: String, fileName: String) = error("쓰지 않는다")
        }
        val noMessenger = object : MessengerRepository {
            override suspend fun findPeople(query: String) = emptyList<Person>()
            override suspend fun send(person: Person, content: String, requestId: String) = SendResult.Failed()
        }
        SendQuestionUseCase(store.conversations, followUp, noMessenger)(id, "핵심만 다시")

        val dtos = asked.single().map(ChatMapper()::toDto)
        assertEquals(listOf(true, true, false), dtos.map { it.summary })
    }

    @Test
    fun `실패하면 이유를 돌려주고 실패로 남긴다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val id = store.conversations.create().id
        val assistant = FakeSummaryAssistant { _, _ ->
            flow {
                emit(AnswerEvent.Progress(1, 3))
                throw AnswerFailure.NoText
            }
        }

        assertEquals(AnswerFailure.NoText, SummarizeFileUseCase(store.conversations, assistant)(id, request, question))

        val reply = store.conversations.messages(id).last()
        assertEquals(MessageStatus.Failed, reply.status)
        assertNull(reply.progress)
        assertEquals(MessageStatus.Failed, reopened(store, id).last().status)
    }

    @Test
    fun `멈추면 받은 데까지 남기고 중지됨으로 저장한다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val id = store.conversations.create().id
        val firstChunk = CompletableDeferred<Unit>()
        val assistant = FakeSummaryAssistant { _, _ ->
            flow {
                emit(AnswerEvent.Delta("앞부분"))
                firstChunk.complete(Unit)
                awaitCancellation()
            }
        }

        val job = async { SummarizeFileUseCase(store.conversations, assistant)(id, request, question) }
        firstChunk.await()
        job.cancel()

        val reply = store.conversations.messages(id).last()
        assertEquals("앞부분", reply.content)
        assertEquals(MessageStatus.Stopped, reply.status)
        assertEquals(MessageStatus.Stopped, reopened(store, id).last().status)
    }

    @Test
    fun `서버가 끝을 알리기 전에 끊긴 요약은 받은 데까지 실패로 남긴다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val id = store.conversations.create().id
        val cut = sseAssistant(
            ": connected\n\nevent: progress\ndata: {\"done\": 2, \"total\": 2}\n\ndata: {\"delta\": \"한 줄 요약\"}\n\ndata: {\"delta\": \"\\n### 주요\"}\n\n"
        )

        assertEquals(AnswerFailure.Unreachable, SummarizeFileUseCase(store.conversations, cut)(id, request, question))

        val reply = store.conversations.messages(id).last()
        assertEquals("한 줄 요약\n### 주요", reply.content)
        assertEquals(MessageStatus.Failed, reply.status)
        assertNull(reply.progress)
        val saved = reopened(store, id).last()
        assertEquals("한 줄 요약\n### 주요", saved.content)
        assertEquals(MessageStatus.Failed, saved.status)
    }

    @Test
    fun `서버가 끝을 알리면 요약을 다 받은 것으로 저장한다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val id = store.conversations.create().id
        val complete = sseAssistant("data: {\"delta\": \"한 줄 요약\"}\n\nevent: done\ndata: {}\n\n")

        assertNull(SummarizeFileUseCase(store.conversations, complete)(id, request, question))

        assertEquals(MessageStatus.Done, reopened(store, id).last().status)
    }

    @Test
    fun `요약 길이 끝에 닿으면 바로 그만 받아 그 뒤에 끊겨도 완료로 남긴다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val id = store.conversations.create().id
        val reachesEndThenCut = sseAssistant(
            "data: {\"delta\": \"${"가".repeat(5_000)}\"}\n\ndata: {\"delta\": \"${"나".repeat(3_000)}\"}\n\n"
        )

        assertNull(SummarizeFileUseCase(store.conversations, reachesEndThenCut)(id, request, question))

        val reply = reopened(store, id).last()
        assertEquals(SummarizeFileUseCase.MAX_SUMMARY_LENGTH, reply.content.length)
        assertEquals(MessageStatus.Done, reply.status)
    }

    @Test
    fun `요약이 브릿지 메시지 길이 끝을 넘으면 자르고 더 받지 않는다`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val id = store.conversations.create().id
        var reachedEnd = false
        val assistant = FakeSummaryAssistant { _, _ ->
            flow {
                emit(AnswerEvent.Delta("가".repeat(5_000)))
                emit(AnswerEvent.Delta("나".repeat(5_000)))
                emit(AnswerEvent.Delta("다"))
                reachedEnd = true
            }
        }

        assertNull(SummarizeFileUseCase(store.conversations, assistant)(id, request, question))

        val reply = store.conversations.messages(id).last()
        assertEquals(SummarizeFileUseCase.MAX_SUMMARY_LENGTH, reply.content.length)
        assertEquals(MessageStatus.Done, reply.status)
        assertFalse(reply.content.contains('다'))
        assertFalse(reachedEnd)
    }
}
