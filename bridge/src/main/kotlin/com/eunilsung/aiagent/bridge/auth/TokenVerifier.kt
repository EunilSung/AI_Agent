package com.eunilsung.aiagent.bridge.auth

import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.bridge.talk.TalkResult
import com.eunilsung.aiagent.shared.api.UserDto
import java.util.concurrent.ConcurrentHashMap

/**
 * 앱이 보낸 토큰의 주인을 Talk 서버에 묻는다.
 *
 * 질문할 때마다 Talk 서버를 부르지 않도록 확인한 결과를 [ttlMillis] 동안 기억한다. 그래서 Talk 에서 로그아웃한
 * 토큰도 그 시간 동안은 통과할 수 있다. 토큰 원문 대신 해시를 키로 써서 메모리에 원문을 남기지 않는다.
 */
class TokenVerifier(
    private val talk: TalkClient,
    private val ttlMillis: Long = 5 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, Pair<UserDto, Long>>()

    suspend fun verify(token: String): Verification {
        val key = token.sha256()
        cache[key]?.let { (user, expiresAt) -> if (expiresAt > now()) return Verification.Valid(user) }
        return when (val result = talk.me(token)) {
            is TalkResult.Success -> {
                cache[key] = result.value to now() + ttlMillis
                Verification.Valid(result.value)
            }
            is TalkResult.Rejected -> {
                cache.remove(key)
                Verification.Invalid
            }
            TalkResult.Unreachable -> Verification.Unknown
        }
    }

    fun forget(token: String) {
        cache.remove(token.sha256())
    }

    private fun String.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

sealed interface Verification {
    data class Valid(val user: UserDto) : Verification
    data object Invalid : Verification
    /** Talk 서버에 닿지 못해 확인하지 못했다. */
    data object Unknown : Verification
}
