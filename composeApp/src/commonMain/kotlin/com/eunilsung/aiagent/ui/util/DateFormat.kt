package com.eunilsung.aiagent.ui.util

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.date_ampm_am
import aiagent.composeapp.generated.resources.date_ampm_pm
import aiagent.composeapp.generated.resources.date_time
import aiagent.composeapp.generated.resources.date_year_month_day
import androidx.compose.runtime.Composable
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** 기기 시간대의 날짜·시각. 메시지를 만든 시각(밀리초)을 화면에 보이거나 날짜로 묶을 때 쓴다. */
fun Long.toLocalDateTime(): LocalDateTime =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())

/** 대화 말풍선 옆 시각 — "오후 3:05". Talk 의 chatTimeText 와 같은 문구를 쓴다. */
@Composable
fun chatTimeText(millis: Long): String {
    val time = millis.toLocalDateTime()
    return stringResource(
        Res.string.date_time,
        stringResource(if (time.hour < 12) Res.string.date_ampm_am else Res.string.date_ampm_pm),
        if (time.hour % 12 == 0) 12 else time.hour % 12,
        time.minute.toString().padStart(2, '0'),
    )
}

/** 목록 위 날짜 구분선 — "2026년 10월 23일". */
@Composable
fun chatDateHeaderText(millis: Long): String {
    val date = millis.toLocalDateTime()
    return stringResource(Res.string.date_year_month_day, date.year, date.monthNumber, date.dayOfMonth)
}
