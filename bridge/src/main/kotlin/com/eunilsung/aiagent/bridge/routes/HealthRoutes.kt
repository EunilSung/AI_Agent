package com.eunilsung.aiagent.bridge.routes

import com.eunilsung.aiagent.bridge.rag.RagClient
import com.eunilsung.aiagent.bridge.talk.TalkClient
import com.eunilsung.aiagent.shared.api.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 브릿지와 그 뒤의 RAG 서버·Talk 서버 상태. 브릿지가 응답하면 브릿지 자신은 떠 있는 것이다. */
fun Route.healthRoutes(rag: RagClient, talk: TalkClient) {
    get("/v1/health") {
        val response = coroutineScope {
            val ragHealth = async { rag.health() }
            val talkUp = async { talk.isUp() }
            HealthResponse(rag = ragHealth.await(), talk = talkUp.await())
        }
        call.respond(response)
    }
}
