package com.eunilsung.aiagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.usecase.ChatUseCases
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 대화방. 질문을 보내고 답변을 받는 일은 [ChatUseCases.sendQuestion] 이 하고, 여기서는 받는 중인지와
 * 실패 알림만 다룬다. 화면을 떠나면 받던 답변은 멈추고 받은 데까지 남는다.
 *
 * Talk 요약 링크가 이 대화에 걸어 둔 요약은 대화방이 있는 동안 걸리는 대로 꺼낸다 — 빈 대화를 다시 쓰면 이 ViewModel 이
 * 이미 있을 수 있어서, 만들 때 한 번만 보지 않고 계속 지켜본다. 질문 문구는 화면이 만들어 돌려준다(추천 질문과 같다).
 */
class ChatViewModel(
    private val conversationId: String,
    private val useCases: ChatUseCases,
) : ViewModel() {
    private val input = MutableStateFlow("")
    private val isAnswering = MutableStateFlow(false)
    private val pickedRecipients = MutableStateFlow<Map<String, String>>(emptyMap())
    private var answerJob: Job? = null

    val uiState: StateFlow<ChatUiState> = combine(
        useCases.observeConversation(conversationId),
        useCases.observeMessages(conversationId),
        input,
        isAnswering,
        pickedRecipients,
    ) { conversation, messages, text, answering, picked ->
        ChatUiState(
            title = conversation?.title.orEmpty(),
            messages = messages,
            groupedMessages = groupMessagesForUi(messages.asReversed()),
            input = text,
            isAnswering = answering,
            pickedRecipients = picked,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events: Flow<ChatEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            useCases.takeHeldSummaries(conversationId).collect { _events.send(ChatEvent.SummaryRequested(it)) }
        }
    }

    fun onAction(action: ChatActions) {
        when (action) {
            is ChatActions.OnInputChange -> input.value = action.text
            ChatActions.OnSend -> send(input.value)
            is ChatActions.OnSuggestion -> send(action.question)
            ChatActions.OnStop -> answerJob?.cancel()
            is ChatActions.OnPickRecipient -> pickedRecipients.update { it + (action.messageId to action.personId) }
            is ChatActions.OnConfirmSend -> confirmSend(action.messageId)
            is ChatActions.OnCancelSend -> viewModelScope.launch { useCases.cancelSend(conversationId, action.messageId) }
            is ChatActions.OnSummarize -> summarize(action.request, action.question)
        }
    }

    private fun confirmSend(messageId: String) {
        val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        val personId = uiState.value.recipientOf(message) ?: return
        viewModelScope.launch {
            if (!useCases.confirmSend(conversationId, messageId, personId)) _events.send(ChatEvent.SendFailed)
        }
    }

    private fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || isAnswering.value) return
        input.value = ""
        answer { useCases.sendQuestion(conversationId, question) }
    }

    private fun summarize(request: SummaryRequest, question: String) {
        if (question.isBlank() || isAnswering.value) return
        answer { useCases.summarizeFile(conversationId, request, question.trim()) }
    }

    /** 답변 하나를 받는다. 받는 동안 [isAnswering] 이고, 실패하면 이유를 알린다. 로그인이 풀렸으면 로그아웃한다. */
    private fun answer(block: suspend () -> AnswerFailure?) {
        isAnswering.value = true
        answerJob = viewModelScope.launch {
            try {
                when (val failure = block()) {
                    null -> Unit
                    AnswerFailure.SessionExpired -> {
                        _events.send(ChatEvent.SessionExpired)
                        useCases.logout()
                    }
                    else -> _events.send(ChatEvent.AnswerFailed(failure))
                }
            } finally {
                isAnswering.value = false
            }
        }
    }
}
