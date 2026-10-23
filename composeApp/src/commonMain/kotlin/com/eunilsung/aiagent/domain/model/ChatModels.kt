package com.eunilsung.aiagent.domain.model

/** 로그인한 사람. Talk 계정과 같다. */
data class User(
    val id: String,
    val name: String,
)

/** AI 와 나눈 대화 하나. 대화 목록의 한 줄이다. */
data class Conversation(
    val id: String,
    /** 비어 있으면 아직 질문하지 않은 새 대화다 — 화면이 "새 대화"로 보인다. */
    val title: String,
    /** 마지막 메시지의 앞부분. 답변의 마크다운 그대로라, 목록 화면이 기호를 지워 보여 준다. */
    val preview: String,
    val updatedAt: Long,
)

/** 대화 안의 메시지 하나. */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    /** 답변 근거로 찾은 문서 조각. AI 답변에만 있다. */
    val sources: List<Source> = emptyList(),
    /** 후속 질문이라 앞 대화를 반영해 다시 쓴 검색어. 다시 쓰지 않았으면 null. */
    val searchQuery: String? = null,
    val status: MessageStatus = MessageStatus.Done,
    val createdAt: Long,
    /** AI 가 답 대신 정한 Talk 메시지 보내기. 있으면 말풍선 대신 확인 카드로 보인다. */
    val sendRequest: SendRequest? = null,
    /** 긴 문서를 나눠 읽는 중이면 얼마나 읽었는지. 받는 동안 화면에만 있고 기기 DB 에는 저장하지 않는다. */
    val progress: AnswerEvent.Progress? = null,
)

enum class ChatRole { User, Assistant }

/** Talk 의 사람. */
data class Person(
    val id: String,
    val name: String,
    /** 부서·직급 같은 덧붙임. 같은 이름을 가려내는 데 쓴다. */
    val detail: String = "",
)

/**
 * AI 가 정한 Talk 메시지 보내기. 사용자가 받는 사람을 확인하고 [보내기]를 눌러야 보낸다.
 *
 * @property recipientQuery 사용자가 말한 받는 사람(이름·아이디). [candidates] 는 그와 맞는 Talk 의 사람이다.
 */
data class SendRequest(
    val recipientQuery: String,
    val content: String,
    val candidates: List<Person> = emptyList(),
    val status: SendStatus = SendStatus.Pending,
    /** 보낸 사람. [SendStatus.Sent] 일 때만 있다. */
    val sentTo: Person? = null,
)

/**
 * 보내기 진행. [Pending] 은 확인을 기다리는 중이다. [Sending] 은 화면에만 있고 저장하지 않는다 — 보내다 앱이 꺼지면
 * 다시 [Pending] 으로 열리고, 같은 id 로 다시 보내도 Talk 는 한 번만 받는다.
 */
enum class SendStatus { Pending, Sending, Sent, Cancelled, Failed }

/**
 * 메시지 상태. 사용자 질문은 늘 [Done] 이다.
 *
 * AI 답변은 받는 동안 [Streaming] 이고, 끝나면 [Done], 사용자가 멈추면 [Stopped], 받지 못하면 [Failed] 가 된다.
 * 앱이 답변을 받다가 꺼졌으면 다음 실행 때 [Stopped] 로 바뀐다.
 */
enum class MessageStatus { Streaming, Done, Stopped, Failed }

/** 근거 문서 조각. [score] 는 질문과의 유사도(0~1)다. */
data class Source(
    val name: String,
    val score: Double,
)

/** 답변을 받는 동안 오는 일. */
sealed interface AnswerEvent {
    /** 근거 문서를 찾았다. 답변보다 먼저 한 번 온다. */
    data class Retrieved(val sources: List<Source>, val searchQuery: String?) : AnswerEvent
    /** 답변 조각. */
    data class Delta(val text: String) : AnswerEvent
    /** 답 대신 Talk 메시지를 보내 달라는 요청을 알아들었다. 아직 보낸 것이 아니다. */
    data class SendRequested(val recipient: String, val content: String) : AnswerEvent
    /** 문서에서 답을 찾지 못했다. 앞서 온 근거·검색어는 이 답의 근거가 아니다. 답변이 끝날 때 한 번 온다. */
    data object NotFound : AnswerEvent
    /** 긴 문서를 [total] 조각으로 나눠 읽는 중이고 [done] 조각을 읽었다. 요약에만 온다. */
    data class Progress(val done: Int, val total: Int) : AnswerEvent
}

/** 답변을 받지 못한 이유. */
sealed class AnswerFailure : Exception() {
    /** 로그인이 풀렸다. 다시 로그인해야 한다. */
    data object SessionExpired : AnswerFailure()
    /** 한 시간에 보낼 수 있는 질문 수를 넘겼다. */
    data object RateLimited : AnswerFailure()
    /** 브릿지나 그 뒤의 서버에 닿지 못했다. 다시 보내면 될 수 있다. */
    data object Unreachable : AnswerFailure()
    /**
     * 서버가 답을 만들다 실패했다. [reason] 은 서버가 남긴 기록용 설명(영어 예외 문장이나 거절 code)이라 로그에만 남고
     * 화면에 쓰지 않는다 — 내부 주소가 들어 있을 수 있고, 같은 실패도 경우마다 문장이 다르다.
     */
    data class Server(val reason: String) : AnswerFailure()
    /** 요약할 수 없는 파일 형식이다(이미지·압축 파일, 옛 doc·xls·ppt 등). */
    data object UnsupportedFile : AnswerFailure()
    /** 문서에 글자가 없다 — 스캔한 PDF·빈 문서. */
    data object NoText : AnswerFailure()
    /** 암호가 걸렸거나 배포용으로 잠긴 문서다. */
    data object EncryptedFile : AnswerFailure()
    /** 형식은 맞는데 파일이 깨져 읽지 못했다. */
    data object BrokenFile : AnswerFailure()
    /** Talk 에 파일이 없거나(지워짐) 로그인한 사람이 그 파일이 있는 방에 없다. */
    data object FileNotFound : AnswerFailure()
    /** 요약할 수 있는 크기(20MB)를 넘는 파일이다. */
    data object FileTooLarge : AnswerFailure()
}

/** 로그인 결과. */
sealed interface LoginResult {
    data class Success(val user: User) : LoginResult
    data object InvalidCredentials : LoginResult
    /** 비밀번호를 연달아 틀려 잠겼다. Talk 서버의 규칙이다. */
    data object Locked : LoginResult
    data object Unreachable : LoginResult
    data object Failed : LoginResult
}

/** 설정 화면의 연결 상태. 브릿지에 닿지 못하면 [bridge] 가 false 이고 나머지는 모른다. */
data class ServiceStatus(
    val bridge: Boolean,
    val talk: Boolean = false,
    val chatModel: String? = null,
    val embedModel: String? = null,
    val chunks: Int? = null,
)
