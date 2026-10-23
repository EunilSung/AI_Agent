package com.eunilsung.aiagent.ui.uikit.dialog

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 버튼 문구는 null 이면 DialogHost 가 로케일 리소스(확인/취소)로 채운다. Talk 의 DialogManager 에서 알림·확인만 옮겨 왔다.
 * 알림에 제목([DialogRequest.Alert.title])을 붙일 수 있는 것만 Talk 와 다르다 — Talk 요약 링크의 로그인 안내에 쓴다.
 */
interface DialogManager {
    fun show(request: DialogRequest)

    fun dismiss()

    fun alert(message: String, title: String? = null, onConfirm: () -> Unit = {}) {
        show(DialogRequest.Alert(message = message, title = title, onConfirm = onConfirm))
    }

    fun confirm(
        title: String,
        message: String,
        confirmText: String? = null,
        dismissText: String? = null,
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit
    ) {
        show(
            DialogRequest.Confirm(
                title = title,
                message = message,
                confirmText = confirmText,
                dismissText = dismissText,
                onCancel = onCancel,
                onConfirm = onConfirm
            )
        )
    }
}

sealed interface DialogRequest {
    data class Alert(
        val message: String,
        /** 있으면 본문 위에 굵게 보인다. */
        val title: String? = null,
        val confirmText: String? = null,
        val onConfirm: () -> Unit = {}
    ) : DialogRequest

    data class Confirm(
        val title: String,
        val message: String,
        val confirmText: String? = null,
        val dismissText: String? = null,
        val onCancel: () -> Unit = {},
        val onConfirm: () -> Unit
    ) : DialogRequest
}

val LocalDialogManager = staticCompositionLocalOf<DialogManager> {
    object : DialogManager {
        override fun show(request: DialogRequest) { }
        override fun dismiss() { }
    }
}
