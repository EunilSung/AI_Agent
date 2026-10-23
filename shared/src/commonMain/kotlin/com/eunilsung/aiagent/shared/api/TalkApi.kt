package com.eunilsung.aiagent.shared.api

import kotlinx.serialization.Serializable

/**
 * Talk 메신저로 메시지 보내기.
 *
 * AI 는 받는 사람과 내용만 정한다(`event: action`). 앱이 사용자에게 확인을 받은 뒤 브릿지를 부르고, 브릿지는
 * 로그인한 사람의 Talk 토큰으로 Talk 서버의 기존 API(사람 목록·1:1 방 열기·대화 보내기)를 부른다.
 * 그래서 Talk 에서는 로그인한 사람이 직접 보낸 메시지로 보인다.
 */

/** `GET /v1/talk/people?q=` 응답 — 이름이나 아이디가 맞는 사람. 나는 빠진다. */
@Serializable
data class PeopleResponse(
    val people: List<TalkPersonDto> = emptyList(),
)

@Serializable
data class TalkPersonDto(
    val id: String,
    val name: String,
    val organName: String = "",
    val positionName: String = "",
)

/**
 * `POST /v1/talk/messages` — [recipientId] 와의 1:1 방에 [content] 를 보낸다. 방이 없으면 Talk 서버가 만든다.
 *
 * [requestId] 는 앱이 만든 id 로, Talk 의 대화 id 가 된다. 응답을 받지 못해 다시 보내도 Talk 는 한 번만 받는다.
 */
@Serializable
data class SendTalkMessageRequest(
    val recipientId: String,
    val content: String,
    val requestId: String,
)

@Serializable
data class SendTalkMessageResponse(
    val roomId: String,
)
