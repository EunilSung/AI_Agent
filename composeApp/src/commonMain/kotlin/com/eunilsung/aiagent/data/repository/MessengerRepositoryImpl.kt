package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.remote.ServerResult
import com.eunilsung.aiagent.domain.model.Person
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

    override suspend fun send(person: Person, content: String, requestId: String): Boolean {
        val token = sessionStore.token ?: return false
        return bridge.sendTalkMessage(token, SendTalkMessageRequest(person.id, content, requestId)) is ServerResult.Success
    }
}
