package com.eunilsung.aiagent

import com.eunilsung.aiagent.domain.util.BridgeAddress
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 평문 통신 허용 범위 — Android `network_security_config` 가 앱의 주소 규칙([BridgeAddress])과 같은 주소만 여는지, iOS ATS 가
 * 임의 평문을 열지 않는지, 빌드 기본 주소 검사(`GenerateBridgeConfigTask`)가 같은 로컬 주소 목록을 쓰는지.
 *
 * iOS 의 `NSAllowsLocalNetworking` 은 `.local`·점 없는 이름의 평문도 열어, iOS 에서는 앱의 주소 규칙이 유일한 막이다.
 * 평문을 전역으로 열면 같은 망의 다른 사람이 비밀번호·토큰·대화를 엿볼 수 있다. 설정 파일을 직접 읽어, 누가 디버깅하다
 * `usesCleartextTraffic`·`NSAllowsArbitraryLoads` 를 다시 켜면 여기서 걸린다. 단위 테스트는 composeApp 폴더에서 돈다.
 */
class PlatformNetworkPolicyTest {

    private val moduleDir = File(".").absoluteFile

    private fun read(path: String): File = File(moduleDir, path).also { assertTrue(it.isFile, "${it.path} 가 없다") }

    @Test
    fun `Android 는 평문을 기본으로 막고 에뮬레이터와 이 기기 주소만 연다`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(read("src/androidMain/res/xml/network_security_config.xml"))

        val base = document.getElementsByTagName("base-config").item(0)
        assertEquals("false", base.attributes.getNamedItem("cleartextTrafficPermitted")?.nodeValue, "기본 설정이 평문을 연다")

        val domainConfigs = document.getElementsByTagName("domain-config")
        assertEquals(1, domainConfigs.length)
        assertEquals("true", domainConfigs.item(0).attributes.getNamedItem("cleartextTrafficPermitted")?.nodeValue)
        val domains = document.getElementsByTagName("domain")
        val hosts = (0 until domains.length).map { domains.item(it).textContent.trim() }.toSet()
        assertEquals(BridgeAddress.LOCAL_HOSTS, hosts, "평문을 여는 주소가 앱의 주소 규칙과 다르다")
        (0 until domains.length).forEach {
            assertEquals("false", domains.item(it).attributes.getNamedItem("includeSubdomains")?.nodeValue)
        }
    }

    @Test
    fun `Android 매니페스트는 평문 전역 허용 대신 network_security_config 를 건다`() {
        val manifest = read("src/androidMain/AndroidManifest.xml").readText()

        assertFalse("usesCleartextTraffic=\"true\"" in manifest, "평문을 전역으로 연다")
        assertTrue("android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest)
    }

    @Test
    fun `빌드 기본 주소 검사는 앱의 주소 규칙과 같은 로컬 주소만 평문으로 받는다`() {
        val script = read("build.gradle.kts").readText()
        val listed = Regex("bridgeCleartextHosts\\s*=\\s*setOf\\(([^)]*)\\)").find(script)?.groupValues?.get(1)
            ?: error("build.gradle.kts 에 bridgeCleartextHosts 가 없다")
        val hosts = Regex("\"([^\"]+)\"").findAll(listed).map { it.groupValues[1] }.toSet()

        assertEquals(BridgeAddress.LOCAL_HOSTS, hosts, "빌드 검사의 평문 주소가 앱의 주소 규칙과 다르다")
        assertTrue("throw GradleException" in script, "맞지 않는 bridge.baseUrl 로도 빌드된다")
    }

    @Test
    fun `iOS 는 임의 평문을 열지 않고 로컬 주소만 연다`() {
        val plist = read("../iosApp/iosApp/Info.plist").readText()

        assertFalse("NSAllowsArbitraryLoads" in plist, "ATS 를 통째로 끈다")
        assertTrue(Regex("<key>NSAllowsLocalNetworking</key>\\s*<true/>").containsMatchIn(plist))
    }
}
