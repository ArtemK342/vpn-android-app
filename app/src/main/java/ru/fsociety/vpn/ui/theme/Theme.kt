package ru.fsociety.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FsocietyColorScheme = darkColorScheme(
    primary = Color(0xFFC8F135),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFA8D420),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF888892),
    outline = Color(0xFF222222),
    error = Color(0xFFFF4444),
    onError = Color(0xFF000000),
)

@Composable
fun VpnappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FsocietyColorScheme,
        typography = Typography,
        content = content
    )
}