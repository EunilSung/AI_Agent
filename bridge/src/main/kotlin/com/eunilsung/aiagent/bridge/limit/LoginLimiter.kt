package com.eunilsung.aiagent.bridge.limit

/**
 * 로그인 시도를 주소마다 1분에 [perAddressPerMinute] 번, 모든 주소를 합쳐 1초에 [globalPerSecond] 번까지만 Talk 에 넘긴다.
 *
 * Talk 는 한 아이디에 연달아 틀리면 그 아이디를 잠근다. 그것만으로는 (a) 여러 아이디에 흔한 비밀번호를 하나씩 넣어 보는 것,
 * (b) 남의 아이디를 일부러 틀려 계속 잠그는 것, (c) 시도마다 Talk 가 도는 비밀번호 해시로 이 기기의 CPU 를 채우는 것을
 * 막지 못한다. 브릿지는 인증 없이 바깥에서 닿는 로그인 대행이라 여기서 먼저 줄인다.
 *
 * 기록은 받아 준 시도만 남긴다. 받아 준 시도는 1초에 [globalPerSecond] 번뿐이라 주소 기록은 1분에 그 60 배를 넘지 않는다.
 * 서버를 다시 띄우면 처음부터 센다.
 */
class LoginLimiter(
    private val perAddressPerMinute: Int,
    private val globalPerSecond: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val byAddress = HashMap<String, ArrayDeque<Long>>()
    private val global = ArrayDeque<Long>()

    /** 이번 시도를 받아 주면 null, 아니면 다시 해 볼 수 있을 때까지의 초(1 이상). */
    @Synchronized
    fun tryAcquire(address: String): Long? {
        val at = now()
        global.dropBefore(at - SECOND_MILLIS)
        byAddress.values.removeAll { it.dropBefore(at - MINUTE_MILLIS); it.isEmpty() }
        val mine = byAddress[address]
        if (mine != null && mine.size >= perAddressPerMinute) return secondsUntil(mine.first() + MINUTE_MILLIS, at)
        if (global.size >= globalPerSecond) return secondsUntil(global.first() + SECOND_MILLIS, at)
        global.addLast(at)
        (mine ?: ArrayDeque<Long>().also { byAddress[address] = it }).addLast(at)
        return null
    }

    /** 기록을 들고 있는 주소 수. 테스트가 기록이 끝없이 쌓이지 않는지 본다. */
    @Synchronized
    internal fun trackedAddresses(): Int = byAddress.size

    private fun ArrayDeque<Long>.dropBefore(windowStart: Long) {
        while (isNotEmpty() && first() <= windowStart) removeFirst()
    }

    private fun secondsUntil(until: Long, at: Long): Long = ((until - at + 999) / 1000).coerceAtLeast(1)

    private companion object {
        const val SECOND_MILLIS = 1_000L
        const val MINUTE_MILLIS = 60_000L
    }
}
