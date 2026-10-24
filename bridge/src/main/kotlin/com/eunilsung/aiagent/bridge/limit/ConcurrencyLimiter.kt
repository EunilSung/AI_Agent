package com.eunilsung.aiagent.bridge.limit

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 동시에 돌리는 LLM 일을 무게로 센다. 모두 합쳐 [globalWeight], 한 사람이 [perUserWeight] 까지다.
 *
 * 한 시간 한도([RateLimiter])만으로는 계정 몇 개로 대화·요약을 한꺼번에 수십 개 열어 Ollama(한 번에 하나씩 처리한다)의
 * 대기열을 몇 시간 치로 밀어 둘 수 있다. 자리가 없으면 [waitMillis] 동안만 기다리고, 그래도 없으면 null 을 돌려준다 —
 * 부른 쪽이 503 `busy` 로 답한다. 요약은 조각마다 LLM 을 부르므로 대화보다 무겁게 센다.
 *
 * 기다리는 동안 자리를 잡고 있지 않는다. 잠깐씩 쉬며 다시 보므로, 기다리다 취소돼도 자리가 새지 않는다.
 */
class ConcurrencyLimiter(
    private val globalWeight: Int,
    private val perUserWeight: Int,
    private val waitMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var used = 0
    private val byUser = HashMap<String, Int>()

    /** 자리를 얻으면 [Permit] 을 돌려준다. 다 쓰면 [Permit.release] 를 꼭 부른다. 못 얻으면 null. */
    suspend fun acquire(userId: String, weight: Int): Permit? {
        val take = weight.coerceIn(1, minOf(globalWeight, perUserWeight).coerceAtLeast(1))
        val deadline = now() + waitMillis
        while (true) {
            if (tryTake(userId, take)) return Permit(userId, take)
            if (now() >= deadline) return null
            delay(POLL_MILLIS)
        }
    }

    /** 지금 쓰고 있는 무게 합. 테스트가 자리가 다 돌아왔는지 본다. */
    @Synchronized
    fun inUse(): Int = used

    @Synchronized
    private fun tryTake(userId: String, weight: Int): Boolean {
        val mine = byUser[userId] ?: 0
        if (mine + weight > perUserWeight || used + weight > globalWeight) return false
        used += weight
        byUser[userId] = mine + weight
        return true
    }

    @Synchronized
    private fun giveBack(userId: String, weight: Int) {
        used -= weight
        val left = (byUser[userId] ?: 0) - weight
        if (left > 0) byUser[userId] = left else byUser.remove(userId)
    }

    /** 얻은 자리. [release] 는 여러 번 불러도 한 번만 돌려준다. */
    inner class Permit internal constructor(private val userId: String, private val weight: Int) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) giveBack(userId, weight)
        }
    }

    companion object {
        /** 대화 하나의 무게. */
        const val CHAT_WEIGHT = 1
        /** 요약 하나의 무게. 조각마다 메모·줄이기·최종 요약으로 LLM 을 여러 번 부른다. */
        const val SUMMARY_WEIGHT = 2
        private const val POLL_MILLIS = 50L
    }
}
