package com.eunilsung.aiagent.data.local

import app.cash.sqldelight.db.SqlDriver
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.domain.model.User
import com.eunilsung.aiagent.domain.util.BridgeAddress
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

private object SettingsKeys {
    const val TOKEN = "session_token"
    const val USER_ID = "session_user_id"
    const val USER_NAME = "session_user_name"
    const val LAST_LOGIN_ID = "last_login_id"
    const val BRIDGE_URL = "bridge_url"
}

/**
 * 로그인 정보. 토큰은 Talk 서버가 발급한 것이라 브릿지 요청마다 붙인다.
 *
 * 토큰·아이디·이름·마지막 로그인 아이디는 [LocalSecret] 으로 잠가 저장한다 — Talk 토큰은 만료가 없는 전체 권한 토큰이라,
 * 설정 파일이 새어 나가도 그대로 쓰이지 않게 한다. 이 기능을 켜기 전에 평문으로 저장된 값은 처음 읽을 때 잠가 옮기고
 * 평문을 지운다(로그인은 그대로 유지된다). 풀지 못하면(다른 기기로 옮겨진 설정 파일 등) 로그아웃 상태로 연다.
 *
 * 푼 값은 메모리에만 들고 있어 요청마다 다시 풀지 않는다. 저장소 구현끼리 함께 쓰는 기기 저장소라 domain 에는 드러내지 않는다.
 */
class SessionStore(private val settings: Settings) {
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedLastLoginId: String = ""

    private val _user: MutableStateFlow<User?>
    val user: StateFlow<User?>

    /** 네 칸을 모두 한 번씩 읽는다 — 로그인하지 않았어도 남은 예전 평문이 있으면 그때 잠가 옮긴다. */
    init {
        val token = readSecret(SettingsKeys.TOKEN)
        val id = readSecret(SettingsKeys.USER_ID)
        val name = readSecret(SettingsKeys.USER_NAME)
        cachedLastLoginId = readSecret(SettingsKeys.LAST_LOGIN_ID).orEmpty()

        val restored = if (token != null && id != null) User(id, name ?: id) else null
        cachedToken = token.takeIf { restored != null }
        _user = MutableStateFlow(restored)
        user = _user.asStateFlow()
    }

    val token: String?
        get() = cachedToken

    var lastLoginId: String
        get() = cachedLastLoginId
        set(value) {
            cachedLastLoginId = value
            settings.putString(SettingsKeys.LAST_LOGIN_ID, LocalSecret.encrypt(value))
        }

    fun save(token: String, user: User) {
        cachedToken = token
        settings.putString(SettingsKeys.TOKEN, LocalSecret.encrypt(token))
        settings.putString(SettingsKeys.USER_ID, LocalSecret.encrypt(user.id))
        settings.putString(SettingsKeys.USER_NAME, LocalSecret.encrypt(user.name))
        _user.value = user
    }

    fun clear() {
        cachedToken = null
        settings.remove(SettingsKeys.TOKEN)
        settings.remove(SettingsKeys.USER_ID)
        settings.remove(SettingsKeys.USER_NAME)
        _user.value = null
    }

    /**
     * [key] 의 값을 풀어 읽는다. 없거나 비었거나 풀지 못하면 null.
     *
     * 잠기지 않은 예전 평문이면 그 자리에 잠근 값을 써서 평문을 지우고, 이번에는 읽은 평문을 그대로 쓴다 — 잠그지 못했어도
     * 이번 실행은 로그인이 유지되고, 평문은 남지 않는다([LocalSecret.encrypt] 는 실패하면 풀리지 않는 값을 준다).
     */
    private fun readSecret(key: String): String? {
        val stored = settings.getStringOrNull(key) ?: return null
        if (!LocalSecret.isSealed(stored)) {
            if (stored.isNotEmpty()) settings.putString(key, LocalSecret.encrypt(stored))
            return stored.ifEmpty { null }
        }
        return LocalSecret.decryptOrNull(stored)?.ifEmpty { null }
    }
}

/**
 * 브릿지 주소. 설정 화면에서 바꾼 값이 없으면 [Config.Bridge.DEFAULT_BASE_URL] 이다(빌드 기본 주소도 [BridgeAddress] 를 거친다).
 *
 * [BridgeAddress] 가 막는 주소(같은 망의 평문 http 등)는 저장하지 않는다. 이 규칙 전에 저장된 그런 주소는 읽을 때 지우고
 * 기본 주소로 돌아간다 — Android 는 OS 도 평문을 막지만, iOS 는 `.local`·IP 평문을 OS 가 열어 두므로 여기서 지워야 막힌다.
 */
class BridgeUrlStore(private val settings: Settings) {
    private val _url = MutableStateFlow(readStored() ?: Config.Bridge.DEFAULT_BASE_URL)
    val url: StateFlow<String> = _url.asStateFlow()

    fun set(url: String) {
        if (url.isBlank()) {
            settings.remove(SettingsKeys.BRIDGE_URL)
            _url.value = Config.Bridge.DEFAULT_BASE_URL
        } else if (BridgeAddress.isAllowed(url)) {
            settings.putString(SettingsKeys.BRIDGE_URL, url)
            _url.value = url
        }
    }

    private fun readStored(): String? {
        val stored = settings.getStringOrNull(SettingsKeys.BRIDGE_URL) ?: return null
        if (BridgeAddress.isAllowed(stored)) return stored
        settings.remove(SettingsKeys.BRIDGE_URL)
        return null
    }
}
