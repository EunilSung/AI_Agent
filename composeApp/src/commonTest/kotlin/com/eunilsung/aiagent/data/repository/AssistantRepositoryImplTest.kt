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
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.SummarizeRequest
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
            requests += request.body.toByteArray().decodeToString()
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
        assertEquals("/v1/chat Bearer token-1", requests.first())
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

    @Test
    fun `모르는 이름의 이벤트는 답변 조각으로 섞지 않고 건너뛴다`() = runTest {
        val (repository, _) = repository(
            body = "event: thinking\ndata: {\"delta\": \"속생각\"}\n\ndata: {\"delta\": \"답\"}\n\nevent: done\ndata: {}\n\n"
        )
        assertEquals(listOf(AnswerEvent.Delta("답")), repository.ask(listOf(question)).toList())
    }

    @Test
    fun `요약은 파일 id 와 이름을 보내고 진행과 요약 조각을 차례로 내보낸다`() = runTest {
        val (repository, requests) = repository(body = SUMMARY_SSE)
        val events = repository.summarize("file-1", "보고서.pdf").toList()
        assertEquals(
            listOf(
                AnswerEvent.Progress(1, 2),
                AnswerEvent.Progress(2, 2),
                AnswerEvent.Delta("한 줄 요약"),
                AnswerEvent.Delta("\n### 주요 내용"),
            ),
            events,
        )
        assertEquals("/v1/summarize Bearer token-1", requests[0])
        assertEquals(SummarizeRequest("file-1", "보고서.pdf"), Json.decodeFromString(SummarizeRequest.serializer(), requests[1]))
    }

    @Test
    fun `모양이 맞지 않는 진행은 버린다`() = runTest {
        val (repository, _) = repository(
            body = "event: progress\ndata: {\"done\": 3, \"total\": 0}\n\nevent: progress\ndata: oops\n\ndata: {\"delta\": \"요약\"}\n\nevent: done\ndata: {}\n\n"
        )
        assertEquals(listOf(AnswerEvent.Delta("요약")), repository.summarize("f", "a.txt").toList())
    }

    @Test
    fun `요약 오류 이벤트의 code 를 실패 이유로 바꾼다`() = runTest {
        val cases = mapOf(
            "unsupported_file" to AnswerFailure.UnsupportedFile,
            "no_text" to AnswerFailure.NoText,
            "encrypted_file" to AnswerFailure.EncryptedFile,
            "broken_file" to AnswerFailure.BrokenFile,
            "stream_interrupted" to AnswerFailure.Unreachable,
            "" to AnswerFailure.Server("요약 실패"),
            "something_new" to AnswerFailure.Server("요약 실패"),
        )
        for ((code, expected) in cases) {
            val (repository, _) = repository(
                body = "event: error\ndata: {\"message\": \"요약 실패\", \"code\": \"$code\"}\n\nevent: done\ndata: {}\n\n"
            )
            val failure = assertFailsWith<AnswerFailure> { repository.summarize("f", "a.pdf").toList() }
            assertEquals(expected, failure, "code=$code")
        }
    }

    @Test
    fun `브릿지가 파일을 받지 못해 거절한 이유를 나눈다`() = runTest {
        assertFailsWith<AnswerFailure.FileNotFound> {
            repository(status = HttpStatusCode.NotFound, body = """{"code":"file_not_found"}""").first.summarize("f", "a.pdf").toList()
        }
        assertFailsWith<AnswerFailure.FileTooLarge> {
            repository(status = HttpStatusCode.PayloadTooLarge, body = """{"code":"file_too_large"}""").first.summarize("f", "a.pdf").toList()
        }
        assertFailsWith<AnswerFailure.Unreachable> {
            repository(status = HttpStatusCode.ServiceUnavailable, body = """{"code":"talk_unreachable"}""").first.summarize("f", "a.pdf").toList()
        }
        assertFailsWith<AnswerFailure.SessionExpired> {
            repository(status = HttpStatusCode.Unauthorized, body = """{"code":"unauthorized"}""").first.summarize("f", "a.pdf").toList()
        }
        assertFailsWith<AnswerFailure.RateLimited> {
            repository(status = HttpStatusCode.TooManyRequests, body = """{"code":"rate_limited"}""").first.summarize("f", "a.pdf").toList()
        }
        val unknown = assertFailsWith<AnswerFailure.Server> {
            repository(status = HttpStatusCode.NotFound, body = "").first.summarize("f", "a.pdf").toList()
        }
        assertEquals("", unknown.reason, "요약 경로가 없는 예전 브릿지의 404 는 파일 없음이 아니다")
    }

    @Test
    fun `done 없이 끝난 답변은 받은 조각을 내보낸 뒤 닿지 못함으로 끝난다`() = runTest {
        val (repository, _) = repository(body = ": connected\n\ndata: {\"delta\": \"4시간\"}\n\ndata: {\"delta\": \"입니\"}\n\n")
        val events = mutableListOf<AnswerEvent>()
        assertFailsWith<AnswerFailure.Unreachable> { repository.ask(listOf(question)).collect { events += it } }
        assertEquals(listOf<AnswerEvent>(AnswerEvent.Delta("4시간"), AnswerEvent.Delta("입니")), events)
    }

    @Test
    fun `done 없이 끝난 요약도 받은 데까지 내보낸 뒤 닿지 못함으로 끝난다`() = runTest {
        val (repository, _) = repository(
            body = ": connected\n\nevent: progress\ndata: {\"done\": 1, \"total\": 2}\n\n: ping\n\ndata: {\"delta\": \"한 줄 요약\"}\n\n"
        )
        val events = mutableListOf<AnswerEvent>()
        assertFailsWith<AnswerFailure.Unreachable> { repository.summarize("f", "a.pdf").collect { events += it } }
        assertEquals(listOf(AnswerEvent.Progress(1, 2), AnswerEvent.Delta("한 줄 요약")), events)
    }

    @Test
    fun `done 뒤의 빈 줄 없이 본문이 끝나도 다 받은 것이다`() = runTest {
        val (repository, _) = repository(body = "data: {\"delta\": \"답\"}\n\nevent: done\ndata: {}")
        assertEquals(listOf(AnswerEvent.Delta("답")), repository.ask(listOf(question)).toList())
    }

    @Test
    fun `done 의 이름만 받고 끊기면 끝을 받지 못한 것이다`() = runTest {
        val (repository, _) = repository(body = "data: {\"delta\": \"답\"}\n\nevent: done\n")
        assertFailsWith<AnswerFailure.Unreachable> { repository.ask(listOf(question)).toList() }
    }

    @Test
    fun `중계가 끊겨 브릿지가 오류를 덧붙이면 조각 경계든 조각 중간이든 done 없이 끝난 것과 같은 닿지 못함이다`() = runTest {
        val appended = "\n\nevent: error\ndata: {\"message\":\"stream interrupted\",\"code\":\"stream_interrupted\"}\n\n" +
            "event: done\ndata: {}\n\n"
        val bodies = mapOf(
            "줄 경계" to "data: {\"delta\": \"앞\"}\n\n$appended",
            "줄 중간" to "data: {\"delta\": \"앞\"}\n\ndata: {\"delta\": \"잘$appended",
            "done 없음" to "data: {\"delta\": \"앞\"}\n\n",
        )
        for ((label, body) in bodies) {
            val repository = repository(body = body).first
            val summarized = mutableListOf<AnswerEvent>()
            assertFailsWith<AnswerFailure.Unreachable>(label) { repository.summarize("f", "a.txt").collect { summarized += it } }
            assertEquals(listOf<AnswerEvent>(AnswerEvent.Delta("앞")), summarized, label)
            assertFailsWith<AnswerFailure.Unreachable>(label) { repository.ask(listOf(question)).toList() }
        }
    }

    @Test
    fun `파일 거절은 상태가 아니라 브릿지 code 로 가린다`() = runTest {
        val proxyLimit = assertFailsWith<AnswerFailure.Server> {
            repository(status = HttpStatusCode.PayloadTooLarge, body = "").first.ask(listOf(question)).toList()
        }
        assertEquals("", proxyLimit.reason, "code 없는 413(중간 프록시)은 20MB 넘는 파일이 아니다")
        assertFailsWith<AnswerFailure.FileTooLarge> {
            repository(status = HttpStatusCode.PayloadTooLarge, body = """{"code":"file_too_large","message":""}""").first.summarize("f", "a.pdf").toList()
        }
        assertFailsWith<AnswerFailure.FileNotFound> {
            repository(status = HttpStatusCode.NotFound, body = """{"code":"file_not_found","message":""}""").first.summarize("f", "a.pdf").toList()
        }
    }

    @Test
    fun `로그인하지 않았으면 요약도 브릿지를 부르지 않는다`() = runTest {
        val (repository, requests) = repository(loggedIn = false)
        assertFailsWith<AnswerFailure.SessionExpired> { repository.summarize("f", "a.pdf").toList() }
        assertTrue(requests.isEmpty())
    }

    /**
     * 긴 답변·요약이 쌓인 대화도 후속 질문이 막히지 않는다 — 브릿지가 받는 상한(메시지 50개·본문 512KB) 안의 최근 기록만 보낸다.
     * 기록을 통째로 보내면 413 payload_too_large 로 그 대화의 후속 질문이 모두 막힌다.
     */
    @Test
    fun `긴 대화도 브릿지 상한 안의 최근 기록과 이번 질문만 보낸다`() = runTest {
        val (repository, requests) = repository()
        val long = "가".repeat(8_000)
        val history = (0 until 60).map { index ->
            ChatMessage(
                id = "m$index",
                conversationId = "c",
                role = if (index % 2 == 0) ChatRole.User else ChatRole.Assistant,
                content = long,
                createdAt = index.toLong(),
                summary = index < 2,
            )
        }
        val followUp = ChatMessage("q", "c", ChatRole.User, "이어서 알려 줘", createdAt = 100)

        repository.ask(history + followUp).toList()

        val body = requests[1]
        val sent = Json.decodeFromString(ChatRequest.serializer(), body).messages
        assertTrue(body.encodeToByteArray().size <= 512 * 1024, "본문이 ${body.encodeToByteArray().size} 바이트다")
        assertTrue(sent.size <= 50)
        assertEquals("이어서 알려 줘", sent.last().content)
        assertTrue(sent.size >= 10, "RAG 가 쓰는 최근 10개보다 적게 보냈다")
        assertTrue(sent.dropLast(1).all { it.content == long })
    }

    private companion object {
        val SUMMARY_SSE = """
            : connected

            event: progress
            data: {"done": 1, "total": 2}

            : ping

            event: progress
            data: {"done": 2, "total": 2}

            data: {"delta": "한 줄 요약"}

            data: {"delta": "\n### 주요 내용"}

            event: done
            data: {}

        """.trimIndent()

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
