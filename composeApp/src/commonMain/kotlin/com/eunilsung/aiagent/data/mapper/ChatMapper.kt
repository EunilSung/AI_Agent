package com.eunilsung.aiagent.data.mapper

import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.Conversation
import com.eunilsung.aiagent.domain.model.MessageStatus
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendRequest
import com.eunilsung.aiagent.domain.model.SendStatus
import com.eunilsung.aiagent.domain.model.Source
import com.eunilsung.aiagent.shared.api.ActionEvent
import com.eunilsung.aiagent.shared.api.ActionType
import com.eunilsung.aiagent.shared.api.ChatMessageDto
import com.eunilsung.aiagent.shared.api.DeltaEvent
import com.eunilsung.aiagent.shared.api.DoneEvent
import com.eunilsung.aiagent.shared.api.ErrorCode
import com.eunilsung.aiagent.shared.api.ProgressEvent
import com.eunilsung.aiagent.shared.api.RetrievalEvent
import com.eunilsung.aiagent.shared.api.SendActionDto
import com.eunilsung.aiagent.shared.api.StreamErrorCode
import com.eunilsung.aiagent.shared.api.StreamErrorEvent
import com.eunilsung.aiagent.shared.api.SummaryErrorCode
import com.eunilsung.aiagent.shared.api.TalkPersonDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.eunilsung.aiagent.db.Conversation as ConversationRow
import com.eunilsung.aiagent.db.Message as MessageRow
import com.eunilsung.aiagent.shared.api.ChatRole as ApiRole

/** 브릿지 응답·기기 DB 행을 도메인 모델로, 도메인 모델을 요청 모양으로 바꾼다. */
class ChatMapper {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 대화 기록 한 줄. 메시지 보내기 카드는 글이 아니라 [SendActionDto] 로 보낸다 — RAG 서버가 도구 호출과 그 결과로 LLM 에 넣는다.
     * "…에게 보냈습니다" 같은 글로 넣으면 LLM 이 다음 보내기 요청에서 도구를 부르지 않고 그 글만 흉내 내 아무것도 보내지 않는다.
     * 받는 사람은 실제로 보낸 사람이 있으면 그 이름, 아니면 사용자가 말한 그대로다.
     */
    fun toDto(message: ChatMessage): ChatMessageDto {
        val role = if (message.role == ChatRole.User) ApiRole.USER else ApiRole.ASSISTANT
        val request = message.sendRequest ?: return ChatMessageDto(role = role, content = message.content)
        return ChatMessageDto(
            role = role,
            content = "",
            sendAction = SendActionDto(
                recipient = request.sentTo?.name ?: request.recipientQuery,
                content = request.content,
                sent = request.status == SendStatus.Sent,
            ),
        )
    }

    /** 답변이 끝났다는 이벤트에 실린 "문서에서 찾지 못함". 예전 서버가 보내는 빈 `{}` 는 false 다. */
    fun notFound(data: String): Boolean =
        runCatching { json.decodeFromString(DoneEvent.serializer(), data).notFound }.getOrDefault(false)

    fun sendRequested(data: String): AnswerEvent.SendRequested? {
        val event = json.decodeFromString(ActionEvent.serializer(), data)
        return if (event.type == ActionType.SEND_MESSAGE) AnswerEvent.SendRequested(event.recipient, event.content) else null
    }

    fun toDomain(dto: TalkPersonDto): Person =
        Person(dto.id, dto.name, listOf(dto.organName, dto.positionName).filter { it.isNotBlank() }.joinToString(" "))

    fun retrieved(data: String): AnswerEvent.Retrieved {
        val event = json.decodeFromString(RetrievalEvent.serializer(), data)
        return AnswerEvent.Retrieved(event.sources.map { Source(it.source, it.score) }, event.query)
    }

    fun delta(data: String): AnswerEvent.Delta = AnswerEvent.Delta(json.decodeFromString(DeltaEvent.serializer(), data).delta)

    /** 긴 문서를 나눠 읽는 진행. 모양이 맞지 않으면 null — 진행은 보여 주기만 하므로 버려도 된다. */
    fun progress(data: String): AnswerEvent.Progress? =
        runCatching { json.decodeFromString(ProgressEvent.serializer(), data) }.getOrNull()
            ?.takeIf { it.total > 0 && it.done in 0..it.total }
            ?.let { AnswerEvent.Progress(it.done, it.total) }

    /**
     * 스트림 중 `error` 이벤트. 이유는 code 로만 가린다 — 요약하다 실패한 이유([SummaryErrorCode])면 그 이유,
     * 브릿지가 RAG 서버와의 연결이 끊긴 것을 알린 것([StreamErrorCode.INTERRUPTED])이면 `done` 없이 끝난 스트림과 같은
     * 닿지 못함, 그 밖은 서버 실패다. message 는 기록용이라 [AnswerFailure.Server.reason] 에 로그용으로만 담는다.
     */
    fun streamFailure(data: String): AnswerFailure {
        val event = runCatching { json.decodeFromString(StreamErrorEvent.serializer(), data) }.getOrDefault(StreamErrorEvent())
        return when (event.code) {
            SummaryErrorCode.UNSUPPORTED_FILE -> AnswerFailure.UnsupportedFile
            SummaryErrorCode.NO_TEXT -> AnswerFailure.NoText
            SummaryErrorCode.ENCRYPTED_FILE -> AnswerFailure.EncryptedFile
            SummaryErrorCode.BROKEN_FILE -> AnswerFailure.BrokenFile
            StreamErrorCode.INTERRUPTED -> AnswerFailure.Unreachable
            else -> AnswerFailure.Server(event.message)
        }
    }

    /**
     * 브릿지가 SSE 를 열지 않고 거절한 이유. 다시 보내도 같은 답인 거절과 다시 보내면 될 수 있는 닿지 못함을 나눈다.
     *
     * 파일 이유는 상태가 아니라 [code] 로 가린다 — 브릿지는 404 를 [ErrorCode.FILE_NOT_FOUND], 413 을
     * [ErrorCode.FILE_TOO_LARGE] 와 함께 준다. 코드 없는 404(요약 경로가 없는 예전 브릿지)나 413(중간 프록시가 큰 대화
     * 요청을 막음)은 파일 탓이 아니므로 서버 실패다.
     */
    fun rejection(status: Int, code: String): AnswerFailure = when {
        status == 401 -> AnswerFailure.SessionExpired
        status == 429 -> AnswerFailure.RateLimited
        code == ErrorCode.FILE_NOT_FOUND -> AnswerFailure.FileNotFound
        code == ErrorCode.FILE_TOO_LARGE -> AnswerFailure.FileTooLarge
        status == 503 -> AnswerFailure.Unreachable
        else -> AnswerFailure.Server(code)
    }

    fun toDomain(row: ConversationRow): Conversation = Conversation(
        id = row.id,
        title = row.title,
        preview = row.preview,
        updatedAt = row.updated_at,
    )

    fun toDomain(row: MessageRow): ChatMessage = ChatMessage(
        id = row.id,
        conversationId = row.conversation_id,
        role = ChatRole.entries.firstOrNull { it.name == row.role } ?: ChatRole.Assistant,
        content = row.content,
        sources = decodeSources(row.sources_json),
        searchQuery = row.search_query,
        status = MessageStatus.entries.firstOrNull { it.name == row.status } ?: MessageStatus.Done,
        createdAt = row.created_at,
        sendRequest = row.send_request_json?.let(::decodeSendRequest),
    )

    fun encodeSources(sources: List<Source>): String =
        json.encodeToString(ListSerializer(StoredSource.serializer()), sources.map { StoredSource(it.name, it.score) })

    fun encodeSendRequest(request: SendRequest?): String? = request?.let {
        json.encodeToString(
            StoredSendRequest.serializer(),
            StoredSendRequest(
                recipientQuery = it.recipientQuery,
                content = it.content,
                candidates = it.candidates.map { person -> StoredPerson(person.id, person.name, person.detail) },
                status = (if (it.status == SendStatus.Sending) SendStatus.Pending else it.status).name,
                sentTo = it.sentTo?.let { person -> StoredPerson(person.id, person.name, person.detail) },
            ),
        )
    }

    private fun decodeSendRequest(text: String): SendRequest? = runCatching {
        val stored = json.decodeFromString(StoredSendRequest.serializer(), text)
        SendRequest(
            recipientQuery = stored.recipientQuery,
            content = stored.content,
            candidates = stored.candidates.map { Person(it.id, it.name, it.detail) },
            status = SendStatus.entries.firstOrNull { it.name == stored.status } ?: SendStatus.Pending,
            sentTo = stored.sentTo?.let { Person(it.id, it.name, it.detail) },
        )
    }.getOrNull()

    private fun decodeSources(text: String): List<Source> =
        runCatching { json.decodeFromString(ListSerializer(StoredSource.serializer()), text) }.getOrDefault(emptyList())
            .map { Source(it.name, it.score) }

    /** 기기 DB 에 근거 문서를 JSON 으로 넣을 때의 모양. */
    @Serializable
    private data class StoredSource(val name: String, val score: Double)

    /** 기기 DB 에 메시지 보내기를 JSON 으로 넣을 때의 모양. 보내는 중(Sending)은 저장하지 않고 확인 대기로 둔다. */
    @Serializable
    private data class StoredSendRequest(
        val recipientQuery: String,
        val content: String,
        val candidates: List<StoredPerson> = emptyList(),
        val status: String,
        val sentTo: StoredPerson? = null,
    )

    @Serializable
    private data class StoredPerson(val id: String, val name: String, val detail: String = "")
}
