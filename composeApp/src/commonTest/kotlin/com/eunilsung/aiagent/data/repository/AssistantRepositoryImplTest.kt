package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.Source
import com.eunilsung.aiagent.domain.model.User
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 브릿지 응답을 [MockEngine] 이 대신한다 — 앱이 실제로 쓰는 [BridgeClient]·SSE 해석·실패 분류를 함께 본다. */
class AssistantRepositoryImplTest {

    private val question = ChatMessage("q", "c", ChatRole.User, "반차는 몇 시간이야?", createdAt = 1)

    private fun repository(status: HttpStatusCode = HttpStatusCode.OK, body: String = SSE, loggedIn: Boolean = true): Pair<AssistantRepositoryImpl, MutableList<String>> {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.url.encodedPath} ${request.headers[HttpHeaders.Authorization]}"
            respond(body, status, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val settings = MapSettings()
        val session = SessionStore(settings).apply { if (loggedIn) save("token-1", User("test1", "김민준")) }
        val bridge = BridgeClient(HttpClient(engine), BridgeUrlStore(settings))
        return AssistantRepositoryImpl(bridge, session, ChatMapper()) to requests
    }

    @Test
    fun `근거 문서와 답변 조각을 차례로 내보낸다`() = runTest {
        val (repository, requests) = repository()
        val events = repository.ask(listOf(question)).toList()
        assertEquals(
            listOf(
                AnswerEvent.Retrieved(listOf(Source("01_휴가규정.md", 0.69)), "반차 시간"),
                AnswerEvent.Delta("4시간"),
                AnswerEvent.Delta("입니다."),
            ),
            events,
        )
        assertEquals(listOf("/v1/chat Bearer token-1"), requests)
    }

    @Test
    fun `메시지 보내기 요청은 답변 조각 대신 SendRequested 로 온다`() = runTest {
        val (repository, _) = repository(body = "event: action\ndata: {\"type\":\"send_message\",\"recipient\":\"이서연\",\"content\":\"회의는 10시입니다.\"}\n\nevent: done\ndata: {}\n\n")
        assertEquals(listOf(AnswerEvent.SendRequested("이서연", "회의는 10시입니다.")), repository.ask(listOf(question)).toList())
    }

    @Test
    fun `문서에서 찾지 못했으면 끝에 NotFound 를 내보낸다`() = runTest {
        val (repository, _) = repository(
            body = "event: sources\ndata: {\"sources\": [{\"source\": \"02_재택근무.md\", \"score\": 0.48}]}\n\n" +
                "data: {\"delta\": \"문서에서 찾을 수 없습니다.\"}\n\nevent: done\ndata: {\"notFound\": true}\n\n"
        )
        assertEquals(
            listOf(
                AnswerEvent.Retrieved(listOf(Source("02_재택근무.md", 0.48)), null),
                AnswerEvent.Delta("문서에서 찾을 수 없습니다."),
                AnswerEvent.NotFound,
            ),
            repository.ask(listOf(question)).toList(),
        )
    }

    @Test
    fun `스트림 중 오류 이벤트는 서버 실패다`() = runTest {
        val (repository, _) = repository(body = "event: error\ndata: {\"message\":\"model not found\"}\n\n")
        val failure = assertFailsWith<AnswerFailure.Server> { repository.ask(listOf(question)).toList() }
        assertEquals("model not found", failure.reason)
    }

    @Test
    fun `토큰이 죽었으면 로그인이 풀린 것이다`() = runTest {
        val (repository, _) = repository(status = HttpStatusCode.Unauthorized, body = """{"code":"unauthorized"}""")
        assertFailsWith<AnswerFailure.SessionExpired> { repository.ask(listOf(question)).toList() }
    }

    @Test
    fun `한도를 넘기면 RateLimited 이고 RAG 가 꺼져 있으면 Unreachable`() = runTest {
        assertFailsWith<AnswerFailure.RateLimited> {
            repository(status = HttpStatusCode.TooManyRequests, body = """{"code":"rate_limited"}""").first.ask(listOf(question)).toList()
        }
        assertFailsWith<AnswerFailure.Unreachable> {
            repository(status = HttpStatusCode.ServiceUnavailable, body = """{"code":"rag_unreachable"}""").first.ask(listOf(question)).toList()
        }
    }

    @Test
    fun `로그인하지 않았으면 브릿지를 부르지 않는다`() = runTest {
        val (repository, requests) = repository(loggedIn = false)
        assertFailsWith<AnswerFailure.SessionExpired> { repository.ask(listOf(question)).toList() }
        assertTrue(requests.isEmpty())
    }

    private companion object {
        val SSE = """
            : connected

            event: sources
            data: {"sources": [{"source": "01_휴가규정.md", "score": 0.69}], "query": "반차 시간"}

            data: {"delta": "4시간"}

            data: {"delta": "입니다."}

            event: done
            data: {}

        """.trimIndent()
    }
}
