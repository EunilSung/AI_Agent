package com.eunilsung.aiagent.bridge

/**
 * 브릿지 설정. 모두 환경변수로 받는다.
 *
 * @property host 듣는 주소. 기본은 이 기기 안(`127.0.0.1`)이다 — Tailscale Funnel 은 `127.0.0.1:8095` 로 붙고, Android
 * 에뮬레이터의 `10.0.2.2` 와 iOS 시뮬레이터의 `localhost` 도 이 상태로 닿는다. 실기기를 LAN 으로 붙일 때만 `HOST=0.0.0.0` 으로 연다.
 * @property chatPerUserHourly 한 사람이 한 시간에 보낼 수 있는 AI 대화 수.
 * @property peoplePerUserHourly 한 사람이 한 시간에 할 수 있는 사람 찾기 수. 부를 때마다 Talk 의 전체 사용자 목록을 받는다.
 * @property accessKey 앱이 [com.eunilsung.aiagent.shared.api.BRIDGE_KEY_HEADER] 에 실어야 하는 접속 키. 없으면 [allowNoKey] 일 때만 뜨고,
 * [MIN_ACCESS_KEY_LENGTH] 자보다 짧으면 뜨지 않는다 — Funnel 너머에서 키를 마음껏 대입해 볼 수 있으므로 키가 바깥의 유일한 문이다.
 * 공백 없는 ASCII 글자(영문·숫자·기호)로 [MAX_ACCESS_KEY_LENGTH] 자까지만 받는다 — 앱의 로그인 화면이 그런 키만 넣을 수 있다.
 * @property allowNoKey 접속 키 없이 띄운다. 이 기기 안에서만 시험할 때 쓴다(`BRIDGE_ALLOW_NO_KEY=1`). Tailscale Funnel 이 켜져 있으면
 * 기본 주소(`127.0.0.1`)로 띄워도 인터넷의 요청이 들어오므로, 이때는 이 기기 안에서 프록시 없이 바로 온 요청만 받는다
 * ([com.eunilsung.aiagent.bridge.auth.requireAccessKey]).
 * @property allowedUserIds 비어 있지 않으면 이 아이디(소문자)의 Talk 계정만 브릿지를 쓸 수 있다.
 * @property loginPerIpPerMinute 한 주소에서 1분에 해 볼 수 있는 로그인 수.
 * @property loginGlobalPerSecond 모든 주소를 합쳐 1초에 Talk 에 넘기는 로그인 수.
 * @property llmConcurrency 동시에 돌리는 LLM 일의 무게 합. 대화는 1, 요약은 2 다.
 * @property llmPerUserConcurrency 한 사람이 동시에 돌리는 LLM 일의 무게 합.
 * @property llmWaitMillis 자리가 없을 때 기다리는 시간. 지나면 503 `busy` 다.
 * @property requestReadTimeoutMillis 요청 하나를 다 받기까지 기다리는 시간. 응답을 보내는 동안은 재지 않는다.
 */
data class BridgeConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = 8095,
    val ragUrl: String = "http://127.0.0.1:8000",
    val talkUrl: String = "http://localhost:8090",
    val chatPerUserHourly: Int = 60,
    val peoplePerUserHourly: Int = 120,
    val accessKey: String? = null,
    val allowNoKey: Boolean = false,
    val allowedUserIds: Set<String> = emptySet(),
    val loginPerIpPerMinute: Int = 10,
    val loginGlobalPerSecond: Int = 5,
    val llmConcurrency: Int = 4,
    val llmPerUserConcurrency: Int = 2,
    val llmWaitMillis: Long = 2_000,
    val requestReadTimeoutMillis: Long = 30_000,
) {
    /**
     * 이 설정으로 띄워도 되는지. 접속 키가 없으면 [allowNoKey] 일 때만 되고, 키가 [MIN_ACCESS_KEY_LENGTH] 자보다 짧거나
     * 앱이 넣을 수 없는 키(공백·한글 같은 글자, [MAX_ACCESS_KEY_LENGTH] 자보다 김)면 안 된다.
     */
    val startup: Startup
        get() = when {
            accessKey == null -> if (allowNoKey) Startup.WithoutKey else Startup.MissingKey
            accessKey.length < MIN_ACCESS_KEY_LENGTH -> Startup.WeakKey
            accessKey.length > MAX_ACCESS_KEY_LENGTH || accessKey.any { it.code !in KEY_CHARS } -> Startup.InvalidKey
            else -> Startup.Ready
        }

    /** 로그·예외 메시지에 접속 키가 남지 않게 키는 있는지만 적는다. */
    override fun toString(): String =
        "BridgeConfig(host=$host, port=$port, ragUrl=$ragUrl, talkUrl=$talkUrl, accessKey=${if (accessKey != null) "<set>" else "<none>"}, " +
            "allowNoKey=$allowNoKey, allowedUserIds=${allowedUserIds.size}, chatPerUserHourly=$chatPerUserHourly, " +
            "peoplePerUserHourly=$peoplePerUserHourly, loginPerIpPerMinute=$loginPerIpPerMinute, " +
            "loginGlobalPerSecond=$loginGlobalPerSecond, llmConcurrency=$llmConcurrency, " +
            "llmPerUserConcurrency=$llmPerUserConcurrency, llmWaitMillis=$llmWaitMillis, " +
            "requestReadTimeoutMillis=$requestReadTimeoutMillis)"

    sealed interface Startup {
        data object Ready : Startup
        /** 접속 키 없이 뜬다. 경고를 남기고, 이 기기 안에서 프록시 없이 바로 온 요청만 받는다. */
        data object WithoutKey : Startup
        /** 접속 키가 없어 뜨지 않는다. */
        data object MissingKey : Startup
        /** 접속 키가 [MIN_ACCESS_KEY_LENGTH] 자보다 짧아 뜨지 않는다. */
        data object WeakKey : Startup
        /** 접속 키에 공백·한글 같은 글자가 있거나 [MAX_ACCESS_KEY_LENGTH] 자보다 길어 뜨지 않는다. 앱이 그런 키를 넣을 수 없다. */
        data object InvalidKey : Startup
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"

        /** 접속 키 길이의 끝(아래). `openssl rand -base64 32` 가 만드는 44자 키면 넉넉하다. */
        const val MIN_ACCESS_KEY_LENGTH = 32

        /** 접속 키 길이의 끝(위). 앱의 로그인 화면(`SetAccessKeyUseCase`)이 받는 길이와 같다. */
        const val MAX_ACCESS_KEY_LENGTH = 256

        /** 접속 키에 쓸 수 있는 글자 — 공백 없는 ASCII(`!`~`~`). 키는 HTTP 헤더로 오가고, 앱도 이 글자만 받는다. */
        private val KEY_CHARS = 0x21..0x7E

        fun fromEnv(env: Map<String, String> = System.getenv()): BridgeConfig = BridgeConfig(
            host = env["HOST"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_HOST,
            port = env["PORT"]?.toIntOrNull() ?: 8095,
            ragUrl = env["RAG_URL"]?.trimEnd('/') ?: "http://127.0.0.1:8000",
            talkUrl = env["TALK_URL"]?.trimEnd('/') ?: "http://localhost:8090",
            chatPerUserHourly = env["CHAT_PER_USER_HOURLY"]?.toIntOrNull() ?: 60,
            peoplePerUserHourly = env["PEOPLE_PER_USER_HOURLY"]?.toIntOrNull() ?: 120,
            accessKey = env["BRIDGE_ACCESS_KEY"]?.trim()?.takeIf { it.isNotEmpty() },
            allowNoKey = env["BRIDGE_ALLOW_NO_KEY"] == "1",
            allowedUserIds = env["ALLOWED_USER_IDS"].orEmpty()
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet(),
            loginPerIpPerMinute = env["LOGIN_PER_IP_PER_MINUTE"]?.toIntOrNull() ?: 10,
            loginGlobalPerSecond = env["LOGIN_GLOBAL_PER_SECOND"]?.toIntOrNull() ?: 5,
            llmConcurrency = env["LLM_CONCURRENCY"]?.toIntOrNull() ?: 4,
            llmPerUserConcurrency = env["LLM_PER_USER_CONCURRENCY"]?.toIntOrNull() ?: 2,
        )
    }
}
