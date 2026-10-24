package com.eunilsung.aiagent.ui.login

import com.eunilsung.aiagent.domain.model.LoginResult

/** 로그인 화면의 상태. */
data class LoginUiState(
    val id: String = "",
    val pw: String = "",
    val status: LoginStatus = LoginStatus.Idle,
    /** 비밀번호 칸의 글자를 그대로 보일지. */
    val isPasswordVisible: Boolean = false,
    /** 지금 붙는 브릿지 주소. */
    val serverUrl: String = "",
    /** 서버 주소를 고치는 중이면 입력 중인 값, 아니면 null. */
    val serverInput: String? = null,
    /** 저장하려던 서버 주소가 https 도 에뮬레이터·시뮬레이터 주소도 아니라 저장하지 않았다. 입력을 고치면 지운다. */
    val isServerInvalid: Boolean = false,
) {
    /** 아이디와 비밀번호를 다 넣었고 로그인 중이 아니면 로그인 버튼을 누를 수 있다. */
    val canSubmit: Boolean
        get() = id.isNotBlank() && pw.isNotBlank() && status !is LoginStatus.Loading
}

/** 로그인 진행 단계. */
sealed interface LoginStatus {
    data object Idle : LoginStatus
    data object Loading : LoginStatus
    data class Error(val reason: LoginResult) : LoginStatus
}
