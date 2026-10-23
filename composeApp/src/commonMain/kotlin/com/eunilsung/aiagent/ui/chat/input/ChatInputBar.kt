package com.eunilsung.aiagent.ui.chat.input

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.chat_input_hint
import aiagent.composeapp.generated.resources.chat_send
import aiagent.composeapp.generated.resources.chat_stop
import aiagent.composeapp.generated.resources.send_dark_icon
import aiagent.composeapp.generated.resources.send_light_icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 입력창 글자 크기 — Talk 의 기본 대화 글자 크기와 같다. */
private val InputFontSize = 15.sp

/**
 * 질문 입력창. 구조는 Talk 의 ChatInputBar 와 같다 — 둥근 회색 입력칸과 보내기 버튼.
 * 답변을 받는 동안에는 보내기 대신 중지 버튼이 나온다. 바탕과 키보드 자리는 감싸는 하단바가 맡는다.
 */
@Composable
fun ChatInputBar(
    text: String,
    isAnswering: Boolean,
    canSend: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(color = AppColors.Gray50Bg, shape = RoundedCornerShape(20.dp))
                .padding(vertical = 2.dp)
                .heightIn(min = 35.dp)
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 15.dp, end = 5.dp)
                    .heightIn(min = 20.dp, max = 80.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(fontSize = InputFontSize, color = AppColors.Text),
                    cursorBrush = SolidColor(AppColors.Main),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.CHAT_INPUT),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(text = stringResource(Res.string.chat_input_hint), color = AppColors.TextHint, fontSize = InputFontSize)
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (isAnswering) StopButton(onStop) else SendButton(enabled = canSend, onSend = onSend)
    }
}

@Composable
private fun SendButton(enabled: Boolean, onSend: () -> Unit) {
    IconButton(
        modifier = Modifier.size(28.dp).testTag(TestTags.CHAT_SEND),
        onClick = onSend,
        enabled = enabled,
    ) {
        Icon(
            painter = painterResource(if (isSystemInDarkTheme()) Res.drawable.send_dark_icon else Res.drawable.send_light_icon),
            contentDescription = stringResource(Res.string.chat_send),
            tint = Color.Unspecified,
            modifier = Modifier.size(28.dp).alpha(if (enabled) 1f else 0.4f),
        )
    }
}

/** 중지 버튼 — 보내기 버튼 자리에 주색 원 안의 흰 네모. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val description = stringResource(Res.string.chat_stop)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(AppColors.PrimaryMain)
            .clickable(onClick = onStop)
            .testTag(TestTags.CHAT_STOP)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(9.dp).background(AppColors.White, RoundedCornerShape(2.dp)))
    }
}
