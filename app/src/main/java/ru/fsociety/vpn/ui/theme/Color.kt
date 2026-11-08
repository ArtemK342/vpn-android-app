package ru.fsociety.vpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Тема: тёмная (по умолчанию, как было) / светлая / как в системе (Настройки → Приложение → Тема).
 * Цвета ниже читают её при каждом обращении, поэтому все экраны перерисовываются сами.
 * Палитра и скругления — как в приложении для Windows (vpn-windows-app, Themes/Dark.xaml, Light.xaml).
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
val Accent: Color get() = themed(0xFFC8F135, 0xFF5A7800)
val Accent2: Color get() = themed(0xFFA8D420, 0xFF6DB80C)
/** Заливка лаймом (кнопки) — в обеих темах настоящий лайм, текст на нём тёмный. */
val AccentFill: Color get() = Color(0xFFC8F135)
val OnAccent: Color get() = Color(0xFF0B0B0C)
/** Полупрозрачный лайм: выбранный пункт, подключённый сервер. */
val AccentSoft: Color get() = themed(0x1CC8F135, 0x33B5DD1F)
val AccentLine: Color get() = themed(0x55C8F135, 0x8C9BBF1A)
val BgDark: Color get() = themed(0xFF0B0B0C, 0xFFF5F5F2)
/** Карточки */
val Bg2: Color get() = themed(0xFF141416, 0xFFFFFFFF)
/** Поля ввода, плашки внутри карточек */
val Surface: Color get() = themed(0xFF1A1A1D, 0xFFF1F1EC)
val Border: Color get() = themed(0xFF25252A, 0xFFE0E0D9)
val Border2: Color get() = themed(0xFF33333A, 0xFFCFCFC7)
val TextPrimary: Color get() = themed(0xFFEDEDEF, 0xFF141416)
val TextMuted: Color get() = themed(0xFF8F8F98, 0xFF66666E)
val TextDim: Color get() = themed(0xFF58585F, 0xFFA2A2A8)
val ErrorRed: Color get() = themed(0xFFFF5A5A, 0xFFD93636)
val ErrorSoft: Color get() = themed(0x1FFF5A5A, 0x17D93636)
val Warn: Color get() = themed(0xFFFFB547, 0xFFB26B00)
val WarnSoft: Color get() = themed(0x1FFFB547, 0x1AB26B00)

/** Кнопки (раньше были прямоугольные) */
val BtnShape = RoundedCornerShape(12.dp)
/** Карточки */
val CardShape = RoundedCornerShape(16.dp)
/** Строки списков, плашки */
val RowShape = RoundedCornerShape(12.dp)
