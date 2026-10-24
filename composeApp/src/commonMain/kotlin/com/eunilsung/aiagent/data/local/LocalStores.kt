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
    const val ACCESS_KEY = "bridge_access_key"
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
 *
 * @param isDefaultUsable 빌드 기본 주소를 그대로 써도 되는 빌드인지([Config.Bridge.IS_DEFAULT_URL_USABLE]).
 */
class BridgeUrlStore(
    private val settings: Settings,
    val isDefaultUsable: Boolean = Config.Bridge.IS_DEFAULT_URL_USABLE,
) {
    private val _url = MutableStateFlow(readStored() ?: Config.Bridge.DEFAULT_BASE_URL)
    val url: StateFlow<String> = _url.asStateFlow()

    /** 서버 주소를 정했는지 — 쓰는 사람이 저장했거나, 빌드 기본 주소를 그대로 써도 되는 빌드다. */
    val isChosen: Boolean
        get() = isDefaultUsable || settings.hasKey(SettingsKeys.BRIDGE_URL)

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

/**
 * 브릿지 접속 키. 공개 릴리스 앱에는 키가 들어 있지 않아, 쓰는 사람이 따로 받은 키를 로그인 화면에서 넣는다.
 *
 * 넣은 키는 그때의 서버 주소 출처(`스킴://호스트:포트`, [BridgeAddress.originOf])에 묶고, 묶인 출처로만 싣는다 — 주소만 바꾸게
 * 속여 키를 다른 서버로 빼 가지 못하게 한다. 주소를 다른 출처로 바꾸면 그 서버의 키를 다시 넣는다.
 * 출처와 키는 한 칸에 함께 [LocalSecret] 으로 잠근다. 두 칸으로 나누면 한쪽만 바뀐 값(쓰다가 멈춘 앱, 고쳐 쓴 설정 파일)이
 * 앞 서버의 키를 새 출처에 묶을 수 있다. 잠기지 않았거나 풀지 못하는 저장값(다른 기기로 옮겨진 설정 파일 등)은 지운다.
 *
 * 빌드 때 넣은 키([Config.Bridge.ACCESS_KEY], 키를 가진 사람이 직접 만든 개발용 빌드)는 빌드 기본 주소의 호스트와 로컬 주소에만
 * 싣고, 같은 출처에 사용자가 넣은 키가 있으면 그것이 먼저다.
 */
class AccessKeyStore(
    private val settings: Settings,
    private val buildKey: String = Config.Bridge.ACCESS_KEY,
    private val buildKeyHosts: Set<String> = Config.Bridge.ACCESS_KEY_HOSTS,
) {
    private class Saved(val origin: String, val key: String)

    @Volatile private var saved: Saved? = readSaved()

    /** [origin](`스킴://호스트:포트`)으로 보낼 접속 키. 없으면 null. */
    fun keyFor(origin: String): String? {
        val target = origin.lowercase()
        saved?.let { if (it.origin == target) return it.key }
        val host = target.substringAfter("://").substringBeforeLast(':')
        return buildKey.takeIf { it.isNotEmpty() && host in buildKeyHosts }
    }

    /** 어느 출처에든 보낼 키가 하나라도 있는지 — 키를 일부러 싣지 않은 것과 키가 아예 없는 것을 가른다. */
    val hasAnyKey: Boolean
        get() = saved != null || buildKey.isNotEmpty()

    /** [key] 를 [origin] 에 묶어 저장한다. 앞서 넣은 키는 지운다. 출처와 키에는 줄바꿈이 들어갈 수 없어 줄바꿈으로 나눈다. */
    fun save(origin: String, key: String) {
        val target = origin.lowercase()
        settings.putString(SettingsKeys.ACCESS_KEY, LocalSecret.encrypt("$target\n$key"))
        saved = Saved(target, key)
    }

    private fun readSaved(): Saved? {
        val record = settings.getStringOrNull(SettingsKeys.ACCESS_KEY)
            ?.takeIf(LocalSecret::isSealed)
            ?.let(LocalSecret::decryptOrNull)
        val origin = record?.substringBefore('\n', "")
        val key = record?.substringAfter('\n', "")
        if (!origin.isNullOrEmpty() && !key.isNullOrEmpty()) return Saved(origin, key)
        settings.remove(SettingsKeys.ACCESS_KEY)
        return null
    }
}
