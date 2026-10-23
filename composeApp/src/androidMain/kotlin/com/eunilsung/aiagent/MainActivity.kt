package com.eunilsung.aiagent

import android.content.Intent
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
import com.eunilsung.aiagent.data.local.IncomingLinks
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

        /** 다시 만들어질 때(savedInstanceState 가 있음)는 처음 연 링크를 이미 처리했으므로 또 넘기지 않는다. */
        if (savedInstanceState == null) offerLink(intent)

        setContent {
            Box(modifier = Modifier.fillMaxSize().exposeTestTags()) {
                Navigator(MainScreen)
            }
        }
    }

    /** singleTask 라 앱이 떠 있을 때 열린 링크는 여기로 온다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerLink(intent)
    }

    /**
     * 링크로 열렸으면 공통 코드로 넘긴다. 최근 앱 목록에서 다시 열면 처음 링크가 그대로 다시 오므로 넘기지 않는다.
     */
    private fun offerLink(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.dataString?.let(IncomingLinks::offer)
    }
}
