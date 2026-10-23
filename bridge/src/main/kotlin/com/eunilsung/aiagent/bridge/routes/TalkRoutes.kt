package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.limit.RateLimiter
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.bridge.talk.TalkResult
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.PeopleResponse
import com.eunilsung.aiagent.shared.api.SendTalkMessageRequest
import com.eunilsung.aiagent.shared.api.SendTalkMessageResponse
import com.eunilsung.aiagent.shared.api.TalkPersonDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Talk 대화 하나의 최대 길이. Talk 서버의 제한과 같다. */
private const val MAX_CONTENT_LENGTH = 6_000
private const val MAX_REQUEST_ID_LENGTH = 100

/**
 * AI Agent 가 Talk 메신저에 메시지를 보낸다 — 로그인한 사람의 Talk 토큰으로 Talk 서버의 기존 API 를 부른다.
 *
 * 브릿지는 Talk 서버를 고치지 않고 Talk 앱이 하는 일을 대신한다. 그래서 Talk 에서는 로그인한 사람이 보낸 메시지로 보이고,
 * 누가 누구에게 보낼 수 있는지는 Talk 서버의 규칙을 그대로 따른다.
 */
fun Route.talkRoutes(talk: TalkClient, verifier: TokenVerifier, limiter: RateLimiter) {
    get("/v1/talk/people") {
        val me = call.requireUser(verifier) ?: return@get
        val query = call.request.queryParameters["q"].orEmpty()
        if (query.isBlank()) {
            call.respondError(HttpStatusCode.BadRequest, ApiError(ErrorCode.BAD_REQUEST))
            return@get
        }
        when (val result = talk.people(call.bearerToken()!!)) {
            is TalkResult.Success -> call.respond(PeopleResponse(result.value.filter { it.id != me.id && it.matches(query) }))
            is TalkResult.Rejected -> call.respondTalkRejected(result)
            TalkResult.Unreachable -> call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
        }
    }

    post("/v1/talk/messages") {
        val me = call.requireUser(verifier) ?: return@post
        val request = runCatching { call.receive<SendTalkMessageRequest>() }.getOrNull()
        if (request == null || request.recipientId.isBlank() || request.content.isBlank() ||
            request.content.length > MAX_CONTENT_LENGTH || request.requestId.isBlank() || request.requestId.length > MAX_REQUEST_ID_LENGTH
        ) {
            call.respondError(HttpStatusCode.BadRequest, ApiError(ErrorCode.BAD_REQUEST))
            return@post
        }
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
