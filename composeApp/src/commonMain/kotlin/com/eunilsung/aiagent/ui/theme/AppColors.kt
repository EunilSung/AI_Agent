package com.eunilsung.aiagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColors {
    val Main  = Color(0xFF3684FA)
    private val Gray_25 = Color(0xFFF8F9FA)
    private val Gray_50 = Color(0xFFF2F4F6)
    private val Gray_100 = Color(0xFFEEEEEE)
    private val Gray_200 = Color(0xFFEAEAEA)
    private val Gray_300 = Color(0xFFD3D3D3)
    private val Gray_400 = Color(0xFFBBBBBB)
    private val Gray_500 = Color(0xFF9B9C9B)
    private val Gray_600 = Color(0xFF888888)
    private val Gray_700 = Color(0xFFA3AAAD)
    private val Gray_800 = Color(0xFF606A70)
    private val Gray_900 = Color(0xFF222222)

    private val Blue_500 = Color(0xFF3684FA)
    private val Blue_700 = Color(0xFF1D5CC9)
    private val Red_500 = Color(0xFFFA5252)
    private val Green_500 = Color(0xFF40C057)
    private val Orange_500 = Color(0xFFFD7E14)
    private val Yellow_500 = Color(0xFFFAE100)


    val Transparent = Color(0x00000000)
    val Primary = Blue_500
    val Secondary = Color(0xFF03DAC6)
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)

    val Divider = Gray_100
    val ClickRipple = Gray_600
    val Border = Gray_300
    val CancelButton = Gray_50
    val Tab = Gray_500
    val LightGray = Gray_300
    val SearchBG = Gray_300

    val Out = Red_500

    val BgChatMe = Yellow_500
    val BgChatEmpathy = Color(0x99FFFFFF)

    val BlueBg = Color(0xFFB8CBDC)


    object Light {
        val PrimaryMain  = Color(0xFF3684FA)
        val PrimaryOver  = Color(0xFF3976D2)
        val PrimaryDown  = Color(0xFF2A5597)
        val PrimaryBg    = Color(0xFFE6EDF5)
        val Text         = Color(0xFF343942)
        val TextSub      = Color(0xFF656E75)
        val TextDisabled = Color(0xFFABB2B5)
        val TextHint = Color(0xFFABB2B5)
        val Line         = Color(0xFFCDD1D4)
        val Gray100Bg    = Color(0xFFE5E8EA)
        val Gray50Bg     = Color(0xFFF5F5F5)
        val Bg           = Color(0xFFFFFFFF)
        val Red          = Color(0xFFF34D4D)
        val LightRed     = Color(0xFFFA7777)
        val RedBg        = Color(0xFFFFD8D8)
        val Orange       = Color(0xFFFC9B29)
        val Yellow       = Color(0xFFFFC852)
        val LightYellow  = Color(0xFFFEFFC6)
        val Green        = Color(0xFF0EC29D)
        val SkyLine      = Color(0xFF95C6F8)
        val SkyBg        = Color(0xFFE8F7FF)
        val Purple       = Color(0xFFAF68E5)
        val Pink         = Color(0xFFF857AE)
    }

    object Dark {
        val PrimaryMain  = Color(0xFF3684FA)
        val PrimaryOver  = Color(0xFF3976D2)
        val PrimaryDown  = Color(0xFF2A5597)
        val PrimaryBg    = Color(0xFF1E2C40)
        val Text         = Color(0xFFF3F5F7)
        val TextSub      = Color(0xFF8B95A1)
        val TextDisabled = Color(0xFF5B6470)
        val TextHint = Color(0xFFABB2B5)
        val Line         = Color(0xFF3A414B)
        val Gray100Bg    = Color(0xFF242931)
        val Gray50Bg     = Color(0xFF1B1F24)
        val Bg           = Color(0xFF121417)
        val Red          = Color(0xFFFF6B6B)
        val LightRed     = Color(0xFFFF8D8D)
        val RedBg        = Color(0xFF402626)
        val Orange       = Color(0xFFFFB347)
        val Yellow       = Color(0xFFFFD76A)
        val LightYellow  = Color(0xFF34301F)
        val Green        = Color(0xFF2DD4AE)
        val SkyLine      = Color(0xFF6EAEEF)
        val SkyBg        = Color(0xFF203244)
        val Purple       = Color(0xFFBB86FC)
        val Pink         = Color(0xFFFF79C6)
    }


    val PrimaryMain: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.PrimaryMain else Light.PrimaryMain
    val PrimaryOver: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.PrimaryOver else Light.PrimaryOver
    val PrimaryDown: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.PrimaryDown else Light.PrimaryDown
    val PrimaryBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.PrimaryBg else Light.PrimaryBg

    val Bg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Bg else Light.Bg
    val BgSub: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Gray100Bg else White
    val BgSub_2: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Gray50Bg else White
    val TextFieldBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Gray50Bg else White
    val DialogCancelBtnBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Line else Light.Gray50Bg
    val UserSelectBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.PrimaryBg else Light.PrimaryBg
    val OrganDepartmentBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Gray100Bg else Light.Gray50Bg
    val CameraBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Bg else Light.Gray100Bg
    val ChatRoomBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Bg else BlueBg
    val ChatOtherBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Gray100Bg else White
    val ChatMeBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.PrimaryMain else Light.PrimaryMain

    val Text: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Text else Light.Text
    val TextSub: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.TextSub else Light.TextSub
    val TextDisabled: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.TextDisabled else Light.TextDisabled
    val TextHint: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.TextHint else Light.TextHint

    val Line: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Line else Light.Line
    val Gray100BgAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Gray100Bg else Light.Gray100Bg
    val Gray50Bg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Gray50Bg else Light.Gray50Bg
    val BgAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Bg else Light.Bg

    val Red: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Red else Light.Red
    val LightRedAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.LightRed else Light.LightRed
    val RedBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.RedBg else Light.RedBg
    val OrangeAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Orange else Light.Orange
    val YellowAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Yellow else Light.Yellow
    val LightYellow: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.LightYellow else Light.LightYellow
    val GreenAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Green else Light.Green
    val SkyLine: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.SkyLine else Light.SkyLine
    val SkyBg: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.SkyBg else Light.SkyBg
    val PurpleAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Purple else Light.Purple
    val PinkAuto: Color
        @Composable get() = if (isSystemInDarkTheme()) Dark.Pink else Light.Pink
}