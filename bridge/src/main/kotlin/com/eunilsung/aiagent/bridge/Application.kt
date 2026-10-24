package com.eunilsung.aiagent.bridge

import com.eunilsung.aiagent.bridge.auth.TokenVerifier
import com.eunilsung.aiagent.bridge.auth.requireAccessKey
import com.eunilsung.aiagent.bridge.http.RequestReadTimeoutHandler
import com.eunilsung.aiagent.bridge.limit.ConcurrencyLimiter
import com.eunilsung.aiagent.bridge.limit.LoginLimiter
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
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main() {
    val config = BridgeConfig.fromEnv()
    val log = LoggerFactory.getLogger("com.eunilsung.aiagent.bridge")
    when (config.startup) {
        BridgeConfig.Startup.Ready -> Unit
        BridgeConfig.Startup.WithoutKey ->
            log.warn(
                "접속 키 없이 뜬다(BRIDGE_ALLOW_NO_KEY=1). 이 기기 안에서 프록시 없이 바로 온 요청만 받는다 — " +
                    "Tailscale Funnel 등 프록시를 거친 요청과 바깥 주소의 요청은 403 이다.",
            )
        BridgeConfig.Startup.MissingKey -> {
            log.error("접속 키가 없어 뜨지 않는다. BRIDGE_ACCESS_KEY 를 주거나 local.properties 에 bridge.accessKey 를 넣는다.")
            exitProcess(1)
        }
        BridgeConfig.Startup.WeakKey -> {
            log.error("접속 키가 ${BridgeConfig.MIN_ACCESS_KEY_LENGTH}자보다 짧아 뜨지 않는다. 길고 무작위한 키(예: openssl rand -base64 32)를 쓴다.")
            exitProcess(1)
        }
    }
    /** LLM 답변은 조각 사이가 길 수 있어 읽기 제한을 넉넉히 둔다. 연결 제한은 짧게 둬 꺼진 서버를 빨리 알아챈다. */
    val http = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(5, TimeUnit.MINUTES)
            }
        }
    }
    bridgeServer(config, RagClient(http, config.ragUrl), TalkClient(http, config.talkUrl)).start(wait = true)
}

/**
 * [config] 의 주소·포트에서 듣는 Netty 서버. 요청을 받는 동안의 시간 제한([RequestReadTimeoutHandler])을 건다.
 * 테스트도 실제 Netty 로 시간 제한을 볼 때 이것을 쓴다.
 */
fun bridgeServer(
    config: BridgeConfig,
    rag: RagClient,
    talk: TalkClient,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(
        Netty,
        configure = {
            connector {
                host = config.host
                port = config.port
            }
            channelPipelineConfig = { RequestReadTimeoutHandler.install(this, config.requestReadTimeoutMillis) }
        },
    ) {
        module(config, rag, talk)
    }

/**
 * 브릿지의 경로와 플러그인. 테스트는 가짜 HttpClient 를 끼운 [rag]·[talk] 로 같은 모듈을 띄운다.
 *
 * 접속 키가 없거나([BridgeConfig.Startup.MissingKey]) 짧은([BridgeConfig.Startup.WeakKey]) 설정으로는 띄우지 않는다.
 */
fun Application.module(config: BridgeConfig, rag: RagClient, talk: TalkClient) {
    check(config.startup == BridgeConfig.Startup.Ready || config.startup == BridgeConfig.Startup.WithoutKey) {
        "bridge access key is required and must be at least ${BridgeConfig.MIN_ACCESS_KEY_LENGTH} characters " +
            "(set BRIDGE_ALLOW_NO_KEY=1 to run without one on this machine only)"
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("처리하지 못한 오류", cause)
            call.respondError(HttpStatusCode.InternalServerError, ApiError(ErrorCode.FAILED))
        }
    }
    requireAccessKey(config.accessKey)

    val verifier = TokenVerifier(talk, config.allowedUserIds)
    val limiter = RateLimiter(config.chatPerUserHourly)
    val peopleLimiter = RateLimiter(config.peoplePerUserHourly)
    val loginLimiter = LoginLimiter(config.loginPerIpPerMinute, config.loginGlobalPerSecond)
    val llm = ConcurrencyLimiter(config.llmConcurrency, config.llmPerUserConcurrency, config.llmWaitMillis)

    routing {
        healthRoutes(rag, talk)
        authRoutes(talk, verifier, loginLimiter)
        chatRoutes(rag, verifier, limiter, llm)
        summaryRoutes(rag, talk, verifier, limiter, llm)
        talkRoutes(talk, verifier, limiter, peopleLimiter)
    }
}
