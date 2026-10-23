package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.Conversation
import com.eunilsung.aiagent.domain.model.MessageStatus
import com.eunilsung.aiagent.domain.model.SendRequest
import com.eunilsung.aiagent.domain.model.SendStatus
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.domain.repository.ConversationRepository
import com.eunilsung.aiagent.domain.repository.MessengerRepository
import com.eunilsung.aiagent.domain.util.newId
import com.eunilsung.aiagent.domain.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ObserveConversationUseCase(private val repository: ConversationRepository) {
    operator fun invoke(conversationId: String): Flow<Conversation?> = repository.observeConversation(conversationId)
}

class ObserveMessagesUseCase(private val repository: ConversationRepository) {
    operator fun invoke(conversationId: String): Flow<List<ChatMessage>> = repository.observeMessages(conversationId)
}

/**
 * 질문을 보내고 답변을 받는 대로 대화에 반영한다.
 *
 * 질문은 바로 저장한다. 답변은 받는 동안 화면에만 그리고([MessageStatus.Streaming]), 끝나면 저장한다.
 * Talk 의 대화 전송이 '전송 중'으로 먼저 보이고 결과에 따라 완료·실패로 바뀌는 것과 같은 모양이다.
 * 사용자가 멈추면(코루틴 취소) 받은 데까지 남기고 [MessageStatus.Stopped] 로 저장한다.
 * 서버가 끝을 알리기 전에 끊기면([AnswerFailure.Unreachable]) 받은 데까지 [MessageStatus.Failed] 로 저장한다 —
 * 잘린 답변은 다 받은 것이 아니라서 다음 질문의 대화 기록에도 넣지 않는다.
 */
class SendQuestionUseCase(
    private val conversations: ConversationRepository,
    private val assistant: AssistantRepository,
    private val messenger: MessengerRepository,
) {
    /** @return 답변을 받지 못한 이유. 다 받았으면 null. */
    suspend operator fun invoke(conversationId: String, question: String): AnswerFailure? {
        val messages = conversations.messages(conversationId)
        val history = messages.filter { it.status == MessageStatus.Done && (it.content.isNotBlank() || it.sendRequest != null) }
        /** 대화는 만든 시각으로 줄을 세운다. 같은 밀리초에 두 번 보내도 앞 답변보다 뒤에 오도록 마지막 메시지보다 늦게 둔다. */
        val createdAt = maxOf(nowMillis(), (messages.maxOfOrNull { it.createdAt } ?: 0) + 1)
        val asked = ChatMessage(newId(), conversationId, ChatRole.User, question, createdAt = createdAt)
        conversations.upsertMessage(asked)

        var reply = ChatMessage(
            id = newId(),
            conversationId = conversationId,
            role = ChatRole.Assistant,
            content = "",
            status = MessageStatus.Streaming,
            createdAt = createdAt + 1,
        )
        conversations.upsertMessage(reply, persist = false)

        return try {
            assistant.ask(history + asked).collect { event ->
                reply = when (event) {
                    is AnswerEvent.Retrieved -> reply.copy(sources = event.sources, searchQuery = event.searchQuery)
                    is AnswerEvent.Delta -> reply.copy(content = reply.content + event.text)
                    is AnswerEvent.SendRequested -> reply.copy(
                        sources = emptyList(),
                        searchQuery = null,
                        sendRequest = SendRequest(
                            recipientQuery = event.recipient,
                            content = event.content,
                            candidates = messenger.findPeople(event.recipient).orEmpty(),
                        ),
                    )
                    AnswerEvent.NotFound -> reply.copy(sources = emptyList(), searchQuery = null)
                    is AnswerEvent.Progress -> reply
                }
                conversations.upsertMessage(reply, persist = false)
            }
            conversations.upsertMessage(reply.copy(status = MessageStatus.Done))
            null
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.upsertMessage(reply.copy(status = MessageStatus.Stopped)) }
            throw e
        } catch (failure: AnswerFailure) {
            conversations.upsertMessage(reply.copy(status = MessageStatus.Failed))
            failure
        }
    }
}

/**
 * 사용자가 확인한 Talk 메시지를 보낸다. 보내는 동안은 화면에만 [SendStatus.Sending] 으로 그리고, 결과만 저장한다.
 * Talk 의 대화 id 로 메시지 id 를 써서, 실패 뒤 다시 보내도 Talk 에는 한 번만 들어간다.
 *
 * @return 보냈으면 true.
 */
class ConfirmSendUseCase(
    private val conversations: ConversationRepository,
    private val messenger: MessengerRepository,
) {
    suspend operator fun invoke(conversationId: String, messageId: String, personId: String): Boolean {
        val message = conversations.messages(conversationId).firstOrNull { it.id == messageId } ?: return false
        val request = message.sendRequest ?: return false
        if (request.status != SendStatus.Pending && request.status != SendStatus.Failed) return false
        val person = request.candidates.firstOrNull { it.id == personId } ?: return false

        conversations.upsertMessage(message.copy(sendRequest = request.copy(status = SendStatus.Sending)), persist = false)
        val sent = messenger.send(person, request.content, requestId = message.id)
        val result = if (sent) request.copy(status = SendStatus.Sent, sentTo = person) else request.copy(status = SendStatus.Failed)
        conversations.upsertMessage(message.copy(sendRequest = result))
        return sent
    }
}

/** 보내지 않기로 했다. */
class CancelSendUseCase(private val conversations: ConversationRepository) {
    suspend operator fun invoke(conversationId: String, messageId: String) {
        val message = conversations.messages(conversationId).firstOrNull { it.id == messageId } ?: return
        val request = message.sendRequest ?: return
        if (request.status != SendStatus.Pending && request.status != SendStatus.Failed) return
        conversations.upsertMessage(message.copy(sendRequest = request.copy(status = SendStatus.Cancelled)))
    }
}

/** AI 대화방 화면이 쓰는 유스케이스 묶음. */
data class ChatUseCases(
    val observeConversation: ObserveConversationUseCase,
    val observeMessages: ObserveMessagesUseCase,
    val sendQuestion: SendQuestionUseCase,
    val confirmSend: ConfirmSendUseCase,
    val cancelSend: CancelSendUseCase,
    val logout: LogoutUseCase,
    val takeHeldSummaries: TakeHeldSummariesUseCase,
    val summarizeFile: SummarizeFileUseCase,
)
