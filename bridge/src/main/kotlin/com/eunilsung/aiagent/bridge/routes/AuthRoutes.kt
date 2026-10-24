package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.auth.Verification
import com.eunilsung.aiagent.bridge.limit.LoginLimiter
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** 아이디·비밀번호 길이의 끝. Talk 에 넘기기 전에 거른다 — 거대한 아이디는 Talk 의 잠금 기록에 그대로 남는다. */
private const val MAX_ID_LENGTH = 64
private const val MAX_PASSWORD_LENGTH = 128

/**
 * 로그인·로그아웃 — Talk 서버에 그대로 넘긴다.
 *
 * 로그인은 인증 없이 닿으므로 본문 크기([BodyLimits.LOGIN])·길이·시도 수([limiter], 보낸 주소마다와 전체)를 먼저 본다.
 * 허용 사용자 목록 밖의 계정이면 Talk 가 발급한 토큰을 바로 로그아웃시키고 403 [ErrorCode.USER_NOT_ALLOWED] 로 답한다.
 */
fun Route.authRoutes(talk: TalkClient, verifier: TokenVerifier, limiter: LoginLimiter) {
    post("/v1/auth/login") {
        val request = call.receiveJsonOrReject(LoginRequest.serializer(), BodyLimits.LOGIN, "아이디와 비밀번호가 필요합니다") {
            it.id.isNotBlank() && it.password.isNotBlank() && it.id.length <= MAX_ID_LENGTH && it.password.length <= MAX_PASSWORD_LENGTH
        } ?: return@post
        limiter.tryAcquire(call.clientAddress())?.let { retryAfter ->
            call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respondError(HttpStatusCode.TooManyRequests, ApiError(ErrorCode.TOO_MANY_ATTEMPTS))
            return@post
        }
        when (val result = talk.login(request)) {
            is TalkResult.Success -> {
                val user = result.value.user
                if (!verifier.isAllowed(user.id)) {
                    talk.logout(result.value.token)
                    call.application.environment.log.info("허용 목록 밖의 계정이라 로그인을 막고 토큰을 로그아웃시켰다 user=${user.id}")
                    call.respondError(HttpStatusCode.Forbidden, ApiError(ErrorCode.USER_NOT_ALLOWED))
                    return@post
                }
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
 * 허용 사용자 목록 밖의 계정이면 403 [ErrorCode.USER_NOT_ALLOWED] 다.
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
        Verification.NotAllowed -> {
            respondError(HttpStatusCode.Forbidden, ApiError(ErrorCode.USER_NOT_ALLOWED))
            null
        }
        Verification.Unknown -> {
            respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.TALK_UNREACHABLE))
            null
        }
    }
}

/** 지금 처리 중인 일이 많아 받지 않는다 — 503 [ErrorCode.BUSY]. 잠시 뒤 다시 하면 된다. */
suspend fun ApplicationCall.respondBusy() {
    response.header(HttpHeaders.RetryAfter, BUSY_RETRY_AFTER_SECONDS)
    respondError(HttpStatusCode.ServiceUnavailable, ApiError(ErrorCode.BUSY))
}

private const val BUSY_RETRY_AFTER_SECONDS = "5"
