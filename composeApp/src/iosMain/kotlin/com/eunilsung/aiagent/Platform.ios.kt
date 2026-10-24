package com.eunilsung.aiagent

import com.eunilsung.aiagent.data.local.DatabaseDriverFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import org.koin.dsl.module

/**
 * 조각 사이 대기 시간(초). NSURLSession 의 timeoutInterval 은 요청 전체가 아니라 새 데이터를 기다리는 시간이다.
 * 긴 문서 요약은 첫 글자까지 몇 분이 걸리고, 그동안 서버는 10초마다 `: ping` 을 보낸다.
 */
private const val IDLE_TIMEOUT_SECONDS = 300.0

/**
 * 브릿지와 통신하는 Darwin(NSURLSession) 클라이언트.
 *
 * 요청과 세션의 대기 시간을 둘 다 [IDLE_TIMEOUT_SECONDS] 로 둬, 어느 쪽이 적용되든 기본값(60초)에 끊기지 않게 한다.
 * 요청 전체 제한(timeoutIntervalForResource)은 기본값(7일)을 그대로 둔다 — 요약·답변 스트림이 몇 분 이어져도 끊지 않는다.
 * Ktor 의 HttpTimeout 은 설치하지 않는다. 짧은 REST 요청은 [com.eunilsung.aiagent.data.remote.BridgeClient] 가 따로 끊는다.
 *
 * 리다이렉트는 따라가지 않는다 — Ktor 는 다른 호스트로 갈 때 `Authorization` 만 떼고 접속 키 헤더는 그대로 옮겨 싣는다.
 * 브릿지는 리다이렉트를 쓰지 않는다. NSURLSession 의 리다이렉트도 Ktor 가 끈다.
 */
val platformModule = module {
    single { DatabaseDriverFactory() }
    single {
        HttpClient(Darwin) {
            followRedirects = false
            engine {
                configureRequest { setTimeoutInterval(IDLE_TIMEOUT_SECONDS) }
                configureSession { setTimeoutIntervalForRequest(IDLE_TIMEOUT_SECONDS) }
            }
        }
    }
}
