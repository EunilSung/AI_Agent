package com.eunilsung.aiagent.data.mapper

import com.eunilsung.aiagent.domain.model.AnswerEvent
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
import com.eunilsung.aiagent.shared.api.RetrievalEvent
import com.eunilsung.aiagent.shared.api.SendActionDto
import com.eunilsung.aiagent.shared.api.StreamErrorEvent
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

    fun errorMessage(data: String): String =
        runCatching { json.decodeFromString(StreamErrorEvent.serializer(), data).message }.getOrDefault("")

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
