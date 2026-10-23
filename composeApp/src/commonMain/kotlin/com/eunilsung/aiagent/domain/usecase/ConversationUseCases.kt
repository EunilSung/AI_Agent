package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.Conversation
import com.eunilsung.aiagent.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.StateFlow

class ObserveConversationsUseCase(private val repository: ConversationRepository) {
    operator fun invoke(): StateFlow<List<Conversation>> = repository.conversations
}

/**
 * 새 대화를 만든다. 이미 질문하지 않은 빈 대화가 있으면 새로 만들지 않고 그것을 쓴다.
 * 로그인 바로 뒤(요약 링크로 앱이 열린 때)에도 빈 대화를 놓치지 않게 다 읽은 목록에서 찾는다.
 */
class CreateConversationUseCase(private val repository: ConversationRepository) {
    suspend operator fun invoke(): Conversation =
        repository.currentConversations().firstOrNull { it.title.isEmpty() } ?: repository.create()
}

class DeleteConversationUseCase(private val repository: ConversationRepository) {
    suspend operator fun invoke(conversationId: String) = repository.delete(conversationId)
}

/** AI 대화 목록 화면이 쓰는 유스케이스 묶음. */
data class ConversationListUseCases(
    val observeConversations: ObserveConversationsUseCase,
    val createConversation: CreateConversationUseCase,
    val deleteConversation: DeleteConversationUseCase,
)
