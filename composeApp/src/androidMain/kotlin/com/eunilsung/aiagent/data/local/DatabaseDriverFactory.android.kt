package com.eunilsung.aiagent.data.local

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.db.AppDatabase
import java.io.File

actual class DatabaseDriverFactory(private val context: Context) {
    /** 새 DB 를 열기 전에 같은 폴더의 예전 DB 파일([ObsoleteDatabases])을 지운다. */
    actual fun createDriver(): SqlDriver {
        context.getDatabasePath(Config.Database.NAME).parentFile?.let(::deleteObsoleteDatabases)
        return AndroidSqliteDriver(AppDatabase.Schema, context, Config.Database.NAME)
    }
}

/** [directory] 에서 예전 DB 파일과 그 곁 파일을 지운다. 폴더가 없거나 비었으면 아무것도 하지 않는다. */
internal fun deleteObsoleteDatabases(directory: File) {
    directory.listFiles { file -> file.isFile && ObsoleteDatabases.isObsolete(file.name) }?.forEach { it.delete() }
}
