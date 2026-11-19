package com.drdisagree.teledrive.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun systemDarkTheme(): Boolean = isSystemInDarkTheme()

// iOS has no wallpaper-based dynamic color, so the app's own scheme is used.
@Composable
internal actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? =
    null

@Composable
internal actual fun PlatformThemeSideEffects(darkTheme: Boolean) {
}
