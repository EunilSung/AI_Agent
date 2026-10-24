package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.repository.AuthRepository
import kotlinx.coroutines.flow.StateFlow

class LoginUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(id: String, password: String): LoginResult = repository.login(id.trim(), password)
}

class GetLastLoginIdUseCase(private val repository: AuthRepository) {
    operator fun invoke(): String = repository.lastLoginId
}

class ObserveSessionUseCase(private val repository: AuthRepository) {
    operator fun invoke(): StateFlow<User?> = repository.session
}

class LogoutUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.logout()
}

/**
 * 로그인 화면이 쓰는 유스케이스 묶음. 로그인 전에도 서버 주소를 정하고 그 서버의 접속 키를 넣을 수 있어야 해서 주소·접속 키
 * 유스케이스를 함께 받는다.
 */
data class LoginUseCases(
    val login: LoginUseCase,
    val getLastLoginId: GetLastLoginIdUseCase,
    val observeServerUrl: ObserveServerUrlUseCase,
    val setServerUrl: SetServerUrlUseCase,
    val isServerChosen: IsServerChosenUseCase,
    val hasAccessKey: HasAccessKeyUseCase,
    val setAccessKey: SetAccessKeyUseCase,
)
