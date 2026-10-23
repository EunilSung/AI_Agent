package com.eunilsung.aiagent.bridge

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.limit.RateLimiter
import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.bridge.routes.authRoutes
import com.eunilsung.aiagent.bridge.routes.chatRoutes
import com.eunilsung.aiagent.bridge.routes.healthRoutes
import com.eunilsung.aiagent.bridge.routes.respondError
import com.eunilsung.aiagent.bridge.routes.summaryRoutes
import com.eunilsung.aiagent.bridge.routes.talkRoutes
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.ErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

fun main() {
    val config = BridgeConfig.fromEnv()
    /** LLM 답변은 조각 사이가 길 수 있어 읽기 제한을 넉넉히 둔다. 연결 제한은 짧게 둬 꺼진 서버를 빨리 알아챈다. */
    val http = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(5, TimeUnit.MINUTES)
            }
        }
    }
    embeddedServer(Netty, port = config.port, host = System.getenv("HOST") ?: "0.0.0.0") {
        module(config, RagClient(http, config.ragUrl), TalkClient(http, config.talkUrl))
    }.start(wait = true)
}

/** 브릿지의 경로와 플러그인. 테스트는 가짜 HttpClient 를 끼운 [rag]·[talk] 로 같은 모듈을 띄운다. */
fun Application.module(config: BridgeConfig, rag: RagClient, talk: TalkClient) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("처리하지 못한 오류", cause)
            call.respondError(HttpStatusCode.InternalServerError, ApiError(ErrorCode.FAILED))
        }
    }

    val verifier = TokenVerifier(talk)
    val limiter = RateLimiter(config.chatPerUserHourly)

    routing {
        healthRoutes(rag, talk)
        authRoutes(talk, verifier)
        chatRoutes(rag, verifier, limiter)
        summaryRoutes(rag, talk, verifier, limiter)
        talkRoutes(talk, verifier, limiter)
    }
}
