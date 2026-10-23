package com.eunilsung.aiagent

import com.eunilsung.aiagent.data.local.DatabaseDriverFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import org.koin.dsl.module

/** LLM 답변은 조각 사이가 길 수 있어 요청 제한 시간을 넉넉히 둔다(NSURLSession 의 timeoutInterval 은 조각 사이 대기 시간이다). */
val platformModule = module {
    single { DatabaseDriverFactory() }
    single {
        HttpClient(Darwin) {
            engine {
                configureRequest { setTimeoutInterval(300.0) }
            }
        }
    }
}
