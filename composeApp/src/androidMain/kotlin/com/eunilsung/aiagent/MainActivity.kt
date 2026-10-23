package com.eunilsung.aiagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import cafe.adriel.voyager.navigator.Navigator
import com.eunilsung.aiagent.ui.main.MainScreen
import com.eunilsung.aiagent.ui.theme.AppColors
import com.eunilsung.aiagent.ui.uikit.exposeTestTags

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isTablet) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AppColors.Light.Bg.toArgb(), AppColors.Dark.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.auto(AppColors.Light.Bg.toArgb(), AppColors.Dark.Bg.toArgb()),
        )

        setContent {
            Box(modifier = Modifier.fillMaxSize().exposeTestTags()) {
                Navigator(MainScreen)
            }
        }
    }
}
