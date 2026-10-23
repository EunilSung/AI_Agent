package com.eunilsung.aiagent.ui.uikit.click

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eunilsung.aiagent.ui.theme.AppColors

@Composable
fun Modifier.clickable(
    rippleColor: Color? = AppColors.ClickRipple,
    cornerRadius: Dp = 0.dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }

    return this
        .clip(RoundedCornerShape(cornerRadius))
        .combinedClickable(
            interactionSource = interactionSource,
            indication = if (rippleColor == null) null else ripple(color = rippleColor),
            onClick = onClick,
            onLongClick = onLongClick
        )
}