package com.eunilsung.aiagent.ui.uikit.toast

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags

@Preview(showBackground = true)
@Composable
fun ToastMessagePreview() {
    MaterialTheme {
        ToastMessage(
            message = "ToastMessage",
            isVisible = true,
            onDismiss = {},
            left = {}
        )
    }
}

/**
 * 화면 아래에 잠깐 떴다 사라지는 안내. Talk 의 ToastMessage 와 같고, 화면 자동화가 찾도록 글에
 * [TestTags.TOAST_MESSAGE] 를 붙인 것만 다르다.
 */
@Composable
fun ToastMessage(
    message: String,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    left: @Composable (() -> Unit)? = null,
    duration: Long = 2000L,
    restartKey: Any? = message,
) {
    /**
     * 이전 토스트가 떠 있는 채로 새 토스트가 오면 isVisible 은 true 로 유지된다.
     * restartKey 를 함께 키로 써야 표시 시간이 처음부터 다시 흐른다.
     */
    LaunchedEffect(restartKey, isVisible) {
        if (isVisible) {
            delay(duration)
            onDismiss()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.8f, animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.8f, animationSpec = tween(300))
        ) {
            /**
             * Surface 는 내부 pointerInput 때문에 토스트가 뜬 동안 그 자리의 버튼·리스트 항목이
             * 눌리지 않는다. 토스트는 입력을 가로채면 안 되므로 배경만 그리는 Box 로 둔다.
             */
            Box(
                modifier = Modifier
                    .padding(bottom = 50.dp)
                    .padding(horizontal = 24.dp)
                    .wrapContentWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(AppColors.Black.copy(alpha = 0.95f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (left != null) {
                        left()
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.3).sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(TestTags.TOAST_MESSAGE),
                    )
                }
            }
        }
    }
}