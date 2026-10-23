package com.eunilsung.aiagent.ui.chat

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.chat_back
import aiagent.composeapp.generated.resources.chat_move_to_latest
import aiagent.composeapp.generated.resources.chat_suggestion_1
import aiagent.composeapp.generated.resources.chat_suggestion_2
import aiagent.composeapp.generated.resources.chat_suggestion_3
import aiagent.composeapp.generated.resources.chat_suggestion_4
import aiagent.composeapp.generated.resources.chat_suggestion_title
import aiagent.composeapp.generated.resources.conversation_untitled
import aiagent.composeapp.generated.resources.move_to_last_chat_icon
import aiagent.composeapp.generated.resources.send_failed
import aiagent.composeapp.generated.resources.summary_question
import aiagent.composeapp.generated.resources.toast_chat_rate_limited
import aiagent.composeapp.generated.resources.toast_chat_server
import aiagent.composeapp.generated.resources.toast_chat_unreachable
import aiagent.composeapp.generated.resources.toast_session_expired
import aiagent.composeapp.generated.resources.toast_summary_broken
import aiagent.composeapp.generated.resources.toast_summary_encrypted
import aiagent.composeapp.generated.resources.toast_summary_no_text
import aiagent.composeapp.generated.resources.toast_summary_not_found
import aiagent.composeapp.generated.resources.toast_summary_too_large
import aiagent.composeapp.generated.resources.toast_summary_unsupported
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import com.eunilsung.aiagent.domain.model.AnswerFailure
import com.eunilsung.aiagent.ui.chat.input.ChatInputBar
import com.eunilsung.aiagent.ui.chat.item.DateHeaderItem
import com.eunilsung.aiagent.ui.chat.item.MessageItem
import com.eunilsung.aiagent.ui.main.KeyBoardPane
import com.eunilsung.aiagent.ui.main.LocalRightNavigator
import com.eunilsung.aiagent.ui.main.popToEmptyRoot
import com.eunilsung.aiagent.ui.main.rememberImeHeight
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.uikit.line.LineDivider
import com.eunilsung.aiagent.ui.uikit.toast.LocalToastManager
import com.eunilsung.aiagent.ui.uikit.topbar.NavIconButton
import com.eunilsung.aiagent.ui.uikit.topbar.TopBarV1
import com.eunilsung.aiagent.ui.util.chatDateHeaderText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** AI 대화방. 우측 네비게이터에 열린다. */
class ChatScreen(val conversationId: String) : Screen {
    override val key: ScreenKey = "chat:$conversationId"

    @Composable
    override fun Content() {
        val viewModel: ChatViewModel = koinViewModel(key = conversationId) { parametersOf(conversationId) }
        val uiState by viewModel.uiState.collectAsState()
        val rightNavigator = LocalRightNavigator.current
        val toast = LocalToastManager.current

        LaunchedEffect(viewModel) {
            viewModel.events.collect { event ->
                val message = when (event) {
                    ChatEvent.SessionExpired -> getString(Res.string.toast_session_expired)
                    ChatEvent.SendFailed -> getString(Res.string.send_failed)
                    is ChatEvent.AnswerFailed -> getString(event.failure.toastText())
                    is ChatEvent.SummaryRequested -> {
                        viewModel.onAction(
                            ChatActions.OnSummarize(event.request, getString(Res.string.summary_question, event.request.fileName))
                        )
                        null
                    }
                }
                message?.let { toast.show(it) }
            }
        }

        ChatContent(uiState = uiState, onAction = viewModel::onAction, onBack = { rightNavigator.popToEmptyRoot() })
    }
}

/**
 * 답변·요약을 받지 못한 이유를 알리는 토스트 문구. 이유마다 정해진 문구(ko/en)만 쓴다 — 서버가 준 설명
 * ([AnswerFailure.Server.reason])은 기록용이라 화면에 넣지 않는다.
 */
internal fun AnswerFailure.toastText(): StringResource = when (this) {
    AnswerFailure.RateLimited -> Res.string.toast_chat_rate_limited
    is AnswerFailure.Server -> Res.string.toast_chat_server
    AnswerFailure.UnsupportedFile -> Res.string.toast_summary_unsupported
    AnswerFailure.NoText -> Res.string.toast_summary_no_text
    AnswerFailure.EncryptedFile -> Res.string.toast_summary_encrypted
    AnswerFailure.BrokenFile -> Res.string.toast_summary_broken
    AnswerFailure.FileNotFound -> Res.string.toast_summary_not_found
    AnswerFailure.FileTooLarge -> Res.string.toast_summary_too_large
    AnswerFailure.SessionExpired -> Res.string.toast_session_expired
    AnswerFailure.Unreachable -> Res.string.toast_chat_unreachable
}

/**
 * 대화방. 구조는 Talk 의 ChatRoomScreen 과 같다 — 상단바, 대화 목록, 하단바(입력창 + 키보드 자리).
 * 창은 키보드에 맞춰 줄지 않고, 하단바의 [KeyBoardPane] 이 키보드 높이만큼 입력창과 목록을 밀어 올린다.
 */
@Composable
fun ChatContent(
    uiState: ChatUiState,
    onAction: (ChatActions) -> Unit,
    onBack: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val hideKeyboard: () -> Unit = { keyboardController?.hide() }
    val imeHeight = rememberImeHeight()
    val isKeyboardVisible = imeHeight > 0.dp
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    val showMoveToLatestButton by remember { derivedStateOf { listState.firstVisibleItemIndex >= 3 } }

    Scaffold(
        topBar = {
            TopBarV1(
                title = uiState.title.ifEmpty { stringResource(Res.string.conversation_untitled) },
                navigationIcon = {
                    Box(Modifier.testTag(TestTags.CHAT_BACK)) { NavIconButton(stringResource(Res.string.chat_back), onBack) }
                },
            )
        },
        bottomBar = {
            ChatBottomBar(
                uiState = uiState,
                isKeyboardVisible = isKeyboardVisible,
                imeHeight = imeHeight,
                onAction = onAction,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.ChatRoomBg)
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
        ) {
            if (uiState.messages.isEmpty()) {
                Suggestions(onPick = { onAction(ChatActions.OnSuggestion(it)) })
            } else {
                /** 새 질문을 보내면 맨 아래(최신)로 돌아온다. 받는 중인 답변은 맨 아래를 보고 있는 동안 따라 내려간다. */
                LaunchedEffect(uiState.messages.size) { listState.scrollToItem(0) }
                ChatMessageList(uiState = uiState, listState = listState, hideKeyboard = hideKeyboard, onAction = onAction)

                if (showMoveToLatestButton) {
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 8.dp),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.move_to_last_chat_icon),
                            contentDescription = stringResource(Res.string.chat_move_to_latest),
                            tint = Color.Unspecified,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 대화 목록 — 날짜 구분선과 말풍선. Talk 의 ChatMessageList 와 같다.
 * 아래에서 위로 쌓고(reverseLayout) 내용 높이만큼만 차지해, 대화가 적으면 위에서부터 보인다. 목록을 누르면 키보드를 내린다.
 */
@Composable
private fun ChatMessageList(
    uiState: ChatUiState,
    listState: LazyListState,
    hideKeyboard: () -> Unit,
    onAction: (ChatActions) -> Unit,
) {
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(AppColors.ChatRoomBg)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { hideKeyboard() })
            }
    ) {
        items(
            items = uiState.groupedMessages,
            key = { it.message.id },
            contentType = { it.message.role },
        ) { grouped ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                if (grouped.showDate) {
                    DateHeaderItem(dateText = chatDateHeaderText(grouped.message.createdAt))
                }
                MessageItem(
                    grouped = grouped,
                    recipientId = uiState.recipientOf(grouped.message),
                    onAction = onAction,
                )
            }
        }
    }
}

/** 대화방 하단바 — 입력창과 그 아래 키보드 자리. Talk 의 ChatRoomBottomBar 와 같다. */
@Composable
private fun ChatBottomBar(
    uiState: ChatUiState,
    isKeyboardVisible: Boolean,
    imeHeight: Dp,
    onAction: (ChatActions) -> Unit,
) {
    Column {
        LineDivider(modifier = Modifier.fillMaxWidth())
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.BgSub)
                .heightIn(min = 70.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChatInputBar(
                text = uiState.input,
                isAnswering = uiState.isAnswering,
                canSend = uiState.canSend,
                onTextChange = { onAction(ChatActions.OnInputChange(it)) },
                onSend = { onAction(ChatActions.OnSend) },
                onStop = { onAction(ChatActions.OnStop) },
            )
        }
        KeyBoardPane(
            modifier = Modifier.background(AppColors.BgSub),
            isKeyboardVisible = isKeyboardVisible,
            imeHeight = imeHeight,
            fillIdleBottomInset = true,
        )
    }
}

/** 빈 대화에 보이는 추천 질문. */
@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(Res.string.chat_suggestion_1),
        stringResource(Res.string.chat_suggestion_2),
        stringResource(Res.string.chat_suggestion_3),
        stringResource(Res.string.chat_suggestion_4),
    )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = stringResource(Res.string.chat_suggestion_title), color = AppColors.TextSub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        suggestions.forEachIndexed { index, question ->
            Text(
                text = question,
                color = AppColors.PrimaryMain,
                fontSize = 15.sp,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.ChatOtherBg)
                    .clickable { onPick(question) }
                    .testTag(TestTags.chatSuggestion(index))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
