package com.eunilsung.aiagent.ui.uikit.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.alarm
import aiagent.composeapp.generated.resources.ok
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.button.ButtonV1
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.testTag
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.uikit.exposeTestTags

@Composable
fun Button1Dialog(
    onDismissRequest: () -> Unit,
    description: String,
    confirmText: String = stringResource(Res.string.ok),
    onDismiss: () -> Unit = onDismissRequest,
    title: String? = null,
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
                modifier = Modifier.padding(15.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(Modifier.size(10.dp))

                if (title != null) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Text,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.DIALOG_TITLE),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                val maxContentHeight = with(LocalDensity.current) {
                    (LocalWindowInfo.current.containerSize.height * 0.6f).toDp()
                }
                Text(
                    text = description,
                    fontSize = 15.sp,
                    color = AppColors.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(rememberScrollState()),
                )

                Spacer(modifier = Modifier.height(15.dp))

                Row(modifier = Modifier.width(207.dp)) {
                    ButtonV1(
                        modifier = Modifier.weight(1f).height(46.dp).testTag(TestTags.DIALOG_CONFIRM),
                        text = confirmText,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun Button1DialogDialogPreview() {
    MaterialTheme {
        Button1Dialog(
            onDismissRequest = {},
            description = "비밀번호가 일치하지 않습니다",
        )
    }
}