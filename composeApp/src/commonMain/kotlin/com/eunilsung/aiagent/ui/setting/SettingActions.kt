package com.eunilsung.aiagent.ui.setting

sealed interface SettingActions {
    data object OnRefreshStatus : SettingActions
    data class OnServerInputChange(val url: String) : SettingActions
    data object OnServerSave : SettingActions
    /** 빌드 기본 주소로 되돌린다. */
    data object OnServerReset : SettingActions
    data object OnLogout : SettingActions
}

sealed interface SettingEvent {
    data object ServerSaved : SettingEvent
    /** https 도 에뮬레이터·시뮬레이터 주소도 아니라 저장하지 않았다. */
    data object ServerInvalid : SettingEvent
}
