package com.eunilsung.aiagent.bridge

import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.HealthResponse
import com.eunilsung.aiagent.shared.api.LoginResponse
import com.eunilsung.aiagent.shared.api.PeopleResponse
import com.eunilsung.aiagent.shared.api.SendActionDto
import com.eunilsung.aiagent.shared.api.SendTalkMessageResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 브릿지 경로 테스트. Talk 서버와 RAG 서버는 [MockEngine] 이 대신한다 — 요청이 어디로 어떻게 넘어가고,
 * 그 답이 앱·Talk 서버에 어떤 모양으로 돌아가는지를 본다.
 */
class BridgeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 가짜 Talk 서버 — test1/1234 만 받고, 그때 준 토큰만 살아 있다. */
    private val talkHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        when (request.url.encodedPath) {
            "/auth/login" -> {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                if ("\"test1\"" in body && "\"1234\"" in body) {
                    respondJson("""{"token":"talk-token","user":{"id":"test1","name":"김민준","positionSort":3}}""")
                } else {
                    respondJson("""{"code":"invalid_credentials","message":"x"}""", HttpStatusCode.Unauthorized)
                }
            }
            "/users/me" ->
                if (request.headers[HttpHeaders.Authorization] == "Bearer talk-token") respondJson("""{"id":"test1","name":"김민준"}""")
                else respondJson("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized)
            "/health" -> respondJson("""{"status":"ok"}""")
            "/users" -> respondJson(
                """{"users":[{"id":"test1","name":"김민준"},{"id":"test2","name":"이서연","organName":"개발팀","positionName":"대리"},""" +
                    """{"id":"test3","name":"박서준"},{"id":"test4","name":"이서윤"}]}"""
            )
            "/rooms" -> {
                createdRooms += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respondJson("""{"id":"room-1-2","title":"","members":[]}""")
            }
            "/rooms/room-1-2/messages" -> {
                sentMessages += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respondJson("""{"roomId":"room-1-2","seq":1,"id":"x","senderId":"test1"}""")
            }
            else -> respondJson("{}", HttpStatusCode.NotFound)
        }
    }

    /** 가짜 Talk 서버가 받은 방 만들기·대화 보내기 본문. */
    private val createdRooms = mutableListOf<String>()
    private val sentMessages = mutableListOf<String>()

    /** 가짜 RAG 서버. */
    private val ragHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        when (request.url.encodedPath) {
            "/v1/health" -> respondJson("""{"status":"ok","chatModel":"gemma4:26b","embedModel":"bge-m3","chunks":7}""")
            "/v1/chat" -> respond(
                content = SSE,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
            else -> respondJson("{}", HttpStatusCode.NotFound)
        }
    }

    private fun bridgeTest(
        config: BridgeConfig = BridgeConfig(),
        rag: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = ragHandler,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            module(config, RagClient(HttpClient(MockEngine(rag)), "http://rag"), TalkClient(HttpClient(MockEngine(talkHandler)), "http://talk"))
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.login(id: String = "test1", password: String = "1234"): HttpResponse =
        client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","password":"$password"}""")
        }

    private suspend fun ApplicationTestBuilder.chat(token: String?, body: String = CHAT_BODY): HttpResponse =
        client.post("/v1/chat") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `Talk 계정으로 로그인하면 Talk 토큰과 이름을 돌려준다`() = bridgeTest {
        val response = login()
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<LoginResponse>(response.bodyAsText())
        assertEquals("talk-token", body.token)
        assertEquals("김민준", body.user.name)
    }

    @Test
    fun `비밀번호가 틀리면 401 invalid_credentials`() = bridgeTest {
        val response = login(password = "0000")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue("invalid_credentials" in response.bodyAsText())
    }

    @Test
    fun `토큰 없이 대화하면 401`() = bridgeTest {
        assertEquals(HttpStatusCode.Unauthorized, chat(token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, chat(token = "wrong").status)
    }

    @Test
    fun `대화는 RAG 서버의 SSE 를 그대로 흘려보낸다`() = bridgeTest {
        val response = chat(token = "talk-token")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.EventStream.toString(), response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        assertEquals(SSE, response.bodyAsText())
    }

    @Test
    fun `보내기 카드 기록은 sendAction 그대로 RAG 서버에 넘긴다`() {
        val ragBodies = mutableListOf<String>()
        val history = """{"messages":[{"role":"user","content":"이서연에게 회의 10시라고 보내줘"},""" +
            """{"role":"assistant","content":"","sendAction":{"recipient":"이서연","content":"회의는 10시입니다.","sent":true}},""" +
            """{"role":"user","content":"박도윤한테도 보내줘"}]}"""
        bridgeTest(rag = { request ->
            if (request.url.encodedPath == "/v1/chat") ragBodies += (request.body as TextContent).text
            ragHandler(this, request)
        }) {
            assertEquals(HttpStatusCode.OK, chat("talk-token", history).status)
        }
        val forwarded = json.decodeFromString<ChatRequest>(ragBodies.single())
        assertEquals(SendActionDto("이서연", "회의는 10시입니다.", sent = true), forwarded.messages[1].sendAction)
        assertEquals(null, forwarded.messages[2].sendAction)
    }

    @Test
    fun `마지막 메시지가 사용자 질문이 아니면 400`() = bridgeTest {
        val response = chat("talk-token", """{"messages":[{"role":"assistant","content":"안녕하세요"}]}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `한 시간 한도를 넘기면 429`() = bridgeTest(config = BridgeConfig(chatPerUserHourly = 2)) {
        assertEquals(HttpStatusCode.OK, chat("talk-token").status)
        assertEquals(HttpStatusCode.OK, chat("talk-token").status)
        val third = chat("talk-token")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertTrue("rate_limited" in third.bodyAsText())
    }

    @Test
    fun `RAG 서버가 꺼져 있으면 503 rag_unreachable`() = bridgeTest(rag = { error("connection refused") }) {
        val response = chat("talk-token")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue("rag_unreachable" in response.bodyAsText())
    }

    @Test
    fun `사람 찾기는 이름 일부·님·아이디·이름(아이디)로 찾고 나는 뺀다`() = bridgeTest {
        suspend fun find(q: String) = json.decodeFromString<PeopleResponse>(
            client.get("/v1/talk/people") {
                url { parameters.append("q", q) }
                header(HttpHeaders.Authorization, "Bearer talk-token")
            }.bodyAsText()
        ).people.map { it.id }

        assertEquals(listOf("test2"), find("이서연님"))
        assertEquals(listOf("test2", "test4"), find("이서"))
        assertEquals(listOf("test3"), find("TEST3"))
        assertEquals(listOf("test2"), find("이서연(test2)"))
        assertEquals(emptyList(), find("김민준"))
    }

    @Test
    fun `메시지 보내기는 1대1 방을 열고 앱이 준 id 로 보낸다`() = bridgeTest {
        val response = client.post("/v1/talk/messages") {
            header(HttpHeaders.Authorization, "Bearer talk-token")
            contentType(ContentType.Application.Json)
            setBody("""{"recipientId":"test2","content":"내일 회의는 10시입니다.","requestId":"req-1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("room-1-2", json.decodeFromString<SendTalkMessageResponse>(response.bodyAsText()).roomId)
        assertEquals(listOf("""{"memberIds":["test2"]}"""), createdRooms)
        assertEquals(listOf("""{"id":"req-1","content":"내일 회의는 10시입니다.","kind":"text"}"""), sentMessages)
    }

    @Test
    fun `로그인하지 않으면 사람 찾기도 보내기도 못 한다`() = bridgeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/talk/people?q=이서연").status)
        val send = client.post("/v1/talk/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"recipientId":"test2","content":"안녕","requestId":"req-2"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, send.status)
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun `빈 내용은 보내지 않는다`() = bridgeTest {
        val response = client.post("/v1/talk/messages") {
            header(HttpHeaders.Authorization, "Bearer talk-token")
            contentType(ContentType.Application.Json)
            setBody("""{"recipientId":"test2","content":"  ","requestId":"req-3"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun `상태 확인은 RAG 모델과 Talk 서버 연결을 알려 준다`() = bridgeTest {
        val health = json.decodeFromString<HealthResponse>(client.get("/v1/health").bodyAsText())
        assertEquals("gemma4:26b", health.rag?.chatModel)
        assertEquals(7, health.rag?.chunks)
        assertTrue(health.talk)
    }

    private companion object {
        const val CHAT_BODY = """{"messages":[{"role":"user","content":"반차는 몇 시간이야?"}]}"""
        val SSE = """
            : connected

            event: sources
            data: {"sources": [{"source": "01_휴가규정.md", "score": 0.69}]}

            data: {"delta": "4시간"}

            event: done
            data: {}


        """.trimIndent()
    }
}

private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
