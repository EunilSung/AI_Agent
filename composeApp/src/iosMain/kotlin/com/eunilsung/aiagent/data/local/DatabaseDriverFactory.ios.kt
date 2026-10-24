package com.eunilsung.aiagent.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.db.AppDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

actual class DatabaseDriverFactory {
    /**
     * 새 DB 를 열기 전에 같은 폴더의 예전 DB 파일([ObsoleteDatabases])을 지운다. 폴더는 [NativeSqliteDriver] 가 쓰는 곳
     * (Application Support/databases)을 SQLiter 에게 물어 정한다.
     */
    actual fun createDriver(): SqlDriver {
        deleteObsoleteDatabases(DatabaseFileContext.databasePath(Config.Database.NAME, null).substringBeforeLast('/'))
        return NativeSqliteDriver(AppDatabase.Schema, Config.Database.NAME)
    }
}

/** [directory] 에서 예전 DB 파일과 그 곁 파일을 지운다. 폴더가 없거나 비었으면 아무것도 하지 않는다. */
@OptIn(ExperimentalForeignApi::class)
internal fun deleteObsoleteDatabases(directory: String) {
    val manager = NSFileManager.defaultManager
    val names = manager.contentsOfDirectoryAtPath(directory, null) ?: return
    names.filterIsInstance<String>()
        .filter { ObsoleteDatabases.isObsolete(it) }
        .forEach { manager.removeItemAtPath("$directory/$it", null) }
}
