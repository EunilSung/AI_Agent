package com.eunilsung.aiagent.data.local

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 기기 설정 저장소에 넣는 로그인 정보(토큰·아이디·이름) 잠그기 — 넣을 때 잠그고 꺼낼 때 푼다. Talk 앱의 `LocalSecret` 과 같은 방식이다.
 *
 * Talk 토큰은 만료가 없는 전체 권한 토큰이다. 설정 파일(SharedPreferences XML·NSUserDefaults plist)이 백업·기기 이전·
 * 루팅 기기에서 새어 나가도 토큰이 그대로 읽히지 않게 한다. 키는 각 플랫폼의 보안 저장소(Android Keystore · iOS Keychain)에만
 * 둔다([LocalSecretCipher]). 키는 기기 밖으로 나가지 않아, 파일만 옮겨 가서는 풀 수 없다.
 *
 * ### 저장 형식
 *
 * `enc1:` + Base64(암호문). **접두어가 없으면 이 기능을 켜기 전에 저장된 평문이다** — [SessionStore] 가 처음 읽을 때
 * 잠가 옮기고 평문을 지운다. 로그인은 그대로 유지된다.
 *
 * ### 잠그지 못했을 때
 *
 * **절대 풀리지 않는 값([PREFIX] 만)을 저장한다.** 평문으로 두면 잠그는 의미가 없다. 다음 실행에서 토큰을 읽지 못해
 * 로그아웃 상태가 되고, 다시 로그인하면 된다.
 *
 * ### 풀지 못했을 때
 *
 * 백업 복원·기기 이전으로 설정 파일만 돌아오면 키가 없어 풀 수 없다. [decryptOrNull] 이 null 을 돌려주고, 로그아웃 상태가 된다.
 */
@OptIn(ExperimentalEncodingApi::class)
object LocalSecret {

    private const val PREFIX = "enc1:"

    /**
     * 실제로 잠그는 구현.
     *
     * **테스트에서만 바꿔 끼운다.** 플랫폼 보안 저장소는 테스트 실행 파일에서 쓸 수 없다 — Kotlin/Native 테스트에는
     * Keychain 이 없고(`errSecNotAvailable`), JVM 단위 테스트에는 Android Keystore 가 없다. 그래서 검증은 같은 암호
     * 코드에 고정 키를 물려 돌린다.
     */
    internal var cipher: LocalSecretCipher = platformLocalSecretCipher()

    /** 잠긴 값인지. 예전 평문과 구분하는 유일한 근거다. */
    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return PREFIX + Base64.encode(cipher.seal(plain.encodeToByteArray()) ?: return PREFIX)
    }

    /** 풀지 못하면 null. 잠기지 않은 예전 값은 그대로 돌려준다. */
    fun decryptOrNull(stored: String): String? {
        if (!isSealed(stored)) return stored
        val payload = runCatching { Base64.decode(stored.substring(PREFIX.length)) }.getOrNull() ?: return null
        return cipher.open(payload)?.decodeToString()
    }
}

/** 플랫폼 암호 — 키는 보안 저장소에만 두고 밖으로 꺼내지 않는다. 실패하면 던지지 않고 null 을 돌려준다. */
interface LocalSecretCipher {
    fun seal(plain: ByteArray): ByteArray?
    fun open(payload: ByteArray): ByteArray?

    /**
     * [payload] 가 **지금 [seal] 이 쓰는 방식**으로 잠긴 것인지. 잠그는 방식이 하나뿐인 구현(iOS·테스트)은 늘 true 다.
     * Android 는 Keystore 로 직접 잠근 값과 데이터 키로 잠근 값을 가린다.
     */
    fun isCurrent(payload: ByteArray): Boolean = true
}

expect fun platformLocalSecretCipher(): LocalSecretCipher
