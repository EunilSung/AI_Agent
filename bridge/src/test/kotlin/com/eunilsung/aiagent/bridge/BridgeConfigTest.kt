package com.eunilsung.aiagent.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 환경변수로 받는 브릿지 설정. */
class BridgeConfigTest {

    private companion object {
        /** 시험용 접속 키. 실제 키는 저장소 밖에 있다. */
        const val FAKE_KEY = "fake-bridge-key-for-unit-tests-only-0123456789"
    }

    @Test
    fun `HOST 가 없으면 이 기기 안(127_0_0_1)에서만 듣는다`() {
        assertEquals("127.0.0.1", BridgeConfig.fromEnv(emptyMap()).host)
        assertEquals("127.0.0.1", BridgeConfig.fromEnv(mapOf("HOST" to "  ")).host)
        assertEquals("0.0.0.0", BridgeConfig.fromEnv(mapOf("HOST" to "0.0.0.0")).host)
        assertEquals("127.0.0.1", BridgeConfig().host)
    }

    @Test
    fun `접속 키가 없으면 BRIDGE_ALLOW_NO_KEY=1 일 때만 뜬다`() {
        assertEquals(BridgeConfig.Startup.MissingKey, BridgeConfig.fromEnv(emptyMap()).startup)
        assertEquals(BridgeConfig.Startup.MissingKey, BridgeConfig.fromEnv(mapOf("BRIDGE_ACCESS_KEY" to "  ")).startup)
        assertEquals(BridgeConfig.Startup.WithoutKey, BridgeConfig.fromEnv(mapOf("BRIDGE_ALLOW_NO_KEY" to "1")).startup)
        assertEquals(BridgeConfig.Startup.MissingKey, BridgeConfig.fromEnv(mapOf("BRIDGE_ALLOW_NO_KEY" to "true")).startup)
        val keyed = BridgeConfig.fromEnv(mapOf("BRIDGE_ACCESS_KEY" to " $FAKE_KEY "))
        assertEquals(BridgeConfig.Startup.Ready, keyed.startup)
        assertEquals(FAKE_KEY, keyed.accessKey)
    }

    @Test
    fun `접속 키가 32자보다 짧으면 뜨지 않는다`() {
        val minimum = BridgeConfig.MIN_ACCESS_KEY_LENGTH
        assertEquals(32, minimum)
        assertEquals(BridgeConfig.Startup.WeakKey, BridgeConfig.fromEnv(mapOf("BRIDGE_ACCESS_KEY" to "1234")).startup)
        assertEquals(BridgeConfig.Startup.WeakKey, BridgeConfig(accessKey = "k".repeat(minimum - 1)).startup)
        assertEquals(BridgeConfig.Startup.WeakKey, BridgeConfig(accessKey = "k".repeat(minimum - 1), allowNoKey = true).startup)
        assertEquals(BridgeConfig.Startup.Ready, BridgeConfig(accessKey = "k".repeat(minimum)).startup)
    }

    @Test
    fun `설정을 글로 찍어도 접속 키는 남지 않는다`() {
        val text = BridgeConfig(accessKey = "fake-secret-value").toString()
        assertFalse("fake-secret-value" in text, text)
        assertTrue("accessKey=<set>" in text, text)
        assertTrue("accessKey=<none>" in BridgeConfig().toString())
    }

    @Test
    fun `허용 사용자 목록은 쉼표로 나누고 공백과 대소문자를 정리한다`() {
        assertEquals(setOf("test1", "test2"), BridgeConfig.fromEnv(mapOf("ALLOWED_USER_IDS" to " Test1, ,test2,")).allowedUserIds)
        assertEquals(emptySet(), BridgeConfig.fromEnv(emptyMap()).allowedUserIds)
    }

    @Test
    fun `한도 기본값`() {
        val config = BridgeConfig.fromEnv(emptyMap())
        assertEquals(10, config.loginPerIpPerMinute)
        assertEquals(5, config.loginGlobalPerSecond)
        assertEquals(4, config.llmConcurrency)
        assertEquals(2, config.llmPerUserConcurrency)
        assertEquals(120, config.peoplePerUserHourly)
        assertNull(config.accessKey)
        assertEquals(3, BridgeConfig.fromEnv(mapOf("LLM_CONCURRENCY" to "3", "LLM_PER_USER_CONCURRENCY" to "1")).llmConcurrency)
    }
}
