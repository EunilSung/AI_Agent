package com.eunilsung.aiagent.domain.repository

import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.Conversation
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendResult
import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.model.SummaryRequest
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

    /**
     * 로그인한 사람의 대화를 다 읽은 목록. 로그인 바로 뒤라 아직 읽지 않았으면 읽고 돌려준다.
     * [conversations] 는 로그인이 바뀐 뒤 잠깐 앞 사람(또는 빈) 목록일 수 있다.
     */
    suspend fun currentConversations(): List<Conversation>

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

/**
 * 로컬 LLM 에 묻는다.
 *
 * 흐름이 정상으로 끝나면 답변을 다 받은 것이다. 서버가 끝을 알리기 전에 연결이 끊기면 받은 데까지 내보낸 뒤
 * [com.eunilsung.aiagent.domain.model.AnswerFailure.Unreachable] 을 던진다.
 */
interface AssistantRepository {
    /**
     * [history] 의 마지막 질문에 답한다. 실패하면 [com.eunilsung.aiagent.domain.model.AnswerFailure] 를 던진다.
     * 긴 대화도 계속 쓸 수 있게, 서버에는 마지막 질문과 그 앞의 최근 기록만 서버가 받는 크기 안에서 보낸다.
     */
    fun ask(history: List<ChatMessage>): Flow<AnswerEvent>

    /**
     * Talk 파일 [fileId] 를 요약한다. 긴 문서는 나눠 읽는 동안 [AnswerEvent.Progress] 가 오고, 그 뒤에 요약 조각이 온다.
     * 브릿지가 로그인한 사람의 Talk 토큰으로 파일을 받으므로 볼 수 없는 파일이면
     * [com.eunilsung.aiagent.domain.model.AnswerFailure.FileNotFound] 를 던진다.
     */
    fun summarize(fileId: String, fileName: String): Flow<AnswerEvent>
}

/**
 * Talk 에서 넘어온 문서 요약 요청.
 *
 * 앱 밖에서 열린 링크를 받아 두고, 처리를 기다리는 요청을 들고 있다 — 로그인을 기다리는 요청 하나와,
 * 대화방이 열리기를 기다리는 요청(대화 id 별). 기기에 저장하지 않는다. 앱이 꺼지면 사라진다.
 */
interface SummaryRequestRepository {
    /** 앱 밖에서 열린 링크. 앱 전체에서 한 곳만 받고, 받은 링크는 다시 나오지 않는다. */
    val incomingLinks: Flow<String>

    /** [request] 를 대화 [conversationId] 에 걸어 둔다. 그 대화방이 열리면 꺼내 요약을 시작한다. */
    fun hold(conversationId: String, request: SummaryRequest)

    /** 대화 [conversationId] 에 걸리는 요약을 걸리는 대로 꺼낸다. 꺼낸 요약은 다시 나오지 않는다. */
    fun takeHeld(conversationId: String): Flow<SummaryRequest>

    /** 로그인을 기다리게 한다. 앞에 기다리던 요청이 있으면 버린다. */
    fun awaitLogin(request: SummaryRequest)

    /** 로그인을 기다리던 요청을 꺼낸다. 없으면 null. */
    fun takeAwaitingLogin(): SummaryRequest?
}

/** Talk 메신저. 로그인한 사람으로 Talk 에 메시지를 보낸다. */
interface MessengerRepository {
    /** 이름이나 아이디가 맞는 Talk 사람. 나는 빠진다. 서버에 닿지 못하면 null. */
    suspend fun findPeople(query: String): List<Person>?

    /** [person] 과의 1:1 방에 [content] 를 보낸다. [requestId] 가 같으면 Talk 는 한 번만 받는다. */
    suspend fun send(person: Person, content: String, requestId: String): SendResult
}

/** 브릿지 주소와 연결 상태. */
interface ServerSettingsRepository {
    val baseUrl: StateFlow<String>

    /**
     * 빈 값을 넣으면 빌드 기본값으로 돌아간다. [com.eunilsung.aiagent.domain.util.BridgeAddress] 가 막는 주소는 저장하지 않는다.
     */
    fun setBaseUrl(url: String)

    /**
     * 빌드 기본 주소. 그대로 쓰면 안 되는 빌드(주소·접속 키 없이 만든 공개 릴리스 — 기본 주소가 에뮬레이터·시뮬레이터용 평문 주소다)면
     * null 이다. 그런 빌드는 기본값으로 되돌리지 않는다.
     */
    fun defaultBaseUrl(): String?

    /** 서버 주소를 정했는지 — 쓰는 사람이 저장했거나, 빌드 기본 주소를 그대로 써도 되는 빌드다. */
    fun isServerChosen(): Boolean

    /** 지금 서버 주소로 보낼 접속 키가 있는지. */
    fun hasAccessKey(): Boolean

    /**
     * 사용자가 받은 접속 키를 지금 서버 주소의 출처(`스킴://호스트:포트`)에 묶어 저장한다. 그 출처로만 싣는다.
     * 서버 주소를 정하기 전([isServerChosen] 이 false)에는 저장하지 않는다.
     */
    fun setAccessKey(key: String)

    suspend fun status(): ServiceStatus
}
