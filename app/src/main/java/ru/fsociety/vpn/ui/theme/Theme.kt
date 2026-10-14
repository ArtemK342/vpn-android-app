package ru.fsociety.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
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

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5BA300),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF6DB80C),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF141518),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141518),
    surfaceVariant = Color(0xFFE9EBEF),
    onSurfaceVariant = Color(0xFF62656E),
    outline = Color(0xFFD5D8DE),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun VpnappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (AppTheme.dark) DarkScheme else LightScheme,
        typography = Typography,
        content = content
    )
}
