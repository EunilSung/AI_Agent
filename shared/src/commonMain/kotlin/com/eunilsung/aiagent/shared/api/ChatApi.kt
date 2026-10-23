package com.eunilsung.aiagent.shared.api

import kotlinx.serialization.Serializable

/**
 * AI 대화 요청 — `POST /v1/chat`.
 *
 * 대화 기록 전체를 보낸다. 마지막 항목이 이번 질문이고, 그 앞은 후속 질문을 이해하는 데 쓴다.
 * 응답은 SSE(`text/event-stream`)로 온다. 이벤트 순서는 [ChatStreamEvent] 에 적었다.
 */
@Serializable
data class ChatRequest(
    val messages: List<ChatMessageDto>,
)

@Serializable
data class ChatMessageDto(
    /** [ChatRole] 중 하나. */
    val role: String,
    val content: String,
    /**
     * AI 가 이 차례에 답 대신 정한 메시지 보내기. 있으면 RAG 서버가 [content] 대신 도구 호출과 그 결과로 LLM 에 넣는다.
     * 글("…에게 보냈습니다")로 넣으면 LLM 이 다음 요청에서 도구를 부르지 않고 그 글만 흉내 내 아무것도 보내지 않는다.
     */
    val sendAction: SendActionDto? = null,
)

/** 대화 기록 속 메시지 보내기 — 누구에게 무엇을, 그리고 사용자가 확인해 실제로 보냈는지. */
@Serializable
data class SendActionDto(
    val recipient: String,
    val content: String,
    /** 사용자가 [보내기]를 눌러 Talk 에 들어갔으면 true. 취소했거나 아직 확인하지 않았으면 false. */
    val sent: Boolean,
)

object ChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/**
 * SSE 이벤트 이름과 순서.
 *
 * ```
 * event: sources   data: RetrievalEvent   — 답변보다 먼저 한 번
 * data: DeltaEvent                         — 답변 조각, 여러 번 (이벤트 이름 없음)
 * event: action    data: ActionEvent      — 답 대신 할 일(메시지 보내기). 이때는 답변 조각이 없다
 * event: error     data: StreamErrorEvent — 실패하면
 * event: done      data: DoneEvent        — 마지막. 문서에서 답을 찾지 못했으면 notFound 가 true
 * ```
 * `:` 로 시작하는 줄은 연결 유지용 주석이라 무시한다.
 */
object ChatStreamEvent {
    const val SOURCES = "sources"
    const val ACTION = "action"
    const val ERROR = "error"
    const val DONE = "done"
}

/**
 * AI 가 정한 할 일. 지금은 메시지 보내기([ActionType.SEND_MESSAGE])뿐이다.
 *
 * 아직 보낸 것이 아니다. 앱이 받는 사람을 찾아 사용자에게 확인을 받은 뒤 `POST /v1/talk/messages` 로 보낸다.
 */
@Serializable
data class ActionEvent(
    val type: String,
    /** 사용자가 말한 받는 사람 — 이름이나 아이디. Talk 의 사람과 맞춰 보기 전이다. */
    val recipient: String = "",
    val content: String = "",
)

object ActionType {
    const val SEND_MESSAGE = "send_message"
}

/** 답변 근거로 찾은 문서 조각과, 후속 질문이라 다시 쓴 검색어(재작성했을 때만). */
@Serializable
data class RetrievalEvent(
    val sources: List<SourceDto> = emptyList(),
    val query: String? = null,
)

@Serializable
data class SourceDto(
    val source: String,
    val score: Double,
)

@Serializable
data class DeltaEvent(
    val delta: String,
)

@Serializable
data class StreamErrorEvent(
    val message: String = "",
)

/**
 * 답변이 끝났다. 예전 서버는 빈 `{}` 를 보낸다.
 *
 * [notFound] 면 답변이 "문서에서 찾을 수 없습니다" 뿐이다 — 앞서 보낸 근거 문서와 다시 쓴 검색어는 이 답의 근거가 아니다.
 * 근거는 답변보다 먼저 보내므로, 쓸모없었다는 것은 답변을 다 받은 뒤에야 알 수 있다.
 */
@Serializable
data class DoneEvent(
    val notFound: Boolean = false,
)

/** `GET /v1/health` 응답. 브릿지 뒤의 RAG 서버와 Talk 서버에 닿는지 함께 알려 준다. */
@Serializable
data class HealthResponse(
    val status: String = "ok",
    /** RAG 서버에 닿지 못하면 null. */
    val rag: RagHealthDto? = null,
    val talk: Boolean = false,
)

@Serializable
data class RagHealthDto(
    val chatModel: String,
    val embedModel: String,
    val chunks: Int,
)
