package com.eunilsung.aiagent.ui.login

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.app_name
import aiagent.composeapp.generated.resources.id
import aiagent.composeapp.generated.resources.login
import aiagent.composeapp.generated.resources.login_fail
import aiagent.composeapp.generated.resources.login_fail_invalid
import aiagent.composeapp.generated.resources.login_fail_locked
import aiagent.composeapp.generated.resources.login_fail_unreachable
import aiagent.composeapp.generated.resources.login_hide_password
import aiagent.composeapp.generated.resources.login_hint
import aiagent.composeapp.generated.resources.login_server_label
import aiagent.composeapp.generated.resources.login_show_password
import aiagent.composeapp.generated.resources.login_tagline
import aiagent.composeapp.generated.resources.logo
import aiagent.composeapp.generated.resources.pw
import aiagent.composeapp.generated.resources.server_url_invalid
import aiagent.composeapp.generated.resources.setting_server_save
import aiagent.composeapp.generated.resources.visibility_icon
import aiagent.composeapp.generated.resources.visibility_off_icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.eunilsung.aiagent.domain.model.LoginResult
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.TestTags
import com.eunilsung.aiagent.ui.uikit.button.ButtonV1
import com.eunilsung.aiagent.ui.uikit.dialog.LocalDialogManager
import com.eunilsung.aiagent.ui.uikit.textfield.TextFieldV1
import com.eunilsung.aiagent.ui.util.text
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 로그인 화면 — 로그인하지 않았으면 메인 화면 위에 덮인다. Talk 의 로그인 화면과 같은 모양이다. */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    LoginContent(uiState = uiState, onAction = viewModel::onAction)
}

@Composable
fun LoginContent(
    uiState: LoginUiState,
    onAction: (LoginActions) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val dialog = LocalDialogManager.current

    LaunchedEffect(uiState.status) {
        val status = uiState.status
        if (status is LoginStatus.Error) {
            dialog.alert(
                message = getString(
                    when (val reason = status.reason) {
                        LoginResult.InvalidCredentials -> Res.string.login_fail_invalid
                        LoginResult.Locked -> Res.string.login_fail_locked
                        LoginResult.Unreachable -> Res.string.login_fail_unreachable
                        is LoginResult.Refused -> reason.refusal.text()
                        else -> Res.string.login_fail
                    }
                ),
                onConfirm = { onAction(LoginActions.OnResetStatus) },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .imePadding()
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Brand()
            LoginInputForm(uiState = uiState, onAction = onAction)
        }
    }
}

/** 브랜드 — 앱 아이콘, 앱 이름, 한 줄 소개. */
@Composable
private fun Brand() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(Res.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
        )
        Text(
            text = stringResource(Res.string.app_name),
            color = AppColors.Text,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(Res.string.login_tagline),
            color = AppColors.TextSub,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun LoginInputForm(
    uiState: LoginUiState,
    onAction: (LoginActions) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submit = {
        keyboardController?.hide()
        onAction(LoginActions.OnLoginClick)
    }
    /**
     * 비밀번호 칸에 들어오면 키보드가 다 올라온 뒤 로그인 버튼까지 보이게 올린다. 작은 폰은 키보드 위 높이가 작아
     * 버튼이 키보드 뒤에 가린다. Talk 의 로그인 화면과 같은 처리다.
     */
    val submitArea = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val revealSubmit: () -> Unit = {
        scope.launch {
            delay(KEYBOARD_SETTLE_MS)
            submitArea.bringIntoView()
        }
    }
    Column(
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoginField(
            value = uiState.id,
            onValueChange = { onAction(LoginActions.OnIdChange(it)) },
            label = stringResource(Res.string.id),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            testTag = TestTags.LOGIN_ID,
        )
        LoginField(
            value = uiState.pw,
            onValueChange = { onAction(LoginActions.OnPwChange(it)) },
            label = stringResource(Res.string.pw),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (uiState.canSubmit) submit() }),
            testTag = TestTags.LOGIN_PW,
            onFocused = revealSubmit,
            visualTransformation = if (uiState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Icon(
                    painter = painterResource(
                        if (uiState.isPasswordVisible) Res.drawable.visibility_off_icon else Res.drawable.visibility_icon
                    ),
                    contentDescription = stringResource(
                        if (uiState.isPasswordVisible) Res.string.login_hide_password else Res.string.login_show_password
                    ),
                    tint = AppColors.TextSub,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onAction(LoginActions.OnTogglePasswordVisible) }
                        .padding(9.dp),
                )
            },
        )

        /** 버튼 아래 안내까지 함께 올려야 키보드 위 자동완성 줄이 있어도 버튼이 다 보인다. */
        Column(
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(submitArea),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoginButton(
                enabled = uiState.canSubmit,
                isLoading = uiState.status is LoginStatus.Loading,
                onClick = submit,
            )

            Text(
                text = stringResource(Res.string.login_hint),
                color = AppColors.TextSub,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Gray50Bg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            ServerAddress(uiState = uiState, onAction = onAction)
        }
    }
}

/** 붙는 서버 주소. 눌러서 고칠 수 있다 — 로그인 전에는 설정 탭에 갈 수 없어서다. */
@Composable
private fun ServerAddress(uiState: LoginUiState, onAction: (LoginActions) -> Unit) {
    val input = uiState.serverInput
    if (input == null) {
        Text(
            text = stringResource(Res.string.login_server_label, uiState.serverUrl),
            color = AppColors.TextHint,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onAction(LoginActions.OnToggleServerEdit) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextFieldV1(
                modifier = Modifier.weight(1f),
                inputText = input,
                hintText = uiState.serverUrl,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAction(LoginActions.OnServerSave) }),
                onValueChange = { onAction(LoginActions.OnServerInputChange(it)) },
            )
            ButtonV1(text = stringResource(Res.string.setting_server_save), onClick = { onAction(LoginActions.OnServerSave) })
        }
        if (uiState.isServerInvalid) {
            Text(
                text = stringResource(Res.string.server_url_invalid),
                color = AppColors.Red,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 떠 있는 라벨이 붙은 테두리 입력칸. */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    testTag: String,
    onFocused: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppColors.PrimaryMain,
            focusedLabelColor = AppColors.PrimaryMain,
            unfocusedBorderColor = AppColors.Line,
            cursorColor = AppColors.PrimaryMain,
            focusedTextColor = AppColors.Text,
            unfocusedTextColor = AppColors.Text,
            unfocusedLabelColor = AppColors.TextSub,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .onFocusChanged { if (it.isFocused) onFocused() },
    )
}

/** 로그인 버튼 — 아이디·비밀번호를 다 넣어야 눌린다. */
@Composable
private fun LoginButton(enabled: Boolean, isLoading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .height(52.dp)
            .testTag(TestTags.LOGIN_SUBMIT)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) AppColors.PrimaryMain else AppColors.Gray100BgAuto)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = AppColors.TextDisabled)
        } else {
            Text(
                text = stringResource(Res.string.login),
                color = if (enabled) AppColors.White else AppColors.TextDisabled,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    MaterialTheme {
        LoginContent(uiState = LoginUiState(id = "test1", pw = "1234", serverUrl = "http://localhost:8095"), onAction = {})
    }
}

/** 키보드가 다 올라와 영역이 줄어든 뒤에 스크롤해야 버튼이 키보드 위에 온다. */
private const val KEYBOARD_SETTLE_MS = 350L
