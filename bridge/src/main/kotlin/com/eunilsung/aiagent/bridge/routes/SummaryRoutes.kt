package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.limit.ConcurrencyLimiter
import com.eunilsung.aiagent.bridge.limit.RateLimiter
import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.bridge.talk.TalkResult
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.SummarizeRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** Talk 의 파일 id 모양. Talk 는 UUID 를 쓰고, 앱도 링크를 받을 때 같은 규칙으로 거른다. */
private val FILE_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** 파일 이름의 최대 길이. Talk 서버가 받는 이름 길이와 같다. */
private const val MAX_FILE_NAME_LENGTH = 255

/**
 * Talk 파일 요약 — 로그인한 사람의 Talk 토큰으로 Talk 서버에서 파일을 받아 RAG 서버에 넘기고, RAG 서버의 SSE 를
 * 받는 대로 앱에 흘려보낸다.
 *
 * 파일을 볼 수 있는지는 Talk 서버가 정한다. 브릿지는 앱이 준 토큰을 그대로 써서, 그 사람이 Talk 앱에서 받을 수 있는
 * 파일만 받는다. 한도는 AI 대화와 같은 [limiter] 를 쓴다 — 요약도 LLM 을 붙잡는 일이다. 파일을 받기 전부터 요약을 다 보낼
 * 때까지 [llm] 의 자리를 대화보다 무겁게([ConcurrencyLimiter.SUMMARY_WEIGHT]) 잡는다 — 조각마다 LLM 을 부르고, 받은 파일
 * (최대 20MB)을 메모리에 들고 있다. 자리가 없으면 503 `busy` 다.
 * 로그에는 사람·파일 id·크기만 남기고 파일 이름과 내용은 남기지 않는다. RAG 서버에도 이름을 URL 이 아니라 헤더로 넘겨
 * ([RagClient.streamSummary]) 그쪽 접근 로그에 남지 않게 한다.
 */
fun Route.summaryRoutes(
    rag: RagClient,
    talk: TalkClient,
    verifier: TokenVerifier,
    limiter: RateLimiter,
    llm: ConcurrencyLimiter,
) {
    post("/v1/summarize") {
        val user = call.requireUser(verifier) ?: return@post
        val request = call.receiveJsonOrReject(SummarizeRequest.serializer(), BodyLimits.SUMMARIZE) { it.isValid() } ?: return@post
        val permit = llm.acquire(user.id, ConcurrencyLimiter.SUMMARY_WEIGHT) ?: return@post call.respondBusy()
        try {
            if (!limiter.tryAcquire(user.id)) {
                call.respondError(HttpStatusCode.TooManyRequests, ApiError(ErrorCode.RATE_LIMITED))
                return@post
            }
            val token = call.bearerToken()!!
            val bytes = when (val file = talk.downloadFile(token, request.fileId)) {
                is TalkResult.Success -> file.value
                is TalkResult.Rejected -> {
                    if (file.status == HttpStatusCode.Unauthorized.value) verifier.forget(token)
                    return@post call.respondFileRejected(file)
                }
                TalkResult.Unreachable ->
                    return@post call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
            }
            call.application.environment.log.info("summarize user=${user.id} file=${request.fileId} bytes=${bytes.size}")

            val opened = rag.streamSummary(request.fileName, bytes) { body -> call.relaySse(body) }
            if (!opened) call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.RAG_UNREACHABLE))
        } finally {
            permit.release()
        }
    }
}

private fun SummarizeRequest.isValid(): Boolean =
    FILE_ID.matches(fileId) &&
        fileName.isNotBlank() &&
        fileName.length <= MAX_FILE_NAME_LENGTH &&
        fileName.none { it.isISOControl() }

/**
 * Talk 서버가 파일을 내주지 않은 이유를 앱에 넘긴다. 없는 파일과 남의 방 파일은 Talk 가 똑같이 404 로 답하므로
 * 둘 다 [ErrorCode.FILE_NOT_FOUND] 다. 크기 상한은 [TalkClient.downloadFile] 이 413 으로 알려 준다.
 */
private suspend fun ApplicationCall.respondFileRejected(result: TalkResult.Rejected) {
    val code = when (result.status) {
        HttpStatusCode.Unauthorized.value -> ErrorCode.UNAUTHORIZED
        HttpStatusCode.NotFound.value -> ErrorCode.FILE_NOT_FOUND
        HttpStatusCode.PayloadTooLarge.value -> ErrorCode.FILE_TOO_LARGE
        HttpStatusCode.BadRequest.value -> ErrorCode.BAD_REQUEST
        else -> ErrorCode.FAILED
    }
    respondError(HttpStatusCode.fromValue(result.status), ApiError(code))
}
