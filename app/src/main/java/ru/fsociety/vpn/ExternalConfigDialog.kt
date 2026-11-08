package ru.fsociety.vpn

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.fsociety.vpn.ui.theme.*

/**
 * Долгое нажатие на сервер → «Для другого приложения»: строка для AmneziaVPN (AmneziaWG) или строка и
 * ссылка-подписка для Happ / v2rayNG / Hiddify (VLESS). То же, что вкладка «Другое приложение» в кабинете
 * на сайте; удалить устройства и сбросить доступ — там же. Без подписки — только пояснение.
 *
 * Ключ AmneziaWG создаёт сервер и отдаёт один раз: строку держим только пока открыто окно.
 */
@Composable
fun ExternalConfigDialog(
    server: ServerResponse,
    ext: ExternalOverview,
    lang: String,
    onUpdated: (ExternalOverview) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAwg = ext.isAwg(server.id)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var awgKey by remember { mutableStateOf<String?>(null) }
    val vlessLine = ext.vless_lines[server.id]

    fun bearer() = "Bearer ${TokenStore.access.value}"

    fun refresh() = scope.launch {
        try { onUpdated(ApiClient.service.getExternal(bearer())) } catch (e: Exception) { AppErrors.log("external refresh", e) }
    }

    fun getAwgKey() = scope.launch {
        busy = true; error = null
        try {
            awgKey = ApiClient.service.createExternalAwg(bearer(), server.id).amnezia_key
            refresh()
        } catch (e: Exception) {
            AppErrors.log("external awg", e)
            error = AppErrors.text(context, e)
        } finally { busy = false }
    }

    fun getVlessAccess() = scope.launch {
        busy = true; error = null
        try {
            ApiClient.service.createExternalSub(bearer())
            onUpdated(ApiClient.service.getExternal(bearer()))
        } catch (e: Exception) {
            AppErrors.log("external sub", e)
            error = AppErrors.text(context, e)
        } finally { busy = false }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg2,
        title = {
            Column {
                Text(stringResource(R.string.ext_title), color = Accent, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(6.dp))
                Text(server.displayName(lang) + if (isAwg) " · AmneziaWG" else " · VLESS",
                    color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    !ext.entitled -> ExtNote(stringResource(R.string.ext_need_sub), TextMuted)
                    isAwg -> {
                        ExtNote(stringResource(R.string.ext_awg_desc), TextMuted)
                        val key = awgKey
                        if (key == null) {
                            ExtButton(stringResource(R.string.ext_awg_get), enabled = !busy, accent = true) { getAwgKey() }
                        } else {
                            ExtNote(stringResource(R.string.ext_awg_ready), Accent)
                            ExtSecret(stringResource(R.string.ext_awg_line), key, context)
                        }
                    }
                    else -> {
                        ExtNote(stringResource(R.string.ext_vless_desc), TextMuted)
                        if (vlessLine == null || ext.sub_url == null) {
                            ExtButton(stringResource(R.string.ext_vless_get), enabled = !busy, accent = true) { getVlessAccess() }
                        } else {
                            ExtSecret(stringResource(R.string.ext_vless_line), vlessLine, context)
                            Spacer(Modifier.height(12.dp))
                            ExtSecret(stringResource(R.string.ext_vless_sub), ext.sub_url, context)
                        }
                    }
                }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = ErrorRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                if (ext.entitled) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.ext_slots, ext.slots.used, ext.slots.max) + " " +
                        stringResource(R.string.ext_manage), color = TextMuted, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ext_close), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    )
}

@Composable
private fun ExtNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ExtButton(text: String, enabled: Boolean, accent: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = BtnShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (accent) Accent else Border)
    ) {
        Text(text, color = if (accent) Accent else TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/** Строка-секрет: выделяется, копируется (без превью в буфере на Android 13+) и отправляется в другое приложение. */
@Composable
private fun ExtSecret(label: String, value: String, context: Context) {
    Text(label.uppercase(), color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier.fillMaxWidth().heightIn(max = 110.dp).background(BgDark).border(1.dp, Border)
            .verticalScroll(rememberScrollState()).padding(10.dp)
    ) {
        SelectionContainer {
            Text(value, color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val copied = stringResource(R.string.ext_copied)
        OutlinedButton(
            onClick = {
                copySecret(context, "fsociety", value)
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            shape = BtnShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, Accent)
        ) { Text(stringResource(R.string.ext_copy), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        OutlinedButton(
            onClick = {
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }, null))
            },
            shape = BtnShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, Border)
        ) { Text(stringResource(R.string.ext_share), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
    }
}
