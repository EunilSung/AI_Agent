package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.remote.ChatRejectedException
import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.ChatStreamEvent
import com.eunilsung.aiagent.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

class AssistantRepositoryImpl(
    private val bridge: BridgeClient,
    private val sessionStore: SessionStore,
    private val mapper: ChatMapper,
) : AssistantRepository {

    override fun ask(history: List<ChatMessage>): Flow<AnswerEvent> = flow {
        val token = sessionStore.token ?: throw AnswerFailure.SessionExpired
        val events = bridge.chat(token, ChatRequest(history.map(mapper::toDto)))
            .transformWhile { event ->
                when (event.name) {
                    ChatStreamEvent.DONE -> if (mapper.notFound(event.data)) emit(AnswerEvent.NotFound)
                    ChatStreamEvent.SOURCES -> emit(mapper.retrieved(event.data))
                    ChatStreamEvent.ACTION -> mapper.sendRequested(event.data)?.let { emit(it) }
                    ChatStreamEvent.ERROR -> throw AnswerFailure.Server(mapper.errorMessage(event.data))
                    else -> emit(mapper.delta(event.data))
                }
                event.name != ChatStreamEvent.DONE
            }
        emitAll(events)
    }.catch { cause ->
        Log.message("[Assistant] 답변 실패: ${cause.stackTraceToString()}")
        throw cause.toFailure()
    }

    /** 브릿지가 거절한 이유를 사용자에게 보일 이유로 바꾼다. 통신 예외는 닿지 못함이다. */
    private fun Throwable.toFailure(): Throwable = when (this) {
        is CancellationException, is AnswerFailure -> this
        is ChatRejectedException -> when (status) {
            401 -> AnswerFailure.SessionExpired
            429 -> AnswerFailure.RateLimited
            503 -> AnswerFailure.Unreachable
            else -> AnswerFailure.Server(code)
        }
        else -> AnswerFailure.Unreachable
    }
}
