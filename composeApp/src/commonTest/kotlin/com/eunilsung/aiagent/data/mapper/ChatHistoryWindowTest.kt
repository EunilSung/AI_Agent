package com.eunilsung.aiagent.data.mapper

import com.eunilsung.aiagent.shared.api.ChatMessageDto
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.ChatRole
import com.eunilsung.aiagent.shared.api.SendActionDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 브릿지에 보낼 대화 기록 고르기 — 이번 질문은 늘 넣고, 그 앞은 최근 것부터 개수·크기 상한 안에서만 넣는다.
 *
 * 기록을 통째로 보내면 긴 답변·요약이 쌓인 대화는 브릿지 상한(메시지 50개·본문 512KB)에 걸려 그 뒤 모든 후속 질문이
 * 413·400 으로 막힌다. RAG 서버는 최근 10개만 쓰므로 그보다 많이 보내면 답은 같다.
 */
class ChatHistoryWindowTest {

    private val json = Json { encodeDefaults = true }

    private fun message(index: Int, content: String = "메시지 $index", summary: Boolean = false) = ChatMessageDto(
        role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
        content = content,
        summary = summary,
    )

    private fun requestBytes(messages: List<ChatMessageDto>): Int =
        json.encodeToString(ChatRequest.serializer(), ChatRequest(messages)).encodeToByteArray().size

    @Test
    fun `짧은 대화는 그대로 보낸다`() {
        val messages = (0..4).map { message(it) }
        assertEquals(messages, ChatHistoryWindow.select(messages))
        assertEquals(emptyList(), ChatHistoryWindow.select(emptyList()))
    }

    @Test
    fun `메시지가 많으면 이번 질문과 그 앞의 최근 것만 보낸다`() {
        val messages = (0..60).map { message(it) }

        val selected = ChatHistoryWindow.select(messages)

        assertEquals(ChatHistoryWindow.MAX_MESSAGES, selected.size)
        assertEquals(messages.takeLast(ChatHistoryWindow.MAX_MESSAGES), selected, "최근 것을 순서대로 이어서 보내야 한다")
        assertEquals(messages.last(), selected.last())
    }

    /** 한글 8000자(브릿지가 받는 메시지 하나의 끝)는 UTF-8 로 약 24KB 다. 이런 답변·요약이 쌓여도 상한 안으로 보낸다. */
    @Test
    fun `긴 메시지가 쌓이면 크기 상한 안의 최근 것만 보낸다`() {
        val long = "가".repeat(8_000)
        val messages = (0..40).map { message(it, content = long, summary = it % 4 == 1) } + message(42, "이어서 질문")

        val selected = ChatHistoryWindow.select(messages)

        assertTrue(requestBytes(selected) <= ChatHistoryWindow.MAX_BYTES, "보낼 본문이 ${requestBytes(selected)} 바이트다")
        assertEquals(messages.takeLast(selected.size), selected)
        assertEquals("이어서 질문", selected.last().content)
        assertTrue(selected.size >= RAG_USED_MESSAGES, "RAG 가 쓰는 최근 ${RAG_USED_MESSAGES}개보다 적게 보냈다: ${selected.size}")
    }

    @Test
    fun `보내기 카드 기록도 크기에 넣어 센다`() {
        val action = ChatMessageDto(ChatRole.ASSISTANT, "", SendActionDto("이서연", "나".repeat(6_000), sent = true))
        val messages = List(30) { action } + message(30, "또 보내 줘")

        val selected = ChatHistoryWindow.select(messages)

        assertTrue(requestBytes(selected) <= ChatHistoryWindow.MAX_BYTES)
        assertEquals("또 보내 줘", selected.last().content)
    }

    @Test
    fun `이번 질문 하나가 상한보다 커도 질문은 보낸다`() {
        val huge = message(0, "다".repeat(ChatHistoryWindow.MAX_BYTES))
        val messages = listOf(message(1), huge)

        assertEquals(listOf(huge), ChatHistoryWindow.select(messages))
    }

    /** 앱의 상한은 브릿지 상한의 절반 아래이고, RAG 가 쓰는 최근 10개보다 많다. 브릿지·RAG 값은 그쪽 코드에서 옮겨 적었다. */
    @Test
    fun `앱의 상한은 브릿지 상한 안에 있고 RAG 가 쓰는 기록보다 넉넉하다`() {
        assertTrue(ChatHistoryWindow.MAX_MESSAGES * 2 <= BRIDGE_MAX_MESSAGES)
        assertTrue(ChatHistoryWindow.MAX_BYTES * 2 <= BRIDGE_MAX_CHAT_BYTES)
        assertTrue(ChatHistoryWindow.MAX_MESSAGES >= RAG_USED_MESSAGES)
    }

    companion object {
        /** 브릿지 `routes/ChatRoutes.kt` 의 MAX_MESSAGES. */
        const val BRIDGE_MAX_MESSAGES = 50

        /** 브릿지 `routes/RequestBody.kt` 의 `BodyLimits.CHAT`. */
        const val BRIDGE_MAX_CHAT_BYTES = 512 * 1024

        /** RAG 서버 `app.py` 가 LLM 에 넣는 최근 메시지 수(`req.messages[-10:]`). */
        const val RAG_USED_MESSAGES = 10
    }
}
