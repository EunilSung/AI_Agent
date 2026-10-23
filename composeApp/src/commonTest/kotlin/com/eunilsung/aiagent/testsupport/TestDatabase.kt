package com.eunilsung.aiagent.testsupport

import com.eunilsung.aiagent.db.AppDatabase

/** 테스트마다 새로 만드는 인메모리 DB. Android(JVM)와 iOS 가 드라이버만 다르다. */
expect fun createTestDatabase(): AppDatabase
