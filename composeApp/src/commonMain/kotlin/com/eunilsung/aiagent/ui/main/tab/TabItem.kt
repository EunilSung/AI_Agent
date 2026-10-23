package com.eunilsung.aiagent.ui.main.tab

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.click.clickable
import androidx.compose.ui.platform.testTag
import com.eunilsung.aiagent.ui.uikit.TestTags

@Composable
fun TabItem(
    tab: AppTab,
    modifier: Modifier,
    isSelected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    val options = tab.options
    val iconPainter = options.icon ?: return
    val contentColor = if (isSelected) AppColors.Main else AppColors.Tab
    Box(
        modifier = modifier
            .fillMaxHeight()
            .testTag(TestTags.tab(tab.key))
            .clickable(
                rippleColor = null,
                onClick = onClick
            )
         ,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = iconPainter,
                    contentDescription = options.title,
                    modifier = Modifier.size(24.dp),
                    tint = contentColor
                )
                if (badgeCount > 0) {
                    val badgeText = if (badgeCount > 999) "999+" else badgeCount.toString()
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = with(LocalDensity.current) { 12.dp.toSp() },
                        lineHeight = with(LocalDensity.current) { 12.dp.toSp() },
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = 12.dp, y = (-10).dp)
                            .clip(CircleShape)
                            .background(AppColors.Out)
                            .padding(horizontal = 5.5.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = options.title,
                color = contentColor,
                fontSize = with(LocalDensity.current) { 11.dp.toSp() },
                lineHeight = with(LocalDensity.current) { 11.dp.toSp() },
                maxLines = 1,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TabItemPreview() {
    MaterialTheme {
        TabItem(
            tab = ChatTab,
            Modifier,
            true,
            3,
            {}
        )
    }
}