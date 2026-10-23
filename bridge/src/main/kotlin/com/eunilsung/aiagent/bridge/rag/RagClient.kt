package com.eunilsung.aiagent.bridge.rag

import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.RagHealthDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json

/**
 * RAG 서버(`rag-server/app.py`)를 부른다. LLM 과 문서 검색은 모두 그쪽이 하고, 브릿지는 결과를 옮기기만 한다.
 */
class RagClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** RAG 서버에 닿지 못하면 null. */
    suspend fun health(): RagHealthDto? = runCatching {
        val response = http.get("$baseUrl/v1/health")
        if (!response.status.isSuccess()) return null
        json.decodeFromString<RagHealthDto>(response.bodyAsText())
    }.getOrNull()

    /**
     * AI 대화를 스트리밍으로 연다. 연결되면 [onOpen] 이 SSE 본문 채널을 받아 끝까지 읽는다.
     *
     * @return RAG 서버에 닿지 못했거나 거절했으면 false. 이때 [onOpen] 은 불리지 않는다.
     */
    suspend fun streamChat(request: ChatRequest, onOpen: suspend (ByteReadChannel) -> Unit): Boolean =
        runCatching {
            http.preparePost("$baseUrl/v1/chat") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ChatRequest.serializer(), request))
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute false
                onOpen(response.bodyAsChannel())
                true
            }
        }.getOrDefault(false)
}
