package com.eunilsung.aiagent.ui.uikit.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.alarm
import aiagent.composeapp.generated.resources.cancel
import aiagent.composeapp.generated.resources.delete
import aiagent.composeapp.generated.resources.leave
import aiagent.composeapp.generated.resources.ok
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.button.ButtonV1
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.uikit.exposeTestTags

/**
 * 두 버튼 대화상자. [onConfirm] 은 확인을, [onDismiss] 는 취소를 뜻한다 — 확인을 눌러도 취소 쪽을 부르지 않는다.
 * 닫는 일은 부르는 쪽([DialogHost])이 각 콜백에서 한다.
 */
@Composable
fun Button2Dialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit = { Text(
        text = stringResource(Res.string.alarm),
        color = AppColors.Text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold) },
    description: @Composable (() -> Unit)? = null,
    confirmText: String = stringResource(Res.string.ok),
    dismissText: String? = stringResource(Res.string.cancel),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit = onDismissRequest
) {
    Dialog(
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight().exposeTestTags(),
            shape = RoundedCornerShape(8.dp),
            color = AppColors.BgSub
        ) {
            Column(
                modifier = Modifier.padding(15.dp).fillMaxWidth()
            ) {
                title()

                if (description != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    description()
                }

                Spacer(modifier = Modifier.height(15.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (dismissText != null) {
                        ButtonV1(
                            modifier = Modifier.weight(1f).testTag(TestTags.DIALOG_CANCEL),
                            text = dismissText,
                            containerColor = AppColors.DialogCancelBtnBg,
                            contentColor = AppColors.TextSub,
                            onClick = onDismiss
                        )
                    }

                    ButtonV1(
                        modifier = Modifier.weight(1f).testTag(TestTags.DIALOG_CONFIRM),
                        text = confirmText,
                        containerColor = if (confirmText == stringResource(Res.string.delete)
                            || confirmText == stringResource(Res.string.leave)) AppColors.Out else AppColors.PrimaryMain,
                        onClick = onConfirm
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun Button2DialogPreview() {
    MaterialTheme {
        Button2Dialog(
            onDismissRequest = {},
            title = { Text("Title") },
            description = {
                Text(
                    text = "Description",
                    color = AppColors.TextSub,
                    fontSize = 14.sp
                )
                          },
            onConfirm = {}
        )
    }
}