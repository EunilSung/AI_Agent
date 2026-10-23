package com.eunilsung.aiagent.data.remote

/** SSE 이벤트 하나. 이름이 없으면 [name] 이 null 이다. */
data class SseEvent(
    val name: String?,
    val data: String,
)

/**
 * SSE 본문을 줄 단위로 받아 이벤트로 묶는다.
 *
 * `event:` 는 이름, `data:` 는 내용이고 빈 줄이 이벤트의 끝이다. `:` 로 시작하는 줄은 연결 유지용 주석이라 버린다.
 * `data:` 가 여러 줄이면 줄바꿈으로 잇는다.
 */
class SseParser {
    private var name: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** 줄 하나를 넣는다. 이벤트가 끝났으면 그 이벤트를 돌려준다. */
    fun feed(line: String): SseEvent? {
        when {
            line.isEmpty() -> return finish()
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> name = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (hasData) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                hasData = true
            }
        }
        return null
    }

    /** 끝을 알리는 빈 줄 없이 본문이 끝났을 때 남은 이벤트를 꺼낸다. */
    fun finish(): SseEvent? {
        val event = if (hasData) SseEvent(name, data.toString()) else null
        name = null
        data.clear()
        hasData = false
        return event
    }
}
