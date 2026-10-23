package com.eunilsung.aiagent.bridge.rag

import com.eunilsung.aiagent.bridge.http.networkBody
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.RagHealthDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * RAG 서버(`rag-server/app.py`)를 부른다. LLM 과 문서 검색은 모두 그쪽이 하고, 브릿지는 결과를 옮기기만 한다.
 */
class RagClient(
    private val http: HttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger(RagClient::class.java)

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
        stream(
            http.preparePost("$baseUrl/v1/chat") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ChatRequest.serializer(), request))
            },
            onOpen,
        )

    /**
     * 문서 요약을 스트리밍으로 연다. 본문은 파일 바이트 그대로이고, RAG 서버가 확장자로 형식을 가리도록 [fileName] 을
     * [FILE_NAME_HEADER] 헤더에 퍼센트 인코딩(UTF-8, 공백은 `%20`)해 싣는다. SSE 는 [streamChat] 과 같은 모양이다
     * (sources·action 대신 progress 가 온다).
     *
     * 파일 이름을 URL 쿼리에 싣지 않는다. 요청 줄은 RAG 서버(uvicorn)의 접근 로그와 HTTP 엔진의 예외 메시지에 그대로
     * 남기 때문이다 — 브릿지가 로그에 사람·파일 id·크기만 남기는 것과 같은 기준을 RAG 서버 쪽에서도 지킨다.
     *
     * @return [streamChat] 과 같다.
     */
    suspend fun streamSummary(fileName: String, bytes: ByteArray, onOpen: suspend (ByteReadChannel) -> Unit): Boolean =
        stream(
            http.preparePost("$baseUrl/v1/summarize") {
                header(FILE_NAME_HEADER, fileName.encodeURLParameter())
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            },
            onOpen,
        )

    /**
     * [statement] 를 보내고, RAG 서버가 받아 주면 [onOpen] 에 본문 채널을 넘긴다. 채널은 엔진이 받은 그대로라
     * ([networkBody]) 다 읽은 뒤 `closedCause` 로 RAG 서버 쪽이 중간에 끊겼는지 알 수 있다.
     *
     * [onOpen] 을 부른 뒤에 난 예외는(대개 앱 쪽 연결이 끊긴 것이다) 앱에 응답을 보내기 시작한 뒤라 다른 응답을 보낼 수
     * 없으므로 로그만 남기고 true 를 돌려준다. RAG 서버 쪽이 끊긴 것은 [onOpen] 이 맡는다 — 브릿지의 중계는 끝에 SSE
     * `error` 이벤트를 덧붙여 앱에 알린다. 로그에는 예외 종류만 남긴다. HTTP 엔진의 예외 메시지에는 요청 URL 같은 내부
     * 주소가 들어 있다.
     */
    private suspend fun stream(statement: HttpStatement, onOpen: suspend (ByteReadChannel) -> Unit): Boolean {
        var opened = false
        return try {
            statement.execute { response ->
                if (!response.status.isSuccess()) return@execute false
                opened = true
                onOpen(response.networkBody())
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (opened) log.warn("SSE 중계가 중간에 끊겼다: {}", e::class.simpleName)
            opened
        }
    }

    companion object {
        /** 요약할 파일의 이름을 싣는 요청 헤더. 값은 UTF-8 퍼센트 인코딩이다(RAG 서버 `app.py` 의 summarize 가 푼다). */
        const val FILE_NAME_HEADER = "X-File-Name"
    }
}
