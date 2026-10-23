package com.eunilsung.aiagent.data.local

import app.cash.sqldelight.db.SqlDriver
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.domain.model.User
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * 저장소 구현끼리 함께 쓰는 기기 저장소라 domain 에는 드러내지 않는다.
 */
class SessionStore(private val settings: Settings) {
    private val _user = MutableStateFlow(readUser())
    val user: StateFlow<User?> = _user.asStateFlow()

    val token: String?
        get() = settings.getStringOrNull(SettingsKeys.TOKEN)

    var lastLoginId: String
        get() = settings.getString(SettingsKeys.LAST_LOGIN_ID, "")
        set(value) = settings.putString(SettingsKeys.LAST_LOGIN_ID, value)

    fun save(token: String, user: User) {
        settings.putString(SettingsKeys.TOKEN, token)
        settings.putString(SettingsKeys.USER_ID, user.id)
        settings.putString(SettingsKeys.USER_NAME, user.name)
        _user.value = user
    }

    fun clear() {
        settings.remove(SettingsKeys.TOKEN)
        settings.remove(SettingsKeys.USER_ID)
        settings.remove(SettingsKeys.USER_NAME)
        _user.value = null
    }

    private fun readUser(): User? {
        if (settings.getStringOrNull(SettingsKeys.TOKEN) == null) return null
        val id = settings.getStringOrNull(SettingsKeys.USER_ID) ?: return null
        return User(id, settings.getString(SettingsKeys.USER_NAME, id))
    }
}

/** 브릿지 주소. 설정 화면에서 바꾼 값이 없으면 [Config.Bridge.DEFAULT_BASE_URL] 이다. */
class BridgeUrlStore(private val settings: Settings) {
    private val _url = MutableStateFlow(settings.getStringOrNull(SettingsKeys.BRIDGE_URL) ?: Config.Bridge.DEFAULT_BASE_URL)
    val url: StateFlow<String> = _url.asStateFlow()

    fun set(url: String) {
        if (url.isBlank()) {
            settings.remove(SettingsKeys.BRIDGE_URL)
            _url.value = Config.Bridge.DEFAULT_BASE_URL
        } else {
            settings.putString(SettingsKeys.BRIDGE_URL, url)
            _url.value = url
        }
    }
}
