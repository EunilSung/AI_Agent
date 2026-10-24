package com.eunilsung.aiagent.data.local

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * iOS 로그인 정보 잠그기 — CommonCrypto(AES-CBC + HMAC-SHA256) 코드를 고정 키로 돌린다. 테스트 실행 파일에는 Keychain 이 없어
 * 앱이 쓰는 Keychain 키 대신 고정 키를 [IosLocalSecretCipher] 에 물린다. 키 읽기의 재시도는 [RetryingKey] 로 본다.
 */
class IosLocalSecretCipherTest {

    private val key = ByteArray(IosLocalSecretCipher.KEY_SIZE) { (it * 7 + 3).toByte() }
    private val otherKey = ByteArray(IosLocalSecretCipher.KEY_SIZE) { (it * 5 + 1).toByte() }

    @Test
    fun 잠근_값은_같은_키로_풀리고_원문이_보이지_않는다() {
        val cipher = IosLocalSecretCipher { key }

        val sealed = assertNotNull(cipher.seal("secret-token-1".encodeToByteArray()))
        val sealedAgain = assertNotNull(cipher.seal("secret-token-1".encodeToByteArray()))

        assertEquals("secret-token-1", cipher.open(sealed)?.decodeToString())
        assertFalse(sealed.contentEquals(sealedAgain), "잠글 때마다 IV 가 달라야 한다")
        assertFalse("secret-token-1" in sealed.decodeToString(), "원문이 그대로 보인다")
    }

    @Test
    fun 변조했거나_다른_키로_잠근_값은_풀지_않는다() {
        val cipher = IosLocalSecretCipher { key }
        val sealed = assertNotNull(cipher.seal("secret-token-1".encodeToByteArray()))
        val tampered = sealed.copyOf().also { it[IosLocalSecretCipher.IV_SIZE] = (it[IosLocalSecretCipher.IV_SIZE] + 1).toByte() }

        assertNull(cipher.open(tampered), "변조된 값을 풀었다")
        assertNull(IosLocalSecretCipher { otherKey }.open(sealed), "다른 기기의 키로 풀렸다")
        assertNull(cipher.open(ByteArray(10)))
    }

    @Test
    fun 키가_없으면_잠그지도_풀지도_않는다() {
        val sealed = assertNotNull(IosLocalSecretCipher { key }.seal("secret-token-1".encodeToByteArray()))
        val noKey = IosLocalSecretCipher { null }

        assertNull(noKey.seal("secret-token-1".encodeToByteArray()))
        assertNull(noKey.open(sealed))
    }

    @Test
    fun 시스템_난수는_요청한_길이만큼_매번_다르게_나온다() {
        val first = assertNotNull(secureRandomBytes(32))
        val second = assertNotNull(secureRandomBytes(32))

        assertEquals(32, first.size)
        assertFalse(first.contentEquals(second))
    }

    /** Keychain 을 한 번 못 읽었다고 그 실행 내내 잠그지 못하면 안 된다. 성공만 기억하고, 실패하면 간격을 두고 다시 읽는다. */
    @Test
    fun 키_읽기는_성공만_기억하고_실패하면_간격을_두고_다시_읽는다() {
        val clock = TestTimeSource()
        var loads = 0
        var available = false
        val retrying = RetryingKey(retryAfter = 1.seconds, timeSource = clock) {
            loads++
            if (available) key else null
        }

        assertNull(retrying.get())
        assertNull(retrying.get(), "실패한 직후 다시 읽었다")
        assertEquals(1, loads)

        available = true
        clock += 1.seconds
        assertContentEquals(key, retrying.get())
        assertContentEquals(key, retrying.get())
        assertEquals(2, loads, "읽은 키를 기억하지 않았다")
    }
}
