package com.eunilsung.aiagent.ui.main

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 키보드 표시 여부와 무관하게 안정적인 하단 인셋(내비바/홈인디케이터). */
@Composable
expect fun keyboardBottomInset(): Dp

/** 지금 키보드 높이. 창은 키보드에 맞춰 줄지 않으므로(Android `adjustNothing`, iOS `OnFocusBehavior.DoNothing`) 화면이 직접 비켜 준다. */
@Composable
fun rememberImeHeight(): Dp {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    return with(density) { imeBottom.toDp() }
}

/** 패널이 올라오는 시간. */
private const val PanelOpenMillis = 220

/** 패널이 내려가는 시간 — 올라올 때보다 길게 잡는다. */
private const val PanelCloseMillis = 450

/**
 * 키보드 높이가 이만큼 그대로면 키보드가 멈춘 것으로 본다.
 *
 * 키보드가 올라오는 도중에 이보다 오래 멈추는 일은 없고, 붙든 높이보다 낮은 키보드가 멈췄을 때 하단이 그 높이로 내려가기까지의
 * 지연이기도 하다.
 */
private const val ImeSettleMillis = 250L

/**
 * 화면 맨 아래에서 키보드 높이만큼 자리를 차지해 위의 내용을 밀어 올린다. Talk 의 KeyBoardPane 과 같다 — AI Agent 에는 이모티콘
 * 패널이 없어 그 부분만 뺐다.
 *
 * @param fillIdleBottomInset 키보드가 없을 때도 내비게이션 바(홈 인디케이터) 높이만큼은 비워 둔다.
 */
@Composable
fun KeyBoardPane(
    modifier: Modifier = Modifier,
    isKeyboardVisible: Boolean,
    imeHeight: Dp,
    fillIdleBottomInset: Boolean = false,
) {
    val bottomInset = keyboardBottomInset()
    val idleFloor = if (fillIdleBottomInset) bottomInset else 0.dp

    /** 하단 높이는 목표값 하나로 정하고 그 값만 애니메이션한다. */
    val keyboardDriven = isKeyboardVisible

    /**
     * 키보드가 높이를 넘겨받기 직전까지 하단이 잡고 있던 높이. 키보드가 올라오는 동안 **이 아래로는 줄이지 않는다.**
     * 키보드가 따라잡거나, 내려가기 시작하거나, 이 높이보다 낮게 멈추면([ImeSettleMillis]) 놓는다.
     *
     * "올라오는 중" 을 직전 컴포지션의 키보드 높이와 비교해 정하면, 키보드 높이는 그대로인데 재구성만 되는 프레임이 끼었을 때
     * 하단이 그 순간의 키보드 높이(거의 0)로 떨어졌다가 다시 올라온다. iOS 는 키보드 인셋 갱신이 렌더 프레임과 1:1 로 맞지 않아
     * 이 정지 프레임이 자주 낀다.
     */
    var holdHeight by remember { mutableStateOf(0.dp) }

    /** 키보드가 내려가기 시작했는지 보려고 직전 키보드 높이를 둔다 — 컴포지션에서는 읽지 않는다. */
    var prevIme by remember { mutableStateOf(imeHeight) }

    val target = if (isKeyboardVisible) maxOf(imeHeight, holdHeight, idleFloor) else idleFloor

    /** 키보드가 [holdHeight] 보다 낮은 곳에서 멈췄을 때만 붙든 높이를 놓는다. 놓지 않으면 입력바가 키보드 위로 떠서 빈 틈이 남는다. */
    val holdingAboveIme = keyboardDriven && holdHeight > imeHeight
    LaunchedEffect(holdingAboveIme, imeHeight) {
        if (!holdingAboveIme) return@LaunchedEffect
        delay(ImeSettleMillis)
        holdHeight = 0.dp
    }

    /** 키보드가 높이를 정하는 동안에는 애니메이션을 걸지 않는다. */
    var prevTarget by remember { mutableStateOf(target) }
    val spec: AnimationSpec<Dp> = if (keyboardDriven) {
        snap()
    } else {
        /** 줄어들 때만 느리게 — 늘어날 때까지 늘어지면 키보드보다 굼떠 보인다. */
        tween(durationMillis = if (target < prevTarget) PanelCloseMillis else PanelOpenMillis)
    }
    val animatedH by animateDpAsState(targetValue = target, animationSpec = spec, label = "kbPane")

    /** 키보드를 따라갈 때는 애니메이션 값을 거치지 않고 목표를 바로 쓴다. */
    val h = if (keyboardDriven) target else animatedH

    SideEffect {
        holdHeight = when {
            !keyboardDriven -> h
            imeHeight < prevIme -> 0.dp
            imeHeight >= holdHeight -> 0.dp
            else -> holdHeight
        }
        prevIme = imeHeight
        prevTarget = target
    }

    if (h <= 0.dp) return

    Spacer(modifier = modifier.fillMaxWidth().height(h))
}
