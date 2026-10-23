package com.eunilsung.aiagent.shared.api

import kotlinx.serialization.Serializable

/**
 * 로그인 요청. 브릿지는 Talk 서버의 `/auth/login` 에 그대로 넘긴다 — 계정은 Talk 계정이다.
 */
@Serializable
data class LoginRequest(
    val id: String,
    val password: String,
)

/**
 * 로그인 응답. [token] 은 Talk 서버가 발급한 토큰이고, 이후 요청에 `Authorization: Bearer` 로 붙인다.
 */
@Serializable
data class LoginResponse(
    val token: String,
    val user: UserDto,
)

@Serializable
data class UserDto(
    val id: String,
    val name: String,
)

/** 실패 응답 본문. [code] 는 [ErrorCode] 중 하나다. */
@Serializable
data class ApiError(
    val code: String,
    val message: String = "",
)

/** 브릿지가 돌려주는 실패 코드. */
object ErrorCode {
    const val BAD_REQUEST = "bad_request"
    const val UNAUTHORIZED = "unauthorized"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val RATE_LIMITED = "rate_limited"
    const val TALK_UNREACHABLE = "talk_unreachable"
    const val RAG_UNREACHABLE = "rag_unreachable"
    const val NOT_FOUND = "not_found"
    const val FAILED = "failed"
}
