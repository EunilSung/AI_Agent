package com.eunilsung.aiagent.ui.chat

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.refusal_access_key_not_sent
import aiagent.composeapp.generated.resources.refusal_busy
import aiagent.composeapp.generated.resources.refusal_client_key_invalid
import aiagent.composeapp.generated.resources.refusal_payload_too_large
import aiagent.composeapp.generated.resources.refusal_user_not_allowed
import aiagent.composeapp.generated.resources.toast_chat_rate_limited
import aiagent.composeapp.generated.resources.toast_chat_server
import aiagent.composeapp.generated.resources.toast_chat_unreachable
import aiagent.composeapp.generated.resources.toast_session_expired
import aiagent.composeapp.generated.resources.toast_summary_broken
import aiagent.composeapp.generated.resources.toast_summary_encrypted
import aiagent.composeapp.generated.resources.toast_summary_no_text
import aiagent.composeapp.generated.resources.toast_summary_not_found
import aiagent.composeapp.generated.resources.toast_summary_too_large
import aiagent.composeapp.generated.resources.toast_summary_unsupported
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.BridgeRefusal
import com.eunilsung.aiagent.ui.util.text
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 답변·요약 실패 토스트. 문구는 이유마다 정해진 문자열(ko/en)이고, 서버가 준 설명(기록용 영어 문장)은 화면에 넣지 않는다.
 * [StringResource][org.jetbrains.compose.resources.StringResource] 를 돌려주므로 서버 설명을 끼워 넣을 자리가 없다.
 */
class FailureToastTest {

    @Test
    fun `서버 실패는 서버가 준 설명과 상관없이 같은 문구다`() {
        val reasons = listOf(
            "",
            "stream interrupted",
            "Server error '500 Internal Server Error' for url 'http://localhost:11434/v1/chat/completions'",
            "file is larger than 20971520 bytes",
        )
        for (reason in reasons) {
            assertEquals(Res.string.toast_chat_server, AnswerFailure.Server(reason).toastText(), reason)
        }
    }

    @Test
    fun `이유마다 정해진 문구를 쓴다`() {
        val cases = mapOf(
            AnswerFailure.RateLimited to Res.string.toast_chat_rate_limited,
            AnswerFailure.Unreachable to Res.string.toast_chat_unreachable,
            AnswerFailure.SessionExpired to Res.string.toast_session_expired,
            AnswerFailure.UnsupportedFile to Res.string.toast_summary_unsupported,
            AnswerFailure.NoText to Res.string.toast_summary_no_text,
            AnswerFailure.EncryptedFile to Res.string.toast_summary_encrypted,
            AnswerFailure.BrokenFile to Res.string.toast_summary_broken,
            AnswerFailure.FileNotFound to Res.string.toast_summary_not_found,
            AnswerFailure.FileTooLarge to Res.string.toast_summary_too_large,
        )
        for ((failure, expected) in cases) {
            assertEquals(expected, failure.toastText(), failure.toString())
        }
    }

    @Test
    fun `브릿지 공통 거절은 이유마다 정해진 문구를 쓰고 보내기 실패도 같은 문구다`() {
        val cases = mapOf(
            BridgeRefusal.ClientKeyInvalid to Res.string.refusal_client_key_invalid,
            BridgeRefusal.AccessKeyNotSent to Res.string.refusal_access_key_not_sent,
            BridgeRefusal.UserNotAllowed to Res.string.refusal_user_not_allowed,
            BridgeRefusal.PayloadTooLarge to Res.string.refusal_payload_too_large,
            BridgeRefusal.Busy to Res.string.refusal_busy,
        )
        for ((refusal, expected) in cases) {
            assertEquals(expected, AnswerFailure.Refused(refusal).toastText(), refusal.name)
            assertEquals(expected, refusal.text(), refusal.name)
        }
        assertEquals(BridgeRefusal.entries.size, cases.size, "문구가 없는 거절 이유가 있다")
    }

    @Test
    fun `브릿지가 알린 중계 끊김은 done 없이 끝난 스트림과 같은 문구다`() {
        val interrupted = ChatMapper().streamFailure("""{"message":"stream interrupted","code":"stream_interrupted"}""")
        assertEquals(AnswerFailure.Unreachable.toastText(), interrupted.toastText())
    }
}
