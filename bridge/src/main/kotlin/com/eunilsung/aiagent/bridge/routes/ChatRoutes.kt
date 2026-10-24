package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.limit.ConcurrencyLimiter
import com.eunilsung.aiagent.bridge.limit.RateLimiter
import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.ChatRole
import com.eunilsung.aiagent.shared.api.ChatStreamEvent
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.MAX_SEND_CONTENT_LENGTH
import com.eunilsung.aiagent.shared.api.MAX_SEND_RECIPIENT_LENGTH
import com.eunilsung.aiagent.shared.api.StreamErrorCode
import com.eunilsung.aiagent.shared.api.StreamErrorEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json

/** 대화 기록이 이보다 길면 받지 않는다. RAG 서버는 이 중 최근 10개만 쓴다. */
private const val MAX_MESSAGES = 50
private const val MAX_CONTENT_LENGTH = 8_000

/** SSE data 를 만든다. 빈 code 도 빼지 않고 쓴다. */
private val eventJson = Json { encodeDefaults = true }

/**
 * RAG 서버와의 연결이 중간에 끊겼을 때 앱에 보내는 마지막 이벤트. RAG 서버가 실패를 알릴 때와 같은 모양
 * (`event: error` 뒤에 `event: done`)이다. code 는 [StreamErrorCode.INTERRUPTED] 라 앱은 `done` 없이 끝난 스트림처럼
 * "닿지 못함"으로 다룬다 — 끊긴 자리가 줄 경계든 줄 중간이든 앱이 띄우는 문구가 같다. message 는 기록용이라 영어이고
 * 화면에 쓰지 않는다([StreamErrorEvent.message]). 앞의 빈 줄 두 개는 끊긴 자리에서 쓰다 만 이벤트를 닫는다.
 */
private val INTERRUPTED_EVENTS: ByteArray = buildString {
    val error = eventJson.encodeToString(
        StreamErrorEvent.serializer(),
        StreamErrorEvent("stream interrupted", code = StreamErrorCode.INTERRUPTED),
    )
    append("\n\nevent: ${ChatStreamEvent.ERROR}\ndata: $error\n\n")
    append("event: ${ChatStreamEvent.DONE}\ndata: {}\n\n")
}.encodeToByteArray()

/**
 * AI 대화 — RAG 서버의 SSE 를 받는 대로 앱에 흘려보낸다.
 *
 * 브릿지는 SSE 내용을 해석하지 않는다. 조각을 받을 때마다 바로 내보내야 글자가 한 번에 몰려 나오지 않는다.
 * 답변을 다 보낼 때까지 [llm] 의 자리 하나([ConcurrencyLimiter.CHAT_WEIGHT])를 잡는다. 자리가 없으면 503 `busy` 다.
 */
fun Route.chatRoutes(rag: RagClient, verifier: TokenVerifier, limiter: RateLimiter, llm: ConcurrencyLimiter) {
    post("/v1/chat") {
        val user = call.requireUser(verifier) ?: return@post
        val request = call.receiveJsonOrReject(ChatRequest.serializer(), BodyLimits.CHAT) { it.isValid() }
            ?.withSendActionsCut()
            ?: return@post
        val permit = llm.acquire(user.id, ConcurrencyLimiter.CHAT_WEIGHT) ?: return@post call.respondBusy()
        try {
            if (!limiter.tryAcquire(user.id)) {
                call.respondError(HttpStatusCode.TooManyRequests, ApiError(ErrorCode.RATE_LIMITED))
                return@post
            }
            call.application.environment.log.info("chat user=${user.id} messages=${request.messages.size}")

            val opened = rag.streamChat(request) { body -> call.relaySse(body) }
            if (!opened) call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.RAG_UNREACHABLE))
        } finally {
            permit.release()
        }
    }
}

/**
 * RAG 서버의 SSE 본문을 받는 대로 앱에 흘려보낸다. 내용은 해석하지 않는다.
 *
 * 조각을 받을 때마다 바로 내보내야 글자가 한 번에 몰려 나오지 않고, 오래 걸리는 단계의 `: ping` 줄도 앱까지 닿아
 * 중간의 프록시·터널이 연결을 끊지 않는다.
 *
 * RAG 서버와의 연결이 중간에 끊기면 이미 200 을 보내기 시작한 뒤라 다른 응답을 보낼 수 없다. 그대로 끝내면 앱은 잘린
 * 답변을 완성본으로 저장하므로, 끝에 [INTERRUPTED_EVENTS] 를 덧붙여 실패로 알린다. 로그에는 예외 종류만 남긴다 —
 * HTTP 엔진의 예외 메시지에는 요청 URL 같은 내부 주소가 들어 있다.
 *
 * 다 옮길 때까지 돌아오지 않는다. 돌아오면 부른 쪽이 RAG 응답을 닫기 때문이다. Netty 는 응답을 다 쓴 뒤에야
 * `respondBytesWriter` 에서 돌아오지만, 테스트 엔진처럼 따로 쓰는 엔진도 있어 끝나기를 직접 기다린다.
 */
suspend fun ApplicationCall.relaySse(body: ByteReadChannel) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header("X-Accel-Buffering", "no")
    val log = application.environment.log
    val relayed = CompletableDeferred<Unit>()
    respondBytesWriter(contentType = ContentType.Text.EventStream) {
        try {
            val interruption = copySseFrom(body) ?: return@respondBytesWriter
            log.warn("RAG SSE 가 중간에 끊겨 error 이벤트로 끝냈다: {}", interruption::class.simpleName)
            writeFully(INTERRUPTED_EVENTS)
            flush()
        } finally {
            relayed.complete(Unit)
        }
    }
    relayed.await()
}

/**
 * [body] 를 끝까지 옮긴다. RAG 서버 쪽이 오류로 끊겼으면 그 이유를, 정상으로 끝났으면 null 을 돌려준다.
 *
 * 오류로 닫힌 채널에서도 `readAvailable` 은 예외 없이 -1 을 돌려줄 때가 있다(이미 닫혔으면 -1, 기다리던 중에 닫히면
 * 예외). 그래서 -1 이면 닫힌 이유도 본다. 앱 쪽에 쓰다 난 예외는 그대로 던진다 — 앱이 떠났으니 덧붙일 곳도 없다.
 */
private suspend fun ByteWriteChannel.copySseFrom(body: ByteReadChannel): Throwable? {
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = try {
            body.readAvailable(buffer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e
        }
        if (read == -1) return body.closedCause
        writeFully(buffer, 0, read)
        flush()
    }
}

/** 대화 기록이 받을 만한지. 보내기 카드 기록의 길이는 거르지 않고 [withSendActionsCut] 이 자른다. */
private fun ChatRequest.isValid(): Boolean =
    messages.isNotEmpty() &&
        messages.size <= MAX_MESSAGES &&
        messages.last().role == ChatRole.USER &&
        messages.all { (it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT) && it.content.length <= MAX_CONTENT_LENGTH } &&
        messages.last().content.isNotBlank()

/**
 * 대화 기록 속 보내기 카드([com.eunilsung.aiagent.shared.api.SendActionDto])의 받는 사람·내용을 [MAX_SEND_RECIPIENT_LENGTH]·
 * [MAX_SEND_CONTENT_LENGTH] 자(UTF-16 단위)로 자른다. RAG 서버는 이 값을 도구 호출 인자로 LLM 에 넣고 같은 상한을 넘으면
 * 받지 않는다. 예전 앱·RAG 서버는 카드 내용을 자르지 않았으므로, 긴 카드가 남은 대화도 거절하지 않고 계속 쓸 수 있게 자른다.
 */
private fun ChatRequest.withSendActionsCut(): ChatRequest = copy(
    messages = messages.map { message ->
        val action = message.sendAction ?: return@map message
        message.copy(
            sendAction = action.copy(
                recipient = action.recipient.cutTo(MAX_SEND_RECIPIENT_LENGTH),
                content = action.content.cutTo(MAX_SEND_CONTENT_LENGTH),
            ),
        )
    },
)

/** [limit] 자(UTF-16 단위) 안으로 자른다. 잘리는 자리에서 이모지처럼 두 단위를 쓰는 글자가 쪼개지면 그 글자는 뺀다. */
internal fun String.cutTo(limit: Int): String {
    if (length <= limit) return this
    val end = if (this[limit - 1].isHighSurrogate()) limit - 1 else limit
    return substring(0, end)
}
