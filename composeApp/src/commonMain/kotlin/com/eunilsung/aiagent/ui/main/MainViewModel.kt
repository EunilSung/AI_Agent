package com.eunilsung.aiagent.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.usecase.ObserveSessionUseCase
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
}

class MainViewModel(observeSession: ObserveSessionUseCase) : ViewModel() {
    private val session = observeSession()

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
                wasLoggedIn = user != null
            }
        }
    }
}
