package com.eunilsung.aiagent.testsupport

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.domain.model.AnswerEvent
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AssistantRepository
import com.eunilsung.aiagent.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 로그인 대역. 진짜 [SessionStore] 를 바꿔서, 그 저장소를 보는 대화 저장소도 로그인이 바뀐 것을 그대로 안다.
 * 비밀번호는 보지 않는다. 이름은 Talk 테스트 계정과 같게 둔다.
 */
class FakeAuth(private val store: SessionStore) : AuthRepository {
    var logoutCount = 0
        private set

    override val session: StateFlow<User?> = store.user
    override val lastLoginId: String get() = store.lastLoginId

    override suspend fun login(id: String, password: String): LoginResult {
        val user = User(id, NAMES[id] ?: id)
        store.lastLoginId = id
        store.save("token-$id", user)
        return LoginResult.Success(user)
    }

    override suspend fun logout() {
        logoutCount++
        store.clear()
    }

    companion object {
        val NAMES = mapOf("test1" to "김민준", "test2" to "이서연", "test3" to "박서준")
    }
}

/** 요약 대역. 받은 요청을 [summarized] 에 남기고 [answer] 가 정한 이벤트를 내보낸다. 대화(ask)는 쓰지 않는다. */
class FakeSummaryAssistant(
    private val answer: (fileId: String, fileName: String) -> Flow<AnswerEvent>,
) : AssistantRepository {
    val summarized = mutableListOf<Pair<String, String>>()

    override fun ask(history: List<ChatMessage>): Flow<AnswerEvent> = error("요약 테스트는 대화를 쓰지 않는다")

    override fun summarize(fileId: String, fileName: String): Flow<AnswerEvent> {
        summarized += fileId to fileName
        return answer(fileId, fileName)
    }
}
