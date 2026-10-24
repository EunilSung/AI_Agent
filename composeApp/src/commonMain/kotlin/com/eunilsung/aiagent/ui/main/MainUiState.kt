package com.eunilsung.aiagent.ui.main

import com.eunilsung.aiagent.domain.model.User

/** 앱 전체 상태 — 로그인했는지. */
data class MainUiState(
    val me: User? = null,
) {
    val isLoggedIn: Boolean get() = me != null
}
