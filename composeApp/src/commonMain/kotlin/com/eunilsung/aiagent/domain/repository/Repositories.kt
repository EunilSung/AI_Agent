package com.eunilsung.aiagent.domain.repository

import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.Conversation
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 로그인 상태. 계정은 Talk 계정이고, 브릿지가 Talk 서버에 확인한다. */
interface AuthRepository {
    /** 로그인한 사람. 로그아웃 상태면 null. */
    val session: StateFlow<User?>

    /** 마지막으로 로그인한 아이디. 로그인 칸을 채우는 데 쓴다. */
    val lastLoginId: String

    suspend fun login(id: String, password: String): LoginResult

    suspend fun logout()
}

/** 로그인한 사람의 AI 대화 기록. 기기 DB 에 사람별로 남는다. */
interface ConversationRepository {
    /** 최근 대화가 앞에 온다. */
    val conversations: StateFlow<List<Conversation>>

    fun observeConversation(conversationId: String): Flow<Conversation?>

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>

    /** 지금 들고 있는 메시지. 질문을 보낼 때 대화 기록으로 쓴다. */
    suspend fun messages(conversationId: String): List<ChatMessage>

    suspend fun create(): Conversation

    suspend fun delete(conversationId: String)

    /**
     * 메시지를 넣거나 바꾼다. 같은 id 면 바꾼다.
     *
     * @param persist false 면 화면에만 반영하고 기기 DB 에는 쓰지 않는다 — 답변을 받는 동안 조각마다 DB 에 쓰지 않으려는 것이다.
     */
    suspend fun upsertMessage(message: ChatMessage, persist: Boolean = true)
}

/** 로컬 LLM 에 묻는다. */
interface AssistantRepository {
    /**
     * [history] 의 마지막 질문에 답한다. 실패하면 [com.eunilsung.aiagent.domain.model.AnswerFailure] 를 던진다.
     */
    fun ask(history: List<ChatMessage>): Flow<AnswerEvent>
}

/** Talk 메신저. 로그인한 사람으로 Talk 에 메시지를 보낸다. */
interface MessengerRepository {
    /** 이름이나 아이디가 맞는 Talk 사람. 나는 빠진다. 서버에 닿지 못하면 null. */
    suspend fun findPeople(query: String): List<Person>?

    /** [person] 과의 1:1 방에 [content] 를 보낸다. [requestId] 가 같으면 Talk 는 한 번만 받는다. */
    suspend fun send(person: Person, content: String, requestId: String): Boolean
}

/** 브릿지 주소와 연결 상태. */
interface ServerSettingsRepository {
    val baseUrl: StateFlow<String>

    /** 빈 값을 넣으면 빌드 기본값으로 돌아간다. */
    fun setBaseUrl(url: String)

    suspend fun status(): ServiceStatus
}
