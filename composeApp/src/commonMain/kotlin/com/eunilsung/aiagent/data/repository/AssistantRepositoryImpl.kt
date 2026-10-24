package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatHistoryWindow
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.remote.ChatRejectedException
import com.eunilsung.aiagent.data.remote.SseEvent
import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.shared.api.ChatRequest
import com.eunilsung.aiagent.shared.api.ChatStreamEvent
import com.eunilsung.aiagent.shared.api.SummarizeRequest
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

    /** 대화 기록은 브릿지 상한 안으로 최근 것만 골라 보낸다([ChatHistoryWindow]). */
    override fun ask(history: List<ChatMessage>): Flow<AnswerEvent> = answer("답변") { token ->
        bridge.chat(token, ChatRequest(ChatHistoryWindow.select(history.map(mapper::toDto))))
    }

    override fun summarize(fileId: String, fileName: String): Flow<AnswerEvent> = answer("요약") { token ->
        bridge.summarize(token, SummarizeRequest(fileId = fileId, fileName = fileName))
    }

    /** 로그인 토큰으로 SSE 를 열어 도메인 이벤트로 바꾼다. 실패는 사용자에게 보일 이유([AnswerFailure])로 바꿔 던진다. */
    private fun answer(label: String, open: (token: String) -> Flow<SseEvent>): Flow<AnswerEvent> = flow {
        val token = sessionStore.token ?: throw AnswerFailure.SessionExpired
        emitAll(open(token).toAnswerEvents())
    }.catch { cause ->
        Log.message("[Assistant] $label 실패: ${cause.stackTraceToString()}")
        throw cause.toFailure()
    }

    /**
     * 이름 없는 이벤트만 답변 조각이다. 모르는 이름은 건너뛴다 — 서버가 새 이벤트를 보내도 그 내용이 답변에 섞이지 않게.
     *
     * `done` 에서 끝낸다. RAG 서버는 성공·실패 모두 마지막에 `done` 을 보내므로, `done` 없이 본문이 끝났으면 중간에 끊긴
     * 것이다 — 브릿지가 중계하다 끊겨도 앱에는 응답이 정상으로 끝난 것처럼 올 수 있다. 그때는 받은 조각을 내보낸 뒤
     * [IncompleteStreamException] 을 던져 잘린 답변을 다 받은 것으로 남기지 않게 한다([AnswerFailure.Unreachable]).
     */
    private fun Flow<SseEvent>.toAnswerEvents(): Flow<AnswerEvent> = flow {
        var completed = false
        emitAll(
            transformWhile { event ->
                when (event.name) {
                    null, "" -> emit(mapper.delta(event.data))
                    ChatStreamEvent.DONE -> {
                        completed = true
                        if (mapper.notFound(event.data)) emit(AnswerEvent.NotFound)
                    }
                    ChatStreamEvent.SOURCES -> emit(mapper.retrieved(event.data))
                    ChatStreamEvent.PROGRESS -> mapper.progress(event.data)?.let { emit(it) }
                    ChatStreamEvent.ACTION -> mapper.sendRequested(event.data)?.let { emit(it) }
                    ChatStreamEvent.ERROR -> throw mapper.streamFailure(event.data)
                    else -> Log.message("[Assistant] 모르는 이벤트 건너뜀: ${event.name}")
                }
                !completed
            }
        )
        if (!completed) throw IncompleteStreamException()
    }

    /** 브릿지가 거절한 이유를 사용자에게 보일 이유로 바꾼다. 통신 예외와 중간에 끊긴 스트림은 닿지 못함이다. */
    private fun Throwable.toFailure(): Throwable = when (this) {
        is CancellationException, is AnswerFailure -> this
        is ChatRejectedException -> mapper.rejection(status, code)
        else -> AnswerFailure.Unreachable
    }
}

/** SSE 본문이 `done` 없이 끝났다 — 답변이 중간에 잘렸다. 로그에 원인이 남도록 이름을 붙인 예외다. */
private class IncompleteStreamException : Exception("SSE stream ended without done")
