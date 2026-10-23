package com.eunilsung.aiagent.ui.chat.item

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.chat_ai_name
import aiagent.composeapp.generated.resources.chat_answer_failed
import aiagent.composeapp.generated.resources.chat_search_query
import aiagent.composeapp.generated.resources.chat_stopped
import aiagent.composeapp.generated.resources.search_icon
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.Config
import com.eunilsung.aiagent.domain.model.ChatMessage
import com.eunilsung.aiagent.domain.model.ChatRole
import com.eunilsung.aiagent.domain.model.MessageStatus
import com.eunilsung.aiagent.domain.model.Source
import com.eunilsung.aiagent.ui.chat.ChatActions
import com.eunilsung.aiagent.ui.chat.GroupedMessage
import com.eunilsung.aiagent.ui.chat.markdown.MarkdownText
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.util.chatTimeText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 받는 중인 답변 끝에 붙는 커서. */
private const val STREAMING_CURSOR = " ▍"

/** 말풍선 글자 크기 — Talk 의 기본 대화 글자 크기와 같다. */
private val ChatFontSize = 15.sp

/** 아바타 크기 — Talk 의 프로필 이미지와 같다. */
private val AvatarSize = 50.dp

/**
 * 대화 말풍선 하나. 구조는 Talk 의 ChatItem 과 같다 — 내 질문은 오른쪽 주색, AI 답변은 왼쪽 흰색이다.
 * 묶음의 첫 답변에만 아바타와 이름이 붙고, 이어지는 답변은 아바타 자리를 비워 둔다. 시각은 묶음의 마지막 말풍선 옆에만 붙는다.
 * 말풍선은 이 줄 너비의 75% 를 넘기지 않는다.
 */
@Composable
fun MessageItem(
    grouped: GroupedMessage,
    recipientId: String?,
    onAction: (ChatActions) -> Unit,
) {
    val message = grouped.message
    val isMe = message.role == ChatRole.User
    val showsAvatar = !isMe && grouped.showProfileAndName

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showsAvatar) Modifier.heightIn(min = 45.dp) else Modifier)
            .padding(horizontal = 10.dp)
    ) {
        if (!isMe) {
            if (showsAvatar) AiAvatar(size = AvatarSize) else Spacer(modifier = Modifier.width(AvatarSize))
        }

        Column(
            modifier = Modifier.padding(horizontal = 5.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
        ) {
            if (showsAvatar) {
                Text(
                    text = stringResource(Res.string.chat_ai_name),
                    color = AppColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxBubbleWidth = maxWidth * 0.75f
                val time = if (grouped.showTime && message.status != MessageStatus.Streaming) chatTimeText(message.createdAt) else null
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChatBubbleRow(isMe = isMe, time = time) {
                        if (isMe) {
                            UserBubble(message, maxBubbleWidth)
                        } else {
                            AssistantBubble(message, maxBubbleWidth, recipientId, onAction)
                        }
                    }
                    if (!isMe) AnswerFooter(message, maxBubbleWidth)
                }
            }
        }
    }
}

/** 말풍선과 시각 칸. 내 말풍선은 시각이 왼쪽, 상대 말풍선은 오른쪽에 붙는다 — Talk 의 ChatBubbleRow 와 같다. */
@Composable
private fun ChatBubbleRow(isMe: Boolean, time: String?, bubble: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (isMe) ChatTimeSlot(time)
        bubble()
        if (!isMe) ChatTimeSlot(time)
    }
}

/** 말풍선 옆 시각 칸. 시각을 보이지 않는 말풍선도 칸 너비는 남겨 둔다. */
@Composable
private fun ChatTimeSlot(time: String?) {
    Box(modifier = Modifier.padding(horizontal = 5.dp)) {
        if (time != null) {
            Text(text = time, color = AppColors.TextSub, fontSize = 10.sp, lineHeight = 10.sp, maxLines = 2)
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage, maxBubbleWidth: Dp) {
    Text(
        text = message.content,
        color = AppColors.White,
        fontSize = ChatFontSize,
        lineHeight = ChatFontSize * 1.4f,
        modifier = Modifier
            .widthIn(max = maxBubbleWidth)
            .background(AppColors.ChatMeBg, RoundedCornerShape(topStart = 5.dp, topEnd = 0.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
            .padding(vertical = 5.dp, horizontal = 10.dp),
    )
}

@Composable
private fun AssistantBubble(message: ChatMessage, maxBubbleWidth: Dp, recipientId: String?, onAction: (ChatActions) -> Unit) {
    val sendRequest = message.sendRequest
    if (sendRequest != null) {
        SendRequestCard(
            request = sendRequest,
            recipientId = recipientId,
            maxWidth = maxBubbleWidth,
            onPick = { onAction(ChatActions.OnPickRecipient(message.id, it)) },
            onConfirm = { onAction(ChatActions.OnConfirmSend(message.id)) },
            onCancel = { onAction(ChatActions.OnCancelSend(message.id)) },
        )
        return
    }
    val isStreaming = message.status == MessageStatus.Streaming
    Box(
        modifier = Modifier
            .widthIn(max = maxBubbleWidth)
            .background(AppColors.ChatOtherBg, RoundedCornerShape(topStart = 0.dp, topEnd = 5.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
            .then(if (message.status == MessageStatus.Done) Modifier.testTag(TestTags.CHAT_ANSWER) else Modifier)
            .padding(vertical = 5.dp, horizontal = 10.dp)
    ) {
        when {
            message.status == MessageStatus.Failed && message.content.isEmpty() ->
                Text(text = stringResource(Res.string.chat_answer_failed), color = AppColors.Red, fontSize = ChatFontSize)
            isStreaming && message.content.isEmpty() ->
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 2.dp).size(18.dp), strokeWidth = 2.dp, color = AppColors.PrimaryMain)
            else -> MarkdownText(
                text = message.content,
                color = AppColors.Text,
                fontSize = ChatFontSize,
                suffix = if (isStreaming) STREAMING_CURSOR else "",
            )
        }
    }
}

/** 답변 아래 — 멈춤 표시, 다시 쓴 검색어, 근거 문서. */
@Composable
private fun AnswerFooter(message: ChatMessage, maxWidth: Dp) {
    if (message.status == MessageStatus.Stopped) {
        Text(text = stringResource(Res.string.chat_stopped), color = AppColors.TextSub, fontSize = 12.sp)
    }
    if (Config.Chat.IS_SEARCH_QUERY_ENABLED) message.searchQuery?.let { SearchQueryLabel(it, maxWidth) }
    if (Config.Chat.IS_SOURCES_ENABLED && message.sources.isNotEmpty()) SourceChips(message.sources, maxWidth)
}

/** 후속 질문을 앞 대화를 반영해 다시 쓴 검색어. */
@Composable
private fun SearchQueryLabel(query: String, maxWidth: Dp) {
    Row(modifier = Modifier.widthIn(max = maxWidth), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(Res.drawable.search_icon), contentDescription = null, tint = AppColors.TextSub, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = stringResource(Res.string.chat_search_query, query), color = AppColors.TextSub, fontSize = 12.sp)
    }
}

/** 근거 문서. 같은 파일의 조각이 여럿이면 가장 높은 유사도 하나만 보인다. */
@Composable
private fun SourceChips(sources: List<Source>, maxWidth: Dp) {
    val best = sources.groupBy { it.name }.map { (name, list) -> name to list.maxOf { it.score } }.sortedByDescending { it.second }
    Row(
        modifier = Modifier.widthIn(max = maxWidth).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        best.forEach { (name, score) ->
            Text(
                text = "$name · ${(score * 100).toInt()}%",
                color = AppColors.TextSub,
                fontSize = 12.sp,
                modifier = Modifier
                    .testTag(TestTags.CHAT_SOURCE)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.BgSub)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** AI 아바타 — 주색 바탕에 "AI". 모서리는 Talk 의 프로필 이미지처럼 둥근 네모다. */
@Composable
fun AiAvatar(size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.36f)).background(AppColors.PrimaryMain),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "AI", color = AppColors.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold)
    }
}
