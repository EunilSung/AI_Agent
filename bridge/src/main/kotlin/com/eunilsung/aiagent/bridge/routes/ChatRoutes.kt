package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.limit.RateLimiter
import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.ChatRole
import com.eunilsung.aiagent.shared.api.ErrorCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully

/** 대화 기록이 이보다 길면 받지 않는다. RAG 서버는 이 중 최근 10개만 쓴다. */
private const val MAX_MESSAGES = 50
private const val MAX_CONTENT_LENGTH = 8_000

/**
 * AI 대화 — RAG 서버의 SSE 를 받는 대로 앱에 흘려보낸다.
 *
 * 브릿지는 SSE 내용을 해석하지 않는다. 조각을 받을 때마다 바로 내보내야 글자가 한 번에 몰려 나오지 않는다.
 */
fun Route.chatRoutes(rag: RagClient, verifier: TokenVerifier, limiter: RateLimiter) {
    post("/v1/chat") {
        val user = call.requireUser(verifier) ?: return@post
        val request = runCatching { call.receive<ChatRequest>() }.getOrNull()
        if (request == null || !request.isValid()) {
            call.respond(HttpStatusCode.BadRequest, ApiError(ErrorCode.BAD_REQUEST))
            return@post
        }
        if (!limiter.tryAcquire(user.id)) {
            call.respond(HttpStatusCode.TooManyRequests, ApiError(ErrorCode.RATE_LIMITED))
            return@post
        }
        call.application.environment.log.info("chat user=${user.id} messages=${request.messages.size}")

        val opened = rag.streamChat(request) { body ->
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header("X-Accel-Buffering", "no")
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = body.readAvailable(buffer)
                    if (read == -1) break
                    writeFully(buffer, 0, read)
                    flush()
                }
            }
        }
        if (!opened) call.respond(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.RAG_UNREACHABLE))
    }
}

private fun ChatRequest.isValid(): Boolean =
    messages.isNotEmpty() &&
        messages.size <= MAX_MESSAGES &&
        messages.last().role == ChatRole.USER &&
        messages.all { (it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT) && it.content.length <= MAX_CONTENT_LENGTH } &&
        messages.last().content.isNotBlank()
