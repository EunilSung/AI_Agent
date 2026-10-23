package com.eunilsung.aiagent.bridge

/**
 * 브릿지 설정. 모두 환경변수로 받는다.
 *
 * @property chatPerUserHourly 한 사람이 한 시간에 보낼 수 있는 AI 대화 수.
 */
data class BridgeConfig(
    val port: Int = 8095,
    val ragUrl: String = "http://127.0.0.1:8000",
    val talkUrl: String = "http://localhost:8090",
    val chatPerUserHourly: Int = 60,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): BridgeConfig = BridgeConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8095,
            ragUrl = env["RAG_URL"]?.trimEnd('/') ?: "http://127.0.0.1:8000",
            talkUrl = env["TALK_URL"]?.trimEnd('/') ?: "http://localhost:8090",
            chatPerUserHourly = env["CHAT_PER_USER_HOURLY"]?.toIntOrNull() ?: 60,
        )
    }
}
