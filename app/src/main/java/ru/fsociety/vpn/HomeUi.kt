package ru.fsociety.vpn

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fsociety.vpn.ui.theme.*

/*
 * Элементы главного экрана в стиле приложения для Windows: карточки, круглая кнопка питания с кольцом,
 * векторные флаги (эмодзи-флаги на части телефонов показываются буквами), плашки протокола.
 */

/** Карточка: фон Bg2, рамка, скругление 16 dp. */
fun Modifier.card(): Modifier = this
    .clip(CardShape)
    .background(Bg2)
    .border(1.dp, Border, CardShape)

/** Подпись раздела «// ПОДКЛЮЧЕНИЕ» — моноширинная, акцентным цветом. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = modifier)
}

/** Плашка: протокол, LOGS ON. */
@Composable
fun Chip(text: String, color: Color = TextMuted, background: Color = Surface) {
    Text(
        text, color = color, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Переключатель «ВСЕ / ★ ИЗБРАННЫЕ» как сегменты в Windows. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgDark)
            .padding(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                color = if (on) Accent else TextMuted,
                fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            )
        }
    }
}

enum class PowerState { Off, Busy, On, Blocked }

/**
 * Круглая кнопка: свечение и кольцо (лайм — подключено, красное — Kill Switch держит интернет), вращающаяся дуга
 * во время подключения / отключения; внутри значок питания и подпись.
 */
@Composable
fun PowerButton(
    state: PowerState,
    label: String,
    enabled: Boolean,
    size: Dp = 132.dp,
    onClick: () -> Unit
) {
    val on = state == PowerState.On
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(400), label = "glow")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle"
    )
    val ringColor = when (state) {
        PowerState.On -> AccentFill
        PowerState.Blocked -> ErrorRed
        else -> Border
    }
    val glowColor = AccentSoft
    val spinColor = Accent
    val content = when (state) {
        PowerState.On -> OnAccent
        PowerState.Busy -> Accent
        PowerState.Blocked -> ErrorRed
        PowerState.Off -> TextMuted
    }
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            if (glow > 0f) drawCircle(glowColor.copy(alpha = glowColor.alpha * glow), radius = r)
            val ringR = r * 0.9f
            val stroke = 2.dp.toPx()
            drawCircle(ringColor, radius = ringR, style = Stroke(stroke))
            if (state == PowerState.Busy) {
                val d = ringR * 2
                drawArc(
                    spinColor, startAngle = angle - 90f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(center.x - ringR, center.y - ringR), size = Size(d, d),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        val inner = size * 0.72f
        Column(
            modifier = Modifier
                .size(inner)
                .clip(CircleShape)
                .background(if (on) AccentFill else Surface)
                .border(1.dp, if (on) AccentFill else Border2, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.PowerSettingsNew, contentDescription = null,
                tint = content.copy(alpha = if (enabled) 1f else 0.5f),
                modifier = Modifier.size(inner * 0.36f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label, color = content.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 9.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1
            )
        }
    }
}

/** Код страны из названия (как Flag.CountryCode в приложении для Windows). */
fun countryCode(country: String?): String = when ((country ?: "").trim().lowercase()) {
    "finland", "fi" -> "FI"; "germany", "de" -> "DE"; "netherlands", "nl" -> "NL"
    "switzerland", "ch" -> "CH"; "russia", "ru" -> "RU"; "sweden", "se" -> "SE"
    "norway", "no" -> "NO"; "denmark", "dk" -> "DK"; "estonia", "ee" -> "EE"
    "latvia", "lv" -> "LV"; "lithuania", "lt" -> "LT"; "austria", "at" -> "AT"
    "poland", "pl" -> "PL"; "ukraine", "ua" -> "UA"; "france", "fr" -> "FR"
    "italy", "it" -> "IT"; "belgium", "be" -> "BE"; "ireland", "ie" -> "IE"
    "japan", "jp" -> "JP"; "turkey", "türkiye", "tr" -> "TR"
    else -> ""
}

/** Векторный флаг 24×16 со скруглёнными углами; неизвестная страна — значок глобуса, режим РФ — молния. */
@Composable
fun Flag(country: String?, bypass: Boolean = false, modifier: Modifier = Modifier) {
    val code = if (bypass) "" else countryCode(country)
    if (code.isEmpty()) {
        Box(modifier.size(24.dp, 16.dp), contentAlignment = Alignment.Center) {
            Text(if (bypass) "⚡" else "🌍", fontSize = 12.sp)
        }
        return
    }
    val edge = if (AppTheme.dark) Color(0x2EFFFFFF) else Color(0x26000000)
    Canvas(modifier.size(24.dp, 16.dp)) {
        val w = size.width; val h = size.height
        val r = minOf(w, h) * 0.18f
        val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(r, r))) }
        clipPath(clip) { drawFlag(code, w, h) }
        drawRoundRect(edge, cornerRadius = CornerRadius(r, r), style = Stroke(1f),
            topLeft = Offset(0.5f, 0.5f), size = Size(w - 1f, h - 1f))
    }
}

private fun c(rgb: Long) = Color(0xFF000000 or rgb)

private fun DrawScope.drawFlag(code: String, w: Float, h: Float) {
    fun rows(vararg cs: Long) = cs.forEachIndexed { i, col ->
        drawRect(c(col), Offset(0f, h * i / cs.size), Size(w, h / cs.size + 0.5f))
    }
    fun cols(vararg cs: Long) = cs.forEachIndexed { i, col ->
        drawRect(c(col), Offset(w * i / cs.size, 0f), Size(w / cs.size + 0.5f, h))
    }
    fun nordic(bg: Long, cross: Long, inner: Long? = null) {
        drawRect(c(bg), Offset.Zero, Size(w, h))
        drawRect(c(cross), Offset(w * 0.28f, 0f), Size(w * 0.18f, h))
        drawRect(c(cross), Offset(0f, h * 0.37f), Size(w, h * 0.26f))
        if (inner != null) {
            drawRect(c(inner), Offset(w * 0.32f, 0f), Size(w * 0.10f, h))
            drawRect(c(inner), Offset(0f, h * 0.43f), Size(w, h * 0.14f))
        }
    }
    when (code) {
        "FI" -> nordic(0xFFFFFF, 0x003580)
        "SE" -> nordic(0x006AA7, 0xFECC00)
        "NO" -> nordic(0xBA0C2F, 0xFFFFFF, 0x00205B)
        "DK" -> nordic(0xC8102E, 0xFFFFFF)
        "DE" -> rows(0x000000, 0xDD0000, 0xFFCE00)
        "NL" -> rows(0xAE1C28, 0xFFFFFF, 0x21468B)
        "RU" -> rows(0xFFFFFF, 0x0039A6, 0xD52B1E)
        "EE" -> rows(0x0072CE, 0x000000, 0xFFFFFF)
        "LT" -> rows(0xFDB913, 0x006A44, 0xC1272D)
        "AT" -> rows(0xED2939, 0xFFFFFF, 0xED2939)
        "PL" -> rows(0xFFFFFF, 0xDC143C)
        "UA" -> rows(0x0057B7, 0xFFD700)
        "LV" -> {
            drawRect(c(0x9E3039), Offset.Zero, Size(w, h))
            drawRect(c(0xFFFFFF), Offset(0f, h * 0.4f), Size(w, h * 0.2f))
        }
        "FR" -> cols(0x0055A4, 0xFFFFFF, 0xEF4135)
        "IT" -> cols(0x009246, 0xFFFFFF, 0xCE2B37)
        "BE" -> cols(0x000000, 0xFDDA24, 0xEF3340)
        "IE" -> cols(0x169B62, 0xFFFFFF, 0xFF883E)
        "CH" -> {
            drawRect(c(0xDA291C), Offset.Zero, Size(w, h))
            val len = h * 0.6f; val t = h * 0.2f
            drawRect(Color.White, Offset((w - t) / 2, (h - len) / 2), Size(t, len))
            drawRect(Color.White, Offset((w - len) / 2, (h - t) / 2), Size(len, t))
        }
        "JP" -> {
            drawRect(Color.White, Offset.Zero, Size(w, h))
            drawCircle(c(0xBC002D), h * 0.3f, Offset(w / 2, h / 2))
        }
        "TR" -> {
            drawRect(c(0xE30A17), Offset.Zero, Size(w, h))
            drawCircle(Color.White, h * 0.25f, Offset(w * 0.40f, h / 2))
            drawCircle(c(0xE30A17), h * 0.2f, Offset(w * 0.45f, h / 2))
        }
    }
}
