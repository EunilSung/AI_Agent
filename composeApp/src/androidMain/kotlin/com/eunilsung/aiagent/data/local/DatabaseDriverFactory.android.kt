package com.eunilsung.aiagent.data.local

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.db.AppDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(AppDatabase.Schema, context, Config.Database.NAME)
}
