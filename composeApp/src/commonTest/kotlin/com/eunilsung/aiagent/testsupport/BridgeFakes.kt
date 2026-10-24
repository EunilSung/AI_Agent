package com.eunilsung.aiagent.testsupport

import com.eunilsung.aiagent.data.local.AccessKeyStore
import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.repository.AssistantRepositoryImpl
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * 브릿지가 대화·요약 모두에 [body] 를 SSE 로 돌려주는 진짜 [AssistantRepositoryImpl].
 * 앱의 통신([BridgeClient])·SSE 해석·실패 분류를 그대로 거쳐, 유스케이스가 서버 응답 모양에 따라 무엇을 남기는지 본다.
 */
fun sseAssistant(body: String): AssistantRepository {
    val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }
    val settings = MapSettings()
    val session = SessionStore(settings).apply { save("token-test1", User("test1", "김민준")) }
    return AssistantRepositoryImpl(BridgeClient(HttpClient(engine), BridgeUrlStore(settings), AccessKeyStore(settings, buildKey = "", buildKeyHosts = emptySet())), session, ChatMapper())
}
