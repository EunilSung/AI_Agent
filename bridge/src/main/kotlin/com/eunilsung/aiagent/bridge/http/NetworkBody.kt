package com.eunilsung.aiagent.bridge.http

import io.ktor.client.statement.HttpResponse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI

/**
 * 응답 본문을 HTTP 엔진이 받은 채널 그대로 연다. 끝까지 읽은 뒤 `closedCause` 로 연결이 중간에 끊겼는지 알 수 있다.
 *
 * `bodyAsChannel()` 은 응답 파이프라인이 본문을 새 채널로 옮겨 담은 것을 준다. 그 옮겨 담기(Ktor 의 `copyTo`)는 원래
 * 채널이 이미 오류로 닫혀 있으면 예외 없이 정상으로 끝내 버려, 받는 쪽은 끊긴 본문과 다 온 본문을 구별하지 못한다.
 * 엔진 채널은 끊긴 이유를 남긴다. 브릿지는 본문을 바꾸는 클라이언트 플러그인(압축 풀기 등)을 쓰지 않으므로 두 채널의
 * 바이트는 같다 — gzip 은 OkHttp 가 엔진 안에서 푼다.
 */
@OptIn(InternalAPI::class)
internal fun HttpResponse.networkBody(): ByteReadChannel = rawContent
