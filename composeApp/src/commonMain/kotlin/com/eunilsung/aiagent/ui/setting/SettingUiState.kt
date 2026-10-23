package com.eunilsung.aiagent.ui.setting

import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.model.User

/** 설정 화면의 상태. */
data class SettingUiState(
    val me: User? = null,
    /** 지금 붙는 브릿지 주소. */
    val serverUrl: String = "",
    /** 입력칸의 값. 저장 전에는 [serverUrl] 과 다를 수 있다. */
    val serverInput: String = "",
    /** 확인 중이면 null. */
    val status: ServiceStatus? = null,
) {
    val canSaveServer: Boolean
        get() = serverInput.isNotBlank() && serverInput.trim().trimEnd('/') != serverUrl
}
