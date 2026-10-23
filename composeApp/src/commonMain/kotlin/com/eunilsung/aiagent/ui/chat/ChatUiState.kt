package com.eunilsung.aiagent.ui.chat

import com.eunilsung.aiagent.domain.model.ChatMessage

/** AI 대화방 화면의 상태. */
data class ChatUiState(
    /** 비어 있으면 아직 질문하지 않은 새 대화다. */
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** 목록에 그릴 메시지 — 최신이 먼저 오고, 묶음·날짜선 판단이 얹혀 있다([groupMessagesForUi]). */
    val groupedMessages: List<GroupedMessage> = emptyList(),
    val input: String = "",
    /** 답변을 받는 중이면 보내기 대신 중지 버튼을 보인다. */
    val isAnswering: Boolean = false,
    /** 보내기 카드마다 고른 받는 사람(메시지 id → 사람 id). 한 사람만 찾았으면 고르지 않아도 그 사람이다. */
    val pickedRecipients: Map<String, String> = emptyMap(),
) {
    val canSend: Boolean
        get() = input.isNotBlank() && !isAnswering

    /** 이 메시지의 보내기 카드에서 받을 사람. 고르지 않았고 찾은 사람이 하나면 그 사람이다. */
    fun recipientOf(message: ChatMessage): String? =
        pickedRecipients[message.id] ?: message.sendRequest?.candidates?.singleOrNull()?.id
}
