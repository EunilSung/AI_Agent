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
}
