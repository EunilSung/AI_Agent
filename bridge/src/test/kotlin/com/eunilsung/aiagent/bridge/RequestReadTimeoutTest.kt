package com.eunilsung.aiagent.bridge

import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.shared.api.BRIDGE_KEY_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 요청을 받는 동안의 시간 제한을 실제 Netty 엔진으로 본다. 테스트 엔진은 Netty 처리기를 쓰지 않는다.
 * Talk·RAG 서버는 [MockEngine] 이 대신한다.
 */
class RequestReadTimeoutTest {

    private val talk = MockEngine { request ->
        when (request.url.encodedPath) {
            "/users/me" -> respond("""{"id":"test1","name":"김민준"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else -> respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    /** 첫 줄을 보낸 뒤 [pauseMillis] 동안 아무것도 보내지 않다가 나머지를 보내는 가짜 RAG 서버. */
    private fun slowRag(pauseMillis: Long) = MockEngine { request ->
        if (request.url.encodedPath != "/v1/chat") return@MockEngine respond("{}", HttpStatusCode.NotFound)
        val body = CoroutineScope(Dispatchers.IO).writer {
            channel.writeFully(FIRST.encodeToByteArray())
            channel.flush()
            delay(pauseMillis)
            channel.writeFully(REST.encodeToByteArray())
        }.channel
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()))
    }

    private fun <T> withServer(timeoutMillis: Long, rag: MockEngine, block: (port: Int) -> T): T {
        val config = BridgeConfig(port = 0, accessKey = KEY, requestReadTimeoutMillis = timeoutMillis)
        val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
            bridgeServer(config, RagClient(HttpClient(rag), "http://rag"), TalkClient(HttpClient(talk), "http://talk"))
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            return block(port)
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `요청 머리를 다 보내지 않고 붙잡고 있으면 시간 제한에서 연결을 닫는다`() = withServer(timeoutMillis = 300, rag = slowRag(0)) { port ->
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("POST /v1/auth/login HTTP/1.1\r\nHost: localhost\r\n$BRIDGE_KEY_HEADER: $KEY\r\n".toByteArray())
                flush()
            }
            val started = System.nanoTime()
            val read = try {
                socket.getInputStream().read()
            } catch (e: SocketTimeoutException) {
                fail("5초가 지나도 연결이 열려 있다")
            } catch (e: java.io.IOException) {
                -1
            }
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(-1, read)
            assertTrue(waited in 200..4_000, "닫힐 때까지 ${waited}ms")
        }
    }

    @Test
    fun `본문을 아주 느리게 보내도 시간 제한에서 연결을 닫는다`() = withServer(timeoutMillis = 300, rag = slowRag(0)) { port ->
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write(
                ("POST /v1/auth/login HTTP/1.1\r\nHost: localhost\r\n$BRIDGE_KEY_HEADER: $KEY\r\n" +
                    "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{").toByteArray(),
            )
            out.flush()
            val read = try {
                socket.getInputStream().read()
            } catch (e: SocketTimeoutException) {
                fail("5초가 지나도 연결이 열려 있다")
            } catch (e: java.io.IOException) {
                -1
            }
            assertEquals(-1, read)
        }
    }

    /**
     * 앱은 SSE 를 받는 동안 아무것도 보내지 않는다. 답변이 시간 제한보다 오래 걸려도 끊기지 않아야 한다 — Netty 엔진의
     * `requestReadTimeoutSeconds` 를 쓰면 여기서 끊긴다.
     */
    @Test
    fun `응답을 보내는 동안은 시간 제한을 재지 않아 긴 SSE 도 끝까지 간다`() = withServer(timeoutMillis = 300, rag = slowRag(1_200)) { port ->
        val client = HttpClient(OkHttp) { engine { config { readTimeout(10, TimeUnit.SECONDS) } } }
        val body = runBlocking {
            client.post("http://127.0.0.1:$port/v1/chat") {
                header(BRIDGE_KEY_HEADER, KEY)
                header(HttpHeaders.Authorization, "Bearer talk-token")
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"messages":[{"role":"user","content":"반차는 몇 시간이야?"}]}""")
            }.bodyAsText()
        }
        client.close()
        assertEquals(FIRST + REST, body)
    }

    private companion object {
        const val KEY = "fake-bridge-key-for-unit-tests-only-0123456789"
        const val FIRST = ": connected\n\n"
        const val REST = "data: {\"delta\": \"4시간\"}\n\nevent: done\ndata: {}\n\n"
    }
}
