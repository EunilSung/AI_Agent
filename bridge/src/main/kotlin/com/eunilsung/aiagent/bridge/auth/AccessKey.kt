package com.eunilsung.aiagent.bridge.auth

import com.eunilsung.aiagent.bridge.http.isDirectLocal
import com.eunilsung.aiagent.bridge.routes.respondError
import com.eunilsung.aiagent.shared.api.ApiError
import com.eunilsung.aiagent.shared.api.BRIDGE_KEY_HEADER
import com.eunilsung.aiagent.shared.api.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import java.security.MessageDigest

/**
 * 모든 요청(`/v1/health` 와 없는 경로까지)이 [BRIDGE_KEY_HEADER] 에 [expected] 를 실었는지 본다. 없거나 틀리면 403
 * [ErrorCode.CLIENT_KEY_INVALID] 로 끝내고 경로까지 가지 않는다.
 *
 * 브릿지 주소(Funnel 의 ts.net 이름)는 공개 인증서 기록에 올라가 숨길 수 없고, Talk 테스트 계정은 공개 저장소에 적혀 있다.
 * 그래서 Talk 로그인만으로는 바깥 사람을 막지 못한다. 접속 키는 앱을 빌드할 때만 들어가므로, 키를 모르는 사람은 상태 확인
 * 응답으로 이 서비스인지 알아볼 수도 없다.
 *
 * 비교는 두 값의 SHA-256 을 [MessageDigest.isEqual] 로 견준다. 길이와 앞 글자가 맞는지에 따라 걸리는 시간이 달라지지 않는다.
 *
 * [expected] 가 null 이면(접속 키 없이 띄운 시험용 브릿지) 키 대신 이 기기 안에서 프록시 없이 바로 온 요청인지([isDirectLocal])
 * 본다. 기본 주소 `127.0.0.1` 로 띄워도 Tailscale Funnel 이 켜져 있으면 인터넷의 요청이 루프백으로 들어오므로, 프록시 헤더가
 * 붙은 요청과 바깥 주소에서 온 요청(`HOST=0.0.0.0`)은 403 이다.
 */
fun Application.requireAccessKey(expected: String?) {
    val expectedDigest = expected?.sha256()
    intercept(ApplicationCallPipeline.Plugins) {
        val allowed = if (expectedDigest == null) {
            isDirectLocal(call.request.local.remoteAddress, call.request.headers.names())
        } else {
            val given = call.request.headers[BRIDGE_KEY_HEADER]
            given != null && MessageDigest.isEqual(given.sha256(), expectedDigest)
        }
        if (!allowed) {
            call.respondError(HttpStatusCode.Forbidden, ApiError(ErrorCode.CLIENT_KEY_INVALID))
            finish()
        }
    }
}

private fun String.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(toByteArray())
