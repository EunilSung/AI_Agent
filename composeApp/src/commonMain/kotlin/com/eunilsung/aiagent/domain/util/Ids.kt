package com.eunilsung.aiagent.domain.util

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 대화·메시지 id. 기기에서 만들고 바꾸지 않는다. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
