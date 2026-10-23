package com.eunilsung.aiagent.ui.chat

import com.eunilsung.aiagent.domain.model.AnswerFailure

sealed interface ChatActions {
    data class OnInputChange(val text: String) : ChatActions
    data object OnSend : ChatActions
    /** 빈 대화의 추천 질문을 눌렀다. */
    data class OnSuggestion(val question: String) : ChatActions
    /** 답변 받기를 멈춘다. 받은 데까지는 남는다. */
    data object OnStop : ChatActions
    /** 보내기 카드에서 받을 사람을 골랐다. */
    data class OnPickRecipient(val messageId: String, val personId: String) : ChatActions
    /** 보내기 카드의 [보내기]. */
    data class OnConfirmSend(val messageId: String) : ChatActions
    data class OnCancelSend(val messageId: String) : ChatActions
}

sealed interface ChatEvent {
    data class AnswerFailed(val failure: AnswerFailure) : ChatEvent
    /** 로그인이 풀려 로그아웃했다. */
    data object SessionExpired : ChatEvent
    data object SendFailed : ChatEvent
}
