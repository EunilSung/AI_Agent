package com.eunilsung.aiagent.ui.login

import com.eunilsung.aiagent.domain.model.BridgeRefusal
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
    /**
     * 서버 주소를 아직 정하지 않았다 — 주소·접속 키 없이 만든 공개 릴리스를 처음 켰다. [serverUrl] 은 에뮬레이터·시뮬레이터용
     * 평문 주소라 그대로 보내지 않는다. 서버 주소 칸을 맨 위에 열고, 주소를 넣어야 로그인할 수 있다.
     */
    val isServerRequired: Boolean = false,
    /** 서버 주소를 고치는 중이면 입력 중인 값, 아니면 null. 로그인을 누르면 이 값을 먼저 저장한다. */
    val serverInput: String? = null,
    /** 저장하려던 서버 주소가 https 도 에뮬레이터·시뮬레이터 주소도 아니라 저장하지 않았다. 입력을 고치면 지운다. */
    val isServerInvalid: Boolean = false,
    /** 접속 키 칸에 넣는 중인 값. 로그인할 때 지금 서버 주소에 묶어 저장한다. */
    val accessKey: String = "",
    /** 지금 서버 주소로 보낼 접속 키가 없다. */
    val isAccessKeyMissing: Boolean = false,
    /** 서버가 접속 키를 받지 않았다(틀렸거나 이 서버의 키가 아니다). 다시 넣을 수 있게 칸을 연다. */
    val isAccessKeyRejected: Boolean = false,
    /** 넣은 접속 키가 형식에 맞지 않아 저장하지 않았다. 입력을 고치면 지운다. */
    val isAccessKeyInvalid: Boolean = false,
    /** 접속 키 칸의 글자를 그대로 보일지. */
    val isAccessKeyVisible: Boolean = false,
    /** 사용자가 [접속 키 바꾸기] 로 칸을 열었다 — 키가 바뀌었을 때 서버의 거절을 기다리지 않고 새 키를 넣는다. */
    val isAccessKeyEditing: Boolean = false,
) {
    /** 접속 키 칸을 보일지 — 보낼 키가 없거나, 서버가 키를 받지 않았거나, 사용자가 키를 바꾸려 할 때. */
    val isAccessKeyFieldShown: Boolean
        get() = isAccessKeyMissing || isAccessKeyRejected || isAccessKeyEditing

    /**
     * [접속 키 바꾸기] 를 보일지 — 서버 주소를 정했고 이 주소로 보낼 키가 있을 때. 사용자가 연 칸이면 닫는 쪽으로 보인다
     * (빈 칸이나 거절로 열린 칸은 닫을 수 없다). 로그인 응답을 기다리는 동안에는 숨긴다 — 넣은 키는 이미 저장돼 앞 키로 돌아갈 수 없다.
     */
    val canToggleAccessKeyEdit: Boolean
        get() = !isServerRequired && !isAccessKeyMissing && !isAccessKeyRejected && !isLoggingIn

    /** 로그인 응답을 기다리는 중이다. 이때는 서버 주소를 바꾸지 못한다. */
    val isLoggingIn: Boolean
        get() = status is LoginStatus.Loading

    /**
     * 아이디와 비밀번호를 다 넣었고(서버 주소를 정하지 않았으면 주소도, 칸이 열렸으면 접속 키도) 로그인 중이 아니면
     * 로그인 버튼을 누를 수 있다.
     */
    val canSubmit: Boolean
        get() = id.isNotBlank() && pw.isNotBlank() && status !is LoginStatus.Loading &&
            (!isServerRequired || !serverInput.isNullOrBlank()) &&
            (!isAccessKeyFieldShown || accessKey.isNotBlank())
}

/** 접속 키 때문에 로그인이 막혔는지 — 키가 틀렸거나, 이 서버로 보낼 키가 없다. */
internal val LoginResult.isAccessKeyRefusal: Boolean
    get() = this is LoginResult.Refused &&
        (refusal == BridgeRefusal.ClientKeyInvalid || refusal == BridgeRefusal.AccessKeyNotSent)

/** 로그인 진행 단계. */
sealed interface LoginStatus {
    data object Idle : LoginStatus
    data object Loading : LoginStatus
    data class Error(val reason: LoginResult) : LoginStatus
}
