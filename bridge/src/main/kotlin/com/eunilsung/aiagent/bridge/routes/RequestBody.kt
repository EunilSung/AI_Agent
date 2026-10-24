package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.http.FORWARDED_FOR
import com.eunilsung.aiagent.bridge.http.clientAddress
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ErrorCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * 경로마다 받는 요청 본문 크기의 끝(바이트). 넘으면 413 [ErrorCode.PAYLOAD_TOO_LARGE] 다.
 *
 * 브릿지는 본문을 메모리에 다 올린 뒤 객체로 바꾼다. 상한이 없으면 인증 없이 닿는 로그인에도 수십 MB 를 밀어 넣을 수 있다.
 */
object BodyLimits {
    const val LOGIN = 4L * 1024
    /** 보내기 내용은 [com.eunilsung.aiagent.shared.api.MAX_SEND_CONTENT_LENGTH] 자까지다. 한글은 UTF-8 로 한 글자 3바이트라 그 세 배에 여유를 둔다. */
    const val SEND_MESSAGE = 32L * 1024
    const val SUMMARIZE = 4L * 1024
    /** 대화 기록 전체. 메시지 50개까지 받지만 RAG 서버는 최근 10개만 쓴다. */
    const val CHAT = 512L * 1024
}

private val bodyJson = Json { ignoreUnknownKeys = true }

/**
 * JSON 본문을 [limit] 바이트까지만 읽어 [serializer] 로 바꾸고 [isValid] 로 거른다. 받지 못했으면 거절 응답을 보내고
 * null 을 돌려준다 — 부른 쪽은 그대로 끝내면 된다.
 *
 * Content-Length 가 [limit] 보다 크면 읽지 않고 413 이다. Content-Length 가 없는(chunked) 본문은 읽으면서 세어 [limit] 를
 * 넘는 순간 그만 읽는다. JSON 이 아니거나 모양이 틀리거나 [isValid] 가 거르면 400 [ErrorCode.BAD_REQUEST] 이고,
 * 그 응답의 message 는 [invalidMessage] 다(기록용).
 */
suspend fun <T> ApplicationCall.receiveJsonOrReject(
    serializer: KSerializer<T>,
    limit: Long,
    invalidMessage: String = "",
    isValid: (T) -> Boolean = { true },
): T? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limit) {
        respondError(HttpStatusCode.PayloadTooLarge, ApiError(ErrorCode.PAYLOAD_TOO_LARGE))
        return null
    }
    if (runCatching { request.contentType().match(ContentType.Application.Json) }.getOrDefault(false).not()) {
        respondError(HttpStatusCode.BadRequest, ApiError(ErrorCode.BAD_REQUEST, invalidMessage))
        return null
    }
    val bytes = readBodyUpTo(limit)
    if (bytes == null) {
        respondError(HttpStatusCode.PayloadTooLarge, ApiError(ErrorCode.PAYLOAD_TOO_LARGE))
        return null
    }
    val value = runCatching { bodyJson.decodeFromString(serializer, bytes.decodeToString()) }.getOrNull()
    if (value == null || !isValid(value)) {
        respondError(HttpStatusCode.BadRequest, ApiError(ErrorCode.BAD_REQUEST, invalidMessage))
        return null
    }
    return value
}

/** 본문을 끝까지 읽는다. [limit] 바이트를 넘으면 더 읽지 않고 null. */
private suspend fun ApplicationCall.readBodyUpTo(limit: Long): ByteArray? {
    val channel = receiveChannel()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        if (out.size().toLong() + read > limit) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** 요청을 보낸 쪽의 주소([clientAddress] — 연결 주소와 `X-Forwarded-For` 로 정한다). 로그인 시도 한도를 주소마다 세는 데 쓴다. */
fun ApplicationCall.clientAddress(): String =
    clientAddress(request.local.remoteAddress, request.headers.getAll(FORWARDED_FOR))
