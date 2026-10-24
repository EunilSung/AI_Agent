package com.eunilsung.aiagent

import com.eunilsung.aiagent.data.remote.BRIDGE_ACCESS_KEY
import com.eunilsung.aiagent.data.remote.BRIDGE_BASE_URL
import com.eunilsung.aiagent.data.remote.platformDefaultBridgeUrl
import com.eunilsung.aiagent.domain.util.BridgeAddress

object Config {
    /** 디버그 로그 on/off — 기본 꺼짐. */
    object Log {
        const val IS_SHOW_LOG: Boolean = false
    }

    /**
     * 브릿지 서버 연결.
     *
     * 기본 주소는 `local.properties` 의 `bridge.baseUrl`(또는 환경변수 `AI_AGENT_BRIDGE_URL`)에서 빌드할 때 들어온다.
     * 비어 있거나 주소 규칙([BridgeAddress])에 맞지 않으면 플랫폼 기본값(에뮬레이터·시뮬레이터에서 이 Mac 의 8095)을 쓴다.
     * 설정 화면에서 바꾼 주소가 있으면 그것이 먼저다.
     */
    object Bridge {
        val DEFAULT_BASE_URL: String = BridgeAddress.buildDefault(BRIDGE_BASE_URL, platformDefaultBridgeUrl)

        /**
         * 브릿지 접속 키 — 요청의 `X-Bridge-Key` 헤더에 싣는다. `local.properties` 의 `bridge.accessKey`
         * (또는 환경변수 `AI_AGENT_BRIDGE_KEY`)에서 빌드할 때 들어온다. 저장소에는 값을 두지 않는다.
         *
         * 모든 설치본이 같은 값을 쓰고, 앱 바이너리(APK·IPA)에 그대로 들어가 꺼낼 수 있다. 빌드를 바깥 사람에게 주지 않는다.
         * 빌드가 새어 나갔으면 브릿지의 `BRIDGE_ACCESS_KEY` 와 이 값을 함께 바꾸고 다시 빌드한다.
         */
        const val ACCESS_KEY: String = BRIDGE_ACCESS_KEY

        /** [ACCESS_KEY] 를 실어도 되는 호스트 — 빌드 기본 주소의 호스트와 로컬 주소뿐이다([BridgeAddress.accessKeyHosts]). */
        val ACCESS_KEY_HOSTS: Set<String> = BridgeAddress.accessKeyHosts(DEFAULT_BASE_URL)
    }

    /** 로컬 데이터베이스(SQLDelight). [NAME] 을 바꾸면 새 DB 로 시작한다. */
    object Database {
        const val NAME: String = "AIAgent_v3.db"

        /**
         * 예전 DB 이름. 앱은 새 DB 를 열기 전에 이 이름의 파일(곁 파일 `-wal`·`-shm`·`-journal` 포함)을 지운다 — 다시 읽지 않는
         * 예전 대화(Talk 파일 요약 포함)가 평문 SQLite 로 기기에 남지 않게 한다. [NAME] 을 올리면 앞 이름을 여기에 더한다.
         */
        val OBSOLETE_NAMES: List<String> = listOf("AIAgent_v2.db")
    }

    /** AI 대화방 화면의 기능 옵션. */
    object Chat {
        /** 답변 아래에 근거 문서 칩을 보일지. */
        const val IS_SOURCES_ENABLED: Boolean = false

        /** 후속 질문을 다시 쓴 검색어를 보일지. */
        const val IS_SEARCH_QUERY_ENABLED: Boolean = false
    }
}
