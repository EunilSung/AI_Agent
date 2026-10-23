package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.auth.Verification
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.bridge.talk.TalkResult
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.LoginRequest
import com.eunilsung.aiagent.shared.api.LoginResponse
import com.eunilsung.aiagent.shared.api.UserDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** 로그인·로그아웃 — Talk 서버에 그대로 넘긴다. */
fun Route.authRoutes(talk: TalkClient, verifier: TokenVerifier) {
    post("/v1/auth/login") {
        val request = runCatching { call.receive<LoginRequest>() }.getOrNull()
        if (request == null || request.id.isBlank() || request.password.isBlank()) {
            call.respondError(HttpStatusCode.BadRequest, ApiError(ErrorCode.BAD_REQUEST, "아이디와 비밀번호가 필요합니다"))
            return@post
        }
        when (val result = talk.login(request)) {
            is TalkResult.Success -> {
                val user = result.value.user
                call.respond(LoginResponse(result.value.token, UserDto(user.id, user.name)))
            }
            is TalkResult.Rejected -> when (result.status) {
                HttpStatusCode.TooManyRequests.value -> {
                    result.retryAfter?.let { call.response.header(HttpHeaders.RetryAfter, it) }
                    call.respondError(HttpStatusCode.TooManyRequests, ApiError(ErrorCode.TOO_MANY_ATTEMPTS))
                }
                HttpStatusCode.Unauthorized.value ->
                    call.respondError(HttpStatusCode.Unauthorized, ApiError(ErrorCode.INVALID_CREDENTIALS))
                else -> call.respondError(HttpStatusCode.fromValue(result.status), ApiError(ErrorCode.FAILED))
            }
            TalkResult.Unreachable -> call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
        }
    }

    post("/v1/auth/logout") {
        val token = call.bearerToken()
        if (token != null) {
            verifier.forget(token)
            talk.logout(token)
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** `Authorization: Bearer <토큰>` 의 토큰. */
fun ApplicationCall.bearerToken(): String? =
    request.header(HttpHeaders.Authorization)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 토큰의 주인을 확인한다. 확인하지 못했으면 응답을 보내고 null 을 돌려준다 — 부른 쪽은 그대로 끝내면 된다.
 */
suspend fun ApplicationCall.requireUser(verifier: TokenVerifier): UserDto? {
    val token = bearerToken()
    if (token == null) {
        respondError(HttpStatusCode.Unauthorized, ApiError(ErrorCode.UNAUTHORIZED))
        return null
    }
    return when (val verification = verifier.verify(token)) {
        is Verification.Valid -> verification.user
        Verification.Invalid -> {
            respondError(HttpStatusCode.Unauthorized, ApiError(ErrorCode.UNAUTHORIZED))
            null
        }
        Verification.Unknown -> {
            respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
            null
        }
    }
}
