package com.eunilsung.aiagent.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class SseParserTest {

    private fun parseAll(body: String): List<SseEvent> {
        val parser = SseParser()
        return body.lines().mapNotNull(parser::feed) + listOfNotNull(parser.finish())
    }

    @Test
    fun `이름 있는 이벤트와 없는 이벤트를 나누고 주석은 버린다`() {
        val events = parseAll(
            """
            : connected

            event: sources
            data: {"sources":[]}

            data: {"delta":"안녕"}

            event: done
            data: {}
            """.trimIndent()
        )
        assertEquals(
            listOf(SseEvent("sources", """{"sources":[]}"""), SseEvent(null, """{"delta":"안녕"}"""), SseEvent("done", "{}")),
            events,
        )
    }

    @Test
    fun `여러 줄 data 는 줄바꿈으로 잇는다`() {
        assertEquals(listOf(SseEvent(null, "a\nb")), parseAll("data: a\ndata: b\n\n"))
    }
}
