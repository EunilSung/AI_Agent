package com.eunilsung.aiagent.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.logo_icon
import com.eunilsung.aiagent.ui.theme.AppColors
import org.jetbrains.compose.resources.painterResource

object EmptyScreen : Screen {
    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize().background(AppColors.Bg), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(Res.drawable.logo_icon),
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = AppColors.LightGray
            )
        }
    }
}
/**
 * 우측 페인을 빈 화면([EmptyScreen])으로 되돌린다 — 화면 닫기의 공통 경로.
 *
 * [Navigator.popUntilRoot] 만 쓰면 스택 크기가 1 일 때 **조용히 아무것도 하지 않는다**(minSize = 1).
 * 프로세스 사망 복원처럼 루트가 [EmptyScreen] 이 아닌 화면으로 굳어버린 상태에서는 뒤로가기와
 * 상단 back 아이콘이 함께 영구히 죽는데, 예외도 로그도 없어 원인을 찾기 어렵다.
 * 루트를 직접 되돌려 그런 상태에서도 반드시 빠져나가게 한다.
 */
fun Navigator.popToEmptyRoot() {
    popUntilRoot()
    if (lastItem !is EmptyScreen) replaceAll(EmptyScreen)
}
