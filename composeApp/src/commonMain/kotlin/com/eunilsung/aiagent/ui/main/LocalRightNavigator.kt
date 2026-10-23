package com.eunilsung.aiagent.ui.main

import androidx.compose.runtime.compositionLocalOf
import cafe.adriel.voyager.navigator.Navigator

val LocalRightNavigator = compositionLocalOf<Navigator> {
    error("LocalRightNavigator is not provided. Wrap usage with MainContent.")
}

val LocalRightPaneOpen = compositionLocalOf { false }
