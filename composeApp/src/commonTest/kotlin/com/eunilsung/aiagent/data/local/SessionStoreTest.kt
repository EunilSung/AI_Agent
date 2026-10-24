package com.eunilsung.aiagent.data.local

import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.testsupport.FakeLocalSecretCipher
import com.eunilsung.aiagent.testsupport.withLocalSecretCipher
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 로그인 정보 저장 — 토큰·아이디·이름·마지막 로그인 아이디가 설정 저장소에 평문으로 남지 않는지, 예전 평문은 처음 읽을 때
 * 잠가 옮기고 로그인을 유지하는지, 풀 수 없으면 로그아웃 상태로 여는지.
 *
 * 설정 저장소는 [MapSettings] 로 들여다보고, 암호는 [FakeLocalSecretCipher] 로 바꾼다. 앱을 다시 켠 것은 같은 설정으로
 * [SessionStore] 를 새로 만드는 것으로 흉내 낸다.
 */
class SessionStoreTest {

    private val keys = listOf("session_token", "session_user_id", "session_user_name", "last_login_id")

    /** 설정 파일에 그대로 적힌 값들 — 백업·기기 이전으로 새어 나가는 것이 이것이다. */
    private fun MapSettings.rawValues(): List<String> = keys.mapNotNull { getStringOrNull(it) }

    @Test
    fun `저장한 로그인 정보는 평문으로 남지 않고 앱을 다시 켜도 그대로 열린다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val settings = MapSettings()
        SessionStore(settings).apply {
            lastLoginId = "test1"
            save("secret-token-1", User("test1", "김민준"))
        }

        val raw = settings.rawValues()
        assertEquals(4, raw.size)
        assertTrue(raw.all { LocalSecret.isSealed(it) }, "잠기지 않은 칸이 있다: $raw")
        assertTrue(raw.none { "secret-token-1" in it || "test1" in it || "김민준" in it }, "원문이 남았다: $raw")

        val reopened = SessionStore(settings)
        assertEquals(User("test1", "김민준"), reopened.user.value)
        assertEquals("secret-token-1", reopened.token)
        assertEquals("test1", reopened.lastLoginId)
    }

    @Test
    fun `예전에 평문으로 저장된 로그인은 처음 읽을 때 잠가 옮기고 로그인을 유지한다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val settings = MapSettings().apply {
            putString("session_token", "legacy-token-1")
            putString("session_user_id", "test1")
            putString("session_user_name", "김민준")
            putString("last_login_id", "test1")
        }

        val store = SessionStore(settings)

        assertEquals(User("test1", "김민준"), store.user.value, "업데이트하자마자 로그아웃됐다")
        assertEquals("legacy-token-1", store.token)
        assertEquals("test1", store.lastLoginId)
        val raw = settings.rawValues()
        assertTrue(raw.all { LocalSecret.isSealed(it) }, "평문이 그대로 남았다: $raw")
        assertTrue(raw.none { "legacy-token-1" in it || "김민준" in it }, "평문이 그대로 남았다: $raw")

        val reopened = SessionStore(settings)
        assertEquals("legacy-token-1", reopened.token, "옮긴 뒤 다시 켜면 로그인이 풀린다")
        assertEquals(User("test1", "김민준"), reopened.user.value)
    }

    /** 잠그지 못해도 평문을 남기지 않는다. 이번 실행은 로그인이 유지되고, 다음 실행에서는 다시 로그인한다. */
    @Test
    fun `예전 평문을 잠그지 못해도 평문은 지우고 이번 실행은 로그인을 유지한다`() {
        val settings = MapSettings().apply {
            putString("session_token", "legacy-token-1")
            putString("session_user_id", "test1")
        }
        val failing = FakeLocalSecretCipher().apply { failSeal = true }

        withLocalSecretCipher(failing) {
            val store = SessionStore(settings)

            assertEquals("legacy-token-1", store.token)
            assertEquals("test1", store.user.value?.id)
            assertTrue(settings.rawValues().none { "legacy-token-1" in it }, "잠그지 못한 평문이 남았다")

            val reopened = SessionStore(settings)
            assertNull(reopened.user.value)
            assertNull(reopened.token)
        }
    }

    /** 다른 기기로 옮겨진 설정 파일, 지워진 Keychain 키 — 풀 수 없으면 로그아웃 상태로 연다. 남의 토큰을 쓰지 않는다. */
    @Test
    fun `풀 수 없는 로그인 정보는 로그아웃 상태로 연다`() {
        val settings = MapSettings()
        withLocalSecretCipher(FakeLocalSecretCipher(key = 1)) { SessionStore(settings).save("secret-token-1", User("test1", "김민준")) }

        withLocalSecretCipher(FakeLocalSecretCipher(key = 2)) {
            val store = SessionStore(settings)

            assertNull(store.user.value)
            assertNull(store.token, "사용자는 없는데 토큰이 남아 요청에 실린다")
        }
    }

    @Test
    fun `로그아웃하면 토큰과 사용자를 지우고 마지막 로그인 아이디는 남긴다`() = withLocalSecretCipher(FakeLocalSecretCipher()) {
        val settings = MapSettings()
        val store = SessionStore(settings).apply {
            lastLoginId = "test1"
            save("secret-token-1", User("test1", "김민준"))
        }

        store.clear()

        assertNull(store.token)
        assertNull(store.user.value)
        assertNull(settings.getStringOrNull("session_token"))
        assertNull(settings.getStringOrNull("session_user_id"))
        assertFalse(settings.hasKey("session_user_name"))
        assertEquals("test1", SessionStore(settings).lastLoginId)
        assertNull(SessionStore(settings).user.value)
    }
}
