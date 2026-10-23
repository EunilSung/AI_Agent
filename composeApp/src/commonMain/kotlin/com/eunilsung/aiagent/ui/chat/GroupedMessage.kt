package com.eunilsung.aiagent.ui.chat

import androidx.compose.runtime.Immutable
import com.eunilsung.aiagent.domain.model.ChatMessage

/** 화면 렌더링 단위 — 메시지 하나에 묶음·날짜선 같은 화면 판단을 얹은 것. Talk 의 GroupedChat 과 같다. */
@Immutable
data class GroupedMessage(
    val message: ChatMessage,
    val showProfileAndName: Boolean,
    val showDate: Boolean,
    val showTime: Boolean,
)
