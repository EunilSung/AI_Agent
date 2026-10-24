package com.eunilsung.aiagent.data.local

import com.eunilsung.aiagent.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 예전 DB 파일 고르기 — DB 이름을 올린 뒤 앱이 다시 읽지 않는 예전 대화(Talk 파일 요약 포함)가 평문으로 기기에 남지 않게,
 * 새 DB 를 열기 전에 지울 파일을 고른다. 지금 쓰는 DB 는 절대 고르지 않는다.
 */
class ObsoleteDatabasesTest {

    @Test
    fun `예전 DB 와 그 곁 파일을 고른다`() {
        listOf("AIAgent_v2.db", "AIAgent_v2.db-wal", "AIAgent_v2.db-shm", "AIAgent_v2.db-journal", "AIAgent_v2.db-mj1A2B")
            .forEach { assertTrue(ObsoleteDatabases.isObsolete(it), it) }
    }

    @Test
    fun `지금 쓰는 DB 와 이름이 비슷한 다른 파일은 고르지 않는다`() {
        val current = Config.Database.NAME
        listOf(current, "$current-wal", "$current-shm", "$current-journal", "AIAgent_v20.db", "AIAgent_v2.dbx", "xAIAgent_v2.db", "settings.xml")
            .forEach { assertFalse(ObsoleteDatabases.isObsolete(it), it) }
    }

    @Test
    fun `지금 DB 이름은 예전 이름 목록에 없고 목록에 넣어도 지우지 않는다`() {
        assertFalse(Config.Database.NAME in Config.Database.OBSOLETE_NAMES, "지금 쓰는 DB 를 예전 이름으로 적었다")
        assertTrue(Config.Database.OBSOLETE_NAMES.isNotEmpty())
        assertFalse(ObsoleteDatabases.isObsolete(Config.Database.NAME, names = listOf(Config.Database.NAME)))
        assertFalse(ObsoleteDatabases.isObsolete("${Config.Database.NAME}-wal", names = listOf(Config.Database.NAME)))
    }
}
