package com.eunilsung.aiagent.ui.uikit.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.eunilsung.aiagent.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarV1(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    scrollBehavior: TopAppBarScrollBehavior? = null,
    modifier: Modifier = Modifier,
    bottomContent: @Composable () -> Unit = {},
    backgroundColor: Color = AppColors.Bg,
    isShowTitle: Boolean = true
) {
    val heightOffset = scrollBehavior?.state?.heightOffset ?: 0f

    Layout(
        content = {
            Column(
                modifier = Modifier
                    .background(backgroundColor)
                    .onGloballyPositioned { layoutCoordinates ->
                        val height = layoutCoordinates.size.height.toFloat()
                        if (scrollBehavior?.state?.heightOffsetLimit != -height) {
                            scrollBehavior?.state?.heightOffsetLimit = -height
                        }
                    }
            ) {
                if (isShowTitle){
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                color = AppColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        navigationIcon = navigationIcon,
                        actions = actions,
                        windowInsets = windowInsets,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = AppColors.Text,
                            navigationIconContentColor = AppColors.Text,
                            actionIconContentColor = AppColors.Text
                        )
                    )
                }
                bottomContent()
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
        val totalHeight = placeable.height
        
        val reportedHeight = (totalHeight + heightOffset.roundToInt()).coerceAtLeast(0)

        layout(constraints.maxWidth, reportedHeight) {
            placeable.placeRelative(0, heightOffset.roundToInt())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun TopBarV1Preview() {
    MaterialTheme {
        TopBarV1(title = "제목")
    }
}
