package com.eunilsung.aiagent.ui.uikit.topbar

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.arrow_left_icon
import org.jetbrains.compose.resources.painterResource

/** 좌측 상단 뒤로가기 아이콘 — 화면마다 같은 모양을 반복하지 않도록 모아 둔다. */
@Composable
fun NavIconButton(
    contentDescription: String?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(Res.drawable.arrow_left_icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** 제목 + 뒤로가기만 있는 상세 화면용 Scaffold. 되돌아가는 방식만 [onBack] 으로 갈린다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopBarV1(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = { NavIconButton(backContentDescription, onBack) },
            )
        },
    ) { padding -> content(padding) }
}
