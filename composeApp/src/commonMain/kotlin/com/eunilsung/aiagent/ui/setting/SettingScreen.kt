package com.eunilsung.aiagent.ui.setting

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.setting_account
import aiagent.composeapp.generated.resources.setting_logout
import aiagent.composeapp.generated.resources.setting_logout_confirm
import aiagent.composeapp.generated.resources.setting_server
import aiagent.composeapp.generated.resources.setting_server_hint
import aiagent.composeapp.generated.resources.setting_server_reset
import aiagent.composeapp.generated.resources.setting_server_save
import aiagent.composeapp.generated.resources.setting_status
import aiagent.composeapp.generated.resources.setting_status_bridge
import aiagent.composeapp.generated.resources.setting_status_checking
import aiagent.composeapp.generated.resources.setting_status_chunks
import aiagent.composeapp.generated.resources.setting_status_docs
import aiagent.composeapp.generated.resources.setting_status_down
import aiagent.composeapp.generated.resources.setting_status_model
import aiagent.composeapp.generated.resources.setting_status_ok
import aiagent.composeapp.generated.resources.setting_status_refresh
import aiagent.composeapp.generated.resources.setting_status_talk
import aiagent.composeapp.generated.resources.setting_title
import aiagent.composeapp.generated.resources.toast_setting_server_saved
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.uikit.button.ButtonV1
import com.eunilsung.aiagent.ui.uikit.dialog.LocalDialogManager
import com.eunilsung.aiagent.ui.uikit.line.LineDivider
import com.eunilsung.aiagent.ui.uikit.textfield.TextFieldV1
import com.eunilsung.aiagent.ui.uikit.toast.LocalToastManager
import com.eunilsung.aiagent.ui.uikit.topbar.TopBarV1
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 설정 탭 — 계정, 서버 주소, 연결 상태. */
object SettingScreen : Screen {
    @Composable
    override fun Content() {
        val viewModel: SettingViewModel = koinViewModel()
        val uiState by viewModel.uiState.collectAsState()
        val toast = LocalToastManager.current

        LaunchedEffect(viewModel) {
            viewModel.events.collect { event ->
                when (event) {
                    SettingEvent.ServerSaved -> toast.show(getString(Res.string.toast_setting_server_saved))
                }
            }
        }

        SettingContent(uiState = uiState, onAction = viewModel::onAction)
    }
}

@Composable
fun SettingContent(
    uiState: SettingUiState,
    onAction: (SettingActions) -> Unit,
) {
    val dialog = LocalDialogManager.current
    val logoutTitle = stringResource(Res.string.setting_logout)
    val logoutMessage = stringResource(Res.string.setting_logout_confirm)

    Column(modifier = Modifier.fillMaxSize().background(AppColors.Bg)) {
        TopBarV1(title = stringResource(Res.string.setting_title))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(title = stringResource(Res.string.setting_account)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = uiState.me?.name.orEmpty(), color = AppColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = uiState.me?.id.orEmpty(), color = AppColors.TextSub, fontSize = 13.sp)
                    }
                    ButtonV1(
                        modifier = Modifier.testTag(TestTags.SETTING_LOGOUT),
                        text = logoutTitle,
                        isSelected = false,
                        onClick = { dialog.confirm(logoutTitle, logoutMessage, onConfirm = { onAction(SettingActions.OnLogout) }) },
                    )
                }
            }
            SectionDivider()
            Section(title = stringResource(Res.string.setting_server)) {
                TextFieldV1(
                    inputText = uiState.serverInput,
                    hintText = uiState.serverUrl,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    onValueChange = { onAction(SettingActions.OnServerInputChange(it)) },
                )
                Text(text = stringResource(Res.string.setting_server_hint), color = AppColors.TextHint, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonV1(text = stringResource(Res.string.setting_server_reset), isSelected = false, onClick = { onAction(SettingActions.OnServerReset) })
                    ButtonV1(
                        text = stringResource(Res.string.setting_server_save),
                        containerColor = if (uiState.canSaveServer) AppColors.PrimaryMain else AppColors.Gray100BgAuto,
                        contentColor = if (uiState.canSaveServer) AppColors.White else AppColors.TextDisabled,
                        onClick = { if (uiState.canSaveServer) onAction(SettingActions.OnServerSave) },
                    )
                }
            }
            SectionDivider()
            Section(
                title = stringResource(Res.string.setting_status),
                action = {
                    ButtonV1(text = stringResource(Res.string.setting_status_refresh), isSelected = false, onClick = { onAction(SettingActions.OnRefreshStatus) })
                },
            ) {
                val status = uiState.status
                val checking = stringResource(Res.string.setting_status_checking)
                val ok = stringResource(Res.string.setting_status_ok)
                val down = stringResource(Res.string.setting_status_down)
                StatusRow(
                    label = stringResource(Res.string.setting_status_bridge),
                    value = when { status == null -> checking; status.bridge -> ok; else -> down },
                    isGood = status?.bridge,
                    modifier = Modifier.testTag(TestTags.SETTING_STATUS_BRIDGE),
                )
                StatusRow(
                    label = stringResource(Res.string.setting_status_talk),
                    value = when { status == null -> checking; status.talk -> ok; else -> down },
                    isGood = status?.talk,
                )
                StatusRow(
                    label = stringResource(Res.string.setting_status_model),
                    value = status?.chatModel?.let { model -> listOfNotNull(model, status.embedModel).joinToString(" · ") }
                        ?: if (status == null) checking else down,
                    isGood = status?.chatModel?.let { true } ?: status?.let { false },
                )
                StatusRow(
                    label = stringResource(Res.string.setting_status_docs),
                    value = status?.chunks?.let { stringResource(Res.string.setting_status_chunks, it) } ?: if (status == null) checking else down,
                    isGood = status?.chunks?.let { it > 0 } ?: status?.let { false },
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, color = AppColors.TextSub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

@Composable
private fun SectionDivider() {
    LineDivider(modifier = Modifier.fillMaxWidth(), color = AppColors.Gray50Bg, thickness = 8.dp)
}

/** 연결 상태 한 줄. [isGood] 이 null 이면 확인 중이다. */
@Composable
private fun StatusRow(label: String, value: String, isGood: Boolean?, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = AppColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = when (isGood) { null -> AppColors.TextSub; true -> AppColors.GreenAuto; false -> AppColors.Red },
            fontSize = 14.sp,
        )
    }
    Spacer(Modifier.height(2.dp))
}
