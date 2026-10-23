package com.eunilsung.aiagent.testsupport

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.eunilsung.aiagent.db.AppDatabase

actual fun createTestDatabase(): AppDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AppDatabase.Schema.create(driver)
    return AppDatabase(driver)
}
