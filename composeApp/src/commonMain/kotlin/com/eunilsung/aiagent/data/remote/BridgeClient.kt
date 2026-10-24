package com.eunilsung.aiagent.data.remote

import com.eunilsung.aiagent.data.local.AccessKeyStore
import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.domain.util.BridgeAddress
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.BRIDGE_KEY_HEADER
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.HealthResponse
import com.eunilsung.aiagent.shared.api.LoginRequest
import com.eunilsung.aiagent.shared.api.LoginResponse
import com.eunilsung.aiagent.shared.api.PeopleResponse
import com.eunilsung.aiagent.shared.api.SendTalkMessageRequest
import com.eunilsung.aiagent.shared.api.SendTalkMessageResponse
import com.eunilsung.aiagent.shared.api.SummarizeRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** 플랫폼 기본 브릿지 주소 — 에뮬레이터·시뮬레이터에서 이 Mac 에 닿는 주소. */
expect val platformDefaultBridgeUrl: String

/**
 * 브릿지 응답. Talk 의 `ServerResult` 와 같이 거절과 닿지 못함을 나눈다 — 앞쪽은 다시 보내도 같은 답이고,
 * 뒤쪽은 다시 보내면 될 수 있다.
 */
sealed interface ServerResult<out T> {
    data class Success<T>(val value: T) : ServerResult<T>
    data class Rejected(val status: Int, val code: String) : ServerResult<Nothing>
    data object Unreachable : ServerResult<Nothing>
}

/**
 * 앱 안에서만 쓰는 거절 code — 브릿지가 보내는 값이 아니다. 앱에 접속 키는 있지만 이 주소의 출처에 쓸 키가 없어 싣지 않았는데
 * 브릿지가 [ErrorCode.CLIENT_KEY_INVALID] 로 거절했을 때 그 code 대신 쓴다. 키가 틀린 것이 아니라 이 서버의 키를 넣지 않았다는
 * 뜻이라, 화면에 다른 안내를 띄운다.
 */
internal const val ACCESS_KEY_NOT_SENT = "app_access_key_not_sent"

/** SSE 대화·요약을 브릿지가 열지 않고 거절했다. [code] 는 [ErrorCode] 중 하나거나 [ACCESS_KEY_NOT_SENT] 다. */
class ChatRejectedException(val status: Int, val code: String) : Exception("chat rejected: $status $code")

/**
 * 브릿지 REST·SSE 클라이언트. 주소는 부를 때마다 [urlStore] 에서 읽어, 설정에서 바꾸면 바로 반영된다.
 *
 * 모든 요청(로그인·로그아웃·연결 확인 포함)에 접속 키 헤더([BRIDGE_KEY_HEADER])를 싣는다. 브릿지는 키가 맞을 때만 받는다 —
 * 공개된 테스트 계정만 아는 바깥 사람은 브릿지에 들어오지 못한다. 키는 [accessKeys] 가 요청 주소의 출처(`스킴://호스트:포트`)마다
 * 고른다 — 사용자가 로그인 화면에서 그 출처에 넣은 키, 아니면 빌드 때 넣은 키(빌드 기본 주소의 호스트와 로컬 주소에만). 보낼 키가
 * 없으면 헤더를 싣지 않고, 브릿지가 [ErrorCode.CLIENT_KEY_INVALID] 로 거절한다. 리다이렉트는 따라가지 않는다(플랫폼의
 * `HttpClient` 설정) — 따라가면 Ktor 가 키 헤더를 다른 호스트로 그대로 옮겨 싣는다.
 *
 * 키가 있는데 이 주소로는 싣지 않았고 브릿지가 키 없음으로 거절하면 [ACCESS_KEY_NOT_SENT] 로 알린다 — 키가 틀린 것이 아니라
 * 이 서버의 키를 넣지 않은 것이다.
 */
class BridgeClient(
    private val http: HttpClient,
    private val urlStore: BridgeUrlStore,
    private val accessKeys: AccessKeyStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val baseUrl: String get() = urlStore.url.value

    suspend fun login(id: String, password: String): ServerResult<LoginResponse> = request(LoginResponse.serializer()) {
        http.post("$baseUrl/v1/auth/login") {
            withAccessKey()
            jsonBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(id, password)))
        }
    }

    suspend fun logout(token: String) {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching { http.post("$baseUrl/v1/auth/logout") { bearer(token) } }
        }
    }

    suspend fun health(): ServerResult<HealthResponse> = request(HealthResponse.serializer()) {
        http.get("$baseUrl/v1/health") { withAccessKey() }
    }

    /**
     * AI 대화를 열어 SSE 이벤트를 하나씩 내보낸다. 연결이 끊기면 그 자리에서 끝난다.
     *
     * 브릿지가 거절하면 [ChatRejectedException], 닿지 못하면 통신 예외를 던진다.
     *
     * iOS(Darwin) 엔진은 응답 본문을 엔진의 문맥에서 읽어, `flow {}` 로 내보내면 Flow 규칙(같은 문맥에서만 emit)을 어긴다.
     * 그래서 어느 문맥에서든 보낼 수 있는 [channelFlow] 를 쓴다.
     */
    fun chat(token: String, request: ChatRequest): Flow<SseEvent> =
        stream(token, "/v1/chat", json.encodeToString(ChatRequest.serializer(), request))

    /**
     * Talk 파일 요약을 열어 SSE 이벤트를 하나씩 내보낸다. 모양은 [chat] 과 같다.
     *
     * 브릿지가 Talk 에서 파일을 받지 못하면 열지 않고 거절한다([ChatRejectedException] — 404 file_not_found, 413 file_too_large).
     */
    fun summarize(token: String, request: SummarizeRequest): Flow<SseEvent> =
        stream(token, "/v1/summarize", json.encodeToString(SummarizeRequest.serializer(), request))

    private fun stream(token: String, path: String, body: String): Flow<SseEvent> = channelFlow {
        http.preparePost("$baseUrl$path") {
            bearer(token)
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            jsonBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) throw ChatRejectedException(response.status.value, response.errorCode())
            val channel = response.bodyAsChannel()
            val parser = SseParser()
            while (true) {
                val line = channel.readLine() ?: break
                parser.feed(line)?.let { send(it) }
            }
            parser.finish()?.let { send(it) }
        }
    }

    suspend fun findPeople(token: String, query: String): ServerResult<PeopleResponse> = request(PeopleResponse.serializer()) {
        http.get("$baseUrl/v1/talk/people") {
            bearer(token)
            url { parameters.append("q", query) }
        }
    }

    suspend fun sendTalkMessage(token: String, request: SendTalkMessageRequest): ServerResult<SendTalkMessageResponse> =
        request(SendTalkMessageResponse.serializer()) {
            http.post("$baseUrl/v1/talk/messages") {
                bearer(token)
                jsonBody(json.encodeToString(SendTalkMessageRequest.serializer(), request))
            }
        }

    private suspend fun <T> request(serializer: KSerializer<T>, call: suspend () -> HttpResponse): ServerResult<T> {
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            try {
                val response = call()
                if (response.status.isSuccess()) {
                    ServerResult.Success(json.decodeFromString(serializer, response.bodyAsText()))
                } else {
                    ServerResult.Rejected(response.status.value, response.errorCode())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ServerResult.Unreachable
            }
        }
        return result ?: ServerResult.Unreachable
    }

    /** 거절 응답의 code. 키를 싣지 않은 출처가 키 없음으로 거절했으면 [ACCESS_KEY_NOT_SENT] 로 바꾼다. */
    private suspend fun HttpResponse.errorCode(): String {
        val code = runCatching { json.decodeFromString(ApiError.serializer(), bodyAsText()).code }.getOrDefault("")
        val url = call.request.url
        val withheld = accessKeys.hasAnyKey && accessKeys.keyFor(BridgeAddress.origin(url.protocol.name, url.host, url.port)) == null
        return if (code == ErrorCode.CLIENT_KEY_INVALID && withheld) ACCESS_KEY_NOT_SENT else code
    }

    /** 로그인한 요청 — 접속 키와 Talk 토큰을 함께 싣는다. */
    private fun HttpRequestBuilder.bearer(token: String) {
        withAccessKey()
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    /** 요청 주소의 출처로 보낼 접속 키가 있으면 싣는다. 주소에 포트를 적지 않았으면 스킴의 기본 포트다. */
    private fun HttpRequestBuilder.withAccessKey() {
        val port = url.port.takeIf { it != DEFAULT_PORT } ?: url.protocol.defaultPort
        accessKeys.keyFor(BridgeAddress.origin(url.protocol.name, url.host, port))?.let { header(BRIDGE_KEY_HEADER, it) }
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
