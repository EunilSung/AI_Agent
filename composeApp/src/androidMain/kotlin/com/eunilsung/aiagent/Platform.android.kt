package com.eunilsung.aiagent

import com.eunilsung.aiagent.data.local.DatabaseDriverFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/** LLM 답변은 조각 사이가 길 수 있어 읽기 제한을 넉넉히 둔다. 연결 제한은 짧게 둬 꺼진 서버를 빨리 알아챈다. */
val platformModule = module {
    single { DatabaseDriverFactory(get()) }
    single {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(5, TimeUnit.MINUTES)
                }
            }
        }
    }
}
