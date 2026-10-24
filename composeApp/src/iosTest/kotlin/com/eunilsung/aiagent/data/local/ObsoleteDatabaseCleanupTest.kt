package com.eunilsung.aiagent.data.local

import com.eunilsung.aiagent.Config
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS 의 예전 DB 지우기 — DB 폴더에서 예전 이름의 파일과 곁 파일만 지우고, 지금 DB 와 다른 파일은 그대로 둔다.
 * 앱의 DB 폴더(Application Support/databases) 대신 임시 폴더에서 돌린다.
 */
@OptIn(ExperimentalForeignApi::class)
class ObsoleteDatabaseCleanupTest {

    private val manager = NSFileManager.defaultManager
    private val directory = NSTemporaryDirectory().trimEnd('/') + "/aiagent-db-" + NSUUID().UUIDString

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(directory, null)
    }

    private fun files(): Set<String> =
        manager.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>().toSet()

    @Test
    fun 예전_DB_파일과_곁_파일만_지운다() {
        manager.createDirectoryAtPath(directory, true, null, null)
        val current = Config.Database.NAME
        val kept = listOf(current, "$current-wal", "$current-shm", "other.db")
        val removed = listOf("AIAgent_v2.db", "AIAgent_v2.db-wal", "AIAgent_v2.db-shm", "AIAgent_v2.db-journal", "AIAgent_v2.db-mj0F")
        (kept + removed).forEach { manager.createFileAtPath("$directory/$it", null, null) }
        assertEquals((kept + removed).toSet(), files())

        deleteObsoleteDatabases(directory)

        assertEquals(kept.toSet(), files())
    }

    @Test
    fun 예전_DB_가_없거나_폴더가_없어도_아무_일_없이_끝난다() {
        deleteObsoleteDatabases(directory)

        manager.createDirectoryAtPath(directory, true, null, null)
        deleteObsoleteDatabases(directory)
        assertEquals(emptySet(), files())
    }
}
