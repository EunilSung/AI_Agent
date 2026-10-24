package com.eunilsung.aiagent.domain.usecase

import com.eunilsung.aiagent.domain.model.ServiceStatus
import com.eunilsung.aiagent.domain.repository.AuthRepository
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
 * 비밀번호·토큰이 오가지 않게 한다. 기본값으로 되돌리는 것은 기본 주소를 그대로 써도 되는 빌드에서만 한다
 * ([ServerSettingsRepository.defaultBaseUrl]). 저장하지 않았으면 화면이 그 이유를 알린다.
 */
class SetServerUrlUseCase(private val repository: ServerSettingsRepository) {
    /** [url] 로 바꾸면 쓰게 될 주소. 저장할 수 없는 값이면 null. */
    fun resolve(url: String): String? {
        val cleaned = url.trim().trimEnd('/')
        return when {
            cleaned.isEmpty() -> repository.defaultBaseUrl()
            BridgeAddress.isAllowed(cleaned) -> cleaned
            else -> null
        }
    }

    /** @return 저장했으면 true, 쓸 수 없는 주소라 그대로 두었으면 false. */
    operator fun invoke(url: String): Boolean {
        if (resolve(url) == null) return false
        repository.setBaseUrl(url.trim().trimEnd('/'))
        return true
    }
}

/**
 * 로그인한 채로 서버 주소를 바꾼다(설정 화면). 주소가 바뀌면 먼저 로그아웃한다 — Talk 토큰은 만료가 없는 전체 권한 토큰이라
 * 다른 서버로 싣지 않는다. 로그아웃은 앞 서버에 알리므로 주소를 바꾸기 전에 한다. 새 서버에서는 로그인 화면에서 다시
 * 로그인한다(그 서버의 접속 키가 없으면 키도 넣는다).
 */
class ChangeServerUseCase(
    private val setServerUrl: SetServerUrlUseCase,
    private val servers: ServerSettingsRepository,
    private val auth: AuthRepository,
) {
    /** [url] 로 바꾸면 지금과 다른 서버가 되는지. 저장할 수 없는 값이면 null. */
    fun moves(url: String): Boolean? = setServerUrl.resolve(url)?.let { it != servers.baseUrl.value }

    /** @return 저장했으면 true, 쓸 수 없는 주소라 그대로 두었으면 false. */
    suspend operator fun invoke(url: String): Boolean {
        val moves = moves(url) ?: return false
        if (moves && auth.session.value != null) auth.logout()
        return setServerUrl(url)
    }
}

/** 빌드 기본 주소. 그대로 쓰면 안 되는 빌드(공개 릴리스)면 null — 설정 화면이 [기본값] 을 숨긴다. */
class GetDefaultServerUrlUseCase(private val repository: ServerSettingsRepository) {
    operator fun invoke(): String? = repository.defaultBaseUrl()
}

/** 서버 주소를 정했는지. 정하지 않았으면(공개 릴리스를 처음 켰다) 로그인 화면이 서버 주소 칸을 먼저 보인다. */
class IsServerChosenUseCase(private val repository: ServerSettingsRepository) {
    operator fun invoke(): Boolean = repository.isServerChosen()
}

/** 지금 서버 주소로 보낼 접속 키가 있는지. 없으면 로그인 화면이 접속 키 칸을 연다. */
class HasAccessKeyUseCase(private val repository: ServerSettingsRepository) {
    operator fun invoke(): Boolean = repository.hasAccessKey()
}

/**
 * 사용자가 받은 접속 키를 지금 서버 주소의 출처에 묶어 저장한다.
 *
 * 브릿지는 32자 이상의 키만 쓰고 키는 HTTP 헤더로 나가므로, 공백 없는 ASCII 문자 32~256자일 때만 저장한다. 앞뒤 공백은 뗀다
 * (붙여 넣다 따라온 줄바꿈).
 */
class SetAccessKeyUseCase(private val repository: ServerSettingsRepository) {
    /** @return 저장했으면 true, 형식이 맞지 않아 그대로 두었으면 false. */
    operator fun invoke(key: String): Boolean {
        val cleaned = key.trim()
        if (cleaned.length !in MIN_LENGTH..MAX_LENGTH || cleaned.any { it.code !in 0x21..0x7E }) return false
        repository.setAccessKey(cleaned)
        return true
    }

    private companion object {
        const val MIN_LENGTH = 32
        const val MAX_LENGTH = 256
    }
}

class GetServiceStatusUseCase(private val repository: ServerSettingsRepository) {
    suspend operator fun invoke(): ServiceStatus = repository.status()
}

/** 설정 화면이 쓰는 유스케이스 묶음. */
data class SettingUseCases(
    val observeSession: ObserveSessionUseCase,
    val observeServerUrl: ObserveServerUrlUseCase,
    val getDefaultServerUrl: GetDefaultServerUrlUseCase,
    val changeServer: ChangeServerUseCase,
    val getServiceStatus: GetServiceStatusUseCase,
    val logout: LogoutUseCase,
)
