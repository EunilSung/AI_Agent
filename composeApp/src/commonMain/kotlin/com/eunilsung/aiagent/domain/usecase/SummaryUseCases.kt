package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.MessageStatus
import com.eunilsung.aiagent.domain.model.SummaryLinkResult
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.domain.repository.AuthRepository
import com.eunilsung.aiagent.domain.repository.ConversationRepository
import com.eunilsung.aiagent.domain.repository.SummaryRequestRepository
import com.eunilsung.aiagent.domain.util.SummaryLink
import com.eunilsung.aiagent.domain.util.newId
import com.eunilsung.aiagent.domain.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

/** 앱 밖에서 열린 링크. 앱 전체(MainViewModel)가 받는다. */
class ObserveSummaryLinksUseCase(private val repository: SummaryRequestRepository) {
    operator fun invoke(): Flow<String> = repository.incomingLinks
}

/**
 * Talk 의 "AI 요약" 링크를 처리한다.
 *
 * - 같은 계정이면 새 대화(빈 대화가 있으면 그것)에 요약을 걸어 두고 그 대화를 열게 한다([SummaryLinkResult.Open]).
 * - 로그인하지 않았거나 다른 계정이면 요청을 로그인 대기로 남긴다. 다른 계정으로 로그인해 있으면 로그아웃한다
 *   ([SummaryLinkResult.LoginRequired]). 로그인한 뒤의 일은 [ResumeSummaryAfterLoginUseCase] 가 한다.
 * - 요약 링크가 아니거나 값이 맞지 않으면 아무것도 하지 않는다([SummaryLinkResult.Ignored]).
 */
class HandleSummaryLinkUseCase(
    private val auth: AuthRepository,
    private val requests: SummaryRequestRepository,
    private val createConversation: CreateConversationUseCase,
) {
    suspend operator fun invoke(link: String): SummaryLinkResult {
        val request = SummaryLink.parse(link) ?: return SummaryLinkResult.Ignored
        val me = auth.session.value
        if (me?.id == request.userId) return openSummary(request, createConversation, requests)

        /** 로그아웃이 끝나기 전에 로그인 대기를 걸어 둔다 — 로그인이 바뀌는 것을 보는 쪽이 요청을 놓치지 않게. */
        requests.awaitLogin(request)
        if (me != null) auth.logout()
        return SummaryLinkResult.LoginRequired(request, loggedOut = me != null)
    }
}

/**
 * 로그인한 뒤 기다리던 요약을 이어 간다. 요청한 계정으로 로그인했으면 요약 대화를 열게 하고,
 * 다른 계정으로 로그인했으면 요청을 버린다([SummaryLinkResult.Cancelled]). 기다리던 요청이 없으면 [SummaryLinkResult.Ignored].
 */
class ResumeSummaryAfterLoginUseCase(
    private val requests: SummaryRequestRepository,
    private val createConversation: CreateConversationUseCase,
) {
    suspend operator fun invoke(user: User): SummaryLinkResult {
        val request = requests.takeAwaitingLogin() ?: return SummaryLinkResult.Ignored
        return if (request.userId == user.id) openSummary(request, createConversation, requests) else SummaryLinkResult.Cancelled
    }
}

private suspend fun openSummary(
    request: SummaryRequest,
    createConversation: CreateConversationUseCase,
    requests: SummaryRequestRepository,
): SummaryLinkResult.Open {
    val conversation = createConversation()
    requests.hold(conversation.id, request)
    return SummaryLinkResult.Open(conversation.id)
}

/** 대화방이 열려 있는 동안 그 대화에 걸리는 요약을 꺼낸다. */
class TakeHeldSummariesUseCase(private val repository: SummaryRequestRepository) {
    operator fun invoke(conversationId: String): Flow<SummaryRequest> = repository.takeHeld(conversationId)
}

/**
 * Talk 파일을 요약해 대화에 남긴다. [SendQuestionUseCase] 와 같은 모양이다.
 *
 * 질문(화면이 만든 "파일명 요약해 줘")은 바로 저장한다. 답변은 받는 동안 화면에만 그리고([MessageStatus.Streaming]) —
 * 긴 문서를 나눠 읽는 진행([ChatMessage.progress])도 화면에만 둔다 — 끝나면 저장한다.
 * 사용자가 멈추면 받은 데까지 [MessageStatus.Stopped], 받지 못하면 [MessageStatus.Failed] 로 저장한다.
 *
 * 서버가 끝을 알리기 전에 끊기면([AssistantRepository.summarize] 가 [AnswerFailure.Unreachable] 을 던진다) 받은 데까지
 * [MessageStatus.Failed] 로 남긴다 — 잘린 요약을 다 받은 것으로 남기지 않는다.
 *
 * 요약은 [MAX_SUMMARY_LENGTH] 자에서 자르고, 그 길이에 닿으면 바로 그만 받는다. 이 대화의 후속 질문은 요약을 대화 기록으로
 * 함께 보내는데, 브릿지가 `/v1/chat` 의 메시지 하나를 그보다 길면 받지 않기 때문이다. 그만 받은 뒤에 연결이 끊겨도
 * 남길 글은 이미 다 받았으므로 [MessageStatus.Done] 이다.
 */
class SummarizeFileUseCase(
    private val conversations: ConversationRepository,
    private val assistant: AssistantRepository,
) {
    /** @return 요약을 받지 못한 이유. 다 받았으면 null. */
    suspend operator fun invoke(conversationId: String, request: SummaryRequest, question: String): AnswerFailure? {
        val messages = conversations.messages(conversationId)
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
            assistant.summarize(request.fileId, request.fileName)
                .transformWhile<AnswerEvent, Unit> { event ->
                    reply = when (event) {
                        is AnswerEvent.Progress -> reply.copy(progress = event)
                        is AnswerEvent.Delta -> reply.copy(content = (reply.content + event.text).take(MAX_SUMMARY_LENGTH), progress = null)
                        is AnswerEvent.Retrieved, is AnswerEvent.SendRequested, AnswerEvent.NotFound -> reply
                    }
                    conversations.upsertMessage(reply, persist = false)
                    reply.content.length < MAX_SUMMARY_LENGTH
                }
                .collect()
            conversations.upsertMessage(reply.copy(status = MessageStatus.Done, progress = null))
            null
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.upsertMessage(reply.copy(status = MessageStatus.Stopped, progress = null)) }
            throw e
        } catch (failure: AnswerFailure) {
            conversations.upsertMessage(reply.copy(status = MessageStatus.Failed, progress = null))
            failure
        }
    }

    companion object {
        /** 브릿지 `/v1/chat` 이 받는 메시지 하나의 길이 끝(`ChatRoutes` 의 MAX_CONTENT_LENGTH). */
        const val MAX_SUMMARY_LENGTH = 8_000
    }
}

/** 앱 전체(MainViewModel)가 쓰는 유스케이스 묶음 — 로그인 상태와 Talk 요약 링크. */
data class MainUseCases(
    val observeSession: ObserveSessionUseCase,
    val observeSummaryLinks: ObserveSummaryLinksUseCase,
    val handleSummaryLink: HandleSummaryLinkUseCase,
    val resumeSummaryAfterLogin: ResumeSummaryAfterLoginUseCase,
)
