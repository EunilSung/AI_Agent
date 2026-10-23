package com.eunilsung.aiagent.ui.main.tab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.painter.Painter

/**
 * 탭 정의 — Voyager 의 `Tab`(= `Screen`) 을 대체한다.
 *
 * Voyager 탭은 화면(Screen)이라 `AndroidScreenLifecycleOwner` 가 붙는데, 그 라이프사이클 소유자가
 * 컴포지션 해제와 네비게이터 폐기 순서가 뒤집힐 때 터진다
 * (`State is 'DESTROYED' and cannot be moved to 'STARTED'`).
 *
 * 우리 탭이 하는 일은 "선택된 하나를 그리는 것"뿐이라 네비게이터가 필요 없다. 라이프사이클 소유자를
 * 걷어내면 그 크래시 경로가 통째로 사라진다.
 */
interface AppTab {
    /** 상태 보존 키 — 탭 전환 시 [rememberSaveable] 상태를 되살리는 기준이다. 중복되면 안 된다. */
    val key: String

    val options: AppTabOptions
        @Composable get

    @Composable
    fun Content()
}

/** 하단 탭바가 그릴 표시 정보. 아이콘이 없는 탭(텍스트 탭)은 [icon] 이 null. */
data class AppTabOptions(
    val title: String,
    val icon: Painter? = null,
)

/** 현재 선택된 탭을 들고 있는 상태 홀더. */
class AppTabState internal constructor(
    private val selectedKey: MutableState<String>,
    private val tabs: List<AppTab>,
) {
    var current: AppTab
        get() = tabs.firstOrNull { it.key == selectedKey.value } ?: tabs.first()
        set(value) { selectedKey.value = value.key }
}

/**
 * 탭 선택 상태를 만든다. 선택은 [rememberSaveable] 이라 프로세스 사망 후에도 복원된다.
 */
@Composable
fun rememberAppTabState(tabs: List<AppTab>, initial: AppTab = tabs.first()): AppTabState {
    val selectedKey = rememberSaveable { mutableStateOf(initial.key) }
    return AppTabState(selectedKey, tabs)
}

/**
 * 선택된 탭의 내용을 그린다.
 *
 * 탭마다 [androidx.compose.runtime.saveable.SaveableStateHolder] 슬롯을 나눠 줘서, 다른 탭을 다녀와도
 * 스크롤 위치 같은 `rememberSaveable` 상태가 유지된다 — Voyager `CurrentTab()` 이 하던 것과 같다.
 */
@Composable
fun CurrentAppTab(state: AppTabState) {
    val holder = rememberSaveableStateHolder()
    val tab = state.current
    holder.SaveableStateProvider(tab.key) {
        tab.Content()
    }
}
