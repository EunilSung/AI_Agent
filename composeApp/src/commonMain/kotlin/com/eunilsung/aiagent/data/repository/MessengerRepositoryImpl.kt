package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.mapper.bridgeRefusalOf
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.remote.ServerResult
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendResult
import com.eunilsung.aiagent.domain.repository.MessengerRepository
import com.eunilsung.aiagent.shared.api.SendTalkMessageRequest

/** Talk 메신저 — 브릿지가 로그인한 사람의 Talk 토큰으로 Talk 서버를 부른다. */
class MessengerRepositoryImpl(
    private val bridge: BridgeClient,
    private val sessionStore: SessionStore,
    private val mapper: ChatMapper,
) : MessengerRepository {

    override suspend fun findPeople(query: String): List<Person>? {
        val token = sessionStore.token ?: return null
        return when (val result = bridge.findPeople(token, query)) {
            is ServerResult.Success -> result.value.people.map(mapper::toDomain)
            else -> null
        }
    }

    /** 브릿지 공통 거절(본문이 너무 큼·접속 키 등)이면 그 이유를 함께 돌려줘, 화면이 '다시 시도'가 아닌 맞는 안내를 띄우게 한다. */
    override suspend fun send(person: Person, content: String, requestId: String): SendResult {
        val token = sessionStore.token ?: return SendResult.Failed()
        return when (val result = bridge.sendTalkMessage(token, SendTalkMessageRequest(person.id, content, requestId))) {
            is ServerResult.Success -> SendResult.Sent
            is ServerResult.Rejected -> SendResult.Failed(bridgeRefusalOf(result.code))
            ServerResult.Unreachable -> SendResult.Failed()
        }
    }
}
