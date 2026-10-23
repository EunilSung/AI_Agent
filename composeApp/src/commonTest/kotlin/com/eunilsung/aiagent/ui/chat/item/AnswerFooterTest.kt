package com.eunilsung.aiagent.ui.chat.item

import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** 답변 말풍선 아래 "답변이 중간에 끊겼습니다" — 받은 글이 있는데 실패한 답변에만 붙는다. */
class AnswerFooterTest {

    private fun answer(content: String, status: MessageStatus) =
        ChatMessage("a", "c", ChatRole.Assistant, content, status = status, createdAt = 1)

    @Test
    fun `받은 글이 있는 실패만 끊긴 답변이다`() {
        val cases = listOf(
            answer("반차는 4시간이고", MessageStatus.Failed) to true,
            answer("", MessageStatus.Failed) to false,
            answer("4시간입니다.", MessageStatus.Done) to false,
            answer("받은 데까지", MessageStatus.Stopped) to false,
            answer("받는 중", MessageStatus.Streaming) to false,
        )
        for ((message, expected) in cases) {
            assertEquals(expected, message.isCutOff(), "${message.status} '${message.content}'")
        }
    }
}
