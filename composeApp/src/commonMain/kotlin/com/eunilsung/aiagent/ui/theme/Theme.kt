package com.eunilsung.aiagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Dark.PrimaryMain,
    secondary = AppColors.Secondary,
    background = AppColors.Dark.Bg,
    surface = AppColors.Dark.Bg,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = AppColors.Dark.Text,
    onSurface = AppColors.Dark.Text,
)

private val LightColorScheme = lightColorScheme(
    primary = AppColors.Light.PrimaryMain,
    secondary = AppColors.Secondary,
    background = AppColors.Light.Bg,
    surface = AppColors.Light.Bg,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = AppColors.Light.Text,
    onSurface = AppColors.Light.Text,
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}