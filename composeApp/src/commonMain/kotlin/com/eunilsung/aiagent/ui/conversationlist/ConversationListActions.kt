package com.eunilsung.aiagent.ui.conversationlist

sealed interface ConversationListActions {
    data object OnNewConversation : ConversationListActions
    data class OnOpen(val conversationId: String) : ConversationListActions
    data class OnDelete(val conversationId: String) : ConversationListActions
}

sealed interface ConversationListEvent {
    /** 대화방을 연다. */
    data class OpenChat(val conversationId: String) : ConversationListEvent
    /** 대화를 지웠다. 그 대화방이 열려 있으면 닫는다. */
    data class Deleted(val conversationId: String) : ConversationListEvent
}
