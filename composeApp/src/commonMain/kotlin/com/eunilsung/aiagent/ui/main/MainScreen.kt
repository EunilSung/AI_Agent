package com.eunilsung.aiagent.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import com.eunilsung.aiagent.ui.login.LoginScreen
import com.eunilsung.aiagent.ui.main.tab.ChatTab
import com.eunilsung.aiagent.ui.main.tab.CurrentAppTab
import com.eunilsung.aiagent.ui.main.tab.SettingTab
import com.eunilsung.aiagent.ui.main.tab.TabItem
import com.eunilsung.aiagent.ui.main.tab.rememberAppTabState
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.theme.AppTheme
import com.eunilsung.aiagent.ui.uikit.BackHandler
import com.eunilsung.aiagent.ui.uikit.line.LineDivider
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 앱 첫 화면. 구조는 Talk 의 MainScreen 과 같다 — 왼쪽은 하단 탭, 오른쪽은 우측 네비게이터([LocalRightNavigator])가 연
 * 대화방이다. 태블릿은 둘을 나란히 두고, 폰은 대화방을 위에 덮어 밀어서 닫는다. 로그인하지 않았으면 로그인 화면을 덮는다.
 */
object MainScreen : Screen {
    @Composable
    override fun Content() {
        val viewModel: MainViewModel = koinViewModel()
        val uiState by viewModel.uiState.collectAsState()

        AppTheme {
            Navigator(EmptyScreen) { rightNavigator ->
                AppHosts(rightNavigator = rightNavigator) {
                    LaunchedEffect(viewModel) {
                        viewModel.events.collect { event ->
                            when (event) {
                                MainEvent.LoggedOut -> rightNavigator.popToEmptyRoot()
                            }
                        }
                    }
                    MainContent(isLoggedIn = uiState.isLoggedIn)
                    if (!uiState.isLoggedIn) LoginScreen()
                }
            }
        }
    }
}

@Composable
private fun MainContent(isLoggedIn: Boolean) {
    val imeHeight = rememberImeHeight()
    val isKeyboardVisible = imeHeight > 0.dp
    val rightNavigator = LocalRightNavigator.current
    val rightPaneOpen = rightNavigator.lastItem !is EmptyScreen

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 600.dp
        androidx.compose.runtime.CompositionLocalProvider(LocalRightPaneOpen provides rightPaneOpen) {
            if (isTablet) {
                Tablet(rightNavigator, isKeyboardVisible, imeHeight, isLoggedIn)
            } else {
                Phone(rightNavigator, isKeyboardVisible, imeHeight, isLoggedIn)
            }
        }
    }
}

/** 탭 화면과 그 아래 키보드 자리. 키보드가 올라오면 [KeyBoardPane] 이 그만큼 탭 화면을 밀어 올린다. */
@Composable
private fun LeftPaneWithKeyboard(isKeyboardVisible: Boolean, imeHeight: Dp, isLoggedIn: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) { LeftPane(isLoggedIn) }
        KeyBoardPane(
            modifier = Modifier.background(AppColors.Gray50Bg),
            isKeyboardVisible = isKeyboardVisible,
            imeHeight = imeHeight,
            fillIdleBottomInset = true,
        )
    }
}

@Composable
private fun Tablet(rightNavigator: Navigator, isKeyboardVisible: Boolean, imeHeight: Dp, isLoggedIn: Boolean) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { LeftPaneWithKeyboard(isKeyboardVisible, imeHeight, isLoggedIn) }
        LineDivider(modifier = Modifier.width(1.dp).fillMaxHeight(), isWidth = true)
        Box(modifier = Modifier.weight(1.2f).fillMaxHeight().clipToBounds()) {
            AnimatedContent(
                targetState = rightNavigator.lastItem,
                transitionSpec = {
                    val (enter, exit) = if (rightNavigator.lastEvent == StackEvent.Pop) {
                        (slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300))) to
                            (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)))
                    } else {
                        (slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))) to
                            (slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(300)))
                    }
                    enter togetherWith exit
                }
            ) { screen ->
                screen.Content()
            }
        }
    }
}

/**
 * 하단 탭과 탭 본문. 아래쪽 인셋(내비게이션 바·키보드)은 밑에 붙는 [KeyBoardPane] 이 맡는다.
 * 탭 본문을 누르면 키보드를 내린다.
 *
 * @param isLoggedIn 바뀌면 탭 선택을 처음으로 되돌린다.
 */
@Composable
private fun LeftPane(isLoggedIn: Boolean) {
    val tabList = listOf(ChatTab, SettingTab)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    key(isLoggedIn) {
        val tabState = rememberAppTabState(tabList, ChatTab)
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column(modifier = Modifier.background(AppColors.Gray50Bg)) {
                    LineDivider(modifier = Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth().height(70.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (tab in tabList) {
                            TabItem(
                                tab = tab,
                                modifier = Modifier.weight(1f),
                                isSelected = tabState.current == tab,
                                onClick = { tabState.current = tab }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                CurrentAppTab(tabState)
            }
        }
    }
}

private const val OverlayParallaxFraction = 0.3f

/** 폰 — 대화방을 탭 위에 덮고, 왼쪽에서 오른쪽으로 밀면 닫는다. Talk 의 Phone 과 같은 동작이다. */
@Composable
private fun Phone(rightNavigator: Navigator, isKeyboardVisible: Boolean, imeHeight: Dp, isLoggedIn: Boolean) {
    val current = rightNavigator.lastItem
    val hasOverlay = current !is EmptyScreen
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    if (hasOverlay) {
        BackHandler { rightNavigator.popToEmptyRoot() }
    }

    /** 닫히는 동안에도 덮었던 화면을 그려야 미끄러져 나가는 모습이 보인다. */
    var stickyOverlay by remember { mutableStateOf<Screen?>(null) }
    SideEffect {
        if (current !is EmptyScreen) stickyOverlay = current
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val swipeStartThresholdPx = with(density) { 60.dp.toPx() }
        val offsetX = remember { Animatable(screenWidthPx) }

        LaunchedEffect(hasOverlay, screenWidthPx) {
            offsetX.animateTo(if (hasOverlay) 0f else screenWidthPx, tween(durationMillis = 300))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    val bg = -OverlayParallaxFraction * (screenWidthPx - offsetX.value)
                    IntOffset(bg.roundToInt(), 0)
                }
        ) {
            LeftPaneWithKeyboard(isKeyboardVisible, imeHeight, isLoggedIn)
        }

        val overlayVisible by remember(offsetX, screenWidthPx) {
            derivedStateOf { offsetX.value < screenWidthPx }
        }
        if (overlayVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(hasOverlay, screenWidthPx, swipeStartThresholdPx) {
                        if (!hasOverlay) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPointerInputChange(down)

                            var dragStarted = false
                            while (!dragStarted) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                                velocityTracker.addPointerInputChange(change)
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (abs(dy) > swipeStartThresholdPx && abs(dy) > abs(dx)) return@awaitEachGesture
                                if (dx >= swipeStartThresholdPx) {
                                    dragStarted = true
                                    change.consume()
                                    scope.launch { offsetX.snapTo((offsetX.value + dx).coerceAtLeast(0f)) }
                                }
                            }

                            horizontalDrag(down.id) { change ->
                                velocityTracker.addPointerInputChange(change)
                                val delta = change.positionChange().x
                                scope.launch { offsetX.snapTo((offsetX.value + delta).coerceAtLeast(0f)) }
                                change.consume()
                            }

                            val velocityX = velocityTracker.calculateVelocity().x
                            val shouldDismiss = offsetX.value > screenWidthPx * 0.3f || velocityX > 1500f
                            scope.launch {
                                if (shouldDismiss) {
                                    offsetX.animateTo(screenWidthPx, tween(durationMillis = 200))
                                    rightNavigator.popToEmptyRoot()
                                } else {
                                    offsetX.animateTo(0f, spring())
                                }
                            }
                        }
                    }
            ) {
                stickyOverlay?.Content()
            }
        }
    }
}
