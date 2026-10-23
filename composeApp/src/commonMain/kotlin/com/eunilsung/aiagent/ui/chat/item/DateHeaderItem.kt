package com.eunilsung.aiagent.ui.chat.item

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.ui.theme.AppColors

/** 목록 위 날짜 구분선. Talk 의 DateHeaderItem 과 같다. */
@Composable
fun DateHeaderItem(dateText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = AppColors.Dark.TextDisabled, shape = CircleShape) {
            Text(
                text = dateText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = AppColors.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}
