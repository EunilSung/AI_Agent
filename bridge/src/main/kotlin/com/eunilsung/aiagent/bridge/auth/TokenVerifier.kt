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
 *
 * [allowedUserIds] 가 비어 있지 않으면 그 아이디의 계정만 통과한다([Verification.NotAllowed]). Talk 서버에 바로 로그인해 받은
 * 토큰을 들고 와도 같다.
 */
class TokenVerifier(
    private val talk: TalkClient,
    allowedUserIds: Set<String> = emptySet(),
    private val ttlMillis: Long = 5 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, Pair<UserDto, Long>>()
    private val allowed = allowedUserIds.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    suspend fun verify(token: String): Verification {
        val key = token.sha256()
        cache[key]?.let { (user, expiresAt) -> if (expiresAt > now()) return user.verified() }
        return when (val result = talk.me(token)) {
            is TalkResult.Success -> {
                cache[key] = result.value to now() + ttlMillis
                result.value.verified()
            }
            is TalkResult.Rejected -> {
                cache.remove(key)
                Verification.Invalid
            }
            TalkResult.Unreachable -> Verification.Unknown
        }
    }

    /** 이 아이디의 계정이 브릿지를 쓸 수 있는지. 허용 목록이 없으면 모두 된다. Talk 아이디는 대소문자를 가리지 않는다. */
    fun isAllowed(userId: String): Boolean = allowed.isEmpty() || userId.lowercase() in allowed

    fun forget(token: String) {
        cache.remove(token.sha256())
    }

    private fun UserDto.verified(): Verification = if (isAllowed(id)) Verification.Valid(this) else Verification.NotAllowed

    private fun String.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

sealed interface Verification {
    data class Valid(val user: UserDto) : Verification
    data object Invalid : Verification
    /** 살아 있는 토큰이지만 허용 사용자 목록 밖의 계정이다. */
    data object NotAllowed : Verification
    /** Talk 서버에 닿지 못해 확인하지 못했다. */
    data object Unknown : Verification
}
