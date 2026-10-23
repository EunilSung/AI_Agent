package com.eunilsung.aiagent.domain.util

import com.eunilsung.aiagent.domain.model.SummaryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Talk 의 "AI 요약" 링크 읽기. Talk 는 값을 ktor 의 encodeURLParameter 로 인코딩한다(공백 %20, + 는 %2B). */
class SummaryLinkTest {

    private fun link(
        fileId: String = "f_01-AB",
        fileName: String = "report.pdf",
        userId: String = "test1",
        userName: String? = "test1",
    ): String = buildString {
        append("aiagent://summarize?fileId=$fileId&fileName=$fileName&userId=$userId")
        if (userName != null) append("&userName=$userName")
    }

    @Test
    fun `정상 링크를 요청으로 읽는다`() {
        assertEquals(
            SummaryRequest(fileId = "f_01-AB", fileName = "report.pdf", userId = "test1", userName = "test1"),
            SummaryLink.parse(link()),
        )
    }

    @Test
    fun `퍼센트 인코딩한 한글 파일명과 이름 공백을 푼다`() {
        val request = SummaryLink.parse(
            link(
                fileName = "2026%EB%85%84%20%EC%82%AC%EC%97%85%20%EA%B3%84%ED%9A%8D.hwp",
                userName = "%EC%9D%B4%EC%84%9C%EC%97%B0",
            )
        )
        assertEquals("2026년 사업 계획.hwp", request?.fileName)
        assertEquals("이서연", request?.userName)
    }

    @Test
    fun `더하기는 글자 그대로이고 인코딩한 더하기도 더하기다`() {
        assertEquals("a+b c+d.txt", SummaryLink.parse(link(fileName = "a+b%20c%2Bd.txt"))?.fileName)
    }

    @Test
    fun `이름이 없거나 비면 아이디를 이름으로 쓴다`() {
        assertEquals("test2", SummaryLink.parse(link(userId = "test2", userName = null))?.userName)
        assertEquals("test2", SummaryLink.parse(link(userId = "test2", userName = ""))?.userName)
        assertEquals("test2", SummaryLink.parse(link(userId = "test2", userName = "%20%20"))?.userName)
    }

    @Test
    fun `순서가 달라도 되고 대소문자가 다른 스킴과 끝의 빗금도 받는다`() {
        val request = SummaryLink.parse("AIAGENT://summarize/?userId=test1&fileName=a.txt&fileId=x1#frag")
        assertEquals(SummaryRequest("x1", "a.txt", "test1", "test1"), request)
    }

    @Test
    fun `요약 링크가 아니면 버린다`() {
        assertNull(SummaryLink.parse("https://summarize?fileId=a&fileName=b&userId=c"))
        assertNull(SummaryLink.parse("aiagent://open?fileId=a&fileName=b&userId=c"))
        assertNull(SummaryLink.parse("aiagent://summarize"))
        assertNull(SummaryLink.parse("summarize?fileId=a&fileName=b&userId=c"))
        assertNull(SummaryLink.parse(""))
    }

    @Test
    fun `값이 빠졌으면 버린다`() {
        assertNull(SummaryLink.parse("aiagent://summarize?fileName=a.txt&userId=test1"))
        assertNull(SummaryLink.parse("aiagent://summarize?fileId=x1&userId=test1"))
        assertNull(SummaryLink.parse("aiagent://summarize?fileId=x1&fileName=a.txt"))
        assertNull(SummaryLink.parse(link(fileName = "")))
        assertNull(SummaryLink.parse(link(fileName = "%20")))
    }

    @Test
    fun `파일 id 와 아이디의 모양이 맞지 않으면 버린다`() {
        assertNull(SummaryLink.parse(link(fileId = "..%2F..%2Fetc")))
        assertNull(SummaryLink.parse(link(fileId = "a b")))
        assertNull(SummaryLink.parse(link(fileId = "a".repeat(129))))
        assertEquals("a".repeat(128), SummaryLink.parse(link(fileId = "a".repeat(128)))?.fileId)
        assertNull(SummaryLink.parse(link(userId = "test1%3Bdrop")))
        assertNull(SummaryLink.parse(link(userId = "u".repeat(65))))
        assertEquals("kim.mj@corp", SummaryLink.parse(link(userId = "kim.mj%40corp"))?.userId)
    }

    @Test
    fun `파일명과 이름의 길이와 제어 문자를 본다`() {
        assertEquals(255, SummaryLink.parse(link(fileName = "가".repeat(255)))?.fileName?.length)
        assertNull(SummaryLink.parse(link(fileName = "가".repeat(256))))
        assertNull(SummaryLink.parse(link(fileName = "a%0Ab.txt")))
        assertEquals(64, SummaryLink.parse(link(userName = "나".repeat(64)))?.userName?.length)
        assertNull(SummaryLink.parse(link(userName = "나".repeat(65))))
        assertNull(SummaryLink.parse(link(userName = "a%00b")))
    }

    @Test
    fun `인코딩이 깨졌으면 버린다`() {
        assertNull(SummaryLink.parse(link(fileName = "a%2")))
        assertNull(SummaryLink.parse(link(fileName = "a%zz.txt")))
        assertNull(SummaryLink.parse(link(fileName = "a%+1.txt")))
        assertNull(SummaryLink.parse(link(fileName = "%FF%FE.txt")))
    }

    @Test
    fun `같은 이름이 여럿이면 앞의 값을 쓴다`() {
        assertEquals("test1", SummaryLink.parse(link() + "&userId=test9")?.userId)
    }
}
