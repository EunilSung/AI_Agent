package com.eunilsung.aiagent

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import org.koin.dsl.koinApplication
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSessionConfiguration
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 앱이 실제로 쓰는 iOS HTTP 클라이언트(Koin [platformModule])가 긴 요약 스트림을 끊지 않는지.
 * 요약은 첫 글자까지 3분까지 걸리고 그동안 서버가 10초마다 `: ping` 을 보낸다.
 */
class PlatformHttpClientTest {

    @Test
    fun `요청 전체 제한이 없고 조각 사이 대기는 첫 글자까지의 시간보다 길다`() {
        val koin = koinApplication { modules(platformModule) }.koin
        val http = koin.get<HttpClient>()
        val config = http.engine.config as DarwinClientEngineConfig

        assertNull(http.pluginOrNull(HttpTimeout), "HttpTimeout 의 requestTimeout 은 스트림 전체를 끊는다")

        val request = NSMutableURLRequest.requestWithURL(NSURL(string = "http://localhost:8095/v1/summarize"))
        config.requestConfig(request)
        assertTrue(request.timeoutInterval >= FIRST_TOKEN_WAIT_SECONDS, "request.timeoutInterval=${request.timeoutInterval}")

        val session = NSURLSessionConfiguration.defaultSessionConfiguration()
        config.sessionConfig(session)
        assertTrue(session.timeoutIntervalForRequest >= FIRST_TOKEN_WAIT_SECONDS, "timeoutIntervalForRequest=${session.timeoutIntervalForRequest}")
        assertTrue(session.timeoutIntervalForResource >= ONE_HOUR_SECONDS, "timeoutIntervalForResource=${session.timeoutIntervalForResource}")

        http.close()
        koin.close()
    }

    /** 리다이렉트를 따라가면 Ktor 가 접속 키 헤더를 다른 호스트로 옮겨 싣는다. 브릿지는 리다이렉트를 쓰지 않는다. */
    @Test
    fun `리다이렉트를 따라가지 않는다`() {
        val koin = koinApplication { modules(platformModule) }.koin
        val http = koin.get<HttpClient>()

        assertNull(http.pluginOrNull(HttpRedirect), "리다이렉트를 따라가면 접속 키가 다른 호스트로 간다")

        http.close()
        koin.close()
    }

    private companion object {
        /** 긴 문서 요약이 첫 글자를 보내기까지 걸릴 수 있는 시간(초). */
        const val FIRST_TOKEN_WAIT_SECONDS = 180.0
        /** 스트림 전체 제한이 이보다 짧으면 긴 요약이 끊길 수 있다. */
        const val ONE_HOUR_SECONDS = 3_600.0
    }
}
