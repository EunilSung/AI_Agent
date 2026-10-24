package com.eunilsung.aiagent.ui.util

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.refusal_access_key_not_sent
import aiagent.composeapp.generated.resources.refusal_busy
import aiagent.composeapp.generated.resources.refusal_client_key_invalid
import aiagent.composeapp.generated.resources.refusal_payload_too_large
import aiagent.composeapp.generated.resources.refusal_user_not_allowed
import com.eunilsung.aiagent.domain.model.BridgeRefusal
import org.jetbrains.compose.resources.StringResource

/** 브릿지가 받지 않은 이유를 알리는 문구. 로그인 안내·대화 토스트·보내기 실패 토스트가 같은 문구를 쓴다. */
fun BridgeRefusal.text(): StringResource = when (this) {
    BridgeRefusal.ClientKeyInvalid -> Res.string.refusal_client_key_invalid
    BridgeRefusal.AccessKeyNotSent -> Res.string.refusal_access_key_not_sent
    BridgeRefusal.UserNotAllowed -> Res.string.refusal_user_not_allowed
    BridgeRefusal.PayloadTooLarge -> Res.string.refusal_payload_too_large
    BridgeRefusal.Busy -> Res.string.refusal_busy
}
