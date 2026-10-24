package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.limit.RateLimiter
import com.eunilsung.aiagent.bridge.talk.TALK_AI_USER_ID
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.bridge.talk.TalkResult
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.MAX_SEND_CONTENT_LENGTH
import com.eunilsung.aiagent.shared.api.MAX_SEND_RECIPIENT_LENGTH
import com.eunilsung.aiagent.shared.api.PeopleResponse
import com.eunilsung.aiagent.shared.api.SendTalkMessageRequest
import com.eunilsung.aiagent.shared.api.SendTalkMessageResponse
import com.eunilsung.aiagent.shared.api.TalkPersonDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private const val MAX_REQUEST_ID_LENGTH = 100

/**
 * AI Agent 가 Talk 메신저에 메시지를 보낸다 — 로그인한 사람의 Talk 토큰으로 Talk 서버의 기존 API 를 부른다.
 *
 * 브릿지는 Talk 서버를 고치지 않고 Talk 앱이 하는 일을 대신한다. 그래서 Talk 에서는 로그인한 사람이 보낸 메시지로 보이고,
 * 누가 누구에게 보낼 수 있는지는 Talk 서버의 규칙을 그대로 따른다. 다만 Talk 의 AI 참여자 계정([TALK_AI_USER_ID])과 나
 * 자신에게는 보내지 않는다 — AI 계정과의 1:1 방은 말할 때마다 Talk 서버가 외부 AI(유료 키)를 부른다. 사람 찾기 결과에서도 뺀다.
 *
 * 사람 찾기는 부를 때마다 Talk 의 전체 사용자 목록을 받으므로 [peopleLimiter] 로 따로 센다. 보내기는 AI 대화와 같은 [limiter] 다.
 */
fun Route.talkRoutes(talk: TalkClient, verifier: TokenVerifier, limiter: RateLimiter, peopleLimiter: RateLimiter) {
    get("/v1/talk/people") {
        val me = call.requireUser(verifier) ?: return@get
        val query = call.request.queryParameters["q"].orEmpty()
        if (query.isBlank() || query.length > MAX_SEND_RECIPIENT_LENGTH) {
            call.respondError(HttpStatusCode.BadRequest, ApiError(ErrorCode.BAD_REQUEST))
            return@get
        }
        if (!peopleLimiter.tryAcquire(me.id)) {
            call.respondError(HttpStatusCode.TooManyRequests, ApiError(ErrorCode.RATE_LIMITED))
            return@get
        }
        when (val result = talk.people(call.bearerToken()!!)) {
            is TalkResult.Success -> call.respond(
                PeopleResponse(result.value.filter { !it.id.isSelfOrAi(me.id) && it.matches(query) }),
            )
            is TalkResult.Rejected -> call.respondTalkRejected(result)
            TalkResult.Unreachable -> call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
        }
    }

    post("/v1/talk/messages") {
        val me = call.requireUser(verifier) ?: return@post
        val request = call.receiveJsonOrReject(SendTalkMessageRequest.serializer(), BodyLimits.SEND_MESSAGE) {
            it.recipientId.isNotBlank() && !it.recipientId.isSelfOrAi(me.id) && it.content.isNotBlank() &&
                it.content.length <= MAX_SEND_CONTENT_LENGTH && it.requestId.isNotBlank() && it.requestId.length <= MAX_REQUEST_ID_LENGTH
        } ?: return@post
        if (!limiter.tryAcquire(me.id)) {
            call.respondError(HttpStatusCode.TooManyRequests, ApiError(ErrorCode.RATE_LIMITED))
            return@post
        }
        val token = call.bearerToken()!!
        val roomId = when (val room = talk.openDirectRoom(token, request.recipientId)) {
            is TalkResult.Success -> room.value
            is TalkResult.Rejected -> return@post call.respondTalkRejected(room)
            TalkResult.Unreachable -> return@post call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
        }
        when (val sent = talk.sendText(token, roomId, request.requestId, request.content)) {
            is TalkResult.Success -> {
                call.application.environment.log.info("talk-send user=${me.id} to=${request.recipientId} room=$roomId ${request.content.length}자")
                call.respond(SendTalkMessageResponse(roomId))
            }
            is TalkResult.Rejected -> call.respondTalkRejected(sent)
            TalkResult.Unreachable -> call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
        }
    }
}

/** Talk 의 AI 참여자 계정이거나 [me] 자신인지. Talk 아이디는 대소문자를 가리지 않는다. */
private fun String.isSelfOrAi(me: String): Boolean {
    val id = trim()
    return id.equals(TALK_AI_USER_ID, ignoreCase = true) || id.equals(me, ignoreCase = true)
}

/**
 * 사용자가 말한 받는 사람과 맞는지. "이서연(test2)", "이서연님", "test2" 처럼 이름과 아이디가 섞여 와도
 * 낱말로 나눠, 어느 낱말이든 아이디와 같거나 이름에 들어 있으면 맞는 사람이다. 두 글자 이상만 이름과 견준다.
 */
private fun TalkPersonDto.matches(query: String): Boolean {
    val words = query.split(Regex("[^\\p{L}\\p{N}]+"))
        .map { it.removeSuffix("님").removeSuffix("씨").lowercase() }
        .filter { it.isNotEmpty() }
    val compactName = name.replace(" ", "").lowercase()
    return words.any { word -> word == id.lowercase() || (word.length >= 2 && compactName.contains(word)) }
}

/** Talk 서버가 거절한 이유를 앱에 넘긴다. 없는 사람·참여하지 않은 방은 404 로 온다. */
private suspend fun ApplicationCall.respondTalkRejected(result: TalkResult.Rejected) {
    val code = when (result.status) {
        HttpStatusCode.Unauthorized.value -> ErrorCode.UNAUTHORIZED
        HttpStatusCode.NotFound.value -> ErrorCode.NOT_FOUND
        HttpStatusCode.BadRequest.value -> ErrorCode.BAD_REQUEST
        else -> ErrorCode.FAILED
    }
    respondError(HttpStatusCode.fromValue(result.status), ApiError(code))
}
