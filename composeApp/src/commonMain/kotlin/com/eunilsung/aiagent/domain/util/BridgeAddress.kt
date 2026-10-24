package com.eunilsung.aiagent.domain.util

/**
 * 앱이 붙을 수 있는 브릿지 주소의 규칙.
 *
 * 평문(`http`)은 에뮬레이터·시뮬레이터에서 이 Mac 에 닿는 주소([LOCAL_HOSTS])만 받고, 그 밖은 `https` 만 받는다.
 * 같은 망의 다른 사람이 평문으로 오가는 비밀번호·토큰·대화를 엿보지 못하게 한다.
 *
 * 평문을 막는 것은 플랫폼마다 다르다. Android 는 `network_security_config` 가 같은 세 주소만 열어 OS 도 막는다.
 * iOS 는 OS 가 막아 주지 않는다 — `NSAllowsLocalNetworking` 은 `.local` 이름과 점 없는 이름(IP 주소도 그렇다고 알려져 있다)의
 * 평문을 연다. 그래서 iOS 에서는 이 규칙이 유일한 막이다. 앱이 쓰는 주소는 모두 여기를 거친다 — 설정·로그인 화면에서 넣는 주소,
 * 예전에 저장된 주소, 빌드 때 넣은 기본 주소([buildDefault]).
 *
 * 주소에 사용자 정보(`user@host`)·질의(`?`)·조각(`#`)·공백이 있으면 받지 않는다 — `http://localhost@다른곳` 처럼
 * 앞부분만 보고 로컬 주소로 착각하게 만드는 값을 막는다.
 */
object BridgeAddress {
    /** 평문을 허용하는 주소 — Android 에뮬레이터의 10.0.2.2 와 이 기기 자신. */
    val LOCAL_HOSTS: Set<String> = setOf("10.0.2.2", "localhost", "127.0.0.1")

    private val HOST = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*")
    private val PORT = Regex("[0-9]{1,5}")
    private const val MAX_PORT = 65_535
    private const val FORBIDDEN = "@?#\\"

    /** [url] 을 브릿지 주소로 써도 되는지. 앞뒤 공백·끝의 `/` 는 부르는 쪽이 지운 뒤에 넘긴다. */
    fun isAllowed(url: String): Boolean = hostOf(url) != null

    /** [url] 이 규칙에 맞으면 그 호스트(소문자), 아니면 null. */
    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        if (rest.any { it.isWhitespace() || it.isISOControl() || it in FORBIDDEN }) return null

        val authority = rest.substringBefore('/')
        val host = authority.substringBefore(':').lowercase()
        val port = authority.substringAfter(':', "")
        if (!HOST.matches(host)) return null
        if (':' in authority && (!PORT.matches(port) || port.toInt() !in 1..MAX_PORT)) return null

        val allowed = when (scheme) {
            "https" -> true
            "http" -> host in LOCAL_HOSTS
            else -> false
        }
        return host.takeIf { allowed }
    }

    /**
     * [url] 이 규칙에 맞으면 그 출처 `스킴://호스트:포트`(소문자, 포트를 적지 않았으면 스킴의 기본 포트), 아니면 null.
     *
     * 사용자가 넣은 접속 키는 호스트가 아니라 출처에 묶는다 — 같은 호스트라도 다른 포트는 다른 서버(프로세스)일 수 있다.
     */
    fun originOf(url: String): String? {
        val host = hostOf(url) ?: return null
        val scheme = url.substringBefore("://").lowercase()
        val port = url.substringAfter("://").substringBefore('/').substringAfter(':', "")
        return origin(scheme, host, port.toIntOrNull() ?: defaultPort(scheme))
    }

    /** 출처 문자열 — [originOf] 와 같은 모양이다. 실제 요청 주소에서 출처를 만들 때 쓴다. */
    fun origin(scheme: String, host: String, port: Int): String = "${scheme.lowercase()}://${host.lowercase()}:$port"

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    /**
     * 빌드 기본 주소. 빌드 때 넣은 [configured](`bridge.baseUrl`·`AI_AGENT_BRIDGE_URL`)가 규칙에 맞으면 그것을, 비었거나 맞지 않으면
     * [platformDefault](에뮬레이터·시뮬레이터에서 이 Mac 에 닿는 주소)를 쓴다.
     *
     * 빌드 설정도 규칙을 거친다 — `http://<Mac 이름>.local:8095` 로 빌드한 iOS 실기기는 OS 가 막지 않아 평문으로 보낸다.
     * 빌드(`GenerateBridgeConfigTask`)도 같은 규칙으로 멈추지만, 그 검사를 빠져나온 값도 여기서 한 번 더 거른다.
     */
    fun buildDefault(configured: String, platformDefault: String): String =
        configured.trim().trimEnd('/').takeIf { it.isNotEmpty() && isAllowed(it) } ?: platformDefault

    /**
     * 접속 키를 실어도 되는 호스트 — 빌드 기본 주소([buildDefault])의 호스트와 [LOCAL_HOSTS].
     *
     * 접속 키는 모든 설치본이 함께 쓰는 값 하나다. 설정에서 주소를 다른 https 호스트로 바꾸게 속이면(피싱) 그 호스트가 키를 받아
     * 브릿지 전체에 들어오게 된다. 그래서 키는 빌드할 때 정한 브릿지와 이 Mac 의 로컬 주소에만 싣는다.
     */
    fun accessKeyHosts(buildDefaultUrl: String): Set<String> = LOCAL_HOSTS + listOfNotNull(hostOf(buildDefaultUrl))
}
