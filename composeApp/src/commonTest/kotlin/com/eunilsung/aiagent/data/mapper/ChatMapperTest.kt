package com.eunilsung.aiagent.data.mapper

import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendRequest
import com.eunilsung.aiagent.domain.model.SendStatus
import com.eunilsung.aiagent.shared.api.ChatMessageDto
import com.eunilsung.aiagent.shared.api.SendActionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMapperTest {

    private val mapper = ChatMapper()
    private val seoyeon = Person("test2", "이서연", "개발1팀 대리")

    private fun card(request: SendRequest) = ChatMessage("a", "c", ChatRole.Assistant, "", createdAt = 1, sendRequest = request)

    @Test
    fun `보낸 카드는 글이 아니라 보낸 사람 이름과 보냄으로 넘긴다`() {
        val sent = card(SendRequest("서연", "회의는 10시입니다.", listOf(seoyeon), SendStatus.Sent, sentTo = seoyeon))

        assertEquals(
            ChatMessageDto(role = "assistant", content = "", sendAction = SendActionDto("이서연", "회의는 10시입니다.", sent = true)),
            mapper.toDto(sent),
        )
    }

    @Test
    fun `확인하지 않았거나 취소한 카드는 사용자가 말한 받는 사람과 안 보냄으로 넘긴다`() {
        for (status in listOf(SendStatus.Pending, SendStatus.Cancelled, SendStatus.Failed)) {
            val dto = mapper.toDto(card(SendRequest("서연", "회의는 10시입니다.", listOf(seoyeon), status)))
            assertEquals(SendActionDto("서연", "회의는 10시입니다.", sent = false), dto.sendAction)
        }
    }

    @Test
    fun `보통 질문과 답변은 글 그대로 넘긴다`() {
        val question = ChatMessage("q", "c", ChatRole.User, "반차는 몇 시간이야?", createdAt = 1)
        assertEquals(ChatMessageDto(role = "user", content = "반차는 몇 시간이야?"), mapper.toDto(question))
    }

    @Test
    fun `파일 요약의 질문과 답변은 요약으로 표시해 넘긴다`() {
        val asked = ChatMessage("q", "c", ChatRole.User, "보고서.pdf 요약해 줘", createdAt = 1, summary = true)
        val summary = ChatMessage("a", "c", ChatRole.Assistant, "한 줄 요약", createdAt = 2, summary = true)

        assertEquals(ChatMessageDto(role = "user", content = "보고서.pdf 요약해 줘", summary = true), mapper.toDto(asked))
        assertEquals(ChatMessageDto(role = "assistant", content = "한 줄 요약", summary = true), mapper.toDto(summary))
    }

    @Test
    fun `스트림 오류는 code 로만 가리고 message 는 이유를 바꾸지 않는다`() {
        val internal = "Server error '500 Internal Server Error' for url 'http://localhost:11434/v1/chat/completions'"
        assertEquals(AnswerFailure.Server(internal), mapper.streamFailure("""{"message":"$internal","code":""}"""))
        assertEquals(AnswerFailure.Unreachable, mapper.streamFailure("""{"message":"stream interrupted","code":"stream_interrupted"}"""))
        assertEquals(AnswerFailure.Unreachable, mapper.streamFailure("""{"message":"","code":"stream_interrupted"}"""))
        assertEquals(AnswerFailure.NoText, mapper.streamFailure("""{"message":"stream interrupted","code":"no_text"}"""))
        assertEquals(AnswerFailure.Server(""), mapper.streamFailure("oops"))
    }

    @Test
    fun `done 의 notFound 를 읽고 예전 서버의 빈 done 은 찾은 것으로 본다`() {
        assertTrue(mapper.notFound("""{"notFound":true}"""))
        assertFalse(mapper.notFound("{}"))
        assertFalse(mapper.notFound(""))
    }
}
