package com.eunilsung.aiagent.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.usecase.SettingUseCases
import kotlinx.coroutines.Job
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
    private val defaultUrl = useCases.getDefaultServerUrl()

    /** 바꾸겠다는 확인을 받는 중인 서버 주소. */
    private var pendingServer: String? = null

    val uiState: StateFlow<SettingUiState> = combine(
        useCases.observeSession(),
        useCases.observeServerUrl(),
        serverInput,
        status,
    ) { me, url, input, current ->
        SettingUiState(me = me, serverUrl = url, serverInput = input, status = current, defaultUrl = defaultUrl)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingUiState(defaultUrl = defaultUrl))

    private val _events = Channel<SettingEvent>(Channel.BUFFERED)
    val events: Flow<SettingEvent> = _events.receiveAsFlow()

    /** 진행 중인 연결 상태 확인. 서버를 바꾸면 멈춰, 앞 서버의 늦은 응답이 새 서버의 상태를 덮지 않게 한다. */
    private var statusJob: Job? = null

    fun onAction(action: SettingActions) {
        when (action) {
            SettingActions.OnShown -> {
                serverInput.value = useCases.observeServerUrl().value
                refreshStatus()
            }
            SettingActions.OnRefreshStatus -> refreshStatus()
            is SettingActions.OnServerInputChange -> serverInput.value = action.url
            SettingActions.OnServerSave -> requestServerChange(serverInput.value)
            SettingActions.OnServerReset -> requestServerChange("")
            SettingActions.OnServerChangeConfirmed -> pendingServer?.let(::changeServer)
            SettingActions.OnLogout -> viewModelScope.launch { useCases.logout() }
        }
    }

    /** 쓸 수 없는 주소면 알린다. 다른 서버로 바뀌면 로그아웃된다는 확인을 먼저 받는다. */
    private fun requestServerChange(url: String) {
        when (useCases.changeServer.moves(url)) {
            null -> viewModelScope.launch { _events.send(SettingEvent.ServerInvalid) }
            false -> serverInput.value = useCases.observeServerUrl().value
            true -> {
                pendingServer = url
                viewModelScope.launch { _events.send(SettingEvent.ConfirmServerChange) }
            }
        }
    }

    /**
     * 로그아웃한 뒤 주소를 바꾼다([com.eunilsung.aiagent.domain.usecase.ChangeServerUseCase]). 보던 연결 상태는 버린다 —
     * 새 서버에 다시 로그인한 뒤 설정 화면을 열면 그때 본다(그 전에는 새 서버의 키가 없을 수 있다).
     */
    private fun changeServer(url: String) {
        pendingServer = null
        statusJob?.cancel()
        status.value = null
        viewModelScope.launch {
            if (!useCases.changeServer(url)) {
                _events.send(SettingEvent.ServerInvalid)
                return@launch
            }
            serverInput.value = useCases.observeServerUrl().value
            _events.send(SettingEvent.ServerSaved)
        }
    }

    /** 연결 상태를 다시 본다. 설정 화면이 보일 때마다([SettingActions.OnShown]) 와 [새로고침] 에서 부른다. */
    private fun refreshStatus() {
        statusJob?.cancel()
        status.value = null
        statusJob = viewModelScope.launch { status.value = useCases.getServiceStatus() }
    }
}
