package com.eunilsung.aiagent.ui.setting

sealed interface SettingActions {
    /**
     * 설정 탭이 보였다. 주소 칸을 지금 주소로 맞추고 연결 상태를 다시 본다 — 이 화면의 ViewModel 은 로그아웃해도 남아,
     * 그 사이 로그인 화면에서 주소를 바꿨거나 다시 로그인했을 수 있다.
     */
    data object OnShown : SettingActions
    data object OnRefreshStatus : SettingActions
    data class OnServerInputChange(val url: String) : SettingActions
    data object OnServerSave : SettingActions
    /** 빌드 기본 주소로 되돌린다. */
    data object OnServerReset : SettingActions
    /** 서버를 바꾸면 로그아웃된다는 확인에 [바꾸기] 를 눌렀다. */
    data object OnServerChangeConfirmed : SettingActions
    data object OnLogout : SettingActions
}

sealed interface SettingEvent {
    data object ServerSaved : SettingEvent
    /** 서버가 바뀌면 로그아웃되므로 먼저 확인을 받는다. */
    data object ConfirmServerChange : SettingEvent
    /** https 도 에뮬레이터·시뮬레이터 주소도 아니라 저장하지 않았다. */
    data object ServerInvalid : SettingEvent
}
