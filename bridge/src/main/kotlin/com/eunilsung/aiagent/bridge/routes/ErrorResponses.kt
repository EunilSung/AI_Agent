package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.shared.api.ApiError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json

private val errorJson = Json { encodeDefaults = true }

/**
 * 거절을 [ApiError] JSON 으로 보낸다. 요청의 `Accept` 와 상관없이 늘 JSON 이다.
 *
 * 앱은 SSE 경로(`/v1/chat`·`/v1/summarize`)를 `Accept: text/event-stream` 으로 부른다. ContentNegotiation 의 `respond` 로
 * 보내면 그 Accept 에 맞는 변환기가 없어 406 이 나가고, 앱은 로그인 만료·한도·파일 없음 같은 이유를 알 수 없게 된다.
 */
suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: ApiError) {
    respondText(errorJson.encodeToString(ApiError.serializer(), error), ContentType.Application.Json, status)
}
