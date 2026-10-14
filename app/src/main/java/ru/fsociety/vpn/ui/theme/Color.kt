package ru.fsociety.vpn.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * Тема: тёмная (по умолчанию, как было) / светлая / как в системе (Настройки → Приложение → Тема).
 * Цвета ниже читают её при каждом обращении, поэтому все экраны перерисовываются сами.
 */
object AppTheme {
    const val PREF = "theme"
    /** "dark" / "light" / "system" */
    val mode = mutableStateOf("dark")
    /** Тёмная ли тема системы — выставляет MainActivity (экран пересоздаётся при смене темы системы). */
    @Volatile var systemDark = true
    val dark: Boolean get() = when (mode.value) {
        "light" -> false
        "system" -> systemDark
        else -> true
    }
}

/** Цвет для тёмной / светлой темы. */
fun themed(dark: Long, light: Long): Color = Color(if (AppTheme.dark) dark else light)

// В светлой теме лаймовый темнее (светлый на белом не читается), но насыщенный — иначе выглядел серым
val Accent: Color get() = themed(0xFFC8F135, 0xFF5BA300)
val Accent2: Color get() = themed(0xFFA8D420, 0xFF6DB80C)
val BgDark: Color get() = themed(0xFF0D0D0D, 0xFFF4F5F7)
val Bg2: Color get() = themed(0xFF111111, 0xFFFFFFFF)
val Surface: Color get() = themed(0xFF161616, 0xFFE9EBEF)
val Border: Color get() = themed(0xFF222222, 0xFFD5D8DE)
val TextPrimary: Color get() = themed(0xFFE8E8E8, 0xFF141518)
val TextMuted: Color get() = themed(0xFF888892, 0xFF62656E)
val TextDim: Color get() = themed(0xFF333333, 0xFFC4C7CE)
val ErrorRed: Color get() = themed(0xFFFF4444, 0xFFD32F2F)
