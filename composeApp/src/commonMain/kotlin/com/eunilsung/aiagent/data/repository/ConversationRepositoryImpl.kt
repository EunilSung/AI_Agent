package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.ChatMapper
import com.eunilsung.aiagent.db.AppDatabase
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.Conversation
import com.eunilsung.aiagent.domain.repository.ConversationRepository
import com.eunilsung.aiagent.domain.util.newId
import com.eunilsung.aiagent.domain.util.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 기기 DB 에 사람별로 남는 AI 대화 기록.
 *
 * 화면은 메모리의 상태를 보고, 저장은 그 뒤에 따라간다. 로그인한 사람이 바뀌면 그 사람의 대화를 다시 읽는다.
 * 앱이 답변을 받다가 꺼졌으면 남아 있는 '받는 중' 답변을 처음 열 때 '중지됨'으로 바꾼다.
 */
class ConversationRepositoryImpl(
    database: AppDatabase,
    private val sessionStore: SessionStore,
    private val mapper: ChatMapper,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ConversationRepository {

    private val queries = database.appDatabaseQueries
    private val mutex = Mutex()
    private var ownerId: String? = null
    /** [ownerId] 의 대화를 한 번이라도 읽었는지. 로그인이 바뀐 것을 두 곳(지켜보는 쪽·[syncOwner])이 알아도 한 번만 읽는다. */
    private var isLoaded = false

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** 열어 본 대화의 메시지. 대화 id 별로 한 번만 DB 에서 읽는다. */
    private val messageCache = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    init {
        queries.markStreamingStopped()
        scope.launch {
            sessionStore.user.collect { user -> reload(user?.id) }
        }
    }

    override fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversations.map { list -> list.firstOrNull { it.id == conversationId } }.distinctUntilChanged()

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageCache
            .onStart { ensureLoaded(conversationId) }
            .map { it[conversationId].orEmpty() }
            .distinctUntilChanged()

    override suspend fun currentConversations(): List<Conversation> = mutex.withLock {
        syncOwner()
        _conversations.value
    }

    override suspend fun messages(conversationId: String): List<ChatMessage> {
        ensureLoaded(conversationId)
        return messageCache.value[conversationId].orEmpty()
    }

    override suspend fun create(): Conversation = mutex.withLock {
        syncOwner()
        val owner = ownerId ?: error("로그인하지 않았다")
        val conversation = Conversation(id = newId(), title = "", preview = "", updatedAt = nowMillis())
        save(owner, conversation)
        _conversations.update { listOf(conversation) + it }
        messageCache.update { it + (conversation.id to emptyList()) }
        conversation
    }

    override suspend fun delete(conversationId: String) = mutex.withLock {
        queries.transaction {
            queries.deleteMessagesOf(conversationId)
            queries.deleteConversation(conversationId)
        }
        _conversations.update { list -> list.filterNot { it.id == conversationId } }
        messageCache.update { it - conversationId }
    }

    override suspend fun upsertMessage(message: ChatMessage, persist: Boolean) = mutex.withLock {
        messageCache.update { cache ->
            val current = cache[message.conversationId].orEmpty()
            val next = if (current.any { it.id == message.id }) {
                current.map { if (it.id == message.id) message else it }
            } else {
                (current + message).sortedBy { it.createdAt }
            }
            cache + (message.conversationId to next)
        }

        val conversation = _conversations.value.firstOrNull { it.id == message.conversationId } ?: return@withLock
        val updated = conversation.copy(
            title = conversation.title.ifEmpty { if (message.role == ChatRole.User) message.content.toTitle() else "" },
            preview = message.content.ifEmpty { message.sendRequest?.content.orEmpty() }.toPreview().ifEmpty { conversation.preview },
            updatedAt = maxOf(conversation.updatedAt, message.createdAt),
        )
        _conversations.update { list -> (listOf(updated) + list.filterNot { it.id == updated.id }).sortedByDescending { it.updatedAt } }

        if (persist) {
            val owner = ownerId ?: return@withLock
            queries.transaction {
                queries.upsertMessage(
                    message.id, message.conversationId, message.role.name, message.content,
                    mapper.encodeSources(message.sources), message.searchQuery, message.status.name, message.createdAt,
                    mapper.encodeSendRequest(message.sendRequest), if (message.summary) 1L else 0L,
                )
                save(owner, updated)
            }
        }
    }

    private suspend fun reload(owner: String?) = mutex.withLock { load(owner) }

    /**
     * 로그인한 사람이 바뀌었는데 아직 그 사람의 대화를 읽지 않았으면 지금 읽는다. [mutex] 를 잡은 채로 부른다.
     * 로그인을 지켜보는 쪽([reload])은 따로 돌아서, 로그인 바로 뒤에 부르면 아직 앞 사람의 상태일 수 있다.
     */
    private fun syncOwner() = load(sessionStore.user.value?.id)

    private fun load(owner: String?) {
        if (isLoaded && ownerId == owner) return
        isLoaded = true
        ownerId = owner
        messageCache.value = emptyMap()
        _conversations.value = owner?.let { queries.selectConversations(it).executeAsList().map(mapper::toDomain) }.orEmpty()
    }

    private suspend fun ensureLoaded(conversationId: String) = mutex.withLock {
        if (conversationId in messageCache.value) return@withLock
        val loaded = queries.selectMessages(conversationId).executeAsList().map(mapper::toDomain)
        messageCache.update { it + (conversationId to loaded) }
    }

    private fun save(owner: String, conversation: Conversation) {
        queries.upsertConversation(conversation.id, owner, conversation.title, conversation.preview, conversation.updatedAt)
    }

    private fun String.toTitle(): String = lineSequence().first().trim().take(TITLE_LENGTH)

    /** 줄바꿈을 남긴다 — 줄 단위 마크다운 기호(제목·목록)는 줄이 살아 있어야 목록 화면이 가려 지울 수 있다. */
    private fun String.toPreview(): String = trim().take(PREVIEW_LENGTH)

    private companion object {
        const val TITLE_LENGTH = 40
        /** 기호를 지우고도 미리보기 두 줄을 채울 만큼 넉넉히 둔다. */
        const val PREVIEW_LENGTH = 200
    }
}
