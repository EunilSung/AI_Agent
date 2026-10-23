package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.repository.ServerSettingsRepository
import kotlinx.coroutines.flow.StateFlow

class ObserveServerUrlUseCase(private val repository: ServerSettingsRepository) {
    operator fun invoke(): StateFlow<String> = repository.baseUrl
}

class SetServerUrlUseCase(private val repository: ServerSettingsRepository) {
    operator fun invoke(url: String) = repository.setBaseUrl(url.trim().trimEnd('/'))
}

class GetServiceStatusUseCase(private val repository: ServerSettingsRepository) {
    suspend operator fun invoke(): ServiceStatus = repository.status()
}

/** 설정 화면이 쓰는 유스케이스 묶음. */
data class SettingUseCases(
    val observeSession: ObserveSessionUseCase,
    val observeServerUrl: ObserveServerUrlUseCase,
    val setServerUrl: SetServerUrlUseCase,
    val getServiceStatus: GetServiceStatusUseCase,
    val logout: LogoutUseCase,
)
