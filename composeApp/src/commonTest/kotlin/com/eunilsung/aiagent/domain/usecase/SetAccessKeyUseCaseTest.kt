package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.repository.ServerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 접속 키 형식 — 브릿지는 32자 이상의 키만 쓰고 키는 HTTP 헤더로 나간다. 그래서 공백 없는 ASCII 32~256자만 저장한다.
 * 받은 키를 붙여 넣다 따라온 앞뒤 공백·줄바꿈은 뗀다.
 */
class SetAccessKeyUseCaseTest {

    private class Recording : ServerSettingsRepository {
        var saved: String? = null
        override val baseUrl: StateFlow<String> = MutableStateFlow("https://bridge.example.com")
        override fun setBaseUrl(url: String) = Unit
        override fun defaultBaseUrl(): String? = null
        override fun isServerChosen(): Boolean = true
        override fun hasAccessKey(): Boolean = saved != null
        override fun setAccessKey(key: String) {
            saved = key
        }
        override suspend fun status(): ServiceStatus = ServiceStatus(bridge = false)
    }

    @Test
    fun `받은 키는 앞뒤 공백을 떼고 저장한다`() {
        val repository = Recording()
        val key = "Zm9vYmFyLWJyaWRnZS1rZXktbm90LXJlYWwtMDEyMw=="

        assertTrue(SetAccessKeyUseCase(repository)("  $key\n"))
        assertEquals(key, repository.saved)
    }

    @Test
    fun `형식이 맞지 않는 키는 저장하지 않는다`() {
        val refused = listOf(
            "",
            "short-key",
            "a".repeat(31),
            "a".repeat(257),
            "has space in the middle of the key 0123",
            "한글이-섞인-접속-키-0123456789-abcdefghij",
            "tab\tinside-the-key-0123456789-abcdefghij",
        )
        for (key in refused) {
            val repository = Recording()
            assertFalse(SetAccessKeyUseCase(repository)(key), key)
            assertNull(repository.saved, key)
        }
    }

    @Test
    fun `32자와 256자 경계는 받는다`() {
        for (key in listOf("a".repeat(32), "b".repeat(256), "+/=-_~!@#\$%^&*()[]{}<>?.,;:0123456789")) {
            val repository = Recording()
            assertTrue(SetAccessKeyUseCase(repository)(key), key)
            assertEquals(key, repository.saved)
        }
    }
}
