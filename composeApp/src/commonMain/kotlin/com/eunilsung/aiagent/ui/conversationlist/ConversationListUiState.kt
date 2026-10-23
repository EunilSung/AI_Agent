package com.eunilsung.aiagent.ui.conversationlist

import com.eunilsung.aiagent.domain.model.Conversation

/** AI 대화 목록 화면의 상태. */
data class ConversationListUiState(
    val conversations: List<Conversation> = emptyList(),
)
