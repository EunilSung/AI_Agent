package com.eunilsung.aiagent.ui.login

sealed interface LoginActions {
    data class OnIdChange(val id: String) : LoginActions
    data class OnPwChange(val pw: String) : LoginActions
    data object OnTogglePasswordVisible : LoginActions
    data object OnLoginClick : LoginActions
    /** 실패 안내를 닫았다. */
    data object OnResetStatus : LoginActions
    /** 서버 주소 줄을 눌러 고치기 시작하거나 그만둔다. */
    data object OnToggleServerEdit : LoginActions
    data class OnServerInputChange(val url: String) : LoginActions
    data object OnServerSave : LoginActions
    /**
     * Talk 요약 링크가 [id] 계정으로 로그인해 달라고 했다. 아이디를 그 계정으로 바꾸고 비밀번호를 비운다.
     * 로그인 화면이 이미 떠 있을 때도 온다.
     */
    data class OnAccountRequested(val id: String) : LoginActions
}
