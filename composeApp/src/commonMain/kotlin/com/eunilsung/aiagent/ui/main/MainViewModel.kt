package com.eunilsung.aiagent.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.model.SummaryLinkResult
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.usecase.MainUseCases
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 앱 전체 — 로그인 상태와 Talk 요약 링크. 링크는 들어온 차례로 하나씩 처리한다.
 * 로그인을 지켜보다가 로그인하면 기다리던 요약을 이어 간다.
 *
 * 다른 계정의 링크는 바로 로그아웃하지 않고 [MainEvent.ConfirmAccountSwitch] 로 묻는다. 답을 기다리는 요청은
 * [pendingSwitch] 에 들고 있다가, [로그아웃]이면 로그아웃하고 [취소]면 버린다. 묻는 사이 새 링크를 처리하면 앞의 물음은 무효다.
 */
class MainViewModel(private val useCases: MainUseCases) : ViewModel() {
    private val session = useCases.observeSession()

    val uiState: StateFlow<MainUiState> = session
        .map { MainUiState(me = it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState(me = session.value))

    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events: Flow<MainEvent> = _events.receiveAsFlow()

    /** 계정을 바꿀지 묻는 중인 요약 요청. 사용자가 답하거나 다른 링크를 처리하면 비운다. */
    private var pendingSwitch: SummaryRequest? = null

    init {
        viewModelScope.launch {
            var wasLoggedIn = session.value != null
            session.collect { user ->
                if (wasLoggedIn && user == null) _events.send(MainEvent.LoggedOut)
                if (!wasLoggedIn && user != null) send(useCases.resumeSummaryAfterLogin(user))
                wasLoggedIn = user != null
            }
        }
        viewModelScope.launch {
            useCases.observeSummaryLinks().collect { link ->
                val result = useCases.handleSummaryLink(link)
                if (result != SummaryLinkResult.Ignored) pendingSwitch = null
                send(result)
            }
        }
    }

    fun onAction(action: MainActions) {
        when (action) {
            MainActions.OnAccountSwitchConfirm -> {
                val request = pendingSwitch ?: return
                pendingSwitch = null
                viewModelScope.launch { send(useCases.confirmAccountSwitch(request)) }
            }
            MainActions.OnAccountSwitchCancel -> pendingSwitch = null
        }
    }

    private suspend fun send(result: SummaryLinkResult) {
        val event = when (result) {
            is SummaryLinkResult.Open -> MainEvent.OpenChat(result.conversationId)
            is SummaryLinkResult.SwitchAccountRequested -> {
                pendingSwitch = result.request
                MainEvent.ConfirmAccountSwitch(currentUserId = result.currentUserId, requestedUserId = result.request.userId)
            }
            is SummaryLinkResult.LoginRequired -> MainEvent.SummaryLoginRequired(result.request.userId, result.loggedOut)
            SummaryLinkResult.Cancelled -> MainEvent.SummaryCancelled
            SummaryLinkResult.Ignored -> return
        }
        _events.send(event)
    }
}
