package com.eunilsung.aiagent.data.local

import com.eunilsung.aiagent.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android 의 예전 DB 지우기 — DB 폴더에서 예전 이름의 파일과 곁 파일만 지우고, 지금 DB 와 다른 파일은 그대로 둔다. */
class ObsoleteDatabaseCleanupTest {

    private val directory: File = Files.createTempDirectory("aiagent-db").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `예전 DB 파일과 곁 파일만 지운다`() {
        val current = Config.Database.NAME
        val kept = listOf(current, "$current-wal", "$current-shm", "other.db")
        val removed = listOf("AIAgent_v2.db", "AIAgent_v2.db-wal", "AIAgent_v2.db-shm", "AIAgent_v2.db-journal", "AIAgent_v2.db-mj0F")
        (kept + removed).forEach { File(directory, it).writeText("x") }

        deleteObsoleteDatabases(directory)

        assertEquals(kept.toSet(), directory.list()!!.toSet())
    }

    @Test
    fun `예전 DB 가 없거나 폴더가 없어도 아무 일 없이 끝난다`() {
        deleteObsoleteDatabases(directory)
        assertEquals(emptySet(), directory.list()!!.toSet())

        deleteObsoleteDatabases(File(directory, "missing"))
    }
}
