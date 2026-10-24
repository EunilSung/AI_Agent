package com.eunilsung.aiagent.bridge.http

import java.net.InetAddress

/** 앞에 선 프록시가 자기가 본 주소를 덧붙이는 헤더. */
const val FORWARDED_FOR = "X-Forwarded-For"

private const val MAX_ADDRESS_LENGTH = 64
private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

/** 이 이름의 헤더가 있으면 프록시를 거쳐 온 요청이다(대소문자 무시). */
private val PROXY_HEADERS = listOf(FORWARDED_FOR, "Forwarded", "X-Real-IP", "Via")

/** 이 말로 시작하는 헤더가 있으면 프록시를 거쳐 온 요청이다 — `X-Forwarded-Proto`·`X-Forwarded-Host`, Tailscale 이 붙이는 `Tailscale-*`. */
private val PROXY_HEADER_PREFIXES = listOf("X-Forwarded-", "Tailscale-")

/**
 * 요청을 보낸 쪽의 주소. 로그인 시도 한도를 주소마다 세는 데 쓴다.
 *
 * 연결 주소 [peer] 가 이 기기 안(루프백)이면 앞에 선 프록시 — Tailscale Funnel·Serve 는 `127.0.0.1:8095` 로 붙는다 — 가
 * 붙인 [forwardedFor](`X-Forwarded-For` 헤더 값들)의 마지막 값을 쓴다. 바깥 사람이 그 헤더를 지어 보내도 프록시는 자기가 본
 * 주소를 맨 뒤에 둔다(헤더를 새로 쓰는 프록시면 값이 하나뿐이다). 바깥 주소에서 바로 온 연결(`HOST=0.0.0.0` 으로 띄운 경우)은
 * 헤더를 믿지 않고 연결 주소를 쓴다 — LAN 의 사람이 헤더를 지어 보내 한도를 피하지 못한다. 주소는 64자까지만 쓴다.
 */
fun clientAddress(peer: String, forwardedFor: List<String>?): String {
    if (!isLoopbackAddress(peer)) return peer
    return forwardedFor
        ?.flatMap { it.split(',') }
        ?.map { it.trim() }
        ?.lastOrNull { it.isNotEmpty() }
        ?.take(MAX_ADDRESS_LENGTH)
        ?: peer
}

/** 루프백 주소인지. 이름 풀이(DNS)를 하지 않도록 IP 글자 그대로인 주소와 `localhost` 만 본다. */
fun isLoopbackAddress(address: String): Boolean {
    if (address.equals("localhost", ignoreCase = true)) return true
    val literal = address.removePrefix("[").removeSuffix("]")
    if (!IPV4.matches(literal) && ':' !in literal) return false
    return runCatching { InetAddress.getByName(literal).isLoopbackAddress }.getOrDefault(false)
}

/** 앞에 선 프록시를 거쳐 온 요청인지. 프록시가 붙이는 헤더([PROXY_HEADERS]·[PROXY_HEADER_PREFIXES])가 하나라도 있으면 그렇다. */
fun isProxied(headerNames: Set<String>): Boolean = headerNames.any { name ->
    PROXY_HEADERS.any { name.equals(it, ignoreCase = true) } || PROXY_HEADER_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
}

/**
 * 이 기기 안에서 프록시 없이 바로 온 요청인지. 접속 키 없이 띄운 시험용 브릿지는 이런 요청만 받는다 —
 * 기본 주소(`127.0.0.1`)로 띄워도 Tailscale Funnel 이 붙어 있으면 인터넷의 요청이 루프백으로 들어오기 때문이다.
 */
fun isDirectLocal(peer: String, headerNames: Set<String>): Boolean = isLoopbackAddress(peer) && !isProxied(headerNames)
