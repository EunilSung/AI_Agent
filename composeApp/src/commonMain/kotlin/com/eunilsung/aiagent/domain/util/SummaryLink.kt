package com.eunilsung.aiagent.domain.util

import com.eunilsung.aiagent.domain.model.SummaryRequest

/**
 * Talk 의 "AI 요약"이 여는 링크를 읽는다.
 *
 * `aiagent://summarize?fileId=<Talk 파일 id>&fileName=<파일명>&userId=<Talk 아이디>&userName=<이름>`
 *
 * 값은 UTF-8 퍼센트 인코딩으로 온다. 공백은 `%20` 이고 `+` 는 글자 그대로다 — Talk 는 `+` 를 `%2B` 로 보낸다.
 * 아무 앱이나 이 링크를 부를 수 있으므로 값의 모양을 확인하고, 하나라도 맞지 않으면 링크 전체를 버린다.
 * 남의 파일 id 를 넣어도 받을 수 없게 막는 것은 Talk 서버다(그 파일이 있는 방의 사람에게만 내준다).
 */
object SummaryLink {
    const val SCHEME = "aiagent"
    const val HOST = "summarize"

    private val FILE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val USER_ID = Regex("[A-Za-z0-9._@-]{1,64}")
    private const val MAX_FILE_NAME_LENGTH = 255
    private const val MAX_USER_NAME_LENGTH = 64

    /** 요약 링크면 그 요청, 요약 링크가 아니거나 값이 맞지 않으면 null. */
    fun parse(link: String): SummaryRequest? {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd <= 0 || !link.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) return null
        val rest = link.substring(schemeEnd + 3).substringBefore('#')
        if (!rest.substringBefore('?').trimEnd('/').equals(HOST, ignoreCase = true) || '?' !in rest) return null

        val params = parseQuery(rest.substringAfter('?')) ?: return null
        val fileId = params["fileId"] ?: return null
        val fileName = params["fileName"]?.trim() ?: return null
        val userId = params["userId"] ?: return null
        val userName = params["userName"].orEmpty().trim()

        if (!FILE_ID.matches(fileId) || !USER_ID.matches(userId)) return null
        if (fileName.isEmpty() || fileName.length > MAX_FILE_NAME_LENGTH || fileName.any { it.isISOControl() }) return null
        if (userName.length > MAX_USER_NAME_LENGTH || userName.any { it.isISOControl() }) return null
        return SummaryRequest(fileId = fileId, fileName = fileName, userId = userId, userName = userName.ifEmpty { userId })
    }

    /** `a=1&b=2` 를 이름 → 값으로. 같은 이름이 여럿이면 앞의 것을 쓴다. 인코딩이 깨졌으면 null. */
    private fun parseQuery(query: String): Map<String, String>? {
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = percentDecode(pair.substringBefore('=')) ?: return null
            val value = percentDecode(pair.substringAfter('=', "")) ?: return null
            if (key !in params) params[key] = value
        }
        return params
    }

    /** `%XX` 를 바이트로 모아 UTF-8 로 읽는다. `%` 뒤가 16진수 두 자리가 아니거나 UTF-8 이 아니면 null. */
    private fun percentDecode(text: String): String? {
        if ('%' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isEmpty()) return
            plain.toString().encodeToByteArray().forEach { bytes += it }
            plain.clear()
        }
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != '%') {
                plain.append(char)
                index++
                continue
            }
            if (index + 2 > text.lastIndex) return null
            val high = hexValue(text[index + 1]) ?: return null
            val low = hexValue(text[index + 2]) ?: return null
            flushPlain()
            bytes += (high * 16 + low).toByte()
            index += 3
        }
        flushPlain()
        return runCatching { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    private fun hexValue(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> null
    }
}
