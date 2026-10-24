package com.eunilsung.aiagent.testsupport

import com.eunilsung.aiagent.data.local.LocalSecret
import com.eunilsung.aiagent.data.local.LocalSecretCipher

/**
 * 테스트용 암호 — 고정 키로 XOR 하고 앞에 매번 다른 바이트(IV 자리)를 붙인다. 키가 다르면 풀지 못한다(표식 바이트가 맞지 않음).
 * 플랫폼 보안 저장소(Keystore·Keychain)는 테스트 실행 파일에 없어서, 저장 규칙은 이 대역으로 본다.
 */
class FakeLocalSecretCipher(private val key: Byte = 0x5A) : LocalSecretCipher {
    var failSeal = false
    private var nonce = 0

    override fun seal(plain: ByteArray): ByteArray? {
        if (failSeal) return null
        val iv = (nonce++).toByte()
        return byteArrayOf(key, iv) + plain.map { (it.toInt() xor key.toInt() xor iv.toInt()).toByte() }
    }

    override fun open(payload: ByteArray): ByteArray? {
        if (payload.size < 2 || payload[0] != key) return null
        val iv = payload[1]
        return payload.copyOfRange(2, payload.size).map { (it.toInt() xor key.toInt() xor iv.toInt()).toByte() }.toByteArray()
    }
}

/** [cipher] 를 끼운 채 [block] 을 돌리고 원래 암호로 되돌린다. */
internal inline fun <T> withLocalSecretCipher(cipher: LocalSecretCipher, block: () -> T): T {
    val previous = LocalSecret.cipher
    LocalSecret.cipher = cipher
    try {
        return block()
    } finally {
        LocalSecret.cipher = previous
    }
}
