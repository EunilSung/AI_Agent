package com.eunilsung.aiagent.bridge.talk

import com.eunilsung.aiagent.bridge.http.networkBody
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.LoginRequest
import com.eunilsung.aiagent.shared.api.LoginResponse
import com.eunilsung.aiagent.shared.api.MAX_SUMMARY_FILE_BYTES
import com.eunilsung.aiagent.shared.api.TalkPersonDto
import com.eunilsung.aiagent.shared.api.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

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

    /**
     * 대화로 올라온 파일 하나를 받는다. Talk 서버는 그 파일이 있는 방의 사람에게만 내주고, 파일이 없거나 그 방 사람이
     * 아니면 404 `file_not_found` 로 거절한다. 토큰이 죽었으면 401 이다.
     *
     * 받은 파일은 메모리에 올리므로 [maxBytes] 를 넘으면 끝까지 읽지 않고 413 `file_too_large` 거절로 돌려준다 —
     * Talk 서버가 큰 파일 올리기를 거절할 때와 같은 상태·코드다. Content-Length 로 먼저 보고, 없으면 읽으면서 센다.
     *
     * 본문이 중간에 끊겼거나(Talk 재시작·네트워크 끊김) Content-Length 보다 짧으면 [TalkResult.Unreachable] 이다.
     * 잘린 파일을 요약하면 앱에는 앞부분만 읽은 요약이 정상 요약처럼 보이거나, 멀쩡한 파일이 깨진 파일로 안내된다.
     */
    suspend fun downloadFile(token: String, fileId: String, maxBytes: Long = MAX_SUMMARY_FILE_BYTES): TalkResult<ByteArray> =
        try {
            http.prepareGet("$baseUrl/files/${fileId.encodeURLPathPart()}") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.execute { response ->
                val declared = response.contentLength()
                when {
                    !response.status.isSuccess() -> rejected(response, response.bodyAsText())
                    declared != null && declared > maxBytes -> FILE_TOO_LARGE
                    else -> response.networkBody().readUpTo(maxBytes, declared)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TalkResult.Unreachable
        }

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
            rejected(response, body)
        }
    }

    private fun rejected(response: HttpResponse, body: String): TalkResult.Rejected {
        val error = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return TalkResult.Rejected(response.status.value, error?.code.orEmpty(), response.headers[HttpHeaders.RetryAfter])
    }

    /**
     * 본문을 끝까지 읽는다. [limit] 를 넘는 순간 더 읽지 않고 413 거절을 돌려준다. [expected] 는 Content-Length 다.
     *
     * 오류로 닫힌 채널에서도 `readAvailable` 은 예외 없이 -1 을 돌려줄 때가 있다(이미 닫혔으면 -1, 기다리던 중에 닫히면
     * 예외). 그래서 -1 을 받으면 닫힌 이유를 보고, 있으면 그 예외를 던져 어느 쪽이든 같은 길([TalkResult.Unreachable])로 간다.
     * 받은 크기가 Content-Length 와 다를 때도 끝까지 받지 못한 것이다.
     */
    private suspend fun ByteReadChannel.readUpTo(limit: Long, expected: Long?): TalkResult<ByteArray> {
        val out = ByteArrayOutputStream((expected ?: READ_BUFFER_BYTES.toLong()).coerceIn(0L, limit).toInt())
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = readAvailable(buffer)
            if (read == -1) break
            if (out.size().toLong() + read > limit) return FILE_TOO_LARGE
            out.write(buffer, 0, read)
        }
        closedCause?.let { throw it }
        if (expected != null && out.size().toLong() != expected) return TalkResult.Unreachable
        return TalkResult.Success(out.toByteArray())
    }

    private companion object {
        val FILE_TOO_LARGE = TalkResult.Rejected(HttpStatusCode.PayloadTooLarge.value, ErrorCode.FILE_TOO_LARGE)
        const val READ_BUFFER_BYTES = 64 * 1024
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
