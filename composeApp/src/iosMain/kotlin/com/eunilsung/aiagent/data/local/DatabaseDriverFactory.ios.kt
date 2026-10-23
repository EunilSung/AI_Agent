package com.eunilsung.aiagent.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.db.AppDatabase

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(AppDatabase.Schema, Config.Database.NAME)
}
