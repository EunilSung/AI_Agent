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
 * Talk 파일 요약 요청 — `POST /v1/summarize`.
 *
 * 브릿지가 사용자의 Talk 토큰으로 Talk 서버에서 [fileId] 파일을 받아 RAG 서버에 넘긴다. Talk 서버는 그 파일이 있는 방의
 * 사람에게만 파일을 내주므로, 남의 파일 id 를 넣어도 받을 수 없다([ErrorCode.FILE_NOT_FOUND]).
 * 응답은 `/v1/chat` 과 같은 SSE 다. 다만 sources·action 은 없고, 긴 문서를 나눠 읽는 동안 progress 가 온다.
 */
@Serializable
data class SummarizeRequest(
    /** Talk 서버의 파일 id(대화 payload 의 fileId). */
    val fileId: String,
    /** 확장자로 문서 형식을 가리고, 요약 첫머리에 쓴다. */
    val fileName: String,
)

/** 요약할 수 있는 파일 크기의 끝. Talk 의 `MAX_FILE_BYTES` 와 같다. */
const val MAX_SUMMARY_FILE_BYTES: Long = 20L * 1024 * 1024

/**
 * SSE 이벤트 이름과 순서.
 *
 * ```
 * event: sources   data: RetrievalEvent   — 답변보다 먼저 한 번 (대화만)
 * event: progress  data: ProgressEvent    — 긴 문서를 나눠 읽는 중, 조각마다 한 번 (요약만)
 * data: DeltaEvent                         — 답변 조각, 여러 번 (이벤트 이름 없음)
 * event: action    data: ActionEvent      — 답 대신 할 일(메시지 보내기). 이때는 답변 조각이 없다 (대화만)
 * event: error     data: StreamErrorEvent — 실패하면
 * event: done      data: DoneEvent        — 마지막. 문서에서 답을 찾지 못했으면 notFound 가 true
 * ```
 * `:` 로 시작하는 줄은 연결 유지용 주석이라 무시한다. 모르는 이벤트 이름도 무시한다.
 */
object ChatStreamEvent {
    const val SOURCES = "sources"
    const val PROGRESS = "progress"
    const val ACTION = "action"
    const val ERROR = "error"
    const val DONE = "done"
}

/** 긴 문서를 [total] 조각으로 나눠 읽고 있고, 그중 [done] 조각을 읽었다. 다 읽으면 요약 조각(DeltaEvent)이 온다. */
@Serializable
data class ProgressEvent(
    val done: Int,
    val total: Int,
)

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

/**
 * 스트림 중 실패 — SSE `error` 이벤트의 data.
 *
 * 화면에 띄울 문구는 앱이 [code] 를 보고 자기 문자열(ko/en)로 고른다. code 가 비어 있으면 "서버가 답하지 못했다" 같은
 * 정해진 문구를 쓴다.
 */
@Serializable
data class StreamErrorEvent(
    /**
     * 기록용 설명(영어)이다. 화면에 쓰지 않는다 — 서버 예외 문장 그대로라 내부 주소가 들어 있을 수 있고, 같은 실패도
     * 경우마다 문장이 달라진다. 앱은 로그에만 남긴다.
     */
    val message: String = "",
    /**
     * 앱이 사용자에게 이유를 알려 줄 수 있는 실패면 [SummaryErrorCode] 중 하나, 브릿지가 RAG 서버와의 연결이 끊긴 것을
     * 알리면 [StreamErrorCode.INTERRUPTED]. 그 밖의 실패는 비어 있다.
     */
    val code: String = "",
)

/** 요약·대화 어느 쪽에서나 날 수 있는 스트림 실패 — SSE `error` 이벤트의 code. */
object StreamErrorCode {
    /**
     * RAG 서버와의 연결이 중간에 끊겼다. 브릿지는 이미 200 을 보낸 뒤라 받은 데까지 흘려보내고 이 code 의 `error` 와
     * `done` 을 덧붙인다. 앱은 `done` 없이 끝난 스트림과 같은 "닿지 못함"(다시 보내면 될 수 있다)으로 다룬다.
     */
    const val INTERRUPTED = "stream_interrupted"
}

/** 요약하다 실패한 이유 — SSE `error` 이벤트의 code. RAG 서버가 파일을 읽어 보고서야 알 수 있는 것들이다. */
object SummaryErrorCode {
    /** 글을 뽑을 수 없는 형식이다(이미지·압축 파일, 옛 doc·xls·ppt 등). */
    const val UNSUPPORTED_FILE = "unsupported_file"
    /** 글자가 없다 — 스캔한 PDF·빈 문서. */
    const val NO_TEXT = "no_text"
    /** 암호가 걸렸거나 배포용으로 잠긴 문서다. */
    const val ENCRYPTED_FILE = "encrypted_file"
    /** 형식은 맞는데 파일이 깨져 읽지 못했다. */
    const val BROKEN_FILE = "broken_file"
}

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
