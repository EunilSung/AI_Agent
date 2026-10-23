package com.eunilsung.aiagent.bridge.limit

/**
 * 사람마다 최근 한 시간 동안 보낸 요청 수를 세어 [perHour] 를 넘으면 막는다.
 *
 * 브릿지를 터널로 바깥에 열면 로그인한 사람 한 명이 LLM 을 붙잡고 있지 않게 하는 마지막 안전장치다.
 * 서버를 다시 띄우면 처음부터 센다.
 */
class RateLimiter(
    private val perHour: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val history = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(userId: String): Boolean {
        val windowStart = now() - HOUR_MILLIS
        val times = history.getOrPut(userId) { ArrayDeque() }
        while (times.isNotEmpty() && times.first() <= windowStart) times.removeFirst()
        if (times.size >= perHour) return false
        times.addLast(now())
        return true
    }

    private companion object {
        const val HOUR_MILLIS = 60 * 60_000L
    }
}
