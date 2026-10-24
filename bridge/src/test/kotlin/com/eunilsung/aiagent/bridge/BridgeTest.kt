package com.eunilsung.aiagent.bridge

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.bridge.routes.BodyLimits
import com.eunilsung.aiagent.bridge.routes.cutTo
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.BRIDGE_KEY_HEADER
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.ChatStreamEvent
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.HealthResponse
import com.eunilsung.aiagent.shared.api.LoginResponse
import com.eunilsung.aiagent.shared.api.MAX_SEND_CONTENT_LENGTH
import com.eunilsung.aiagent.shared.api.MAX_SUMMARY_FILE_BYTES
import com.eunilsung.aiagent.shared.api.PeopleResponse
import com.eunilsung.aiagent.shared.api.SendActionDto
import com.eunilsung.aiagent.shared.api.SendTalkMessageResponse
import com.eunilsung.aiagent.shared.api.StreamErrorCode
import com.eunilsung.aiagent.shared.api.StreamErrorEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
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
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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
                loginBodies += body
                if ("\"test1\"" in body && "\"1234\"" in body) {
                    respondJson("""{"token":"talk-token","user":{"id":"test1","name":"김민준","positionSort":3}}""")
                } else {
                    respondJson("""{"code":"invalid_credentials","message":"x"}""", HttpStatusCode.Unauthorized)
                }
            }
            "/users/me" -> {
                meCalls++
                if (request.headers[HttpHeaders.Authorization] == "Bearer talk-token") respondJson("""{"id":"test1","name":"김민준"}""")
                else respondJson("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized)
            }
            "/auth/logout" -> {
                logouts += request.headers[HttpHeaders.Authorization].orEmpty()
                respond("", HttpStatusCode.NoContent)
            }
            "/health" -> respondJson("""{"status":"ok"}""")
            "/users" -> respondJson(
                """{"users":[{"id":"test1","name":"김민준"},{"id":"test2","name":"이서연","organName":"개발팀","positionName":"대리"},""" +
                    """{"id":"test3","name":"박서준"},{"id":"test4","name":"이서윤"},{"id":"ai","name":"AI 이서"}]}"""
            )
            "/rooms" -> {
                createdRooms += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respondJson("""{"id":"room-1-2","title":"","members":[]}""")
            }
            "/rooms/room-1-2/messages" -> {
                sentMessages += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respondJson("""{"roomId":"room-1-2","seq":1,"id":"x","senderId":"test1"}""")
            }
            "/files/$FILE_ID" -> {
                fileRequests += request.headers[HttpHeaders.Authorization].orEmpty()
                if (request.headers[HttpHeaders.Authorization] == "Bearer talk-token") {
                    respond(
                        content = FILE_BYTES,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.OctetStream.toString()),
                            HttpHeaders.ContentLength to listOf(FILE_BYTES.size.toString()),
                            HttpHeaders.ContentDisposition to listOf("attachment; filename=minutes.pdf"),
                        ),
                    )
                } else {
                    respondJson("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized)
                }
            }
            else ->
                if (request.url.encodedPath.startsWith("/files/")) {
                    fileRequests += request.headers[HttpHeaders.Authorization].orEmpty()
                    respondJson("""{"code":"file_not_found","message":"x"}""", HttpStatusCode.NotFound)
                } else {
                    respondJson("{}", HttpStatusCode.NotFound)
                }
        }
    }

    /** 가짜 Talk 서버가 받은 방 만들기·대화 보내기 본문, 로그인 요청 본문, 로그아웃 요청의 Authorization 헤더. */
    private val createdRooms = mutableListOf<String>()
    private val sentMessages = mutableListOf<String>()
    private val loginBodies = mutableListOf<String>()
    private val logouts = mutableListOf<String>()

    /** 가짜 Talk 서버가 받은 파일 받기 요청의 Authorization 헤더와, 토큰 주인 묻기 횟수. */
    private val fileRequests = mutableListOf<String>()
    private var meCalls = 0

    /**
     * 가짜 RAG 서버가 받은 요약 요청. [name] 은 [RagClient.FILE_NAME_HEADER] 헤더를 퍼센트 디코딩한 것, [rawName] 은
     * 헤더 값 그대로, [url] 은 요청 URL 전체다.
     */
    private class SummaryCall(
        val name: String?,
        val rawName: String?,
        val url: String,
        val contentType: ContentType?,
        val bytes: ByteArray,
    )
    private val summaryCalls = mutableListOf<SummaryCall>()

    /** 가짜 RAG 서버. */
    private val ragHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        when (request.url.encodedPath) {
            "/v1/health" -> respondJson("""{"status":"ok","chatModel":"gemma4:26b","embedModel":"bge-m3","chunks":7}""")
            "/v1/chat" -> respond(
                content = SSE,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
            "/v1/summarize" -> {
                val body = request.body as OutgoingContent.ByteArrayContent
                val rawName = request.headers[RagClient.FILE_NAME_HEADER]
                summaryCalls += SummaryCall(rawName?.decodeURLPart(), rawName, request.url.toString(), body.contentType, body.bytes())
                respond(
                    content = SUMMARY_SSE,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            }
            else -> respondJson("{}", HttpStatusCode.NotFound)
        }
    }

    /**
     * 가짜 Talk·RAG 서버를 끼운 브릿지. 기본 [client] 는 앱처럼 모든 요청에 접속 키([TEST_KEY])를 싣는다.
     * 키 없이 보내 보려면 [keylessClient] 를 쓴다.
     */
    private fun bridgeTest(
        config: BridgeConfig = KEYED,
        rag: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = ragHandler,
        talk: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = talkHandler,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            module(config, RagClient(HttpClient(MockEngine(rag)), "http://rag"), TalkClient(HttpClient(MockEngine(talk)), "http://talk"))
        }
        client = createClient { defaultRequest { header(BRIDGE_KEY_HEADER, TEST_KEY) } }
        block()
    }

    /** 접속 키를 싣지 않는 클라이언트 — 앱이 아닌 바깥 사람이다. */
    private fun ApplicationTestBuilder.keylessClient(): HttpClient = createClient { }

    /** 파일 받기만 [files] 가 답하고 나머지는 [talkHandler] 가 답하는 가짜 Talk 서버. */
    private fun talkWithFiles(
        files: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        if (request.url.encodedPath.startsWith("/files/")) files(this, request) else talkHandler(this, request)
    }

    /**
     * 끝없이 이어지는 큰 파일을 내주는 가짜 Talk 서버. 내보낸 바이트 수를 [produced] 에 센다 — 브릿지가 상한에서 읽기를
     * 멈추면 그 뒤로는 채널이 차서 더 내보내지 못한다. [declaredLength] 가 있으면 Content-Length 로 알린다.
     */
    private fun talkWithEndlessFile(produced: AtomicLong, declaredLength: Long?) = talkWithFileBody(declaredLength) {
        CoroutineScope(Dispatchers.IO).writer {
            val chunk = ByteArray(64 * 1024)
            while (produced.get() < 3 * MAX_SUMMARY_FILE_BYTES) {
                channel.writeFully(chunk)
                channel.flush()
                produced.addAndGet(chunk.size.toLong())
            }
        }.channel
    }

    /** 파일 받기에 [body] 가 만든 본문을 내주는 가짜 Talk 서버. [declaredLength] 가 있으면 Content-Length 로 알린다. */
    private fun talkWithFileBody(declaredLength: Long?, body: () -> ByteReadChannel) = talkWithFiles {
        val headers = listOfNotNull(
            HttpHeaders.ContentType to listOf(ContentType.Application.OctetStream.toString()),
            declaredLength?.let { HttpHeaders.ContentLength to listOf(it.toString()) },
        )
        respond(body(), HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
    }

    /** [path] 만 [body] 가 만든 SSE 본문으로 답하고 나머지는 [ragHandler] 가 답하는 가짜 RAG 서버. [body] 는 받은 요청을 본다. */
    private fun ragWithSse(
        path: String,
        body: (HttpRequestData) -> ByteReadChannel,
    ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        if (request.url.encodedPath == path) {
            respond(body(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
        } else {
            ragHandler(this, request)
        }
    }

    private suspend fun ApplicationTestBuilder.login(id: String = "test1", password: String = "1234"): HttpResponse =
        client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","password":"$password"}""")
        }

    private suspend fun ApplicationTestBuilder.chat(token: String?, body: String = CHAT_BODY): HttpResponse =
        client.post("/v1/chat") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.summarize(
        token: String?,
        body: String = SUMMARIZE_BODY,
    ): HttpResponse =
        client.post("/v1/summarize") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** 앱처럼 로그인 토큰으로 [path] 에 [body] 를 보낸다. */
    private suspend fun ApplicationTestBuilder.postAsApp(path: String, body: String): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer talk-token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /**
     * 앱이 받은 본문이 [prefix] 그대로 시작하고, 그 뒤가 code 가 [StreamErrorCode.INTERRUPTED] 인 error 이벤트와 done
     * 이벤트뿐인지 본다. 앱은 이 code 로 "닿지 못함" 문구를 고른다 — message 는 기록용이다.
     */
    private fun assertEndsWithInterruption(body: String, prefix: String = "") {
        assertTrue(body.startsWith(prefix), body)
        val tail = sseEvents(body.removePrefix(prefix))
        assertEquals(listOf(ChatStreamEvent.ERROR, ChatStreamEvent.DONE), tail.map { it.first }, body)
        val error = json.decodeFromString<StreamErrorEvent>(tail.first().second)
        assertEquals(StreamErrorCode.INTERRUPTED, error.code)
        assertTrue(error.message.isNotBlank(), body)
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

    /** 앱은 SSE 경로를 `Accept: text/event-stream` 으로 부른다. 거절이 406 으로 바뀌면 앱은 이유를 알 수 없다. */
    @Test
    fun `SSE 를 바라는 요청의 거절도 상태와 JSON 코드로 온다`() = bridgeTest {
        for (response in listOf(chat(token = null), summarize(token = null))) {
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals(ErrorCode.UNAUTHORIZED, json.decodeFromString<ApiError>(response.bodyAsText()).code)
        }
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
    fun `한 시간 한도를 넘기면 429`() = bridgeTest(config = KEYED.copy(chatPerUserHourly = 2)) {
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

    /** 본문 상한이 내용 길이 상한보다 작으면 정상 보내기가 413 으로 막힌다. 한글 끝 길이로 확인한다. */
    @Test
    fun `한글로 보낼 수 있는 가장 긴 내용도 본문 상한에 걸리지 않는다`() = bridgeTest {
        val content = "가".repeat(MAX_SEND_CONTENT_LENGTH)
        val response = client.post("/v1/talk/messages") {
            header(HttpHeaders.Authorization, "Bearer talk-token")
            contentType(ContentType.Application.Json)
            setBody("""{"recipientId":"test2","content":"$content","requestId":"req-long"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, sentMessages.size)
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

    @Test
    fun `요약은 Talk 에서 받은 파일을 RAG 서버에 넘기고 SSE 를 그대로 흘려보낸다`() = bridgeTest {
        val response = summarize("talk-token")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.EventStream.toString(), response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        assertEquals(SUMMARY_SSE, response.bodyAsText())

        val forwarded = summaryCalls.single()
        assertEquals(FILE_NAME, forwarded.name)
        assertEquals(ContentType.Application.OctetStream, forwarded.contentType?.withoutParameters())
        assertTrue(FILE_BYTES.contentEquals(forwarded.bytes))
    }

    @Test
    fun `요약 요청은 파일 이름을 URL 이 아니라 헤더에 퍼센트 인코딩해 싣는다`() = bridgeTest {
        assertEquals(HttpStatusCode.OK, summarize("talk-token").status)
        val forwarded = summaryCalls.single()
        assertEquals("http://rag/v1/summarize", forwarded.url, "URL 은 RAG 서버 접근 로그에 그대로 남는다")
        assertEquals(
            "%ED%9A%8C%EC%9D%98%EB%A1%9D%20Q%26A%20%2B%20%EA%B2%B0%EA%B3%BC%231.pdf",
            forwarded.rawName,
            "공백은 %20, + 는 %2B 라야 RAG 서버의 unquote 가 이름을 그대로 되살린다",
        )
        assertEquals(FILE_NAME, forwarded.name)
    }

    @Test
    fun `요약은 앱이 준 Talk 토큰을 그대로 Talk 서버에 쓴다`() = bridgeTest {
        assertEquals(HttpStatusCode.OK, summarize("talk-token").status)
        assertEquals(listOf("Bearer talk-token"), fileRequests)
    }

    @Test
    fun `토큰 없이 요약하면 401 이고 Talk 에서 파일을 받지 않는다`() = bridgeTest {
        assertEquals(HttpStatusCode.Unauthorized, summarize(token = null).status)
        val wrong = summarize(token = "wrong")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertTrue("unauthorized" in wrong.bodyAsText())
        assertTrue(fileRequests.isEmpty())
        assertTrue(summaryCalls.isEmpty())
    }

    @Test
    fun `파일을 받으려는데 Talk 토큰이 죽었으면 401 이고 다음 요청은 토큰을 다시 확인한다`() = bridgeTest(
        talk = talkWithFiles { respondJson("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized) },
    ) {
        val first = summarize("talk-token")
        assertEquals(HttpStatusCode.Unauthorized, first.status)
        assertTrue("unauthorized" in first.bodyAsText())
        assertEquals(1, meCalls)
        summarize("talk-token")
        assertEquals(2, meCalls)
        assertTrue(summaryCalls.isEmpty())
    }

    @Test
    fun `파일 id 나 이름이 이상하면 400 이고 Talk 에 묻지 않는다`() = bridgeTest {
        val bodies = listOf(
            """{"fileId":"../users/me","fileName":"a.pdf"}""",
            """{"fileId":"a/b","fileName":"a.pdf"}""",
            """{"fileId":"","fileName":"a.pdf"}""",
            """{"fileId":"${"a".repeat(129)}","fileName":"a.pdf"}""",
            """{"fileId":"$FILE_ID","fileName":"  "}""",
            """{"fileId":"$FILE_ID","fileName":"${"가".repeat(256)}"}""",
            """{"fileId":"$FILE_ID","fileName":"a
b.pdf"}""",
            """{"fileId":"$FILE_ID"}""",
            """not json""",
        )
        bodies.forEach { body ->
            val response = summarize("talk-token", body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue("bad_request" in response.bodyAsText(), body)
        }
        assertTrue(fileRequests.isEmpty())
        assertTrue(summaryCalls.isEmpty())
    }

    @Test
    fun `Talk 가 파일을 내주지 않으면 404 file_not_found`() = bridgeTest {
        val response = summarize("talk-token", """{"fileId":"someone-elses-file","fileName":"a.pdf"}""")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue("file_not_found" in response.bodyAsText())
        assertEquals(listOf("Bearer talk-token"), fileRequests)
        assertTrue(summaryCalls.isEmpty())
    }

    @Test
    fun `Content-Length 가 20MB 를 넘으면 읽지 않고 413 file_too_large`() {
        val produced = AtomicLong()
        bridgeTest(talk = talkWithEndlessFile(produced, declaredLength = MAX_SUMMARY_FILE_BYTES + 1)) {
            val response = summarize("talk-token")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue("file_too_large" in response.bodyAsText())
        }
        assertTrue(summaryCalls.isEmpty())
        assertTrue(produced.get() < MAX_SUMMARY_FILE_BYTES, "브릿지가 파일을 읽었다: ${produced.get()}")
    }

    @Test
    fun `Content-Length 없이 20MB 를 넘으면 상한에서 읽기를 멈추고 413 file_too_large`() {
        val produced = AtomicLong()
        bridgeTest(talk = talkWithEndlessFile(produced, declaredLength = null)) {
            val response = summarize("talk-token")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue("file_too_large" in response.bodyAsText())
        }
        assertTrue(summaryCalls.isEmpty())
        assertTrue(produced.get() < MAX_SUMMARY_FILE_BYTES + 4 * 1024 * 1024, "브릿지가 상한을 넘겨 읽었다: ${produced.get()}")
    }

    @Test
    fun `요약도 대화와 같은 한 시간 한도를 쓴다`() = bridgeTest(config = KEYED.copy(chatPerUserHourly = 2)) {
        assertEquals(HttpStatusCode.OK, chat("talk-token").status)
        assertEquals(HttpStatusCode.OK, summarize("talk-token").status)
        val third = summarize("talk-token")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertTrue("rate_limited" in third.bodyAsText())
        assertEquals(1, fileRequests.size)
    }

    @Test
    fun `파일을 받으려는데 Talk 서버에 닿지 못하면 503 talk_unreachable`() = bridgeTest(
        talk = talkWithFiles { error("connection refused") },
    ) {
        val response = summarize("talk-token")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue("talk_unreachable" in response.bodyAsText())
        assertTrue(summaryCalls.isEmpty())
    }

    @Test
    fun `요약하려는데 RAG 서버가 꺼져 있거나 거절하면 503 rag_unreachable`() {
        bridgeTest(rag = { error("connection refused") }) {
            val response = summarize("talk-token")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue("rag_unreachable" in response.bodyAsText())
        }
        bridgeTest(rag = { respondJson("""{"detail":"x"}""", HttpStatusCode.InternalServerError) }) {
            val response = summarize("talk-token")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue("rag_unreachable" in response.bodyAsText())
        }
    }

    @Test
    fun `Talk 파일 본문이 Content-Length 보다 짧게 끝나면 503 talk_unreachable 이고 RAG 서버를 부르지 않는다`() = bridgeTest(
        talk = talkWithFileBody(declaredLength = FILE_BYTES.size.toLong()) { ByteReadChannel(FILE_BYTES.copyOf(FILE_BYTES.size / 2)) },
    ) {
        val response = summarize("talk-token")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue("talk_unreachable" in response.bodyAsText())
        assertTrue(summaryCalls.isEmpty())
    }

    @Test
    fun `Talk 파일 본문이 오류로 끊기면 503 talk_unreachable 이고 RAG 서버를 부르지 않는다`() {
        val bodies = listOf<Pair<String, () -> ByteReadChannel>>(
            "처음부터 끊김" to { brokenBody(IOException("unexpected end of stream")) },
            "보내던 중 끊김" to { bodyCutAfter(FILE_BYTES.copyOf(100_000), IOException("connection reset")) },
        )
        bodies.forEach { (label, body) ->
            bridgeTest(talk = talkWithFileBody(declaredLength = null, body)) {
                val response = summarize("talk-token")
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, label)
                assertTrue("talk_unreachable" in response.bodyAsText(), label)
            }
            assertTrue(summaryCalls.isEmpty(), label)
        }
    }

    @Test
    fun `정확히 20MB 인 파일은 받아 RAG 서버에 그대로 넘기고 1바이트라도 넘으면 413`() {
        val exact = ByteArray(MAX_SUMMARY_FILE_BYTES.toInt()) { (it % 251).toByte() }
        listOf(MAX_SUMMARY_FILE_BYTES, null).forEach { declared ->
            summaryCalls.clear()
            bridgeTest(talk = talkWithFileBody(declared) { ByteReadChannel(exact) }) {
                assertEquals(HttpStatusCode.OK, summarize("talk-token").status, "Content-Length=$declared")
            }
            assertTrue(exact.contentEquals(summaryCalls.single().bytes), "Content-Length=$declared")
        }
        summaryCalls.clear()
        bridgeTest(talk = talkWithFileBody(declaredLength = null) { ByteReadChannel(exact.copyOf(exact.size + 1)) }) {
            val response = summarize("talk-token")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue("file_too_large" in response.bodyAsText())
        }
        assertTrue(summaryCalls.isEmpty())
    }

    @Test
    fun `RAG 서버가 끊긴 본문을 주면 대화도 요약도 error 이벤트로 끝을 알리고 500 을 내지 않는다`() {
        listOf("/v1/chat" to CHAT_BODY, "/v1/summarize" to SUMMARIZE_BODY).forEach { (path, body) ->
            val logs = capturedLogs {
                bridgeTest(rag = ragWithSse(path) { brokenBody(IOException("connection reset")) }) {
                    val response = postAsApp(path, body)
                    assertEquals(HttpStatusCode.OK, response.status, path)
                    assertEndsWithInterruption(response.bodyAsText())
                }
            }
            assertTrue(logs.any { it.level == Level.WARN }, path)
            assertTrue(logs.none { it.level == Level.ERROR }, logs.joinToString("\n") { it.fullText() })
        }
    }

    @Test
    fun `RAG 서버가 보내던 중에 끊기면 받은 데까지 흘려보낸 뒤 error 이벤트로 끝을 알린다`() {
        listOf("/v1/chat" to CHAT_BODY, "/v1/summarize" to SUMMARIZE_BODY).forEach { (path, body) ->
            val logs = capturedLogs {
                bridgeTest(rag = ragWithSse(path) { bodyCutAfter(PARTIAL_SSE.encodeToByteArray(), IOException("connection reset")) }) {
                    val response = postAsApp(path, body)
                    assertEquals(HttpStatusCode.OK, response.status, path)
                    assertEndsWithInterruption(response.bodyAsText(), prefix = PARTIAL_SSE)
                }
            }
            assertTrue(logs.any { it.level == Level.WARN }, path)
            assertTrue(logs.none { it.level == Level.ERROR }, logs.joinToString("\n") { it.fullText() })
        }
    }

    @Test
    fun `RAG 중계가 끊겨도 로그에 요청 URL 과 파일 이름을 남기지 않는다`() {
        var ragUrl = ""
        val logs = capturedLogs {
            bridgeTest(rag = ragWithSse("/v1/summarize") { request ->
                ragUrl = request.url.toString()
                brokenBody(IOException("Socket timeout has expired [url=$ragUrl, socket_timeout=unknown] ms"))
            }) {
                assertEndsWithInterruption(summarize("talk-token").bodyAsText())
            }
        }
        assertEquals("http://rag/v1/summarize", ragUrl)
        assertTrue(logs.any { it.level == Level.WARN })
        assertLogsHide(logs, "http://rag", FILE_NAME, FILE_NAME.encodeURLParameter())
    }

    @Test
    fun `RAG 응답을 연 뒤에 난 오류는 true 로 끝내고 로그에 요청 URL 과 파일 이름을 남기지 않는다`() {
        var ragUrl = ""
        val rag = RagClient(HttpClient(MockEngine { request -> ragUrl = request.url.toString(); ragHandler(this, request) }), "http://rag")
        val logs = capturedLogs {
            val opened = runBlocking {
                rag.streamSummary(FILE_NAME, FILE_BYTES) { throw IOException("Socket timeout has expired [url=$ragUrl] ms") }
            }
            assertTrue(opened)
        }
        assertEquals("http://rag/v1/summarize", ragUrl)
        assertTrue(logs.any { it.level == Level.WARN })
        assertLogsHide(logs, "http://rag", FILE_NAME, FILE_NAME.encodeURLParameter())
    }


    /** [response] 가 [status] 와 JSON [code] 의 거절인지 본다. */
    private suspend fun assertRefused(response: HttpResponse, status: HttpStatusCode, code: String, label: String = "") {
        assertEquals(status, response.status, label)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters(), label)
        assertEquals(code, json.decodeFromString<ApiError>(response.bodyAsText()).code, label)
    }

    /**
     * [path] 를 [release] 가 끝날 때까지 붙잡아 두는 가짜 RAG 서버. 요청이 닿으면 [started] 가 끝난다 — 그 뒤로 브릿지는
     * LLM 자리를 잡고 있다.
     */
    private fun ragHeldUntil(path: String, started: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>, body: String) =
        ragWithSse(path) {
            started.complete(Unit)
            CoroutineScope(Dispatchers.IO).writer {
                release.await()
                channel.writeFully(body.encodeToByteArray())
            }.channel
        }

    private suspend fun ApplicationTestBuilder.loginFrom(forwardedFor: String, id: String = "test1", password: String = "1234") =
        client.post("/v1/auth/login") {
            header("X-Forwarded-For", forwardedFor)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","password":"$password"}""")
        }

    @Test
    fun `접속 키가 없거나 틀리면 모든 경로가 403 client_key_invalid 이고 Talk 와 RAG 에 닿지 않는다`() {
        var ragCalls = 0
        bridgeTest(rag = { request -> ragCalls++; ragHandler(this, request) }) {
            val outsider = keylessClient()
            val calls: List<Pair<String, suspend (HttpClient) -> HttpResponse>> = listOf(
                "health" to { it.get("/v1/health") },
                "login" to { it.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"id":"test1","password":"1234"}""") } },
                "logout" to { it.post("/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer talk-token") } },
                "chat" to {
                    it.post("/v1/chat") {
                        header(HttpHeaders.Authorization, "Bearer talk-token")
                        header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                        contentType(ContentType.Application.Json)
                        setBody(CHAT_BODY)
                    }
                },
                "summarize" to {
                    it.post("/v1/summarize") {
                        header(HttpHeaders.Authorization, "Bearer talk-token")
                        header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                        contentType(ContentType.Application.Json)
                        setBody(SUMMARIZE_BODY)
                    }
                },
                "people" to { it.get("/v1/talk/people?q=test2") { header(HttpHeaders.Authorization, "Bearer talk-token") } },
                "send" to {
                    it.post("/v1/talk/messages") {
                        header(HttpHeaders.Authorization, "Bearer talk-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"recipientId":"test2","content":"안녕","requestId":"req-k"}""")
                    }
                },
                "unknown" to { it.get("/v1/nothing-here") },
            )
            for ((label, call) in calls) {
                assertRefused(call(outsider), HttpStatusCode.Forbidden, ErrorCode.CLIENT_KEY_INVALID, "$label 키 없음")
                for (wrong in listOf("wrong", TEST_KEY.dropLast(1), TEST_KEY + "x", TEST_KEY.uppercase(), "")) {
                    val response = call(createClient { defaultRequest { header(BRIDGE_KEY_HEADER, wrong) } })
                    assertRefused(response, HttpStatusCode.Forbidden, ErrorCode.CLIENT_KEY_INVALID, "$label 틀린 키 '$wrong'")
                }
            }
        }
        assertEquals(0, ragCalls)
        assertEquals(0, meCalls)
        assertTrue(loginBodies.isEmpty() && logouts.isEmpty() && fileRequests.isEmpty() && sentMessages.isEmpty())
    }

    @Test
    fun `맞는 접속 키를 실으면 상태 확인과 로그인이 된다`() = bridgeTest {
        assertEquals(HttpStatusCode.OK, client.get("/v1/health").status)
        assertEquals(HttpStatusCode.OK, login().status)
    }

    @Test
    fun `접속 키 없이 띄운 시험용 브릿지는 키를 보지 않는다`() = bridgeTest(config = BridgeConfig(allowNoKey = true)) {
        assertEquals(HttpStatusCode.OK, keylessClient().get("/v1/health").status)
    }

    @Test
    fun `접속 키 없이 띄운 시험용 브릿지도 프록시(Funnel)를 거쳐 온 요청은 403 client_key_invalid`() =
        bridgeTest(config = BridgeConfig(allowNoKey = true)) {
            val outsider = keylessClient()
            for (name in listOf("X-Forwarded-For", "x-forwarded-proto", "Forwarded", "X-Real-IP", "Via", "Tailscale-Funnel-Request")) {
                val health = outsider.get("/v1/health") { header(name, "203.0.113.7") }
                assertRefused(health, HttpStatusCode.Forbidden, ErrorCode.CLIENT_KEY_INVALID, name)
                val login = outsider.post("/v1/auth/login") {
                    header(name, "203.0.113.7")
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"test1","password":"1234"}""")
                }
                assertRefused(login, HttpStatusCode.Forbidden, ErrorCode.CLIENT_KEY_INVALID, name)
            }
            assertTrue(loginBodies.isEmpty())
            assertEquals(HttpStatusCode.OK, outsider.get("/v1/health").status)
        }

    @Test
    fun `접속 키가 짧은 설정으로는 브릿지가 뜨지 않는다`() {
        val error = assertFails {
            testApplication {
                application {
                    module(
                        BridgeConfig(accessKey = "x".repeat(BridgeConfig.MIN_ACCESS_KEY_LENGTH - 1)),
                        RagClient(HttpClient(MockEngine(ragHandler)), "http://rag"),
                        TalkClient(HttpClient(MockEngine(talkHandler)), "http://talk"),
                    )
                }
                startApplication()
            }
        }
        assertTrue(generateSequence(error) { it.cause }.any { "at least" in it.message.orEmpty() }, error.toString())
    }

    @Test
    fun `접속 키가 없는 설정으로는 브릿지가 뜨지 않는다`() {
        val error = assertFails {
            testApplication {
                application {
                    module(BridgeConfig(), RagClient(HttpClient(MockEngine(ragHandler)), "http://rag"), TalkClient(HttpClient(MockEngine(talkHandler)), "http://talk"))
                }
                startApplication()
            }
        }
        assertTrue(generateSequence(error) { it.cause }.any { "access key" in it.message.orEmpty() }, error.toString())
    }

    @Test
    fun `허용 사용자 목록 밖의 계정은 로그인이 403 user_not_allowed 이고 받은 토큰은 바로 로그아웃시킨다`() =
        bridgeTest(config = KEYED.copy(allowedUserIds = setOf("test2"))) {
            assertRefused(login(), HttpStatusCode.Forbidden, ErrorCode.USER_NOT_ALLOWED)
            assertEquals(listOf("Bearer talk-token"), logouts)
            assertRefused(chat("talk-token"), HttpStatusCode.Forbidden, ErrorCode.USER_NOT_ALLOWED)
            assertRefused(summarize("talk-token"), HttpStatusCode.Forbidden, ErrorCode.USER_NOT_ALLOWED)
            val send = postAsApp("/v1/talk/messages", """{"recipientId":"test2","content":"안녕","requestId":"req-a"}""")
            assertRefused(send, HttpStatusCode.Forbidden, ErrorCode.USER_NOT_ALLOWED)
            assertTrue(fileRequests.isEmpty() && summaryCalls.isEmpty() && sentMessages.isEmpty())
        }

    @Test
    fun `허용 사용자 목록은 대소문자를 가리지 않는다`() = bridgeTest(config = KEYED.copy(allowedUserIds = setOf("TEST1", "test2"))) {
        assertEquals(HttpStatusCode.OK, login().status)
        assertEquals(HttpStatusCode.OK, chat("talk-token").status)
        assertTrue(logouts.isEmpty())
    }

    @Test
    fun `아이디 64자 비밀번호 128자를 넘으면 400 이고 Talk 에 넘기지 않는다`() = bridgeTest {
        assertRefused(login(id = "a".repeat(65)), HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST)
        assertRefused(login(password = "p".repeat(129)), HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST)
        assertTrue(loginBodies.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, login(id = "a".repeat(64), password = "p".repeat(128)).status)
        assertEquals(1, loginBodies.size)
    }

    @Test
    fun `한 주소에서 1분 한도를 넘긴 로그인은 429 too_many_attempts 이고 다른 주소는 된다`() =
        bridgeTest(config = KEYED.copy(loginPerIpPerMinute = 3, loginGlobalPerSecond = 100)) {
            repeat(3) { assertEquals(HttpStatusCode.Unauthorized, loginFrom("203.0.113.5", password = "0000").status) }
            val fourth = loginFrom("203.0.113.5")
            assertRefused(fourth, HttpStatusCode.TooManyRequests, ErrorCode.TOO_MANY_ATTEMPTS)
            assertTrue((fourth.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 0) in 1..60, fourth.headers.toString())
            assertEquals(3, loginBodies.size)
            assertEquals(HttpStatusCode.OK, loginFrom("203.0.113.6").status)
        }

    /** Funnel 은 자기가 본 주소를 X-Forwarded-For 맨 뒤에 둔다. 앞에 지어 넣은 값을 바꿔 가며 보내도 한도를 피하지 못한다. */
    @Test
    fun `X-Forwarded-For 앞에 지어 넣은 주소로는 로그인 한도를 피하지 못한다`() =
        bridgeTest(config = KEYED.copy(loginPerIpPerMinute = 2, loginGlobalPerSecond = 100)) {
            val statuses = (1..3).map { loginFrom("10.0.0.$it, 198.51.100.7", password = "0000").status }
            assertEquals(listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests), statuses)
            assertEquals(2, loginBodies.size)
        }

    @Test
    fun `모든 주소를 합친 1초 한도를 넘긴 로그인은 429`() = bridgeTest(config = KEYED.copy(loginPerIpPerMinute = 100, loginGlobalPerSecond = 2)) {
        val statuses = (1..3).map { loginFrom("192.0.2.$it").status }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.TooManyRequests), statuses)
        assertEquals(2, loginBodies.size)
    }

    @Test
    fun `경로마다 본문 상한을 넘으면 413 payload_too_large 이고 뒤로 넘기지 않는다`() {
        var ragCalls = 0
        bridgeTest(rag = { request -> ragCalls++; ragHandler(this, request) }) {
            val bigLogin = """{"id":"test1","password":"1234","pad":"${"a".repeat(BodyLimits.LOGIN.toInt())}"}"""
            assertRefused(
                client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(bigLogin) },
                HttpStatusCode.PayloadTooLarge, ErrorCode.PAYLOAD_TOO_LARGE, "login",
            )
            val bigChat = """{"messages":[{"role":"user","content":"${"가".repeat(BodyLimits.CHAT.toInt() / 3 + 1)}"}]}"""
            assertRefused(chat("talk-token", bigChat), HttpStatusCode.PayloadTooLarge, ErrorCode.PAYLOAD_TOO_LARGE, "chat")
            val bigSummary = """{"fileId":"$FILE_ID","fileName":"a.pdf","pad":"${"a".repeat(BodyLimits.SUMMARIZE.toInt())}"}"""
            assertRefused(summarize("talk-token", bigSummary), HttpStatusCode.PayloadTooLarge, ErrorCode.PAYLOAD_TOO_LARGE, "summarize")
            val bigSend = """{"recipientId":"test2","content":"안녕","requestId":"r","pad":"${"a".repeat(BodyLimits.SEND_MESSAGE.toInt())}"}"""
            assertRefused(postAsApp("/v1/talk/messages", bigSend), HttpStatusCode.PayloadTooLarge, ErrorCode.PAYLOAD_TOO_LARGE, "send")
        }
        assertEquals(0, ragCalls)
        assertTrue(loginBodies.isEmpty() && fileRequests.isEmpty() && sentMessages.isEmpty())
    }

    /** Content-Length 없이(chunked) 보내도 읽으면서 세어 상한에서 끊는다. */
    @Test
    fun `Content-Length 없는 본문도 상한을 넘으면 413`() = bridgeTest {
        val body = """{"id":"test1","password":"1234","pad":"${"a".repeat(64 * 1024)}"}""".encodeToByteArray()
        val response = client.post("/v1/auth/login") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    body.toList().chunked(1024).forEach { channel.writeFully(it.toByteArray()); channel.flush() }
                }
            })
        }
        assertRefused(response, HttpStatusCode.PayloadTooLarge, ErrorCode.PAYLOAD_TOO_LARGE)
        assertTrue(loginBodies.isEmpty())
        val small = client.post("/v1/auth/login") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeFully("""{"id":"test1","password":"1234"}""".encodeToByteArray())
                }
            })
        }
        assertEquals(HttpStatusCode.OK, small.status)
    }

    @Test
    fun `JSON 이 아닌 본문은 400`() = bridgeTest {
        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Text.Plain)
            setBody("""{"id":"test1","password":"1234"}""")
        }
        assertRefused(response, HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST)
        assertTrue(loginBodies.isEmpty())
    }

    @Test
    fun `대화 기록 속 보내기 카드가 상한보다 길면 거절하지 않고 잘라서 RAG 에 넘긴다`() {
        val ragBodies = mutableListOf<String>()
        fun history(recipient: String, content: String) =
            """{"messages":[{"role":"user","content":"보내줘"},""" +
                """{"role":"assistant","content":"","sendAction":{"recipient":"$recipient","content":"$content","sent":false}},""" +
                """{"role":"user","content":"다시"}]}"""
        val emoji = "\uD83D\uDE00"
        bridgeTest(rag = { request ->
            if (request.url.encodedPath == "/v1/chat") ragBodies += (request.body as TextContent).text
            ragHandler(this, request)
        }) {
            assertEquals(HttpStatusCode.OK, chat("talk-token", history("가".repeat(101), "나".repeat(6_001))).status)
            assertEquals(HttpStatusCode.OK, chat("talk-token", history("이서연", "a" + emoji.repeat(3_000))).status)
            assertEquals(HttpStatusCode.OK, chat("talk-token", history("가".repeat(100), "나".repeat(6_000))).status)
        }
        val actions = ragBodies.map { body -> json.decodeFromString<ChatRequest>(body).messages[1].sendAction!! }
        assertEquals("가".repeat(100), actions[0].recipient)
        assertEquals("나".repeat(6_000), actions[0].content)
        assertEquals("a" + emoji.repeat(2_999), actions[1].content, "이모지를 쪼개지 않는다")
        assertEquals("가".repeat(100) to "나".repeat(6_000), actions[2].recipient to actions[2].content)
    }

    @Test
    fun `글자 수 자르기는 UTF-16 단위로 재고 짝을 이룬 글자를 쪼개지 않는다`() {
        val emoji = "\uD83D\uDE00"
        assertEquals("짧다", "짧다".cutTo(10))
        assertEquals("가나", "가나다".cutTo(2))
        assertEquals("a", "a$emoji".cutTo(2))
        assertEquals("a$emoji", "a${emoji}b".cutTo(3))
    }

    @Test
    fun `대화 기록의 요약 표시는 RAG 서버에 그대로 넘긴다`() {
        val ragBodies = mutableListOf<String>()
        val history = """{"messages":[{"role":"user","content":"회의록.pdf 요약해 줘","summary":true},""" +
            """{"role":"assistant","content":"회의 요약","summary":true},{"role":"user","content":"핵심만 다시"}]}"""
        bridgeTest(rag = { request ->
            if (request.url.encodedPath == "/v1/chat") ragBodies += (request.body as TextContent).text
            ragHandler(this, request)
        }) {
            assertEquals(HttpStatusCode.OK, chat("talk-token", history).status)
        }
        val forwarded = json.decodeFromString<ChatRequest>(ragBodies.single())
        assertEquals(listOf(true, true, false), forwarded.messages.map { it.summary })
    }

    @Test
    fun `한 사람이 동시에 돌리는 대화가 상한이면 503 busy 이고 끝나면 다시 된다`() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        bridgeTest(
            config = KEYED.copy(llmPerUserConcurrency = 1, llmWaitMillis = 100),
            rag = ragHeldUntil("/v1/chat", started, release, SSE),
        ) {
            coroutineScope {
                val first = async { chat("talk-token") }
                started.await()
                val second = chat("talk-token")
                assertRefused(second, HttpStatusCode.ServiceUnavailable, ErrorCode.BUSY)
                assertTrue(second.headers[HttpHeaders.RetryAfter] != null)
                release.complete(Unit)
                assertEquals(SSE, first.await().bodyAsText())
            }
            assertEquals(HttpStatusCode.OK, chat("talk-token").status)
        }
    }

    @Test
    fun `자리가 없어도 잠깐은 기다렸다가 비면 받는다`() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        bridgeTest(
            config = KEYED.copy(llmPerUserConcurrency = 1, llmWaitMillis = 5_000),
            rag = ragHeldUntil("/v1/chat", started, release, SSE),
        ) {
            coroutineScope {
                val first = async { chat("talk-token") }
                started.await()
                val second = async { chat("talk-token") }
                delay(300)
                release.complete(Unit)
                assertEquals(HttpStatusCode.OK, first.await().status)
                assertEquals(HttpStatusCode.OK, second.await().status)
            }
        }
    }

    @Test
    fun `요약은 대화보다 무겁게 세어 전체 자리를 다 쓰면 대화도 503 busy`() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        bridgeTest(
            config = KEYED.copy(llmConcurrency = 2, llmPerUserConcurrency = 2, llmWaitMillis = 100),
            rag = ragHeldUntil("/v1/summarize", started, release, SUMMARY_SSE),
        ) {
            coroutineScope {
                val summary = async { summarize("talk-token") }
                started.await()
                assertRefused(chat("talk-token"), HttpStatusCode.ServiceUnavailable, ErrorCode.BUSY)
                assertRefused(summarize("talk-token"), HttpStatusCode.ServiceUnavailable, ErrorCode.BUSY)
                release.complete(Unit)
                assertEquals(SUMMARY_SSE, summary.await().bodyAsText())
            }
            assertEquals(HttpStatusCode.OK, chat("talk-token").status)
            assertEquals(1, fileRequests.size, "자리를 얻지 못한 요약은 Talk 에서 파일을 받지 않는다")
        }
    }

    @Test
    fun `실패한 요청도 LLM 자리를 돌려준다`() = bridgeTest(
        config = KEYED.copy(llmPerUserConcurrency = 1, llmWaitMillis = 100, chatPerUserHourly = 1),
        rag = { error("connection refused") },
    ) {
        assertRefused(chat("talk-token"), HttpStatusCode.ServiceUnavailable, ErrorCode.RAG_UNREACHABLE)
        assertRefused(chat("talk-token"), HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED)
        assertRefused(summarize("talk-token"), HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `Talk 의 AI 계정이나 나 자신에게는 보내지 않는다`() = bridgeTest {
        for (recipient in listOf("ai", "AI", " ai ", "test1", "TEST1")) {
            val response = postAsApp("/v1/talk/messages", """{"recipientId":"$recipient","content":"안녕","requestId":"req-ai"}""")
            assertRefused(response, HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, recipient)
        }
        assertTrue(createdRooms.isEmpty() && sentMessages.isEmpty())
    }

    @Test
    fun `사람 찾기 결과에 Talk 의 AI 계정은 나오지 않는다`() = bridgeTest {
        suspend fun find(q: String) = json.decodeFromString<PeopleResponse>(
            client.get("/v1/talk/people") {
                url { parameters.append("q", q) }
                header(HttpHeaders.Authorization, "Bearer talk-token")
            }.bodyAsText()
        ).people.map { it.id }

        assertEquals(emptyList(), find("ai"))
        assertEquals(listOf("test2", "test4"), find("AI 이서"))
        assertEquals(listOf("test2", "test4"), find("이서"))
    }

    @Test
    fun `사람 찾기도 한 시간 한도가 있다`() = bridgeTest(config = KEYED.copy(peoplePerUserHourly = 1)) {
        suspend fun find() = client.get("/v1/talk/people?q=test2") { header(HttpHeaders.Authorization, "Bearer talk-token") }
        assertEquals(HttpStatusCode.OK, find().status)
        assertRefused(find(), HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED)
        assertEquals(HttpStatusCode.OK, chat("talk-token").status, "사람 찾기 한도는 대화 한도와 따로 센다")
    }

    @Test
    fun `사람 찾기 검색어가 100자를 넘으면 400`() = bridgeTest {
        val response = client.get("/v1/talk/people") {
            url { parameters.append("q", "가".repeat(101)) }
            header(HttpHeaders.Authorization, "Bearer talk-token")
        }
        assertRefused(response, HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST)
    }

    private companion object {
        /** 시험용 접속 키. 실제 키는 저장소 밖에 있다. */
        const val TEST_KEY = "fake-bridge-key-for-unit-tests-only-0123456789"
        val KEYED = BridgeConfig(accessKey = TEST_KEY)
        const val CHAT_BODY = """{"messages":[{"role":"user","content":"반차는 몇 시간이야?"}]}"""
        const val FILE_ID = "3f2b8c1e-7d4a-4f6b-9a0e-2c5d8e1f4a7b"
        /** 공백·&·+·# 이 섞여도 RAG 서버에 그대로 닿아야 한다. */
        const val FILE_NAME = "회의록 Q&A + 결과#1.pdf"
        const val SUMMARIZE_BODY = """{"fileId":"$FILE_ID","fileName":"$FILE_NAME"}"""
        /** 끊기기 전까지 RAG 서버가 보낸 SSE. */
        const val PARTIAL_SSE = ": connected\n\ndata: {\"delta\": \"앞부분\"}\n\n"
        val FILE_BYTES = ByteArray(300_000) { (it % 251).toByte() }
        val SUMMARY_SSE = """
            : connected

            event: progress
            data: {"done": 1, "total": 2}

            : ping

            event: progress
            data: {"done": 2, "total": 2}

            data: {"delta": "회의 결과 요약"}

            event: done
            data: {}


        """.trimIndent()
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

/**
 * 처음부터 [cause] 로 끊긴 본문. 이때 `readAvailable` 은 예외 없이 -1 을 돌려주므로, 읽는 쪽이 `closedCause` 를 보지
 * 않으면 정상으로 끝난 본문과 구별하지 못한다.
 */
private fun brokenBody(cause: Throwable): ByteReadChannel = ByteChannel().apply { cancel(cause) }

/**
 * [prefix] 를 내준 뒤 다음 읽기에서 [cause] 를 던지는 본문 — 상대 서버가 보내던 중에 재시작되거나 연결이 리셋된 경우다.
 * 읽는 쪽이 [prefix] 를 다 읽고 더 기다릴 때 끊기므로, 이때 `readAvailable` 은 -1 이 아니라 예외를 던진다.
 */
private fun bodyCutAfter(prefix: ByteArray, cause: IOException): ByteReadChannel {
    var sent = false
    val network = object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (sent) throw cause
            sent = true
            sink.write(prefix)
            return prefix.size.toLong()
        }

        override fun close() = Unit
    }
    return ByteReadChannel(network.buffered())
}

/** SSE 본문을 (이벤트 이름, data) 로 나눈다. 주석 줄과 data 없는 묶음은 버린다. */
private fun sseEvents(body: String): List<Pair<String?, String>> =
    body.split("\n\n").mapNotNull { block ->
        val lines = block.lines().filter { it.isNotEmpty() && !it.startsWith(":") }
        val data = lines.filter { it.startsWith("data:") }.ifEmpty { return@mapNotNull null }
        lines.firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim() to
            data.joinToString("\n") { it.removePrefix("data:").trim() }
    }

/** [block] 이 도는 동안 남은 모든 로그. */
private inline fun capturedLogs(block: () -> Unit): List<ILoggingEvent> {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
        block()
    } finally {
        root.detachAppender(appender)
    }
    return synchronized(appender) { appender.list.toList() }
}

/** 로그 한 줄의 글과, 함께 남긴 예외들의 메시지. */
private fun ILoggingEvent.fullText(): String = buildString {
    append(formattedMessage)
    var proxy = throwableProxy
    while (proxy != null) {
        append('\n').append(proxy.className).append(": ").append(proxy.message)
        proxy = proxy.cause
    }
}

/** [logs] 어디에도 [secrets] 가 없는지 본다. */
private fun assertLogsHide(logs: List<ILoggingEvent>, vararg secrets: String) {
    logs.forEach { event ->
        val text = event.fullText()
        secrets.forEach { secret -> assertTrue(secret !in text, "로그에 남으면 안 되는 값이 있다: $text") }
    }
}
