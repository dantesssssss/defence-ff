package com.da4a.smartcity.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ColorScheme = darkColorScheme(
    primary = IosBlue,
    onPrimary = IosLabel,
    background = IosBackground,
    onBackground = IosLabel,
    surface = IosSecondaryBackground,
    onSurface = IosLabel,
    onSurfaceVariant = IosSecondaryLabel,
)

/** Always dark, iOS-style: the demo runs on a black screen that turns green when found. */
@Composable
fun SmartCityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = Typography,
        content = content,
    )
}
