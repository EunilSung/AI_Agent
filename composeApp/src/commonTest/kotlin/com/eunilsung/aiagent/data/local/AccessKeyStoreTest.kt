package com.eunilsung.aiagent.data.local

import com.eunilsung.aiagent.testsupport.FakeLocalSecretCipher
import com.eunilsung.aiagent.testsupport.withLocalSecretCipher
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 접속 키 저장 — 공개 릴리스 앱은 키 없이 빌드하고, 쓰는 사람이 받은 키를 로그인 화면에서 넣는다.
 *
 * 넣은 키가 설정 저장소에 평문으로 남지 않는지, 넣을 때의 서버 출처(`스킴://호스트:포트`)로만 나가는지, 빌드 때 넣은 키(개발용 빌드)와
 * 어떻게 함께 쓰이는지 본다. 앱을 다시 켠 것은 같은 설정으로 [AccessKeyStore] 를 새로 만드는 것으로 흉내 낸다.
 */
class AccessKeyStoreTest {

    @Test
    fun `넣은 접속 키는 출처와 함께 한 칸에 잠기고 앱을 다시 켜도 그 출처로 나간다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val settings = MapSettings()
        AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet()).save("HTTPS://Bridge.Example.com:443", USER_KEY)

        assertEquals(setOf(KEY_SLOT), settings.keys, "출처와 키를 한 칸에 두지 않았다")
        val raw = settings.getString(KEY_SLOT, "")
        assertTrue(LocalSecret.isSealed(raw), "잠기지 않았다: $raw")
        assertFalse(USER_KEY in raw || "bridge.example.com" in raw, "원문이 남았다: $raw")

        val reopened = AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet())
        assertEquals(USER_KEY, reopened.keyFor(ORIGIN))
        assertEquals(USER_KEY, reopened.keyFor("HTTPS://BRIDGE.example.com:443"), "출처는 대소문자를 가리지 않는다")
        assertTrue(reopened.hasAnyKey)
    }

    /** 같은 호스트라도 다른 포트·스킴은 다른 서버(프로세스)일 수 있다. */
    @Test
    fun `넣은 키는 다른 출처로 나가지 않는다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val store = AccessKeyStore(MapSettings(), buildKey = "", buildKeyHosts = emptySet())
        store.save(ORIGIN, USER_KEY)

        val others = listOf(
            "https://bridge.example.com:8443",
            "http://bridge.example.com:80",
            "http://bridge.example.com:443",
            "https://other.example.com:443",
            "https://bridge.example.com.other.example:443",
            "https://example.com:443",
            "http://localhost:8095",
        )
        others.forEach { assertNull(store.keyFor(it), it) }
    }

    @Test
    fun `새로 넣으면 앞서 넣은 키와 출처를 바꾼다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val settings = MapSettings()
        val store = AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet())
        store.save("https://old.example.com:443", USER_KEY)

        store.save("https://new.example.com:443", REPLACED_KEY)

        assertNull(store.keyFor("https://old.example.com:443"))
        val reopened = AccessKeyStore(settings, "", emptySet())
        assertEquals(REPLACED_KEY, reopened.keyFor("https://new.example.com:443"))
        assertNull(reopened.keyFor("https://old.example.com:443"))
    }

    /** 빌드 때 넣은 키는 호스트로만 가린다 — 키를 넣어 빌드한 사람이 정한 브릿지와 이 기기의 로컬 주소다. */
    @Test
    fun `빌드 때 넣은 키는 빌드 기본 주소의 호스트와 로컬 주소에만 나가고 같은 출처에 넣은 키가 먼저다`() =
        withLocalSecretCipher(FakeLocalSecretCipher()) {
            val store = AccessKeyStore(MapSettings(), buildKey = BUILD_KEY, buildKeyHosts = setOf("localhost", "bridge.example.com"))

            assertEquals(BUILD_KEY, store.keyFor("http://localhost:8095"))
            assertEquals(BUILD_KEY, store.keyFor("https://Bridge.Example.com:8443"))
            assertNull(store.keyFor("https://other.example.com:443"))
            assertNull(store.keyFor("https://localhost.other.example:443"))

            store.save("http://localhost:8095", USER_KEY)
            assertEquals(USER_KEY, store.keyFor("http://localhost:8095"))
            assertEquals(BUILD_KEY, store.keyFor("http://localhost:9000"), "넣은 키의 출처가 아닌 로컬 주소에는 빌드 키가 간다")
            assertEquals(BUILD_KEY, store.keyFor(ORIGIN), "다른 빌드 호스트에는 빌드 키가 그대로 간다")
        }

    @Test
    fun `키가 하나도 없으면 어디로도 싣지 않는다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val store = AccessKeyStore(MapSettings(), buildKey = "", buildKeyHosts = setOf("localhost"))

        assertFalse(store.hasAnyKey)
        assertNull(store.keyFor("http://localhost:8095"))
    }

    /** 다른 기기로 옮겨진 설정 파일처럼 풀 수 없는 값은 지우고, 키를 다시 넣게 한다. */
    @Test
    fun `풀 수 없는 저장값은 지우고 키가 없는 것으로 연다`() {
        val settings = MapSettings()
        withLocalSecretCipher(FakeLocalSecretCipher(key = 0x11)) {
            AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet()).save(ORIGIN, USER_KEY)
        }

        withLocalSecretCipher(FakeLocalSecretCipher(key = 0x22)) {
            val reopened = AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet())
            assertNull(reopened.keyFor(ORIGIN))
            assertFalse(reopened.hasAnyKey)
            assertTrue(settings.keys.isEmpty(), "풀 수 없는 값이 남았다")
        }
    }

    /**
     * 설정 파일을 쓸 수 있는 쪽(루팅한 기기 등)이 평문으로 출처를 바꿔 넣어도 받지 않는다 — 잠긴 값만 읽는다.
     * 출처 없이 키만 잠긴 값(한 칸에 함께 잠그기 전의 모양)도 받지 않는다.
     */
    @Test
    fun `잠기지 않았거나 출처가 없는 저장값은 받지 않고 지운다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val plain = MapSettings().apply { putString(KEY_SLOT, "https://evil.example.com:443\n$USER_KEY") }
        val forged = AccessKeyStore(plain, buildKey = "", buildKeyHosts = emptySet())
        assertNull(forged.keyFor("https://evil.example.com:443"))
        assertFalse(forged.hasAnyKey)
        assertTrue(plain.keys.isEmpty(), "평문 값이 남았다")

        val keyOnly = MapSettings().apply { putString(KEY_SLOT, LocalSecret.encrypt(USER_KEY)) }
        assertFalse(AccessKeyStore(keyOnly, buildKey = "", buildKeyHosts = emptySet()).hasAnyKey)
        assertTrue(keyOnly.keys.isEmpty(), "출처 없는 값이 남았다")
    }

    private companion object {
        const val KEY_SLOT = "bridge_access_key"
        const val ORIGIN = "https://bridge.example.com:443"
        const val USER_KEY = "user-entered-key-not-real-0123456789"
        const val REPLACED_KEY = "replaced-key-not-real-0123456789abc"
        const val BUILD_KEY = "build-time-key-not-real-0123456789"
    }
}
