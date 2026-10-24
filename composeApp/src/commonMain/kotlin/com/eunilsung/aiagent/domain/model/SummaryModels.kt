package com.eunilsung.aiagent.domain.model

/**
 * Talk 에서 파일 대화를 길게 눌러 "AI 요약"을 고른 요청. Talk 가 `aiagent://summarize?…` 링크로 이 앱을 연다.
 *
 * @property fileId Talk 서버의 파일 id. 브릿지가 로그인한 사람의 Talk 토큰으로 이 파일을 받는다.
 * @property fileName 화면에 보이는 파일 이름. 확장자로 문서 형식을 가린다.
 * @property userId Talk 에 로그인한 사람의 아이디. 이 앱에 로그인한 [User.id] 와 같아야 요약한다.
 * @property userName 그 사람의 이름. 링크에 없으면 [userId] 다. 링크를 만든 앱이 정한 값이라 화면에 쓰지 않는다 —
 *   아무 앱이나 이 링크를 부를 수 있어서 이름 자리에 거짓 안내를 넣을 수 있다. 화면은 [userId] 만 보인다.
 */
data class SummaryRequest(
    val fileId: String,
    val fileName: String,
    val userId: String,
    val userName: String,
)

/** 요약 링크를 받거나 로그인한 뒤에 앱이 할 일. */
sealed interface SummaryLinkResult {
    /** 같은 계정이다. 이 대화를 열면 요약이 시작된다. */
    data class Open(val conversationId: String) : SummaryLinkResult

    /**
     * 다른 계정([currentUserId])으로 로그인해 있다. 로그아웃하고 [request] 의 계정으로 로그인할지 사용자에게 먼저 묻는다.
     * 아직 아무것도 바꾸지 않았다 — 사용자가 고르면 [com.eunilsung.aiagent.domain.usecase.ConfirmAccountSwitchUseCase] 가 로그아웃한다.
     */
    data class SwitchAccountRequested(val request: SummaryRequest, val currentUserId: String) : SummaryLinkResult

    /**
     * 요청한 계정으로 로그인해야 한다. 그 계정으로 로그인하면 이어서 요약한다.
     *
     * @property loggedOut 사용자가 고른 대로 다른 계정에서 로그아웃했으면 true, 처음부터 로그인하지 않았으면 false.
     */
    data class LoginRequired(val request: SummaryRequest, val loggedOut: Boolean) : SummaryLinkResult

    /** 요청한 계정이 아닌 계정으로 로그인해서 기다리던 요약을 버렸다. */
    data object Cancelled : SummaryLinkResult

    /** 할 일이 없다 — 요약 링크가 아니거나 값이 맞지 않거나, 로그인을 기다리던 요약이 없다. */
    data object Ignored : SummaryLinkResult
}
