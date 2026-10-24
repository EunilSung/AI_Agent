package com.eunilsung.aiagent.data.local

import com.eunilsung.aiagent.Config

/**
 * 예전 DB 파일 고르기.
 *
 * 스키마를 바꿔 [Config.Database.NAME] 을 올리면 앱은 새 이름만 연다. 예전 파일은 다시 읽지 않아 로그아웃해도 지워지지 않고,
 * Talk 파일 요약까지 든 대화가 평문 SQLite 로 기기에 남는다. 그래서 플랫폼의 [DatabaseDriverFactory] 가 새 DB 를 열기 전에
 * DB 폴더에서 이 규칙에 맞는 파일을 지운다.
 */
object ObsoleteDatabases {
    /**
     * [fileName] 이 지울 파일인지 — [names] 의 DB 파일과 그 곁 파일(`-wal`·`-shm`·`-journal`·`-mj…`).
     * 지금 쓰는 [Config.Database.NAME] 과 그 곁 파일은 고르지 않는다.
     */
    fun isObsolete(fileName: String, names: List<String> = Config.Database.OBSOLETE_NAMES): Boolean =
        names.any { it != Config.Database.NAME && (fileName == it || fileName.startsWith("$it-")) }
}
