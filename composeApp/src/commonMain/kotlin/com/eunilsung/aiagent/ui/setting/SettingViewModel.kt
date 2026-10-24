package com.eunilsung.aiagent.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.usecase.SettingUseCases
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingViewModel(
    private val useCases: SettingUseCases,
) : ViewModel() {
    private val serverInput = MutableStateFlow(useCases.observeServerUrl().value)
    private val status = MutableStateFlow<ServiceStatus?>(null)

    val uiState: StateFlow<SettingUiState> = combine(
        useCases.observeSession(),
        useCases.observeServerUrl(),
        serverInput,
        status,
    ) { me, url, input, current ->
        SettingUiState(me = me, serverUrl = url, serverInput = input, status = current)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingUiState())

    private val _events = Channel<SettingEvent>(Channel.BUFFERED)
    val events: Flow<SettingEvent> = _events.receiveAsFlow()

    init {
        refreshStatus()
    }

    fun onAction(action: SettingActions) {
        when (action) {
            SettingActions.OnRefreshStatus -> refreshStatus()
            is SettingActions.OnServerInputChange -> serverInput.value = action.url
            SettingActions.OnServerSave -> saveServer(serverInput.value)
            SettingActions.OnServerReset -> saveServer("")
            SettingActions.OnLogout -> viewModelScope.launch { useCases.logout() }
        }
    }

    private fun saveServer(url: String) {
        if (!useCases.setServerUrl(url)) {
            viewModelScope.launch { _events.send(SettingEvent.ServerInvalid) }
            return
        }
        serverInput.value = useCases.observeServerUrl().value
        viewModelScope.launch { _events.send(SettingEvent.ServerSaved) }
        refreshStatus()
    }

    private fun refreshStatus() {
        status.value = null
        viewModelScope.launch { status.value = useCases.getServiceStatus() }
    }
}
