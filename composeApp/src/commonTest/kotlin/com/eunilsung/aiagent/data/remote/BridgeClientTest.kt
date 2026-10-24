package com.eunilsung.aiagent.data.remote

import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.data.local.AccessKeyStore
import com.eunilsung.aiagent.data.local.BridgeUrlStore
import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.repository.AssistantRepositoryImpl
import com.eunilsung.aiagent.data.repository.AuthRepositoryImpl
import com.eunilsung.aiagent.data.repository.MessengerRepositoryImpl
import com.eunilsung.aiagent.data.repository.ServerSettingsRepositoryImpl
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.BridgeRefusal
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendResult
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.util.BridgeAddress
import com.eunilsung.aiagent.shared.api.BRIDGE_KEY_HEADER
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 브릿지 접속 키와 브릿지 공통 거절.
 *
 * - 모든 요청(로그인·로그아웃·연결 확인·대화·요약·사람 찾기·보내기)에 `X-Bridge-Key` 를 싣는다. 브릿지는 키가 맞을 때만 받는다.
 * - 빌드 때 넣은 키는 빌드 기본 주소의 호스트와 로컬 주소에만 싣는다. 설정에서 다른 호스트로 바꾸면 싣지 않고, 그 거절은 따로 알린다.
 * - 사용자가 로그인 화면에서 넣은 키는 넣을 때의 서버 출처(`스킴://호스트:포트`)에만 싣고, 같은 출처에서는 빌드 때 넣은 키보다 먼저다.
 * - 403 client_key_invalid·user_not_allowed, 413 payload_too_large, 503 busy 를 어느 요청에서나 같은 이유([BridgeRefusal])로 알린다.
 *
 * 키는 가짜 값이다. 실제 키는 저장소 밖(`local.properties`)에만 있다.
 */
class BridgeClientTest {

    private val question = ChatMessage("q", "c", ChatRole.User, "반차는 몇 시간이야?", createdAt = 1)

    private class Recorded(val path: String, val key: String?)

    private class Env(
        val auth: AuthRepositoryImpl,
        val assistant: AssistantRepositoryImpl,
        val messenger: MessengerRepositoryImpl,
        val settings: ServerSettingsRepositoryImpl,
        val requests: MutableList<Recorded>,
    )

    private fun env(
        accessKey: String = FAKE_KEY,
        status: HttpStatusCode = HttpStatusCode.OK,
        accessKeyHosts: Set<String> = Config.Bridge.ACCESS_KEY_HOSTS,
        url: String? = null,
        isDefaultUsable: Boolean = true,
        body: (path: String) -> String = ::okBody,
    ): Env {
        val requests = mutableListOf<Recorded>()
        val engine = MockEngine { request ->
            requests += Recorded(request.url.encodedPath, request.headers[BRIDGE_KEY_HEADER])
            respond(body(request.url.encodedPath), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val store = MapSettings()
        val session = SessionStore(store).apply { save("token-1", User("test1", "김민준")) }
        val urlStore = BridgeUrlStore(store, isDefaultUsable).apply { url?.let { set(it) } }
        val keys = AccessKeyStore(store, buildKey = accessKey, buildKeyHosts = accessKeyHosts)
        val bridge = BridgeClient(HttpClient(engine), urlStore, keys)
        val mapper = ChatMapper()
        return Env(
            auth = AuthRepositoryImpl(bridge, session),
            assistant = AssistantRepositoryImpl(bridge, session, mapper),
            messenger = MessengerRepositoryImpl(bridge, session, mapper),
            settings = ServerSettingsRepositoryImpl(urlStore, keys, bridge),
            requests = requests,
        )
    }

    private fun okBody(path: String): String = when (path) {
        "/v1/auth/login" -> """{"token":"token-2","user":{"id":"test2","name":"이서연"}}"""
        "/v1/health" -> """{"status":"ok","talk":true}"""
        "/v1/talk/people" -> """{"people":[]}"""
        "/v1/talk/messages" -> """{"roomId":"room-1"}"""
        else -> "data: {\"delta\": \"답\"}\n\nevent: done\ndata: {}\n\n"
    }

    @Test
    fun `모든 브릿지 요청에 접속 키를 싣는다`() = realTime {
        val env = env()

        env.settings.status()
        env.assistant.ask(listOf(question)).toList()
        env.assistant.summarize("file-1", "a.pdf").toList()
        env.messenger.findPeople("이서연")
        env.messenger.send(Person("test2", "이서연"), "안녕하세요", "request-1")
        env.auth.logout()
        env.auth.login("test2", "1234")

        assertEquals(
            listOf("/v1/health", "/v1/chat", "/v1/summarize", "/v1/talk/people", "/v1/talk/messages", "/v1/auth/logout", "/v1/auth/login"),
            env.requests.map { it.path },
        )
        env.requests.forEach { assertEquals(FAKE_KEY, it.key, "${it.path} 에 접속 키가 없다") }
    }

    /** 모든 경로를 한 번씩 부른다 — 연결 확인·대화·요약·사람 찾기·보내기·로그아웃·로그인. */
    private suspend fun Env.callEveryPath() {
        settings.status()
        assistant.ask(listOf(question)).toList()
        assistant.summarize("file-1", "a.pdf").toList()
        messenger.findPeople("이서연")
        messenger.send(Person("test2", "이서연"), "안녕하세요", "request-1")
        auth.logout()
        auth.login("test2", "1234")
    }

    /**
     * 접속 키는 모든 설치본이 함께 쓰는 값이다. 설정 주소를 다른 https 호스트로 바꾸게 속이면(피싱) 그 호스트가 키를 받아
     * 브릿지 전체에 들어올 수 있다. 키는 빌드 기본 주소의 호스트(대소문자 무시)와 로컬 주소에만 간다.
     */
    @Test
    fun `접속 키는 빌드 기본 주소의 호스트와 로컬 주소에만 싣는다`() = realTime {
        val hosts = BridgeAddress.accessKeyHosts("https://bridge.example.com")

        for (trusted in listOf("https://bridge.example.com", "https://BRIDGE.example.com:8443", "http://localhost:8095", "http://10.0.2.2:8095")) {
            val env = env(accessKeyHosts = hosts, url = trusted)
            env.callEveryPath()
            assertEquals(7, env.requests.size, trusted)
            env.requests.forEach { assertEquals(FAKE_KEY, it.key, "$trusted ${it.path} 에 접속 키가 없다") }
        }

        for (other in listOf("https://other.example.com", "https://bridge.example.com.other.example", "https://example.com")) {
            val env = env(accessKeyHosts = hosts, url = other)
            env.callEveryPath()
            assertEquals(7, env.requests.size, other)
            env.requests.forEach { assertNull(it.key, "$other ${it.path} 에 접속 키를 실었다") }
        }
    }

    @Test
    fun `키를 싣지 않은 주소가 키 없음으로 거절하면 키 불일치가 아니라 다른 서버라고 알린다`() = realTime {
        val keyMissing: (String) -> String = { """{"code":"client_key_invalid"}""" }
        val hosts = BridgeAddress.accessKeyHosts("https://bridge.example.com")
        val env = env(status = HttpStatusCode.Forbidden, accessKeyHosts = hosts, url = "https://other.example.com", body = keyMissing)
        val refused = BridgeRefusal.AccessKeyNotSent

        assertEquals(LoginResult.Refused(refused), env.auth.login("test1", "1234"))
        assertEquals(AnswerFailure.Refused(refused), assertFailsWith<AnswerFailure> { env.assistant.ask(listOf(question)).toList() })
        assertEquals(AnswerFailure.Refused(refused), assertFailsWith<AnswerFailure> { env.assistant.summarize("f", "a.pdf").toList() })
        assertEquals(SendResult.Failed(refused), env.messenger.send(Person("test2", "이서연"), "안녕하세요", "request-1"))
        assertEquals(refused, env.settings.status().refusal)

        val trusted = env(status = HttpStatusCode.Forbidden, accessKeyHosts = hosts, url = "https://bridge.example.com", body = keyMissing)
        assertEquals(LoginResult.Refused(BridgeRefusal.ClientKeyInvalid), trusted.auth.login("test1", "1234"))

        val keyless = env(accessKey = "", status = HttpStatusCode.Forbidden, accessKeyHosts = hosts, url = "https://other.example.com", body = keyMissing)
        assertEquals(LoginResult.Refused(BridgeRefusal.ClientKeyInvalid), keyless.auth.login("test1", "1234"), "키가 하나도 없는 앱은 키를 넣어야 한다")
    }

    /**
     * 공개 릴리스 앱은 키 없이 빌드하고, 쓰는 사람이 받은 키를 로그인 화면에서 넣는다. 그 키는 넣을 때의 서버 출처에 묶인다 —
     * 주소를 다른 호스트로 바꾸게 속여도 키가 따라가지 않는다.
     */
    @Test
    fun `사용자가 넣은 접속 키는 넣을 때의 서버 출처로만 싣는다`() = realTime {
        val env = env(accessKey = "", url = "https://bridge.example.com")
        env.settings.setAccessKey(USER_KEY)
        assertTrue(env.settings.hasAccessKey())

        env.callEveryPath()
        assertEquals(7, env.requests.size)
        env.requests.forEach { assertEquals(USER_KEY, it.key, "${it.path} 에 넣은 접속 키가 없다") }

        val moved = env(accessKey = "", url = "https://bridge.example.com")
        moved.settings.setAccessKey(USER_KEY)
        moved.settings.setBaseUrl("https://other.example.com")
        assertFalse(moved.settings.hasAccessKey(), "다른 호스트로 바꿨는데 키가 있다고 한다")
        moved.callEveryPath()
        assertEquals(7, moved.requests.size)
        moved.requests.forEach { assertNull(it.key, "다른 호스트 ${it.path} 에 넣은 키를 실었다") }
    }

    /** 같은 호스트라도 포트·스킴이 다르면 다른 서버(프로세스)일 수 있다. 포트를 적지 않은 주소는 스킴의 기본 포트와 같다. */
    @Test
    fun `넣은 키는 같은 호스트의 다른 포트로 싣지 않는다`() = realTime {
        for (same in listOf("https://bridge.example.com", "https://bridge.example.com:443", "https://BRIDGE.example.com/")) {
            val env = env(accessKey = "", url = "https://bridge.example.com")
            env.settings.setAccessKey(USER_KEY)
            env.settings.setBaseUrl(same.trimEnd('/'))
            env.callEveryPath()
            assertEquals(7, env.requests.size, same)
            env.requests.forEach { assertEquals(USER_KEY, it.key, "$same ${it.path} 에 넣은 키가 없다") }
        }

        for (other in listOf("https://bridge.example.com:8443", "http://localhost:9000")) {
            val env = env(accessKey = "", url = if (other.startsWith("http://")) "http://localhost:8095" else "https://bridge.example.com")
            env.settings.setAccessKey(USER_KEY)
            env.settings.setBaseUrl(other)
            assertFalse(env.settings.hasAccessKey(), other)
            env.callEveryPath()
            assertEquals(7, env.requests.size, other)
            env.requests.forEach { assertNull(it.key, "$other ${it.path} 에 넣은 키를 실었다") }
        }
    }

    /** 주소·키 없이 만든 빌드는 기본 주소가 에뮬레이터용 평문 주소라, 주소를 정하기 전에는 키를 묶지 않는다. */
    @Test
    fun `서버 주소를 정하기 전에는 넣은 키를 저장하지 않는다`() = realTime {
        val env = env(accessKey = "", isDefaultUsable = false)
        assertFalse(env.settings.isServerChosen())
        assertNull(env.settings.defaultBaseUrl())

        env.settings.setAccessKey(USER_KEY)
        assertFalse(env.settings.hasAccessKey(), "기본 주소에 키를 묶었다")

        env.settings.setBaseUrl("https://bridge.example.com")
        assertTrue(env.settings.isServerChosen())
        env.settings.setAccessKey(USER_KEY)
        assertTrue(env.settings.hasAccessKey())
    }

    @Test
    fun `넣은 키가 있는데 다른 호스트가 키 없음으로 거절하면 이 서버의 키가 없다고 알린다`() = realTime {
        val keyMissing: (String) -> String = { """{"code":"client_key_invalid"}""" }
        val env = env(accessKey = "", status = HttpStatusCode.Forbidden, url = "https://bridge.example.com", body = keyMissing)
        env.settings.setAccessKey(USER_KEY)
        env.settings.setBaseUrl("https://other.example.com")

        assertEquals(LoginResult.Refused(BridgeRefusal.AccessKeyNotSent), env.auth.login("test1", "1234"))
    }

    @Test
    fun `같은 출처에서는 넣은 키가 빌드 때 넣은 키보다 먼저다`() = realTime {
        val env = env(url = "http://localhost:8095")
        env.settings.setAccessKey(USER_KEY)

        env.callEveryPath()
        assertEquals(7, env.requests.size)
        env.requests.forEach { assertEquals(USER_KEY, it.key, "${it.path} 에 빌드 키를 실었다") }
    }

    @Test
    fun `키 없이 빌드했으면 헤더를 싣지 않는다`() = realTime {
        val env = env(accessKey = "")

        env.settings.status()
        env.auth.login("test2", "1234")

        assertTrue(env.requests.isNotEmpty())
        env.requests.forEach { assertNull(it.key, "${it.path} 에 빈 접속 키를 실었다") }
    }

    @Test
    fun `접속 키가 틀리면 로그인·대화·보내기·연결 확인이 모두 같은 이유로 알린다`() = realTime {
        val env = env(status = HttpStatusCode.Forbidden, body = { """{"code":"client_key_invalid","message":"client key"}""" })
        val refused = BridgeRefusal.ClientKeyInvalid

        assertEquals(LoginResult.Refused(refused), env.auth.login("test1", "1234"))
        assertEquals(AnswerFailure.Refused(refused), assertFailsWith<AnswerFailure> { env.assistant.ask(listOf(question)).toList() })
        assertEquals(AnswerFailure.Refused(refused), assertFailsWith<AnswerFailure> { env.assistant.summarize("f", "a.pdf").toList() })
        assertEquals(SendResult.Failed(refused), env.messenger.send(Person("test2", "이서연"), "안녕하세요", "request-1"))
        assertEquals(refused, env.settings.status().refusal)
        assertEquals(false, env.settings.status().bridge)
    }

    @Test
    fun `허용 사용자 목록 밖의 계정은 로그인에서 그 이유를 알린다`() = realTime {
        val env = env(status = HttpStatusCode.Forbidden, body = { """{"code":"user_not_allowed"}""" })

        assertEquals(LoginResult.Refused(BridgeRefusal.UserNotAllowed), env.auth.login("test9", "1234"))
    }

    @Test
    fun `본문이 너무 크면 다시 시도가 아니라 너무 길다고 알린다`() = realTime {
        val env = env(status = HttpStatusCode.PayloadTooLarge, body = { """{"code":"payload_too_large"}""" })
        val refused = BridgeRefusal.PayloadTooLarge

        assertEquals(AnswerFailure.Refused(refused), assertFailsWith<AnswerFailure> { env.assistant.ask(listOf(question)).toList() })
        assertEquals(SendResult.Failed(refused), env.messenger.send(Person("test2", "이서연"), "긴 내용", "request-1"))
        assertEquals(LoginResult.Refused(refused), env.auth.login("test1", "1234"))
    }

    @Test
    fun `동시 실행 상한의 503 busy 는 꺼진 서버와 다른 이유다`() = realTime {
        val busy = env(status = HttpStatusCode.ServiceUnavailable, body = { """{"code":"busy"}""" })
        assertEquals(
            AnswerFailure.Refused(BridgeRefusal.Busy),
            assertFailsWith<AnswerFailure> { busy.assistant.summarize("f", "a.pdf").toList() },
        )
        assertEquals(LoginResult.Refused(BridgeRefusal.Busy), busy.auth.login("test1", "1234"))

        val down = env(status = HttpStatusCode.ServiceUnavailable, body = { """{"code":"rag_unreachable"}""" })
        assertFailsWith<AnswerFailure.Unreachable> { down.assistant.ask(listOf(question)).toList() }
        assertEquals(LoginResult.Unreachable, down.auth.login("test1", "1234"))
        assertEquals(SendResult.Failed(null), down.messenger.send(Person("test2", "이서연"), "안녕하세요", "request-1"))
    }

    @Test
    fun `스트림 중 busy 오류도 같은 이유로 알린다`() = realTime {
        val env = env(body = { "event: error\ndata: {\"message\":\"busy\",\"code\":\"busy\"}\n\nevent: done\ndata: {}\n\n" })

        assertEquals(AnswerFailure.Refused(BridgeRefusal.Busy), assertFailsWith<AnswerFailure> { env.assistant.ask(listOf(question)).toList() })
    }

    /**
     * 실제 시간으로 돈다. [BridgeClient] 의 REST 요청은 `withTimeoutOrNull` 로 끊는데, `runTest` 의 가상 시간에서는 MockEngine 이
     * 다른 스레드에서 답하는 동안 시간이 건너뛰어 바로 끊긴다.
     */
    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default, block) }

    private companion object {
        const val FAKE_KEY = "test-bridge-key-not-real"
        const val USER_KEY = "user-entered-key-not-real-0123456789"
    }
}
