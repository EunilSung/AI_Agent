package com.eunilsung.aiagent.bridge.talk

import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.LoginRequest
import com.eunilsung.aiagent.shared.api.LoginResponse
import com.eunilsung.aiagent.shared.api.TalkPersonDto
import com.eunilsung.aiagent.shared.api.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Talk 서버를 부른다. 계정과 토큰은 Talk 서버가 정본이라, 브릿지는 로그인을 넘기고 토큰이 살아 있는지 묻기만 한다.
 */
class TalkClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun login(request: LoginRequest): TalkResult<LoginResponse> = call(
        { http.post("$baseUrl/auth/login") { contentType(ContentType.Application.Json); setBody(json.encodeToString(LoginRequest.serializer(), request)) } },
        { json.decodeFromString(LoginResponse.serializer(), it) },
    )

    /** 토큰의 주인. 토큰이 죽었으면 [TalkResult.Rejected]. */
    suspend fun me(token: String): TalkResult<UserDto> = call(
        { http.get("$baseUrl/users/me") { header(HttpHeaders.Authorization, "Bearer $token") } },
        { json.decodeFromString(UserDto.serializer(), it) },
    )

    suspend fun logout(token: String): Boolean = runCatching {
        http.post("$baseUrl/auth/logout") { header(HttpHeaders.Authorization, "Bearer $token") }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun isUp(): Boolean = runCatching { http.get("$baseUrl/health").status.isSuccess() }.getOrDefault(false)

    /** Talk 의 모든 사람. 토큰 주인의 눈으로 본 목록이다. */
    suspend fun people(token: String): TalkResult<List<TalkPersonDto>> = call(
        { http.get("$baseUrl/users") { header(HttpHeaders.Authorization, "Bearer $token") } },
        { json.decodeFromString(PeopleJson.serializer(), it).users },
    )

    /** [userId] 와의 1:1 방 id. 이미 있으면 Talk 서버가 그 방을 돌려준다. */
    suspend fun openDirectRoom(token: String, userId: String): TalkResult<String> = call(
        {
            http.post("$baseUrl/rooms") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(CreateRoomJson.serializer(), CreateRoomJson(listOf(userId))))
            }
        },
        { json.decodeFromString(RoomJson.serializer(), it).id },
    )

    /** 글 대화 하나를 보낸다. [messageId] 가 같으면 Talk 서버가 한 번만 받는다. */
    suspend fun sendText(token: String, roomId: String, messageId: String, content: String): TalkResult<Unit> = call(
        {
            http.post("$baseUrl/rooms/$roomId/messages") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SendMessageJson.serializer(), SendMessageJson(messageId, content)))
            }
        },
        { },
    )

    /** Talk 서버의 요청·응답 모양 중 브릿지가 쓰는 칸만 옮겨 왔다. Talk 의 `:shared` 와 이름을 맞춘다. */
    @Serializable
    private data class PeopleJson(val users: List<TalkPersonDto> = emptyList())

    @Serializable
    private data class CreateRoomJson(val memberIds: List<String>)

    @Serializable
    private data class RoomJson(val id: String)

    @Serializable
    private data class SendMessageJson(val id: String, val content: String, val kind: String = "text")

    private suspend fun <T> call(request: suspend () -> HttpResponse, decode: (String) -> T): TalkResult<T> {
        val response = runCatching { request() }.getOrElse { return TalkResult.Unreachable }
        val body = runCatching { response.bodyAsText() }.getOrElse { return TalkResult.Unreachable }
        return if (response.status.isSuccess()) {
            runCatching { TalkResult.Success(decode(body)) }.getOrElse { TalkResult.Unreachable }
        } else {
            val error = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
            TalkResult.Rejected(response.status.value, error?.code.orEmpty(), response.headers[HttpHeaders.RetryAfter])
        }
    }
}

/**
 * Talk 서버 응답. Talk 의 `ServerResult` 처럼 거절([Rejected], 다시 보내도 같은 답)과 닿지 못함([Unreachable])을 나눈다.
 */
sealed interface TalkResult<out T> {
    data class Success<T>(val value: T) : TalkResult<T>
    data class Rejected(val status: Int, val code: String, val retryAfter: String? = null) : TalkResult<Nothing>
    data object Unreachable : TalkResult<Nothing>
}
