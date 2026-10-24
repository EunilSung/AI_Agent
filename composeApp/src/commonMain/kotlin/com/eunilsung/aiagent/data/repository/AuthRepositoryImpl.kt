package com.eunilsung.aiagent.data.repository

import com.eunilsung.aiagent.data.local.SessionStore
import com.eunilsung.aiagent.data.mapper.bridgeRefusalOf
import com.eunilsung.aiagent.data.remote.BridgeClient
import com.eunilsung.aiagent.data.remote.ServerResult
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AuthRepository
import kotlinx.coroutines.flow.StateFlow

class AuthRepositoryImpl(
    private val bridge: BridgeClient,
    private val sessionStore: SessionStore,
) : AuthRepository {

    override val session: StateFlow<User?> = sessionStore.user

    override val lastLoginId: String
        get() = sessionStore.lastLoginId

    override suspend fun login(id: String, password: String): LoginResult {
        sessionStore.lastLoginId = id
        return when (val result = bridge.login(id, password)) {
            is ServerResult.Success -> {
                val user = User(result.value.user.id, result.value.user.name)
                sessionStore.save(result.value.token, user)
                LoginResult.Success(user)
            }
            is ServerResult.Rejected -> bridgeRefusalOf(result.code)?.let { LoginResult.Refused(it) } ?: when (result.status) {
                401 -> LoginResult.InvalidCredentials
                429 -> LoginResult.Locked
                503 -> LoginResult.Unreachable
                else -> LoginResult.Failed
            }
            ServerResult.Unreachable -> LoginResult.Unreachable
        }
    }

    /** 브릿지에 알리지 못해도 기기에서는 로그아웃한다. */
    override suspend fun logout() {
        sessionStore.token?.let { bridge.logout(it) }
        sessionStore.clear()
    }
}
