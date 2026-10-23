package com.eunilsung.aiagent.ui.conversationlist

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.conversation_delete_confirm
import aiagent.composeapp.generated.resources.conversation_delete_title
import aiagent.composeapp.generated.resources.conversation_empty_desc
import aiagent.composeapp.generated.resources.conversation_empty_title
import aiagent.composeapp.generated.resources.conversation_list_title
import aiagent.composeapp.generated.resources.conversation_new
import aiagent.composeapp.generated.resources.conversation_no_messages
import aiagent.composeapp.generated.resources.conversation_untitled
import aiagent.composeapp.generated.resources.delete
import aiagent.composeapp.generated.resources.plus_bold_icon
import aiagent.composeapp.generated.resources.time_days_ago
import aiagent.composeapp.generated.resources.time_hours_ago
import aiagent.composeapp.generated.resources.time_just_now
import aiagent.composeapp.generated.resources.time_minutes_ago
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.eunilsung.aiagent.domain.model.Conversation
import com.eunilsung.aiagent.domain.util.nowMillis
import com.eunilsung.aiagent.ui.chat.ChatScreen
import com.eunilsung.aiagent.ui.chat.item.AiAvatar
import com.eunilsung.aiagent.ui.chat.markdown.MarkdownParser
import com.eunilsung.aiagent.ui.main.LocalRightNavigator
import com.eunilsung.aiagent.ui.main.popToEmptyRoot
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.uikit.button.ButtonV1
import com.eunilsung.aiagent.ui.uikit.dialog.LocalDialogManager
import com.eunilsung.aiagent.ui.uikit.line.LineDivider
import com.eunilsung.aiagent.ui.uikit.topbar.TopBarV1
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** AI 대화 목록 탭. 대화를 누르면 우측 네비게이터에 대화방을 연다. */
object ConversationListScreen : Screen {
    @Composable
    override fun Content() {
        val viewModel: ConversationListViewModel = koinViewModel()
        val uiState by viewModel.uiState.collectAsState()
        val rightNavigator = LocalRightNavigator.current
        val openedId = (rightNavigator.lastItem as? ChatScreen)?.conversationId

        LaunchedEffect(viewModel) {
            viewModel.events.collect { event ->
                when (event) {
                    is ConversationListEvent.OpenChat -> {
                        if ((rightNavigator.lastItem as? ChatScreen)?.conversationId != event.conversationId) {
                            rightNavigator.popToEmptyRoot()
                            rightNavigator.push(ChatScreen(event.conversationId))
                        }
                    }
                    is ConversationListEvent.Deleted ->
                        if ((rightNavigator.lastItem as? ChatScreen)?.conversationId == event.conversationId) rightNavigator.popToEmptyRoot()
                }
            }
        }

        ConversationListContent(uiState = uiState, openedId = openedId, onAction = viewModel::onAction)
    }
}

@Composable
fun ConversationListContent(
    uiState: ConversationListUiState,
    openedId: String?,
    onAction: (ConversationListActions) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(AppColors.Bg)) {
        TopBarV1(
            title = stringResource(Res.string.conversation_list_title),
            actions = {
                IconButton(
                    onClick = { onAction(ConversationListActions.OnNewConversation) },
                    modifier = Modifier.testTag(TestTags.CONVERSATION_NEW),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.plus_bold_icon),
                        contentDescription = stringResource(Res.string.conversation_new),
                        tint = AppColors.Text,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
        )
        if (uiState.conversations.isEmpty()) {
            EmptyConversations(onNew = { onAction(ConversationListActions.OnNewConversation) })
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.conversations, key = { it.id }) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        isSelected = conversation.id == openedId,
                        onClick = { onAction(ConversationListActions.OnOpen(conversation.id)) },
                        onDelete = { onAction(ConversationListActions.OnDelete(conversation.id)) },
                    )
                    LineDivider(modifier = Modifier.padding(start = 72.dp), color = AppColors.Gray50Bg)
                }
            }
        }
    }
}

/** 대화 한 줄. 길게 누르면 지울지 묻는다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dialog = LocalDialogManager.current
    val deleteTitle = stringResource(Res.string.conversation_delete_title)
    val deleteMessage = stringResource(Res.string.conversation_delete_confirm)
    val deleteText = stringResource(Res.string.delete)
    val preview = remember(conversation.preview) { MarkdownParser.toPlainText(conversation.preview) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) AppColors.UserSelectBg else AppColors.Bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { dialog.confirm(deleteTitle, deleteMessage, confirmText = deleteText, onConfirm = onDelete) },
            )
            .testTag(TestTags.CONVERSATION_ITEM)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiAvatar(size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title.ifEmpty { stringResource(Res.string.conversation_untitled) },
                    color = AppColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = relativeTime(conversation.updatedAt), color = AppColors.TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = preview.ifEmpty { stringResource(Res.string.conversation_no_messages) },
                color = AppColors.TextSub,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyConversations(onNew: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiAvatar(size = 64.dp)
            Text(
                text = stringResource(Res.string.conversation_empty_title),
                color = AppColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(Res.string.conversation_empty_desc),
                color = AppColors.TextSub,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            ButtonV1(
                modifier = Modifier.padding(top = 6.dp).testTag(TestTags.CONVERSATION_NEW),
                text = stringResource(Res.string.conversation_new),
                onClick = onNew,
            )
        }
    }
}

/** "방금", "5분 전" 같은 상대 시간. */
@Composable
private fun relativeTime(epochMillis: Long): String {
    val minutes = ((nowMillis() - epochMillis) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(Res.string.time_just_now)
        minutes < 60 -> stringResource(Res.string.time_minutes_ago, minutes.toInt())
        minutes < 60 * 24 -> stringResource(Res.string.time_hours_ago, (minutes / 60).toInt())
        else -> stringResource(Res.string.time_days_ago, (minutes / (60 * 24)).toInt())
    }
}
