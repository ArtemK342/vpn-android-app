package ru.fsociety.vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC8F135),
    onPrimary = Color(0xFF0B0B0C),
    secondary = Color(0xFFA8D420),
    onSecondary = Color(0xFF0B0B0C),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFEDEDEF),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFEDEDEF),
    surfaceVariant = Color(0xFF1A1A1D),
    onSurfaceVariant = Color(0xFF8F8F98),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    outline = Color(0xFF25252A),
    error = Color(0xFFFF5A5A),
    onError = Color(0xFF0B0B0C),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5A7800),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF6DB80C),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F2),
    onBackground = Color(0xFF141416),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141416),
    surfaceVariant = Color(0xFFF1F1EC),
    onSurfaceVariant = Color(0xFF66666E),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F1EC),
    outline = Color(0xFFE0E0D9),
    error = Color(0xFFD93636),
    onError = Color(0xFFFFFFFF),
)

/** Скругления как в Windows: поля 10 dp, карточки 16, диалоги 20 (раньше в Material по умолчанию 4 и 28). */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun VpnappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (AppTheme.dark) DarkScheme else LightScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
