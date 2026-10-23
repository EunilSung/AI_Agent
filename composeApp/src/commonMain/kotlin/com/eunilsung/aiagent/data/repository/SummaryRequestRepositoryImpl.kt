package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.IncomingLinks
import com.eunilsung.aiagent.domain.model.SummaryRequest
import com.eunilsung.aiagent.domain.repository.SummaryRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update

/**
 * Talk 에서 넘어온 요약 요청 — 메모리에만 둔다.
 *
 * 대화방에 걸어 둔 요청은 [Held] 로 감싼다. [Held] 는 같은 요청이라도 걸 때마다 다른 값이라, 같은 파일을 다시 요약해 달라고
 * 해도 지켜보는 쪽([takeHeld])이 새 요청으로 알아챈다(StateFlow 는 같은 값을 다시 내보내지 않는다).
 */
class SummaryRequestRepositoryImpl(
    override val incomingLinks: Flow<String> = IncomingLinks.links,
) : SummaryRequestRepository {

    private class Held(val request: SummaryRequest)

    private val held = MutableStateFlow<Map<String, Held>>(emptyMap())
    private val awaitingLogin = MutableStateFlow<SummaryRequest?>(null)

    override fun hold(conversationId: String, request: SummaryRequest) {
        held.update { it + (conversationId to Held(request)) }
    }

    override fun takeHeld(conversationId: String): Flow<SummaryRequest> =
        held.mapNotNull { map -> map[conversationId]?.let { take(conversationId, it) } }

    override fun awaitLogin(request: SummaryRequest) {
        awaitingLogin.value = request
    }

    override fun takeAwaitingLogin(): SummaryRequest? = awaitingLogin.getAndUpdate { null }

    /** [entry] 가 아직 걸려 있으면 떼어 내고 그 요청을 준다. 다른 곳이 먼저 뗐으면 null. */
    private fun take(conversationId: String, entry: Held): SummaryRequest? {
        while (true) {
            val current = held.value
            if (current[conversationId] !== entry) return null
            if (held.compareAndSet(current, current - conversationId)) return entry.request
        }
    }
}
