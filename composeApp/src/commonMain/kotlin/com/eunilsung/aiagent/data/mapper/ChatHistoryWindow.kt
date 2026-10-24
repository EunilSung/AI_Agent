package com.eunilsung.aiagent.data.mapper

import com.eunilsung.aiagent.shared.api.ChatMessageDto
import kotlinx.serialization.json.Json

/**
 * 브릿지 `/v1/chat` 에 보낼 대화 기록 고르기 — 이번 질문(마지막 메시지)은 늘 넣고, 그 앞은 최근 것부터 모두 [MAX_MESSAGES] 개,
 * 직렬화한 크기 [MAX_BYTES] 바이트까지만 넣는다.
 *
 * 브릿지는 메시지 50개(`ChatRoutes` 의 MAX_MESSAGES)나 본문 512KB(`BodyLimits.CHAT`)를 넘는 대화를 받지 않는다(400·413).
 * 기록을 통째로 보내면 긴 답변·요약이 쌓인 대화는 그 뒤 모든 후속 질문이 막혀 다시 쓸 수 없게 된다. RAG 서버는 최근 10개만
 * LLM 에 넣고 질문 다시 쓰기에는 그 앞 6개만 보므로, 그보다 넉넉히 보내면 답은 같다. 두 값 모두 브릿지 상한의 절반 아래로 둔다.
 */
object ChatHistoryWindow {
    /** 보낼 메시지 수의 끝(이번 질문 포함). */
    const val MAX_MESSAGES = 20

    /** 보낼 메시지들을 직렬화한 크기의 끝(바이트, UTF-8). 요청을 감싸는 `{"messages":[…]}` 와 쉼표 몫은 [ENVELOPE_BYTES] 로 센다. */
    const val MAX_BYTES = 256 * 1024

    private const val ENVELOPE_BYTES = 16

    /** [com.eunilsung.aiagent.data.remote.BridgeClient] 와 같은 설정 — 기본값 칸도 실제 요청처럼 넣고 센다. */
    private val json = Json { encodeDefaults = true }

    /** [messages] 의 마지막은 이번 질문이다. 비었으면 그대로 돌려준다. */
    fun select(messages: List<ChatMessageDto>): List<ChatMessageDto> {
        if (messages.isEmpty()) return messages
        var bytes = ENVELOPE_BYTES + sizeOf(messages.last())
        var start = messages.lastIndex
        while (start > 0 && messages.size - start < MAX_MESSAGES) {
            val next = bytes + sizeOf(messages[start - 1]) + 1
            if (next > MAX_BYTES) break
            bytes = next
            start--
        }
        return messages.subList(start, messages.size)
    }

    private fun sizeOf(message: ChatMessageDto): Int =
        json.encodeToString(ChatMessageDto.serializer(), message).encodeToByteArray().size
}
