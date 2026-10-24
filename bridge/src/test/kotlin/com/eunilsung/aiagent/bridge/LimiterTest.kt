package com.eunilsung.aiagent.bridge

import com.eunilsung.aiagent.bridge.limit.ConcurrencyLimiter
import com.eunilsung.aiagent.bridge.limit.LoginLimiter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 로그인 시도 한도와 LLM 동시 실행 상한. */
class LimiterTest {

    @Test
    fun `로그인 한도는 주소마다 1분이 지나면 풀린다`() {
        var now = 0L
        val limiter = LoginLimiter(perAddressPerMinute = 2, globalPerSecond = 100, now = { now })
        assertNull(limiter.tryAcquire("a"))
        now += 10_000
        assertNull(limiter.tryAcquire("a"))
        assertEquals(50L, limiter.tryAcquire("a"))
        assertNull(limiter.tryAcquire("b"))
        now = 60_000
        assertNull(limiter.tryAcquire("a"), "첫 시도가 1분 창을 벗어났다")
        assertNotNull(limiter.tryAcquire("a"))
    }

    @Test
    fun `로그인 전체 한도는 1초 창이고 막힌 시도는 기록하지 않는다`() {
        var now = 0L
        val limiter = LoginLimiter(perAddressPerMinute = 100, globalPerSecond = 2, now = { now })
        assertNull(limiter.tryAcquire("a"))
        assertNull(limiter.tryAcquire("b"))
        assertEquals(1L, limiter.tryAcquire("c"))
        now = 1_000
        assertNull(limiter.tryAcquire("c"))
    }

    @Test
    fun `로그인 주소 기록은 받아 준 시도만큼만 쌓인다`() {
        var now = 0L
        val limiter = LoginLimiter(perAddressPerMinute = 10, globalPerSecond = 1, now = { now })
        repeat(10_000) { limiter.tryAcquire("addr-$it") }
        assertEquals(1, limiter.trackedAddresses(), "막힌 시도는 주소 기록을 만들지 않는다")
        now = 61_000
        assertNull(limiter.tryAcquire("addr-1"))
        assertEquals(1, limiter.trackedAddresses(), "1분이 지난 기록은 지워진다")
    }

    @Test
    fun `동시 실행은 무게로 세고 한 사람과 전체 상한을 함께 본다`() = runBlocking {
        val limiter = ConcurrencyLimiter(globalWeight = 3, perUserWeight = 2, waitMillis = 0)
        val summary = assertNotNull(limiter.acquire("u1", ConcurrencyLimiter.SUMMARY_WEIGHT))
        assertNull(limiter.acquire("u1", ConcurrencyLimiter.CHAT_WEIGHT), "u1 은 이미 무게 2")
        val chat = assertNotNull(limiter.acquire("u2", ConcurrencyLimiter.CHAT_WEIGHT))
        assertNull(limiter.acquire("u3", ConcurrencyLimiter.CHAT_WEIGHT), "전체 무게 3 을 다 썼다")
        assertEquals(3, limiter.inUse())
        summary.release()
        summary.release()
        assertEquals(1, limiter.inUse(), "두 번 돌려줘도 한 번만 센다")
        assertNotNull(limiter.acquire("u3", ConcurrencyLimiter.SUMMARY_WEIGHT))
        chat.release()
        assertEquals(2, limiter.inUse())
    }

    @Test
    fun `무게가 상한보다 커도 혼자일 때는 받는다`() = runBlocking {
        val limiter = ConcurrencyLimiter(globalWeight = 4, perUserWeight = 1, waitMillis = 0)
        val permit = assertNotNull(limiter.acquire("u1", ConcurrencyLimiter.SUMMARY_WEIGHT))
        assertNull(limiter.acquire("u1", ConcurrencyLimiter.CHAT_WEIGHT))
        permit.release()
        assertEquals(0, limiter.inUse())
    }

    @Test
    fun `자리가 날 때까지 정한 시간만큼 기다린다`() = runBlocking {
        val limiter = ConcurrencyLimiter(globalWeight = 1, perUserWeight = 1, waitMillis = 2_000)
        val first = assertNotNull(limiter.acquire("u1", 1))
        coroutineScope {
            val second = async { limiter.acquire("u2", 1) }
            delay(200)
            first.release()
            assertNotNull(second.await())
        }
        assertEquals(1, limiter.inUse())
        val short = ConcurrencyLimiter(globalWeight = 1, perUserWeight = 1, waitMillis = 150)
        assertNotNull(short.acquire("u1", 1))
        val started = System.nanoTime()
        assertNull(short.acquire("u2", 1))
        val waited = (System.nanoTime() - started) / 1_000_000
        assert(waited in 140..2_000) { "기다린 시간 ${waited}ms" }
    }
}
