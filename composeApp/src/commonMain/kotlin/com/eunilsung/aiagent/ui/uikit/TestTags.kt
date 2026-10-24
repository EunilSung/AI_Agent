package com.eunilsung.aiagent.ui.uikit

import androidx.compose.ui.Modifier

/**
 * 화면 자동화(Maestro)가 요소를 찾는 이름. `.maestro` 의 흐름 파일이 같은 문자열을 `id` 로 쓴다.
 *
 * 화면 문구는 기기 언어에 따라 바뀌므로, 흐름이 누르고 확인하는 요소는 이 이름으로 찾는다.
 * 이름을 바꾸면 흐름 파일도 함께 바꾼다.
 */
object TestTags {
    const val LOGIN_ID = "login_id"
    const val LOGIN_PW = "login_pw"
    const val LOGIN_SUBMIT = "login_submit"

    const val CONVERSATION_NEW = "conversation_new"
    const val CONVERSATION_ITEM = "conversation_item"

    const val CHAT_BACK = "chat_back"
    const val CHAT_INPUT = "chat_input"
    const val CHAT_SEND = "chat_send"
    const val CHAT_STOP = "chat_stop"
    /** 다 받은 AI 답변 말풍선. 받는 중에는 붙지 않는다. */
    const val CHAT_ANSWER = "chat_answer"
    const val CHAT_SOURCE = "chat_source"
    /** 받는 중인 요약의 "문서를 읽는 중이에요 (2/5)". 긴 문서만 보인다. */
    const val CHAT_PROGRESS = "chat_progress"

    /** AI 가 정한 Talk 메시지 보내기 카드. */
    const val SEND_CARD = "send_card"
    const val SEND_CONFIRM = "send_confirm"
    const val SEND_CANCEL = "send_cancel"
    /** 보낸 뒤 결과 문구. */
    const val SEND_RESULT = "send_result"

    const val SETTING_LOGOUT = "setting_logout"
    const val SETTING_STATUS_BRIDGE = "setting_status_bridge"

    const val DIALOG_CONFIRM = "dialog_confirm"
    /** 두 버튼 대화상자의 [취소] — Talk 요약 링크의 계정 바꾸기 물음 등. */
    const val DIALOG_CANCEL = "dialog_cancel"
    /** 제목이 있는 알림·확인 대화상자의 제목 — Talk 요약 링크의 로그인 안내·계정 바꾸기 물음. Talk 의 실화면 흐름도 이것을 본다. */
    const val DIALOG_TITLE = "dialog_title"

    /** 화면 아래에 잠깐(2초) 떴다 사라지는 토스트의 글 — 요약 취소 안내 등. */
    const val TOAST_MESSAGE = "toast_message"

    /** 하단 탭. [key] 는 탭의 상태 보존 키(chat·setting)다. */
    fun tab(key: String): String = "tab_$key"

    /** 빈 대화의 [index] 번째 추천 질문(0부터). */
    fun chatSuggestion(index: Int): String = "chat_suggestion_$index"

    /** 보내기 카드에서 고를 수 있는 [index] 번째 사람(0부터). */
    fun sendCandidate(index: Int): String = "send_candidate_$index"
}

/**
 * 이 아래에 붙은 testTag 를 화면 자동화가 찾을 수 있게 내보낸다.
 *
 * Android 는 testTag 를 resource-id 로 내보내라고 알려야 하고, 다이얼로그는 창이 따로라 다이얼로그마다 붙인다.
 * iOS 는 testTag 가 그대로 접근성 식별자로 나가 할 일이 없다.
 */
expect fun Modifier.exposeTestTags(): Modifier
