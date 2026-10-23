package com.eunilsung.aiagent.ui.conversationlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.usecase.ConversationListUseCases
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversationListViewModel(
    private val useCases: ConversationListUseCases,
) : ViewModel() {
    private val conversations = useCases.observeConversations()

    val uiState: StateFlow<ConversationListUiState> = conversations
        .map { ConversationListUiState(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConversationListUiState(conversations.value))

    private val _events = Channel<ConversationListEvent>(Channel.BUFFERED)
    val events: Flow<ConversationListEvent> = _events.receiveAsFlow()

    fun onAction(action: ConversationListActions) {
        when (action) {
            ConversationListActions.OnNewConversation -> viewModelScope.launch {
                _events.send(ConversationListEvent.OpenChat(useCases.createConversation().id))
            }
            is ConversationListActions.OnOpen -> viewModelScope.launch {
                _events.send(ConversationListEvent.OpenChat(action.conversationId))
            }
            is ConversationListActions.OnDelete -> viewModelScope.launch {
                useCases.deleteConversation(action.conversationId)
                _events.send(ConversationListEvent.Deleted(action.conversationId))
            }
        }
    }
}
