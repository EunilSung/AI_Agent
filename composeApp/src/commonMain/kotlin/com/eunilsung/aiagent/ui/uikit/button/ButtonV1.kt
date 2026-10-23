package com.eunilsung.aiagent.ui.uikit.button

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eunilsung.aiagent.ui.theme.AppColors

@Composable
fun ButtonV1(
    modifier: Modifier = Modifier,
    text: String,
    content: @Composable () -> Unit = { Text(text, fontWeight = FontWeight.SemiBold) },
    containerColor: Color = AppColors.PrimaryMain,
    contentColor: Color = AppColors.White,
    round: Dp = 8.dp,
    isSelected: Boolean? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    onClick: () -> Unit
) {
    Button(
        modifier = modifier,
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected == null) containerColor
            else{
                if (isSelected) AppColors.PrimaryMain
                else AppColors.BgSub
            },
            contentColor = if (isSelected == null) contentColor
            else{
                if (isSelected) AppColors.White
                else AppColors.TextSub
            }
        ),
        border = if (isSelected == false) {
            BorderStroke(1.dp, AppColors.Line)
        } else {
            null
        },
        contentPadding = contentPadding,
        shape = RoundedCornerShape(round),
        elevation = null
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
fun ButtonV1Preview() {
    MaterialTheme {
        ButtonV1(
            text = "Button",
            onClick = {}
        )
    }
}