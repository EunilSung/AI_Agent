package com.eunilsung.aiagent.data.local

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android 데이터 키 잠그기 — 값마다 Keystore 를 부르지 않는지, 데이터 키 파일을 지키는지. Talk 앱의 같은 테스트를 옮겼다.
 *
 * Keystore 는 JVM 단위 테스트에 없어 [FakeKek] 로 대신한다. Android Keystore 암호와 같은 형식(`[IV 12][암호문+태그]`)의
 * AES-GCM 을 고정 키로 돌리고, 몇 번 불렸는지 센다.
 */
class EnvelopeLocalSecretCipherTest {

    private class FakeKek(private val ivOverride: ByteArray? = null) : LocalSecretCipher {
        private val key = SecretKeySpec(ByteArray(32) { (it * 3 + 1).toByte() }, "AES")
        var seals = 0
        var opens = 0
        var failOpen = false

        override fun seal(plain: ByteArray): ByteArray {
            seals++
            val iv = ivOverride ?: ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plain)
        }

        override fun open(payload: ByteArray): ByteArray? {
            opens++
            if (failOpen || payload.size <= 12) return null
            return runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload, 0, 12))
                cipher.doFinal(payload, 12, payload.size - 12)
            }.getOrNull()
        }
    }

    private class MemoryDekStore(var ready: Boolean = true) : DekStore {
        var stored: ByteArray? = null
        var creates = 0
        override fun isReady() = ready
        override fun read(): ByteArray? = stored
        override fun create(bytes: ByteArray): Boolean {
            if (stored != null) return false
            creates++
            stored = bytes.copyOf()
            return true
        }
    }

    private fun String.bytes() = encodeToByteArray()

    private fun ByteArray.startsWithMagic() =
        EnvelopeLocalSecretCipher.MAGIC.indices.all { this[it] == EnvelopeLocalSecretCipher.MAGIC[it] }

    @Test
    fun `새로 잠근 값은 데이터 키로 잠기고 다시 풀린다`() {
        val cipher = EnvelopeLocalSecretCipher(FakeKek(), MemoryDekStore())

        val sealed = assertNotNull(cipher.seal("secret-token-1".bytes()))

        assertTrue(sealed.startsWithMagic(), "표식이 없다 — 읽을 때 Keystore 로 푼다")
        assertTrue(cipher.isCurrent(sealed))
        assertEquals("secret-token-1", cipher.open(sealed)?.decodeToString())
    }

    @Test
    fun `값을 아무리 많이 잠가도 Keystore 는 데이터 키에만 한 번 쓴다`() {
        val kek = FakeKek()
        val cipher = EnvelopeLocalSecretCipher(kek, MemoryDekStore())

        repeat(50) { i ->
            val sealed = assertNotNull(cipher.seal("value $i".bytes()))
            assertEquals("value $i", cipher.open(sealed)?.decodeToString())
        }

        assertEquals(1, kek.seals, "값마다 Keystore 로 잠갔다")
        assertEquals(0, kek.opens, "값마다 Keystore 로 풀었다")
    }

    /** 앱 시작 전(위치를 모를 때) 잠근 값은 Keystore 키로 직접 잠긴다. 그것도 풀려야 한다. */
    @Test
    fun `Keystore 로 직접 잠근 값도 풀린다`() {
        val kek = FakeKek()
        val direct = kek.seal("direct".bytes())
        val cipher = EnvelopeLocalSecretCipher(kek, MemoryDekStore())

        assertEquals("direct", cipher.open(direct)?.decodeToString())
        assertFalse(cipher.isCurrent(direct))
    }

    /** 앱을 다시 켜면 같은 데이터 키를 풀어 쓴다. 새로 만들면 지난 실행에 잠근 토큰이 풀리지 않아 로그아웃된다. */
    @Test
    fun `앱을 다시 켜도 같은 데이터 키를 쓴다`() {
        val kek = FakeKek()
        val store = MemoryDekStore()
        val sealed = assertNotNull(EnvelopeLocalSecretCipher(kek, store).seal("last run".bytes()))
        val wrapped = store.stored?.copyOf()

        val reopened = EnvelopeLocalSecretCipher(kek, store)

        assertEquals("last run", reopened.open(sealed)?.decodeToString())
        assertEquals(1, store.creates, "데이터 키를 다시 만들었다")
        assertContentEquals(wrapped, store.stored)
    }

    /** 데이터 키를 풀지 못해도 파일을 덮어쓰지 않는다. 그 실행 동안은 Keystore 키로 직접 잠그고, 풀 수 없는 값은 null 이다. */
    @Test
    fun `데이터 키를 풀지 못하면 파일을 지키고 Keystore 로 직접 잠근다`() {
        val kek = FakeKek()
        val store = MemoryDekStore()
        val sealedBefore = assertNotNull(EnvelopeLocalSecretCipher(kek, store).seal("last run".bytes()))
        val wrapped = store.stored?.copyOf()

        kek.failOpen = true
        val broken = EnvelopeLocalSecretCipher(kek, store)
        val sealedNow = assertNotNull(broken.seal("this run".bytes()))

        assertContentEquals(wrapped, store.stored, "풀지 못한 데이터 키 파일을 덮어썼다")
        assertEquals(1, store.creates)
        assertFalse(sealedNow.startsWithMagic(), "쓸 수 없는 데이터 키로 잠갔다")
        assertNull(broken.open(sealedBefore), "풀 수 없는 값에 엉뚱한 값을 돌려줬다")

        kek.failOpen = false
        assertEquals("this run", broken.open(sealedNow)?.decodeToString())
    }

    @Test
    fun `위치를 모를 때는 Keystore 로 직접 잠그고 알게 되면 데이터 키를 쓴다`() {
        val store = MemoryDekStore(ready = false)
        val cipher = EnvelopeLocalSecretCipher(FakeKek(), store)

        val early = assertNotNull(cipher.seal("before".bytes()))
        store.ready = true
        val later = assertNotNull(cipher.seal("after".bytes()))

        assertFalse(early.startsWithMagic())
        assertTrue(later.startsWithMagic(), "앱 시작 전 한 번 실패로 굳었다")
        assertEquals("before", cipher.open(early)?.decodeToString())
    }

    @Test
    fun `표식과 우연히 같게 시작하는 Keystore 값도 풀린다`() {
        val magicIv = EnvelopeLocalSecretCipher.MAGIC + ByteArray(8) { 7 }
        val legacy = FakeKek(ivOverride = magicIv).seal("chance".bytes())
        assertTrue(legacy.startsWithMagic())

        val cipher = EnvelopeLocalSecretCipher(FakeKek(), MemoryDekStore())

        assertEquals("chance", cipher.open(legacy)?.decodeToString())
    }

    @Test
    fun `엉터리 값은 풀지 않는다`() {
        val cipher = EnvelopeLocalSecretCipher(FakeKek(), MemoryDekStore())
        val tampered = assertNotNull(cipher.seal("original".bytes())).also { it[it.size - 1] = (it.last() + 1).toByte() }

        assertNull(cipher.open(tampered), "변조된 값을 풀었다")
        assertNull(cipher.open(ByteArray(40) { 9 }))
    }

    /** 키 파일은 없을 때만 만들고, 이미 있으면 덮어쓰지 않는다. 쓰다 끊긴 임시 파일은 키로 읽지 않는다. */
    @Test
    fun `키 파일은 없을 때만 만든다`() {
        val directory = Files.createTempDirectory("aiagent-dek").toFile()
        try {
            val file = File(directory, "dek")
            val store = FileDekStore { file }

            assertTrue(store.create(byteArrayOf(1, 2, 3)))
            assertFalse(store.create(byteArrayOf(9, 9, 9)), "있는 키 파일을 덮어썼다")
            assertContentEquals(byteArrayOf(1, 2, 3), store.read())
            assertFalse(FileDekStore { null }.isReady())
        } finally {
            directory.deleteRecursively()
        }
    }
}
