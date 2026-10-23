package com.eunilsung.aiagent.ui.uikit.line

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eunilsung.aiagent.ui.theme.AppColors

@Composable
fun LineDivider(
    modifier: Modifier = Modifier,
    color: Color = AppColors.Line,
    thickness: Dp = 1.dp,
    isWidth: Boolean = false
    ) {
    Divider(modifier = if (isWidth) modifier.width(thickness) else modifier.height(thickness), color = color)
}

@Preview(showBackground = true)
@Composable
fun LineDividerPreview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxWidth().height(10.dp)){
            LineDivider(modifier = Modifier.align(Alignment.Center),)
        }
    }
}