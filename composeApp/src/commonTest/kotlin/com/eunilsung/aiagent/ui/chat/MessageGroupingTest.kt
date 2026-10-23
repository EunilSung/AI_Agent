package com.eunilsung.aiagent.ui.chat

import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageGroupingTest {

    /** 기기 시간대의 그 시각. 시간대가 달라도 같은 날·같은 분으로 묶이게 한다. */
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime(2026, 10, day, hour, minute, second).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private fun message(id: String, role: ChatRole, createdAt: Long) =
        ChatMessage(id = id, conversationId = "c", role = role, content = id, createdAt = createdAt)

    /** 목록은 최신이 먼저 온다. */
    private fun group(vararg oldestFirst: ChatMessage) = groupMessagesForUi(oldestFirst.toList().asReversed()).associateBy { it.message.id }

    @Test
    fun `질문과 답변은 서로 다른 쪽이라 둘 다 시각이 붙고 답변에 아바타가 붙는다`() {
        val grouped = group(message("q", ChatRole.User, at(23, 15, 5)), message("a", ChatRole.Assistant, at(23, 15, 5, 30)))

        assertEquals(true, grouped.getValue("a").showProfileAndName)
        assertEquals(true, grouped.getValue("a").showTime)
        assertEquals(true, grouped.getValue("q").showTime)
    }

    @Test
    fun `같은 분에 이어진 답변은 첫 답변에만 아바타가 마지막 답변에만 시각이 붙는다`() {
        val grouped = group(message("a1", ChatRole.Assistant, at(23, 15, 5)), message("a2", ChatRole.Assistant, at(23, 15, 5, 40)))

        assertEquals(true, grouped.getValue("a1").showProfileAndName)
        assertEquals(false, grouped.getValue("a2").showProfileAndName)
        assertEquals(false, grouped.getValue("a1").showTime)
        assertEquals(true, grouped.getValue("a2").showTime)
    }

    @Test
    fun `분이 바뀌면 같은 쪽이어도 따로 묶는다`() {
        val grouped = group(message("a1", ChatRole.Assistant, at(23, 15, 5)), message("a2", ChatRole.Assistant, at(23, 15, 6)))

        assertEquals(true, grouped.getValue("a2").showProfileAndName)
        assertEquals(true, grouped.getValue("a1").showTime)
    }

    @Test
    fun `날짜선은 그날의 첫 메시지 위에만 붙는다`() {
        val grouped = group(
            message("d1", ChatRole.User, at(22, 23, 59)),
            message("d2a", ChatRole.User, at(23, 0, 1)),
            message("d2b", ChatRole.Assistant, at(23, 0, 1, 20)),
        )

        assertEquals(true, grouped.getValue("d1").showDate)
        assertEquals(true, grouped.getValue("d2a").showDate)
        assertEquals(false, grouped.getValue("d2b").showDate)
    }

    @Test
    fun `메시지가 없으면 빈 목록이다`() {
        assertEquals(emptyList(), groupMessagesForUi(emptyList()))
    }
}
