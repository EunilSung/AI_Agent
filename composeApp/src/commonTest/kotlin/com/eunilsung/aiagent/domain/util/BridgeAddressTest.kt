package com.eunilsung.aiagent.domain.util

import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.data.local.AccessKeyStore
import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.repository.ServerSettingsRepositoryImpl
import com.eunilsung.aiagent.domain.usecase.SetServerUrlUseCase
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 브릿지 주소 규칙 — https 이거나 에뮬레이터·시뮬레이터 주소일 때만 쓴다. 같은 망에서 평문으로 비밀번호·토큰이 오가지 않게 한다.
 * 주소는 문서용 예시 도메인(example.com)과 사설 대역 예시만 쓴다.
 */
class BridgeAddressTest {

    @Test
    fun `https 주소와 에뮬레이터·시뮬레이터의 http 주소만 받는다`() {
        val allowed = listOf(
            "https://bridge.example.com",
            "https://bridge.example.com:8443/base",
            "HTTPS://Bridge.Example.com",
            "http://10.0.2.2:8095",
            "http://localhost:8095",
            "http://127.0.0.1:8095",
            "http://LOCALHOST:8095",
        )
        allowed.forEach { assertTrue(BridgeAddress.isAllowed(it), it) }
    }

    @Test
    fun `같은 망의 http 주소와 이상한 주소는 받지 않는다`() {
        val refused = listOf(
            "http://192.168.0.10:8095",
            "http://bridge.example.com",
            "http://10.0.2.2.example.com:8095",
            "http://localhost@192.168.0.10:8095",
            "http://localhost:8095@bridge.example.com",
            "https://user:pw@bridge.example.com",
            "http://localhost:8095?x=1",
            "https://bridge.example.com#a",
            "ftp://bridge.example.com",
            "bridge.example.com",
            "https://",
            "https://bridge example.com",
            "https://bridge.example.com:",
            "https://bridge.example.com:99999",
            "https://[::1]:8095",
            "javascript:alert(1)",
        )
        refused.forEach { assertFalse(BridgeAddress.isAllowed(it), it) }
    }

    private fun setup(stored: String? = null, isDefaultUsable: Boolean = true): Pair<SetServerUrlUseCase, BridgeUrlStore> {
        val settings = MapSettings().apply { stored?.let { putString("bridge_url", it) } }
        val store = BridgeUrlStore(settings, isDefaultUsable)
        val keys = AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet())
        val bridge = BridgeClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }), store, keys)
        val repository = ServerSettingsRepositoryImpl(store, keys, bridge)
        return SetServerUrlUseCase(repository) to store
    }

    @Test
    fun `쓸 수 없는 주소는 저장하지 않고 그대로 둔다`() {
        val (setServerUrl, store) = setup()

        assertTrue(setServerUrl(" https://bridge.example.com/ "))
        assertEquals("https://bridge.example.com", store.url.value)

        assertFalse(setServerUrl("http://192.168.0.10:8095"))
        assertEquals("https://bridge.example.com", store.url.value, "막힌 주소로 바꿨다")

        assertTrue(setServerUrl(""), "빈 값은 기본 주소로 되돌린다")
        assertEquals(Config.Bridge.DEFAULT_BASE_URL, store.url.value)
    }

    /**
     * 주소·접속 키 없이 만든 공개 릴리스는 기본 주소가 에뮬레이터·시뮬레이터용 평문 주소다. 실기기에서는 같은 망의 아무 기기에나
     * 닿을 수 있어, 주소를 정하기 전에는 정하지 않은 것으로 보고 기본값으로 되돌리지도 않는다.
     */
    @Test
    fun `기본 주소를 쓰면 안 되는 빌드는 주소를 정하기 전까지 정하지 않은 것으로 보고 기본값으로 되돌리지 않는다`() {
        val (setServerUrl, store) = setup(isDefaultUsable = false)
        assertFalse(store.isChosen)
        assertNull(setServerUrl.resolve(""))

        assertFalse(setServerUrl(""), "기본 주소로 되돌렸다")
        assertFalse(store.isChosen)

        assertTrue(setServerUrl("https://bridge.example.com"))
        assertTrue(store.isChosen)
        assertFalse(setServerUrl(" "), "정한 주소를 평문 기본 주소로 되돌렸다")
        assertEquals("https://bridge.example.com", store.url.value)

        val (_, devStore) = setup(isDefaultUsable = true)
        assertTrue(devStore.isChosen, "키나 주소를 넣어 만든 개발용 빌드는 기본 주소를 그대로 쓴다")
    }

    /**
     * 규칙 전에 같은 망의 http 주소를 저장해 두었으면 지우고 기본 주소로 돌아간다. Android 는 OS 도 막지만 iOS 는
     * `NSAllowsLocalNetworking` 이 `.local`·IP 평문을 열어 두므로, 지우지 않으면 평문으로 나간다.
     */
    @Test
    fun `예전에 저장한 막힌 주소는 읽을 때 지운다`() {
        val settings = MapSettings().apply { putString("bridge_url", "http://192.168.0.10:8095") }

        val store = BridgeUrlStore(settings)

        assertEquals(Config.Bridge.DEFAULT_BASE_URL, store.url.value)
        assertNull(settings.getStringOrNull("bridge_url"))
        assertEquals("http://10.0.2.2:8095", BridgeUrlStore(MapSettings().apply { putString("bridge_url", "http://10.0.2.2:8095") }).url.value)
    }

    /**
     * 빌드 때 넣은 기본 주소(`bridge.baseUrl`·`AI_AGENT_BRIDGE_URL`)도 같은 규칙을 거친다. `http://<Mac 이름>.local:8095` 로
     * 빌드한 iOS 실기기는 OS 가 막지 않아 평문으로 보낸다 — 규칙에 맞지 않으면 플랫폼 기본값으로 돌아간다.
     */
    @Test
    fun `빌드 기본 주소가 규칙에 맞지 않으면 플랫폼 기본값을 쓴다`() {
        val platform = "http://localhost:8095"
        val refused = listOf(
            "http://my-mac.local:8095",
            "http://my-mac:8095",
            "http://192.168.0.10:8095",
            "http://bridge.example.com",
            "https://user@bridge.example.com",
            "ftp://bridge.example.com",
        )
        refused.forEach { assertEquals(platform, BridgeAddress.buildDefault(it, platform), it) }

        assertEquals(platform, BridgeAddress.buildDefault("", platform))
        assertEquals(platform, BridgeAddress.buildDefault("   ", platform))
        assertEquals("https://bridge.example.com", BridgeAddress.buildDefault(" https://bridge.example.com/ ", platform))
        assertEquals("http://10.0.2.2:8095", BridgeAddress.buildDefault("http://10.0.2.2:8095", platform))
    }

    @Test
    fun `이 빌드의 기본 주소는 규칙에 맞는다`() {
        assertTrue(BridgeAddress.isAllowed(Config.Bridge.DEFAULT_BASE_URL), "빌드 기본 주소가 주소 규칙을 거치지 않았다")
        assertTrue(BridgeAddress.isAllowed(BridgeUrlStore(MapSettings()).url.value))
    }

    @Test
    fun `호스트는 규칙에 맞는 주소에서만 소문자로 꺼낸다`() {
        assertEquals("bridge.example.com", BridgeAddress.hostOf("HTTPS://Bridge.Example.com:8443/base"))
        assertEquals("localhost", BridgeAddress.hostOf("http://localhost:8095"))
        assertNull(BridgeAddress.hostOf("http://192.168.0.10:8095"))
        assertNull(BridgeAddress.hostOf("https://localhost@bridge.example.com"))
    }

    /** 사용자가 넣은 접속 키는 출처에 묶는다. 포트를 적지 않았으면 스킴의 기본 포트이고, 규칙에 맞지 않는 주소는 출처가 없다. */
    @Test
    fun `출처는 규칙에 맞는 주소에서만 스킴·호스트·포트를 소문자로 꺼낸다`() {
        assertEquals("https://bridge.example.com:443", BridgeAddress.originOf("HTTPS://Bridge.Example.com/base"))
        assertEquals("https://bridge.example.com:443", BridgeAddress.originOf("https://bridge.example.com:443"))
        assertEquals("https://bridge.example.com:8443", BridgeAddress.originOf("https://bridge.example.com:08443/base"))
        assertEquals("http://localhost:8095", BridgeAddress.originOf("http://localhost:8095"))
        assertEquals("http://10.0.2.2:80", BridgeAddress.originOf("http://10.0.2.2"))
        assertNull(BridgeAddress.originOf("http://192.168.0.10:8095"))
        assertNull(BridgeAddress.originOf("https://localhost@bridge.example.com"))
        assertEquals(BridgeAddress.originOf("https://bridge.example.com"), BridgeAddress.origin("HTTPS", "Bridge.Example.com", 443))
    }

    /** 접속 키는 모든 설치본이 함께 쓴다 — 설정에서 다른 호스트로 바꾸게 속여도 그 호스트는 키를 받지 못한다. */
    @Test
    fun `접속 키는 빌드 기본 주소의 호스트와 로컬 주소에만 싣는다`() {
        assertEquals(
            BridgeAddress.LOCAL_HOSTS + "bridge.example.com",
            BridgeAddress.accessKeyHosts("https://Bridge.Example.com:8443/base"),
        )
        assertEquals(BridgeAddress.LOCAL_HOSTS, BridgeAddress.accessKeyHosts("http://localhost:8095"))
        assertEquals(BridgeAddress.LOCAL_HOSTS, BridgeAddress.accessKeyHosts("http://192.168.0.10:8095"))

        val defaultHost = BridgeAddress.hostOf(Config.Bridge.DEFAULT_BASE_URL)
        assertEquals(BridgeAddress.LOCAL_HOSTS + listOfNotNull(defaultHost), Config.Bridge.ACCESS_KEY_HOSTS)
    }
}
