package com.eunilsung.aiagent.ui.chat

import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.SummaryRequest

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
    /** Talk 요약 링크로 걸린 [request] 를 요약한다. [question] 은 화면이 만든 "파일명 요약해 줘" 문구다. */
    data class OnSummarize(val request: SummaryRequest, val question: String) : ChatActions
}

sealed interface ChatEvent {
    data class AnswerFailed(val failure: AnswerFailure) : ChatEvent
    /** 로그인이 풀려 로그아웃했다. */
    data object SessionExpired : ChatEvent
    data object SendFailed : ChatEvent
    /** Talk 요약 링크로 이 대화에 요약이 걸렸다. 화면이 질문 문구를 만들어 [ChatActions.OnSummarize] 로 돌려준다. */
    data class SummaryRequested(val request: SummaryRequest) : ChatEvent
}
