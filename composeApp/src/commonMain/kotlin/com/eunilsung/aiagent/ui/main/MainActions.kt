package com.eunilsung.aiagent.ui.main

sealed interface MainActions {
    /** 다른 계정의 요약 링크에 [로그아웃]을 골랐다. 지금 계정에서 로그아웃하고 요청한 계정의 로그인을 기다린다. */
    data object OnAccountSwitchConfirm : MainActions

    /** [취소]를 골랐거나 대화상자를 닫았다. 아무것도 바꾸지 않는다. */
    data object OnAccountSwitchCancel : MainActions
}

/** 앱 전체가 한 번만 처리할 일. */
sealed interface MainEvent {
    /** 로그아웃했다. 열려 있던 대화방을 닫는다. */
    data object LoggedOut : MainEvent

    /** Talk 요약 링크로 이 대화를 연다. 대화방이 열리면 요약이 시작된다. */
    data class OpenChat(val conversationId: String) : MainEvent

    /**
     * Talk 요약 링크가 지금([currentUserId])과 다른 계정([requestedUserId])의 요청이다. 로그아웃하고 그 계정으로 로그인할지 묻는다.
     * 답은 [MainActions.OnAccountSwitchConfirm]·[MainActions.OnAccountSwitchCancel] 로 돌려준다. 이름은 링크를 만든 앱이 정한
     * 값이라 보이지 않고 아이디만 보인다.
     */
    data class ConfirmAccountSwitch(val currentUserId: String, val requestedUserId: String) : MainEvent

    /**
     * Talk 요약 링크의 계정([userId])으로 로그인해야 한다. 로그인 칸의 아이디를 그 계정으로 바꾼다.
     *
     * @property loggedOut 사용자가 고른 대로 다른 계정에서 로그아웃했으면 true — 방금 물어봤으므로 안내를 다시 띄우지 않는다.
     *   처음부터 로그인하지 않았으면 false — 로그인 안내를 띄운다.
     */
    data class SummaryLoginRequired(val userId: String, val loggedOut: Boolean) : MainEvent

    /** 요약을 요청한 계정이 아닌 계정으로 로그인해 요약을 취소했다. */
    data object SummaryCancelled : MainEvent
}
