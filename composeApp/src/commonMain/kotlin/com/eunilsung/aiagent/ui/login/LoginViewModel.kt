package com.eunilsung.aiagent.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.usecase.LoginUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val loginUseCases: LoginUseCases,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        LoginUiState(id = loginUseCases.getLastLoginId()).onServer(loginUseCases.observeServerUrl().value)
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** 서버 주소가 바뀌면(설정 화면에서 바꿔도) 그 주소에 맞게 다시 본다. */
    init {
        viewModelScope.launch {
            loginUseCases.observeServerUrl().collect { url -> _uiState.update { it.onServer(url) } }
        }
    }

    fun onAction(action: LoginActions) {
        when (action) {
            is LoginActions.OnIdChange -> _uiState.update { it.copy(id = action.id.trim()) }
            is LoginActions.OnPwChange -> _uiState.update { it.copy(pw = action.pw) }
            LoginActions.OnTogglePasswordVisible -> _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
            LoginActions.OnLoginClick -> login()
            LoginActions.OnResetStatus -> _uiState.update { it.copy(status = LoginStatus.Idle) }
            LoginActions.OnToggleServerEdit -> _uiState.update {
                if (it.isServerRequired || it.isLoggingIn) it
                else it.copy(serverInput = if (it.serverInput == null) it.serverUrl else null, isServerInvalid = false)
            }
            is LoginActions.OnServerInputChange -> _uiState.update {
                if (it.isLoggingIn) it else it.copy(serverInput = action.url, isServerInvalid = false)
            }
            LoginActions.OnServerSave -> _uiState.value.takeUnless { it.isLoggingIn }?.serverInput?.let(::saveServer)
            is LoginActions.OnAccessKeyChange -> _uiState.update { it.copy(accessKey = action.key, isAccessKeyInvalid = false) }
            LoginActions.OnToggleAccessKeyVisible -> _uiState.update { it.copy(isAccessKeyVisible = !it.isAccessKeyVisible) }
            LoginActions.OnToggleAccessKeyEdit -> _uiState.update {
                when {
                    !it.canToggleAccessKeyEdit -> it
                    it.isAccessKeyEditing -> it.copy(isAccessKeyEditing = false, accessKey = "", isAccessKeyVisible = false, isAccessKeyInvalid = false)
                    else -> it.copy(isAccessKeyEditing = true)
                }
            }
            is LoginActions.OnAccountRequested -> _uiState.update {
                it.copy(id = action.id.trim(), pw = "", isPasswordVisible = false)
            }
        }
    }

    /**
     * [url] 을 지금 서버로 하는 상태. 서버 주소를 정했는지와 그 주소로 보낼 접속 키가 있는지 다시 본다 — 키는 넣을 때의 주소에 묶인다.
     *
     * 주소가 바뀌면 넣던 접속 키와 키 거절·형식 안내를 지운다. 앞 서버를 위해 넣던 키가 새 서버로 가지 않게 한다.
     * 주소를 정하지 않았으면 서버 주소 칸을 열어 둔다.
     */
    private fun LoginUiState.onServer(url: String): LoginUiState {
        val moved = url != serverUrl
        val isChosen = loginUseCases.isServerChosen()
        return copy(
            serverUrl = url,
            isServerRequired = !isChosen,
            serverInput = if (isChosen) serverInput else serverInput ?: "",
            isAccessKeyMissing = !loginUseCases.hasAccessKey(),
            accessKey = if (moved) "" else accessKey,
            isAccessKeyRejected = isAccessKeyRejected && !moved,
            isAccessKeyInvalid = isAccessKeyInvalid && !moved,
            isAccessKeyEditing = isAccessKeyEditing && !moved,
            isAccessKeyVisible = isAccessKeyVisible && !moved,
        )
    }

    /** 쓸 수 없는 주소면 저장하지 않고 입력칸을 연 채로 안내한다. @return 저장했으면 true. */
    private fun saveServer(input: String): Boolean {
        if (!loginUseCases.setServerUrl(input)) {
            _uiState.update { it.copy(isServerInvalid = true) }
            return false
        }
        _uiState.update { it.copy(serverInput = null, isServerInvalid = false).onServer(loginUseCases.observeServerUrl().value) }
        return true
    }

    /**
     * 서버 주소 칸이 열려 있으면 그 주소를 먼저 저장하고, 접속 키 칸이 열려 있으면 넣은 키를 그 주소에 묶어 저장한 뒤 로그인한다.
     *
     * 주소나 키의 형식이 맞지 않으면 로그인하지 않고 칸에서 알린다. 주소를 바꿔 그 서버의 키가 필요해졌는데 키를 넣지 않았으면
     * 키 칸을 열고 멈춘다. 넣은 키는 결과와 상관없이 화면 상태에서 지운다(잠가 저장한 것만 남는다). 서버가 키를 받지 않았을 때만
     * 칸을 비워 연 채 다시 넣게 한다 — 브릿지는 키를 먼저 보므로, 다른 이유의 실패(비밀번호 등)는 키를 받았다는 뜻이다.
     * 로그인 응답을 기다리는 동안에는 서버 주소를 바꾸지 못한다. 앞 서버가 준 토큰이 바뀐 주소로 실리지 않게 한다.
     */
    private fun login() {
        val typed = _uiState.value
        if (!typed.canSubmit) return
        if (typed.serverInput != null && !saveServer(typed.serverInput)) return
        val state = _uiState.value
        if (state.isAccessKeyFieldShown) {
            if (typed.accessKey.isBlank()) return
            if (!loginUseCases.setAccessKey(typed.accessKey)) {
                _uiState.update { it.copy(accessKey = typed.accessKey, isAccessKeyInvalid = true) }
                return
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(status = LoginStatus.Loading) }
            val result = loginUseCases.login(state.id, state.pw)
            _uiState.update {
                val settled = it.copy(
                    accessKey = "",
                    isAccessKeyVisible = false,
                    isAccessKeyEditing = false,
                    isAccessKeyMissing = !loginUseCases.hasAccessKey(),
                )
                when (result) {
                    is LoginResult.Success -> settled.copy(status = LoginStatus.Idle, pw = "", isAccessKeyRejected = false)
                    else -> settled.copy(status = LoginStatus.Error(result), isAccessKeyRejected = result.isAccessKeyRefusal)
                }
            }
        }
    }
}
