package com.eunilsung.aiagent.ui.chat.item

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.send_cancel
import aiagent.composeapp.generated.resources.send_cancelled
import aiagent.composeapp.generated.resources.send_card_title
import aiagent.composeapp.generated.resources.send_confirm
import aiagent.composeapp.generated.resources.send_content
import aiagent.composeapp.generated.resources.send_failed
import aiagent.composeapp.generated.resources.send_not_found
import aiagent.composeapp.generated.resources.send_pick
import aiagent.composeapp.generated.resources.send_retry
import aiagent.composeapp.generated.resources.send_sending
import aiagent.composeapp.generated.resources.send_sent
import aiagent.composeapp.generated.resources.send_to
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.domain.model.Person
import com.eunilsung.aiagent.domain.model.SendRequest
import com.eunilsung.aiagent.domain.model.SendStatus
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.uikit.button.ButtonV1
import org.jetbrains.compose.resources.stringResource

/**
 * AI 가 정한 Talk 메시지 보내기 확인 카드. 사용자가 받는 사람과 내용을 보고 [보내기]를 눌러야 보낸다.
 *
 * 같은 이름이 여럿이면 고르게 하고, 찾지 못했으면 보낼 수 없다.
 *
 * @param recipientId 고른 받는 사람. 아직 고르지 않았으면 null.
 */
@Composable
fun SendRequestCard(
    request: SendRequest,
    recipientId: String?,
    maxWidth: Dp,
    onPick: (personId: String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val canChange = request.status == SendStatus.Pending || request.status == SendStatus.Failed
    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .background(AppColors.ChatOtherBg, RoundedCornerShape(topStart = 0.dp, topEnd = 5.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
            .testTag(TestTags.SEND_CARD)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = stringResource(Res.string.send_card_title), color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        Label(stringResource(Res.string.send_to))
        when {
            request.status == SendStatus.Sent && request.sentTo != null -> PersonRow(request.sentTo, selected = true, onClick = null)
            request.candidates.isEmpty() ->
                Text(text = stringResource(Res.string.send_not_found, request.recipientQuery), color = AppColors.Red, fontSize = 14.sp)
            request.candidates.size == 1 -> PersonRow(request.candidates.single(), selected = true, onClick = null)
            else -> {
                if (canChange) Text(text = stringResource(Res.string.send_pick), color = AppColors.TextSub, fontSize = 13.sp)
                request.candidates.forEachIndexed { index, person ->
                    PersonRow(
                        person = person,
                        selected = person.id == recipientId,
                        onClick = if (canChange) ({ onPick(person.id) }) else null,
                        modifier = Modifier.testTag(TestTags.sendCandidate(index)),
                    )
                }
            }
        }

        Label(stringResource(Res.string.send_content))
        Text(
            text = request.content,
            color = AppColors.Text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Gray50Bg, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )

        when (request.status) {
            SendStatus.Sending -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.PrimaryMain)
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(Res.string.send_sending), color = AppColors.TextSub, fontSize = 14.sp)
            }
            SendStatus.Sent -> Text(
                text = "✓ " + stringResource(Res.string.send_sent, request.sentTo?.name.orEmpty()),
                color = AppColors.GreenAuto,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(TestTags.SEND_RESULT),
            )
            SendStatus.Cancelled -> Text(text = stringResource(Res.string.send_cancelled), color = AppColors.TextSub, fontSize = 14.sp)
            SendStatus.Pending, SendStatus.Failed -> {
                if (request.status == SendStatus.Failed) {
                    Text(text = stringResource(Res.string.send_failed), color = AppColors.Red, fontSize = 13.sp)
                }
                val canConfirm = recipientId != null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonV1(
                        modifier = Modifier.testTag(TestTags.SEND_CANCEL),
                        text = stringResource(Res.string.send_cancel),
                        isSelected = false,
                        onClick = onCancel,
                    )
                    ButtonV1(
                        modifier = Modifier.testTag(TestTags.SEND_CONFIRM),
                        text = stringResource(if (request.status == SendStatus.Failed) Res.string.send_retry else Res.string.send_confirm),
                        containerColor = if (canConfirm) AppColors.PrimaryMain else AppColors.Gray100BgAuto,
                        contentColor = if (canConfirm) AppColors.White else AppColors.TextDisabled,
                        onClick = { if (canConfirm) onConfirm() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text = text, color = AppColors.TextSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

/** 받는 사람 한 줄. [onClick] 이 있으면 눌러서 고른다. */
@Composable
private fun PersonRow(person: Person, selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) AppColors.PrimaryMain else AppColors.Line, RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClick != null) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = AppColors.PrimaryMain),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text = person.name, color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (person.detail.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(text = person.detail, color = AppColors.TextSub, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(text = person.id, color = AppColors.TextHint, fontSize = 12.sp)
    }
}
