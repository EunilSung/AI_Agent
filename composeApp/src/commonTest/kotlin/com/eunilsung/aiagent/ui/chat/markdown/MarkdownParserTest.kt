package com.eunilsung.aiagent.ui.chat.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownParserTest {

    @Test
    fun `제목·목록·하위 목록·문단을 나눈다`() {
        val blocks = MarkdownParser.parse(
            """
            ### 병가 신청
            당일 아침에는 이렇게 합니다.

            * **알림**: 오전 9시 30분까지
                * 메신저로 알림
            1. 사후 신청
            """.trimIndent()
        )
        assertEquals(
            listOf(
                MarkdownBlock.Heading(3, "병가 신청"),
                MarkdownBlock.Paragraph("당일 아침에는 이렇게 합니다."),
                MarkdownBlock.Bullet(0, "**알림**: 오전 9시 30분까지"),
                MarkdownBlock.Bullet(2, "메신저로 알림"),
                MarkdownBlock.Numbered(0, "1", "사후 신청"),
            ),
            blocks,
        )
    }

    @Test
    fun `표의 구분 줄은 빼고 칸을 나눈다`() {
        val blocks = MarkdownParser.parse(
            """
            | 구분 | 연차 |
            |---|:---:|
            | 신청 | 3일 전 |
            """.trimIndent()
        )
        assertEquals(listOf(MarkdownBlock.Table(listOf(listOf("구분", "연차"), listOf("신청", "3일 전")))), blocks)
    }

    @Test
    fun `받는 중이라 닫히지 않은 코드 블록도 그린다`() {
        assertEquals(listOf(MarkdownBlock.Code("val a = 1")), MarkdownParser.parse("```kotlin\nval a = 1"))
    }

    @Test
    fun `굵게·기울임·코드를 나눈다`() {
        assertEquals(
            listOf(InlineSpan("a "), InlineSpan("b", bold = true), InlineSpan(" "), InlineSpan("c", italic = true), InlineSpan(" "), InlineSpan("d", code = true)),
            MarkdownParser.parseInline("a **b** *c* `d`"),
        )
    }

    @Test
    fun `파일 이름의 밑줄은 기울임이 아니다`() {
        val text = "근거: [01_휴가규정.md], [02_재택근무.md]"
        assertEquals(listOf(InlineSpan(text)), MarkdownParser.parseInline(text))
    }

    @Test
    fun `띄어 쓴 별표는 기울임이 아니다`() {
        val text = "3 * 4 * 5"
        assertEquals(listOf(InlineSpan(text)), MarkdownParser.parseInline(text))
    }

    @Test
    fun `미리보기용 평문은 기호를 지우고 한 줄로 잇는다`() {
        val text = MarkdownParser.toPlainText(
            """
            갑작스러운 병가 발생 시 보고 방법은 다음과 같습니다.

            ### 병가 신청 방법
            * **보고:** 당일 아침 **오전 9시 30분까지** 팀장에게 `메신저`로 알려야 합니다.
            1. 사후 신청
            > 근거: [01_휴가규정.md]
            """.trimIndent()
        )
        assertEquals(
            "갑작스러운 병가 발생 시 보고 방법은 다음과 같습니다. 병가 신청 방법 보고: 당일 아침 오전 9시 30분까지 팀장에게 메신저로 알려야 합니다. 1. 사후 신청 근거: [01_휴가규정.md]",
            text,
        )
    }

    @Test
    fun `미리보기용 평문은 표의 구분 줄을 빼고 칸 글자만 남긴다`() {
        assertEquals("구분 연차 신청 3일 전", MarkdownParser.toPlainText("| 구분 | 연차 |\n|---|---|\n| 신청 | 3일 전 |"))
    }

    @Test
    fun `받는 중에 잘린 강조 기호는 글자 그대로 둔다`() {
        assertEquals("보고: **오전 9시", MarkdownParser.toPlainText("**보고:** **오전 9시"))
    }
}
