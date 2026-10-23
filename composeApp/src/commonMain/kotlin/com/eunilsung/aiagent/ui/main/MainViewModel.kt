package com.eunilsung.aiagent.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.model.SummaryLinkResult
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.usecase.MainUseCases
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 앱 전체 상태 — 로그인했는지. */
data class MainUiState(
    val me: User? = null,
) {
    val isLoggedIn: Boolean get() = me != null
}

/** 앱 전체가 한 번만 처리할 일. */
sealed interface MainEvent {
    /** 로그아웃했다. 열려 있던 대화방을 닫는다. */
    data object LoggedOut : MainEvent

    /** Talk 요약 링크로 이 대화를 연다. 대화방이 열리면 요약이 시작된다. */
    data class OpenChat(val conversationId: String) : MainEvent

    /**
     * Talk 요약 링크가 다른 계정(또는 로그인 전)의 요청이다. 로그인 칸의 아이디를 [userId] 로 바꾸고 안내를 띄운다.
     *
     * @property loggedOut 다른 계정에서 로그아웃했으면 true.
     */
    data class SummaryLoginRequired(val userId: String, val userName: String, val loggedOut: Boolean) : MainEvent

    /** 요약을 요청한 계정이 아닌 계정으로 로그인해 요약을 취소했다. */
    data object SummaryCancelled : MainEvent
}

/**
 * 앱 전체 — 로그인 상태와 Talk 요약 링크. 링크는 들어온 차례로 하나씩 처리한다.
 * 로그인을 지켜보다가 로그인하면 기다리던 요약을 이어 간다.
 */
class MainViewModel(private val useCases: MainUseCases) : ViewModel() {
    private val session = useCases.observeSession()

    val uiState: StateFlow<MainUiState> = session
        .map { MainUiState(me = it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState(me = session.value))

    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events: Flow<MainEvent> = _events.receiveAsFlow()

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
            useCases.observeSummaryLinks().collect { link -> send(useCases.handleSummaryLink(link)) }
        }
    }

    private suspend fun send(result: SummaryLinkResult) {
        val event = when (result) {
            is SummaryLinkResult.Open -> MainEvent.OpenChat(result.conversationId)
            is SummaryLinkResult.LoginRequired ->
                MainEvent.SummaryLoginRequired(result.request.userId, result.request.userName, result.loggedOut)
            SummaryLinkResult.Cancelled -> MainEvent.SummaryCancelled
            SummaryLinkResult.Ignored -> return
        }
        _events.send(event)
    }
}
