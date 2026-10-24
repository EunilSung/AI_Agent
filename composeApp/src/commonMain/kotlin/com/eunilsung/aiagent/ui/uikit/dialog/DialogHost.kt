package com.eunilsung.aiagent.ui.uikit.dialog

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.cancel
import aiagent.composeapp.generated.resources.ok
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import org.jetbrains.compose.resources.stringResource

@Composable
fun DialogHost(content: @Composable () -> Unit) {
    var request by remember { mutableStateOf<DialogRequest?>(null) }

    val manager = remember {
        object : DialogManager {
            override fun show(r: DialogRequest) {
                request = r
            }
            override fun dismiss() {
                request = null
            }
        }
    }

    CompositionLocalProvider(LocalDialogManager provides manager) {
        content()
        when (val req = request) {
            is DialogRequest.Alert -> Button1Dialog(
                onDismissRequest = {
                    request = null
                    req.onConfirm()
                },
                description = req.message,
                title = req.title,
            )
            is DialogRequest.Confirm -> Button2Dialog(
                onDismissRequest = {
                    request = null
                    req.onCancel()
                },
                title = {
                    Text(
                        text = req.title,
                        color = AppColors.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag(TestTags.DIALOG_TITLE),
                    )
                },
                description = {
                    Text(
                        text = req.message,
                        color = AppColors.TextSub,
                        fontSize = 14.sp
                    )
                },
                confirmText = req.confirmText ?: stringResource(Res.string.ok),
                dismissText = req.dismissText ?: stringResource(Res.string.cancel),
                onConfirm = {
                    request = null
                    req.onConfirm()
                },
                onDismiss = {
                    request = null
                    req.onCancel()
                }
            )
            null -> Unit
        }
    }
}
