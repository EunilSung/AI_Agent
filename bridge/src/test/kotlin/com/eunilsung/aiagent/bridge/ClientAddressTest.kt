package com.eunilsung.aiagent.bridge

import com.eunilsung.aiagent.bridge.http.clientAddress
import com.eunilsung.aiagent.bridge.http.isDirectLocal
import com.eunilsung.aiagent.bridge.http.isLoopbackAddress
import com.eunilsung.aiagent.bridge.http.isProxied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 요청을 보낸 쪽의 주소와 프록시를 거쳤는지. 테스트 엔진(testApplication)의 연결 주소는 늘 `localhost` 라
 * 루프백이 아닌 연결은 이 순수 함수로 본다.
 */
class ClientAddressTest {

    @Test
    fun `바깥 주소에서 바로 온 연결은 지어 보낸 X-Forwarded-For 를 믿지 않는다`() {
        assertEquals("192.168.0.20", clientAddress("192.168.0.20", listOf("127.0.0.1")))
        assertEquals("192.168.0.20", clientAddress("192.168.0.20", listOf("10.0.0.1, 203.0.113.7")))
        assertEquals("203.0.113.9", clientAddress("203.0.113.9", null))
        assertEquals("fe80::1", clientAddress("fe80::1", listOf("127.0.0.1")))
    }

    @Test
    fun `루프백 연결은 프록시가 맨 뒤에 붙인 값을 쓴다`() {
        assertEquals("203.0.113.7", clientAddress("127.0.0.1", listOf("1.1.1.1, 203.0.113.7")))
        assertEquals("203.0.113.8", clientAddress("127.0.0.1", listOf("1.1.1.1", "2.2.2.2 , 203.0.113.8 ,")))
        assertEquals("203.0.113.7", clientAddress("::1", listOf("203.0.113.7")))
        assertEquals("203.0.113.7", clientAddress("[::1]", listOf("203.0.113.7")))
        assertEquals("203.0.113.7", clientAddress("localhost", listOf("203.0.113.7")))
        assertEquals("127.0.0.1", clientAddress("127.0.0.1", null))
        assertEquals("127.0.0.1", clientAddress("127.0.0.1", listOf(" , ")))
        assertEquals(64, clientAddress("127.0.0.1", listOf("x".repeat(500))).length)
    }

    @Test
    fun `루프백 판정은 IP 글자와 localhost 만 보고 이름을 풀지 않는다`() {
        for (address in listOf("127.0.0.1", "127.0.0.2", "::1", "[::1]", "0:0:0:0:0:0:0:1", "localhost", "LOCALHOST")) {
            assertTrue(isLoopbackAddress(address), address)
        }
        for (address in listOf("10.0.2.2", "192.168.0.20", "203.0.113.7", "fe80::1", "localhost.example.com", "my-mac", "")) {
            assertFalse(isLoopbackAddress(address), address)
        }
    }

    @Test
    fun `프록시 헤더가 하나라도 있으면 프록시를 거친 요청이다`() {
        for (name in listOf("X-Forwarded-For", "x-forwarded-for", "X-Forwarded-Proto", "X-Forwarded-Host", "Forwarded", "X-Real-IP", "Via", "Tailscale-Funnel-Request", "tailscale-user-login")) {
            assertTrue(isProxied(setOf("Host", name)), name)
        }
        assertFalse(isProxied(setOf("Host", "Accept", "Content-Type", "X-Bridge-Key", "Authorization")))
    }

    @Test
    fun `접속 키 없이 받는 요청은 이 기기 안에서 프록시 없이 바로 온 것뿐이다`() {
        assertTrue(isDirectLocal("127.0.0.1", setOf("Host", "Accept")))
        assertTrue(isDirectLocal("localhost", emptySet()))
        assertFalse(isDirectLocal("127.0.0.1", setOf("Host", "X-Forwarded-For")))
        assertFalse(isDirectLocal("::1", setOf("Tailscale-Funnel-Request")))
        assertFalse(isDirectLocal("192.168.0.20", setOf("Host")))
    }
}
