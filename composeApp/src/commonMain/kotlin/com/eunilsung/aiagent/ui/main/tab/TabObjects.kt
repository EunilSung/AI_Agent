package com.eunilsung.aiagent.ui.main.tab

import aiagent.composeapp.generated.resources.Res
import aiagent.composeapp.generated.resources.chat_tab_icon
import aiagent.composeapp.generated.resources.setting_tab_icon
import aiagent.composeapp.generated.resources.tab_chat
import aiagent.composeapp.generated.resources.tab_setting
import androidx.compose.runtime.Composable
import com.eunilsung.aiagent.ui.conversationlist.ConversationListScreen
import com.eunilsung.aiagent.ui.setting.SettingScreen
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal object ChatTab : AppTab {
    override val key: String = "chat"

    override val options: AppTabOptions
        @Composable
        get() = AppTabOptions(
            title = stringResource(Res.string.tab_chat),
            icon = painterResource(Res.drawable.chat_tab_icon)
        )

    @Composable
    override fun Content() {
        ConversationListScreen.Content()
    }
}

internal object SettingTab : AppTab {
    override val key: String = "setting"

    override val options: AppTabOptions
        @Composable
        get() = AppTabOptions(
            title = stringResource(Res.string.tab_setting),
            icon = painterResource(Res.drawable.setting_tab_icon)
        )

    @Composable
    override fun Content() {
        SettingScreen.Content()
    }
}
