package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.repository.ServerSettingsRepository
import com.eunilsung.aiagent.domain.util.BridgeAddress
import kotlinx.coroutines.flow.StateFlow

class ObserveServerUrlUseCase(private val repository: ServerSettingsRepository) {
    operator fun invoke(): StateFlow<String> = repository.baseUrl
}

/**
 * 브릿지 주소를 바꾼다. 빈 값은 빌드 기본값으로 되돌린다.
 *
 * `https` 주소이거나 에뮬레이터·시뮬레이터 주소([BridgeAddress.LOCAL_HOSTS])일 때만 저장한다 — 같은 망에서 평문으로
 * 비밀번호·토큰이 오가지 않게 한다. 저장하지 않았으면 화면이 그 이유를 알린다.
 */
class SetServerUrlUseCase(private val repository: ServerSettingsRepository) {
    /** @return 저장했으면 true, 쓸 수 없는 주소라 그대로 두었으면 false. */
    operator fun invoke(url: String): Boolean {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isNotEmpty() && !BridgeAddress.isAllowed(cleaned)) return false
        repository.setBaseUrl(cleaned)
        return true
    }
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
