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
        LoginUiState(id = loginUseCases.getLastLoginId(), serverUrl = loginUseCases.observeServerUrl().value)
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loginUseCases.observeServerUrl().collect { url -> _uiState.update { it.copy(serverUrl = url) } }
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
                it.copy(serverInput = if (it.serverInput == null) it.serverUrl else null)
            }
            is LoginActions.OnServerInputChange -> _uiState.update { it.copy(serverInput = action.url) }
            LoginActions.OnServerSave -> {
                _uiState.value.serverInput?.let { loginUseCases.setServerUrl(it) }
                _uiState.update { it.copy(serverInput = null) }
            }
            is LoginActions.OnAccountRequested -> _uiState.update {
                it.copy(id = action.id.trim(), pw = "", isPasswordVisible = false)
            }
        }
    }

    private fun login() {
        val state = _uiState.value
        if (!state.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(status = LoginStatus.Loading) }
            val result = loginUseCases.login(state.id, state.pw)
            _uiState.update {
                if (result is LoginResult.Success) it.copy(status = LoginStatus.Idle, pw = "")
                else it.copy(status = LoginStatus.Error(result))
            }
        }
    }
}
