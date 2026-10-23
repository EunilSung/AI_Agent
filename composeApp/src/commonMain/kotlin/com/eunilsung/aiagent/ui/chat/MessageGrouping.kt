package com.eunilsung.aiagent.ui.chat

import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.ui.util.toLocalDateTime
import kotlinx.datetime.LocalDateTime

/**
 * 메시지를 화면 단위로 묶는다. Talk 의 groupChatsForUi 와 같은 규칙이다.
 *
 * [messages] 는 최신이 먼저 오는 순서다 — 목록이 아래에서 위로 쌓인다(reverseLayout).
 *  - 아바타·이름: 같은 쪽이 같은 분에 이어 말하면 묶음의 첫 말풍선에만 붙인다.
 *  - 시각: 같은 쪽이 같은 분에 이어 말하면 묶음의 마지막 말풍선에만 붙인다.
 *  - 날짜선: 그날의 첫 메시지 위에 붙인다.
 */
fun groupMessagesForUi(messages: List<ChatMessage>): List<GroupedMessage> {
    if (messages.isEmpty()) return emptyList()

    val times = messages.map { it.createdAt.toLocalDateTime() }

    return messages.mapIndexed { i, current ->
        val curT = times[i]
        val next = messages.getOrNull(i + 1)
        val nextT = times.getOrNull(i + 1)
        val prev = messages.getOrNull(i - 1)
        val prevT = times.getOrNull(i - 1)

        GroupedMessage(
            message = current,
            showProfileAndName = next == null || current.role != next.role || !sameMinute(curT, nextT) || !sameDay(curT, nextT),
            showTime = prev == null || current.role != prev.role || !sameMinute(curT, prevT) || !sameDay(curT, prevT),
            showDate = next == null || !sameDay(curT, nextT),
        )
    }
}

private fun sameDay(a: LocalDateTime, b: LocalDateTime?): Boolean = b != null && a.date == b.date

private fun sameMinute(a: LocalDateTime, b: LocalDateTime?): Boolean = b != null && a.hour == b.hour && a.minute == b.minute
