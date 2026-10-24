package com.eunilsung.aiagent

import com.eunilsung.aiagent.data.local.DatabaseDriverFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * 브릿지와 통신하는 OkHttp 클라이언트.
 *
 * - 연결 제한은 짧게 둬 꺼진 서버를 빨리 알아챈다.
 * - 읽기 제한은 조각 사이 대기 시간이라 넉넉히 둔다. 긴 문서 요약은 첫 글자까지 몇 분이 걸리고, 그동안 서버는 10초마다
 *   `: ping` 을 보낸다.
 * - 요청 전체 제한(callTimeout)은 두지 않는다. 요약·답변 스트림은 몇 분 동안 이어진다. 짧은 REST 요청은
 *   [com.eunilsung.aiagent.data.remote.BridgeClient] 가 따로 끊는다. Ktor 의 HttpTimeout 도 설치하지 않는다.
 */
private fun bridgeOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()

/**
 * 리다이렉트는 따라가지 않는다 — Ktor 는 다른 호스트로 갈 때 `Authorization` 만 떼고 접속 키 헤더는 그대로 옮겨 싣는다.
 * 브릿지는 리다이렉트를 쓰지 않는다. OkHttp 자체의 리다이렉트도 Ktor 가 끈다.
 */
val platformModule = module {
    single { DatabaseDriverFactory(get()) }
    single {
        HttpClient(OkHttp) {
            followRedirects = false
            engine { preconfigured = bridgeOkHttpClient() }
        }
    }
}
