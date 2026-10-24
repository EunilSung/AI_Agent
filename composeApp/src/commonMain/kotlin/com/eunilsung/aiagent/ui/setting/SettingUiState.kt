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
    /** 빌드 기본 주소. 그대로 쓰면 안 되는 빌드(공개 릴리스)면 null 이고 [기본값] 을 숨긴다. */
    val defaultUrl: String? = null,
) {
    val canSaveServer: Boolean
        get() = serverInput.isNotBlank() && serverInput.trim().trimEnd('/') != serverUrl

    /** [기본값] 을 보일지 — 기본 주소를 써도 되는 빌드이고 지금 주소가 기본 주소가 아닐 때. */
    val canResetServer: Boolean
        get() = defaultUrl != null && defaultUrl != serverUrl
}
