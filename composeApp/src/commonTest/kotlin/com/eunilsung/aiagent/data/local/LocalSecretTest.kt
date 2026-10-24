package com.eunilsung.aiagent.data.local

import com.eunilsung.aiagent.testsupport.FakeLocalSecretCipher
import com.eunilsung.aiagent.testsupport.withLocalSecretCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 로그인 정보 잠그기의 저장 형식. Talk 앱의 LocalSecretTest 와 같은 규칙이다.
 *
 * 실제 플랫폼 암호(Keystore·Keychain)는 Android 는 EnvelopeLocalSecretCipherTest, iOS 는 IosLocalSecretCipherTest 가
 * 같은 암호 코드에 고정 키를 물려 본다.
 */
class LocalSecretTest {

    @Test
    fun `잠기지 않은 예전 값은 그대로 읽는다`() {
        val plain = "token-before-encryption"

        assertEquals(plain, LocalSecret.decryptOrNull(plain))
        assertFalse(LocalSecret.isSealed(plain))
    }

    @Test
    fun `빈 값은 잠그지 않는다`() {
        assertEquals("", LocalSecret.encrypt(""))
        assertEquals("", LocalSecret.decryptOrNull(""))
    }

    @Test
    fun `잠근 값에는 원문이 없고 같은 암호로 다시 풀린다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val sealed = LocalSecret.encrypt("secret-token-1")
        val sealedAgain = LocalSecret.encrypt("secret-token-1")

        assertTrue(LocalSecret.isSealed(sealed))
        assertFalse("secret-token-1" in sealed, "저장된 값에 원문이 남는다")
        assertNotEquals(sealed, sealedAgain, "잠글 때마다 값이 달라야 한다")
        assertEquals("secret-token-1", LocalSecret.decryptOrNull(sealed))
        assertEquals("secret-token-1", LocalSecret.decryptOrNull(sealedAgain))
    }

    /** 잠그지 못했을 때 평문으로 저장하면 잠그는 의미가 없다. 풀 수 없는 값으로 저장해 평문이 남지 않게 한다. */
    @Test
    fun `잠그지 못하면 풀 수 없는 값으로 저장한다`() =
        withLocalSecretCipher(FakeLocalSecretCipher().apply { failSeal = true }) {
            val stored = LocalSecret.encrypt("secret-token-1")

            assertTrue(LocalSecret.isSealed(stored), "평문이 그대로 저장된다")
            assertFalse("secret" in stored, "저장된 값에 원문이 남는다")
            assertNull(LocalSecret.decryptOrNull(stored))
        }

    /** 다른 기기로 옮겨진 설정 파일 — 그 기기의 키로는 풀리지 않는다. 엉뚱한 값을 내지 않고 null 이다. */
    @Test
    fun `다른 키로 잠근 값은 풀지 못한다`() {
        val sealed = withLocalSecretCipher(FakeLocalSecretCipher(key = 1)) { LocalSecret.encrypt("secret-token-1") }

        withLocalSecretCipher(FakeLocalSecretCipher(key = 2)) {
            assertNull(LocalSecret.decryptOrNull(sealed))
            assertNull(LocalSecret.decryptOrNull("enc1:!!!not-base64"))
        }
    }

    @Test
    fun `접두어로 잠긴 값을 가린다`() {
        assertTrue(LocalSecret.isSealed("enc1:abcd"))
        assertFalse(LocalSecret.isSealed("enc1 은 아니다"))
        assertFalse(LocalSecret.isSealed(""))
    }
}
