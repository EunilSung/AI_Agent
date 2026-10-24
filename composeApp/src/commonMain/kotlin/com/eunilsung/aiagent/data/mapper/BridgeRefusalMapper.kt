package com.eunilsung.aiagent.data.mapper

import com.eunilsung.aiagent.data.remote.ACCESS_KEY_NOT_SENT
import com.eunilsung.aiagent.domain.model.BridgeRefusal
import com.eunilsung.aiagent.shared.api.ErrorCode

/**
 * 브릿지 거절 code([ErrorCode]) 가운데 어느 요청에서나 올 수 있는 것을 [BridgeRefusal] 로 바꾼다. 그 밖의 code 면 null —
 * 부르는 쪽이 자기 규칙(상태 401·429 등)으로 가린다.
 *
 * 상태가 아니라 code 로 가린다. 브릿지는 403·413·503 을 늘 code 와 함께 준다. code 없는 413·503 은 중간 프록시나
 * 꺼진 서버라 이 이유들이 아니다. [ACCESS_KEY_NOT_SENT] 는 브릿지가 아니라 [com.eunilsung.aiagent.data.remote.BridgeClient] 가
 * 붙이는 code 다(키를 싣지 않은 주소에서 키 없음으로 거절됨).
 */
fun bridgeRefusalOf(code: String): BridgeRefusal? = when (code) {
    ErrorCode.CLIENT_KEY_INVALID -> BridgeRefusal.ClientKeyInvalid
    ACCESS_KEY_NOT_SENT -> BridgeRefusal.AccessKeyNotSent
    ErrorCode.USER_NOT_ALLOWED -> BridgeRefusal.UserNotAllowed
    ErrorCode.PAYLOAD_TOO_LARGE -> BridgeRefusal.PayloadTooLarge
    ErrorCode.BUSY -> BridgeRefusal.Busy
    else -> null
}
