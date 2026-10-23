package com.eunilsung.aiagent

import com.eunilsung.aiagent.data.remote.BRIDGE_BASE_URL
import com.eunilsung.aiagent.data.remote.platformDefaultBridgeUrl

object Config {
    /** 디버그 로그 on/off — 기본 꺼짐. */
    object Log {
        const val IS_SHOW_LOG: Boolean = false
    }

    /**
     * 브릿지 서버 연결.
     *
     * 기본 주소는 `local.properties` 의 `bridge.baseUrl` 에서 빌드할 때 들어온다. 비어 있으면 플랫폼 기본값
     * (에뮬레이터·시뮬레이터에서 이 Mac 의 8095)을 쓴다. 설정 화면에서 바꾼 주소가 있으면 그것이 먼저다.
     */
    object Bridge {
        val DEFAULT_BASE_URL: String = BRIDGE_BASE_URL.trimEnd('/').ifBlank { platformDefaultBridgeUrl }
    }

    /** 로컬 데이터베이스(SQLDelight). [NAME] 을 바꾸면 새 DB 로 시작한다. */
    object Database {
        const val NAME: String = "AIAgent_v2.db"
    }

    /** AI 대화방 화면의 기능 옵션. */
    object Chat {
        /** 답변 아래에 근거 문서 칩을 보일지. */
        const val IS_SOURCES_ENABLED: Boolean = true

        /** 후속 질문을 다시 쓴 검색어를 보일지. */
        const val IS_SEARCH_QUERY_ENABLED: Boolean = true
    }
}
