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

/**
 * 앱이 브릿지의 모든 요청에 싣는 접속 키 헤더. 값은 쓰는 사람이 앱의 로그인 화면에서 넣고(키를 넣어 만든 개발용 빌드는
 * `local.properties` 의 `bridge.accessKey` 가 기본 키), 브릿지는 같은 값일 때만 요청을 받는다([ErrorCode.CLIENT_KEY_INVALID]).
 * 공개된 테스트 계정만으로는 바깥에서 들어오지 못하게 한다.
 */
const val BRIDGE_KEY_HEADER = "X-Bridge-Key"

/** 브릿지가 돌려주는 실패 코드. */
object ErrorCode {
    /** 접속 키([BRIDGE_KEY_HEADER])가 없거나 틀렸다. 403. 앱의 로그인 화면에서 맞는 키를 다시 넣는다. */
    const val CLIENT_KEY_INVALID = "client_key_invalid"
    /** 요청 본문이 그 경로의 상한보다 크다. 413. */
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    /** 지금 처리 중인 일이 많아 받지 않았다(동시 실행 상한). 503. 잠시 뒤 다시 하면 된다. */
    const val BUSY = "busy"
    /** 이 계정은 이 브릿지를 쓸 수 없다(허용 사용자 목록 밖). 403. */
    const val USER_NOT_ALLOWED = "user_not_allowed"
    const val BAD_REQUEST = "bad_request"
    const val UNAUTHORIZED = "unauthorized"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val RATE_LIMITED = "rate_limited"
    const val TALK_UNREACHABLE = "talk_unreachable"
    const val RAG_UNREACHABLE = "rag_unreachable"
    const val NOT_FOUND = "not_found"
    /** 요약할 파일이 Talk 에 없거나, 그 파일이 있는 방의 사람이 아니다. */
    const val FILE_NOT_FOUND = "file_not_found"
    /** 요약할 파일이 [MAX_SUMMARY_FILE_BYTES] 보다 크다. */
    const val FILE_TOO_LARGE = "file_too_large"
    const val FAILED = "failed"
}
