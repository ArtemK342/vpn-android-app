package ru.fsociety.vpn

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.fsociety.vpn.ui.theme.*

@Composable
fun SettingsScreen(
    token: String, user: UserResponse?, subscription: SubscriptionResponse?,
    isLoading: Boolean, onLogout: () -> Unit,
    backgroundMode: Boolean, onBackgroundModeChange: (Boolean) -> Unit,
    killSwitch: Boolean, onKillSwitchChange: (Boolean) -> Unit,
    autoReconnect: Boolean, onAutoReconnectChange: (Boolean) -> Unit,
    language: String, onLanguageChange: (String) -> Unit,
    onReloadAccount: () -> Unit = {}  // аккаунт не загрузился — повторить по нажатию
) {
    var currentScreen by remember { mutableStateOf("main") }

    BackHandler(enabled = currentScreen != "main") {
        currentScreen = "main"
    }

    when (currentScreen) {
        "account" -> AccountTab(token = token, user = user, subscription = subscription, isLoading = isLoading, onLogout = onLogout, onBack = { currentScreen = "main" })
        "support" -> TicketsTab(token = token, onBack = { currentScreen = "main" })
        "app_settings" -> AppSettingsTab(
            killSwitch = killSwitch, onKillSwitchChange = onKillSwitchChange,
            autoReconnect = autoReconnect, onAutoReconnectChange = onAutoReconnectChange,
            backgroundMode = backgroundMode, onBackgroundModeChange = onBackgroundModeChange,
            language = language,
            onBack = { currentScreen = "main" },
            onAutoReconnect = { currentScreen = "autoreconnect" },
            onKillSwitch = { currentScreen = "killswitch" },
            onBackground = { currentScreen = "background" },
            onLanguage = { currentScreen = "language" },
            onProtocols = { currentScreen = "protocols" },
            onLogging = { currentScreen = "logging" },
            onOpen = { currentScreen = it }
        )
        "background" -> BackgroundModeTab(
            backgroundMode = backgroundMode,
            onBackgroundModeChange = onBackgroundModeChange,
            onBack = { currentScreen = "app_settings" }
        )
        "killswitch" -> KillSwitchTab(
            killSwitch = killSwitch,
            onKillSwitchChange = onKillSwitchChange,
            onBack = { currentScreen = "app_settings" }
        )
        "autoreconnect" -> AutoReconnectTab(
            autoReconnect = autoReconnect,
            onAutoReconnectChange = onAutoReconnectChange,
            onBack = { currentScreen = "app_settings" }
        )
        "language" -> LanguageTab(
            language = language,
            onLanguageChange = onLanguageChange,
            onBack = { currentScreen = "app_settings" }
        )
        "protocols" -> ProtocolSettingsTab(
            onBack = { currentScreen = "app_settings" },
            onHysteria = { currentScreen = "protocol_hysteria2" }
        )
        "protocol_hysteria2" -> Hysteria2SettingsTab(onBack = { currentScreen = "protocols" })
        "logging" -> LoggingTab(onBack = { currentScreen = "app_settings" })
        "theme" -> ThemeTab(onBack = { currentScreen = "app_settings" })
        "dns" -> DnsTab(onBack = { currentScreen = "app_settings" })
        "lan" -> SwitchOptionTab(VpnOptions.PREF_LAN, R.string.lan_title, R.string.lan_desc, R.string.lan_on_desc, R.string.lan_off_desc,
            R.string.lan_note, onBack = { currentScreen = "app_settings" })
        "autostart" -> SwitchOptionTab(VpnOptions.PREF_AUTOSTART, R.string.autostart_title, R.string.autostart_desc,
            R.string.autostart_on_desc, R.string.autostart_off_desc, R.string.autostart_note, onBack = { currentScreen = "app_settings" })
        "update" -> UpdateTab(onBack = { currentScreen = "main" })
        "subscription" -> ComingSoonTab(title = stringResource(R.string.settings_subscription), onBack = { currentScreen = "main" })
        "payments" -> ComingSoonTab(title = stringResource(R.string.settings_payments), onBack = { currentScreen = "main" })
        else -> Column(modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.statusBarsPadding().padding(24.dp)) {
                Text(stringResource(R.string.settings_section), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.settings_title), color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_account),
                // «Загрузка…» только пока грузится; если не загрузилось — честно сказать и дать повторить
                subtitle = user?.email ?: stringResource(
                    if (isLoading) R.string.settings_loading else R.string.settings_account_load_failed),
                onClick = { if (user == null && !isLoading) onReloadAccount(); currentScreen = "account" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.SupportAgent, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_support),
                subtitle = stringResource(R.string.settings_support_sub),
                onClick = { currentScreen = "support" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_app),
                subtitle = stringResource(R.string.settings_app_sub),
                onClick = { currentScreen = "app_settings" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_subscription),
                subtitle = stringResource(R.string.settings_in_dev),
                onClick = { currentScreen = "subscription" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_payments),
                subtitle = stringResource(R.string.settings_in_dev),
                onClick = { currentScreen = "payments" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_version),
                subtitle = "${BuildConfig.VERSION_NAME}",
                onClick = { currentScreen = "update" }
            )
        }
    }
}

@Composable
fun SettingsMenuItem(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
    }
    HorizontalDivider(color = Border, thickness = 1.dp)
}

@Composable
fun SettingsRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, Border).padding(16.dp)
    ) {
        Text(label.uppercase(), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
fun AccountTab(token: String, user: UserResponse?, subscription: SubscriptionResponse?, isLoading: Boolean, onLogout: () -> Unit, onBack: () -> Unit) {
    val errCtx = LocalContext.current
    BackHandler { onBack() }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPassword2 by remember { mutableStateOf("") }
    var pwError by remember { mutableStateOf("") }
    var pwSuccess by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Выход — с подтверждением. Анониму показываем его код: без него войти в аккаунт больше нельзя
    if (confirmLogout) {
        val isAnon = user?.account_type == "numeric"
        val code = user?.email.orEmpty()
        var copied by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = Bg2,
            title = { Text(stringResource(R.string.logout_confirm_title), color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(if (isAnon) R.string.logout_confirm_anon else R.string.logout_confirm_text),
                        color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (isAnon && code.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(code, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable { copySecret(errCtx, "fsociety code", code); copied = true })
                        Text(stringResource(if (copied) R.string.anon_code_copied else R.string.anon_code_tap_to_copy),
                            color = if (copied) Accent else TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) {
                    Text(stringResource(R.string.account_btn_logout), color = ErrorRed, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text(stringResource(R.string.tickets_cancel), color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    val strErrorEmpty    = stringResource(R.string.account_error_empty)
    val strErrorMismatch = stringResource(R.string.account_error_mismatch)
    val strErrorLength   = stringResource(R.string.account_error_length)
    val strPasswordChanged = stringResource(R.string.account_password_changed)
    val strErrorWrongPw  = stringResource(R.string.account_error_wrong_password)

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.account_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                }
            } else {
                // Для анонимов в поле лежит 16-значный код, для логин-аккаунтов — логин (раньше всё «Email»)
                SettingsRow(
                    label = stringResource(when (user?.account_type) {
                        "numeric"  -> R.string.account_label_code
                        "username" -> R.string.account_label_login
                        else       -> R.string.account_label_email
                    }),
                    value = user?.email ?: "—"
                )
                SettingsRow(
                    label = stringResource(R.string.account_label_subscription),
                    value = if (subscription?.is_active == true)
                        stringResource(R.string.account_sub_active,
                            when (subscription.plan) {
                                "month" -> stringResource(R.string.plan_month)
                                "year"  -> stringResource(R.string.plan_year)
                                else    -> subscription.plan ?: ""
                            },
                            subscription.expires_at?.take(10) ?: "")
                    else stringResource(R.string.account_sub_none)
                )
                if (user?.role != "user" && user?.role != null) {
                    SettingsRow(label = stringResource(R.string.account_label_role), value = user.role)
                }

                Spacer(modifier = Modifier.height(24.dp))

                val isAnonymous = user?.account_type == "numeric"
                if (!isAnonymous) {
                    Text(stringResource(R.string.account_change_password), color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = oldPassword, onValueChange = { oldPassword = it; pwError = ""; pwSuccess = "" },
                        label = { Text(stringResource(R.string.account_field_old_password), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !isSaving,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPassword, onValueChange = { newPassword = it; pwError = ""; pwSuccess = "" },
                        label = { Text(stringResource(R.string.account_field_new_password), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !isSaving,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPassword2, onValueChange = { newPassword2 = it; pwError = ""; pwSuccess = "" },
                        label = { Text(stringResource(R.string.account_field_repeat_password), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !isSaving,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                    )
                    if (pwError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(pwError, color = ErrorRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (pwSuccess.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(pwSuccess, color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (oldPassword.isBlank() || newPassword.isBlank() || newPassword2.isBlank()) {
                                pwError = strErrorEmpty; return@Button
                            }
                            if (newPassword != newPassword2) {
                                pwError = strErrorMismatch; return@Button
                            }
                            if (newPassword.length < 8) {
                                pwError = strErrorLength; return@Button
                            }
                            scope.launch {
                                isSaving = true; pwError = ""
                                try {
                                    val resp = ApiClient.service.changePassword(
                                        "Bearer $token",
                                        ChangePasswordRequest(oldPassword, newPassword)
                                    )
                                    when {
                                        resp.isSuccessful -> {
                                            pwSuccess = strPasswordChanged
                                            oldPassword = ""; newPassword = ""; newPassword2 = ""
                                        }
                                        // 400 — неверный текущий пароль; остальное (сессия, лимит, сервер)
                                        // раньше тоже показывалось как «неверный пароль»
                                        resp.code() == 400 -> pwError = strErrorWrongPw
                                        else -> pwError = AppErrors.httpText(errCtx, resp.code(),
                                            try { resp.errorBody()?.string() } catch (_: Exception) { null })
                                    }
                                } catch (e: Exception) {
                                    AppErrors.log("change password", e)
                                    pwError = AppErrors.text(errCtx, e)
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                    ) {
                        if (isSaving) CircularProgressIndicator(color = BgDark, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.account_btn_change_password), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { confirmLogout = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = ErrorRed),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed)
                ) {
                    Text(stringResource(R.string.account_btn_logout), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { confirmDelete = true }, enabled = !isDeleting, modifier = Modifier.fillMaxWidth()) {
                    if (isDeleting) CircularProgressIndicator(color = ErrorRed, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.account_btn_delete), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }

    // Удаление — сразу на сервере, без писем и ожидания; подтверждение одним нажатием
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Bg2,
            title = { Text(stringResource(R.string.account_delete_title), color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.account_delete_text), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    isDeleting = true
                    scope.launch {
                        try {
                            val resp = ApiClient.service.deleteMe("Bearer $token")
                            if (resp.isSuccessful) onLogout()
                            else AppErrors.toast(errCtx, "delete account", retrofit2.HttpException(resp))
                        } catch (e: Exception) {
                            AppErrors.toast(errCtx, "delete account", e)
                        } finally { isDeleting = false }
                    }
                }) {
                    Text(stringResource(R.string.account_delete_confirm), color = ErrorRed, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.tickets_cancel), color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

/** Время с сервера (UTC, ISO) → местное «гггг-ММ-дд ЧЧ:мм» (раньше показывалось UTC как есть). */
fun formatMsgTime(raw: String): String = try {
    val zone = java.time.ZoneId.systemDefault()
    val local = try {
        java.time.OffsetDateTime.parse(raw).atZoneSameInstant(zone)
    } catch (_: Exception) {
        java.time.LocalDateTime.parse(raw).atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(zone)
    }
    local.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
} catch (_: Exception) {
    raw.replace("T", " ").take(16)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TicketsTab(token: String, onBack: () -> Unit) {
    val errCtx = LocalContext.current
    var tickets by remember { mutableStateOf<List<TicketResponse>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var selectedTicket by remember { mutableStateOf<TicketDetailResponse?>(null) }
    // Тикет открыт сразу по нажатию, сообщения ещё грузятся
    var loadingMessages by remember { mutableStateOf(false) }
    var newSubject by remember { mutableStateOf("") }
    var newMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var ticketToDelete by remember { mutableStateOf<TicketResponse?>(null) }
    var limitText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler {
        if (selectedTicket != null) selectedTicket = null
        else onBack()
    }

    // Открытый тикет сам подгружает новые ответы поддержки (раньше — только после повторного открытия)
    val openTicketId = selectedTicket?.ticket?.id
    LaunchedEffect(openTicketId) {
        if (openTicketId == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(20_000)
            try {
                val fresh = ApiClient.service.getTicket("Bearer $token", openTicketId)
                if (selectedTicket?.ticket?.id == openTicketId) selectedTicket = fresh
            } catch (e: Exception) { AppErrors.log("refresh ticket", e) }
        }
    }

    LaunchedEffect(token) {
        try { tickets = ApiClient.service.getTickets("Bearer $token") }
        catch (e: Exception) { AppErrors.toast(errCtx, "load tickets", e) }
        finally { isLoading = false }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newSubject = "" },
            containerColor = Bg2,
            title = { Text(stringResource(R.string.tickets_new_title), color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newSubject, onValueChange = { newSubject = it },
                    label = { Text(stringResource(R.string.tickets_new_subject), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                )
            },
            confirmButton = {
                TextButton(enabled = !isSending, onClick = {
                    // Двойное нажатие создавало два тикета (и съедало лимит 2 в сутки)
                    if (newSubject.isBlank() || isSending) return@TextButton
                    isSending = true
                    scope.launch {
                        isSending = true
                        try {
                            val t = ApiClient.service.createTicket("Bearer $token", TicketCreateRequest(newSubject))
                            tickets = listOf(t) + tickets
                            showCreateDialog = false; newSubject = ""
                        } catch (e: retrofit2.HttpException) {
                            if (e.code() == 429) {
                                // Лимит сервера — 2 тикета в сутки; он сам пишет, когда можно следующий (по-русски)
                                val detail = AppErrors.readBody(e)?.let {
                                    try { org.json.JSONObject(it).optString("detail") } catch (_: Exception) { null }
                                }
                                val isEn = errCtx.resources.configuration.locales[0].language == "en"
                                limitText = detail?.takeIf { !isEn && it.isNotBlank() }
                                showCreateDialog = false
                                showLimitDialog = true
                            } else AppErrors.toast(errCtx, "create ticket", e)
                        } catch (e: Exception) { AppErrors.toast(errCtx, "create ticket", e) }
                        finally { isSending = false }
                    }
                }) { Text(stringResource(R.string.tickets_btn_create), color = Accent, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newSubject = "" }) {
                    Text(stringResource(R.string.tickets_cancel), color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    if (showLimitDialog) {
        AlertDialog(
            onDismissRequest = { showLimitDialog = false },
            containerColor = Bg2,
            title = { Text(stringResource(R.string.tickets_limit_title), color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = { Text(limitText ?: stringResource(R.string.tickets_limit_msg), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = { showLimitDialog = false }) {
                    Text(stringResource(R.string.tickets_limit_ok), color = Accent, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    ticketToDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { ticketToDelete = null },
            containerColor = Bg2,
            title = { Text(stringResource(R.string.tickets_delete_title), color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.tickets_delete_msg, t.subject), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            ApiClient.service.deleteTicket("Bearer $token", t.id)
                            tickets = tickets.filter { it.id != t.id }
                        } catch (e: Exception) { AppErrors.toast(errCtx, "delete ticket", e) }
                        ticketToDelete = null
                    }
                }) { Text(stringResource(R.string.tickets_delete_confirm), color = ErrorRed, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = {
                TextButton(onClick = { ticketToDelete = null }) {
                    Text(stringResource(R.string.tickets_cancel), color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    selectedTicket?.let { detail ->
        Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { selectedTicket = null })
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(detail.ticket.subject, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(if (detail.ticket.status == "open") R.string.tickets_open else R.string.tickets_closed),
                        color = if (detail.ticket.status == "open") Accent else TextMuted,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
                if (detail.ticket.status == "open") {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                ApiClient.service.closeTicket("Bearer $token", detail.ticket.id)
                                selectedTicket = detail.copy(ticket = detail.ticket.copy(status = "closed"))
                                tickets = tickets.map { if (it.id == detail.ticket.id) it.copy(status = "closed") else it }
                            } catch (e: Exception) { AppErrors.toast(errCtx, "close ticket", e) }
                        }
                    }) { Text(stringResource(R.string.tickets_btn_close), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
            HorizontalDivider(color = Border)

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (detail.messages.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            if (loadingMessages) CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                            else Text(stringResource(R.string.tickets_no_messages), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
                items(detail.messages) { msg ->
                    val isUser = msg.sender == "user"
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                    ) {
                        Box(
                            modifier = Modifier.widthIn(max = 280.dp).background(if (isUser) Accent else Bg2).padding(12.dp)
                        ) {
                            Text(msg.message, color = if (isUser) BgDark else TextPrimary, fontSize = 13.sp)
                        }
                        Text(formatMsgTime(msg.created_at), color = TextMuted, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
            }

            if (detail.ticket.status == "open") {
                HorizontalDivider(color = Border)
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newMessage, onValueChange = { newMessage = it },
                        placeholder = { Text(stringResource(R.string.tickets_message_hint), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newMessage.isBlank()) return@Button
                            scope.launch {
                                isSending = true
                                try {
                                    val msg = ApiClient.service.sendMessage("Bearer $token", detail.ticket.id, MessageCreateRequest(newMessage))
                                    // К актуальному тикету: автообновление могло уже добавить ответы поддержки
                                    selectedTicket = selectedTicket?.let {
                                        if (it.messages.any { m -> m.id == msg.id }) it else it.copy(messages = it.messages + msg)
                                    }
                                    newMessage = ""
                                } catch (e: Exception) { AppErrors.toast(errCtx, "send ticket message", e) }
                                finally { isSending = false }
                            }
                        },
                        enabled = !isSending && newMessage.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ) { Text("→", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.support_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        if (isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
            }
        } else if (tickets.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.tickets_empty), color = TextMuted, fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(tickets) { ticket ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    // Открываем сразу (тема и статус уже есть), сообщения догружаются. Раньше экран
                                    // ждал ответа сервера и казалось, что нажатие не сработало — жали по 2–3 раза
                                    selectedTicket = TicketDetailResponse(ticket = ticket)
                                    loadingMessages = true
                                    scope.launch {
                                        try {
                                            val detail = ApiClient.service.getTicket("Bearer $token", ticket.id)
                                            if (selectedTicket?.ticket?.id == ticket.id) selectedTicket = detail
                                        } catch (e: Exception) { AppErrors.toast(errCtx, "open ticket", e) }
                                        finally { loadingMessages = false }
                                    }
                                },
                                onLongClick = { ticketToDelete = ticket }
                            )
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ticket.subject, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(formatMsgTime(ticket.updated_at).take(10), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            stringResource(if (ticket.status == "open") R.string.tickets_open else R.string.tickets_closed),
                            color = if (ticket.status == "open") Accent else TextMuted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(color = Border)
                }
            }
        }

        val context = LocalContext.current
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/reich_kanzlei"))
                    context.startActivity(intent)
                }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✈", fontSize = 16.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.tickets_telegram_urgent), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.tickets_telegram_link), color = TextMuted, fontSize = 12.sp,
                    textDecoration = TextDecoration.Underline)
            }
        }
        HorizontalDivider(color = Border)
        Button(
            onClick = {
                // Лимит проверяет сервер (2 в сутки); раньше приложение само запрещало при «3 открытых»
                showCreateDialog = true
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
        ) {
            Text(stringResource(R.string.tickets_btn_new), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
fun ComingSoonTab(title: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text("$title.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.coming_soon_section), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.coming_soon_msg),
                    color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
fun AppSettingsTab(
    killSwitch: Boolean, onKillSwitchChange: (Boolean) -> Unit,
    autoReconnect: Boolean, onAutoReconnectChange: (Boolean) -> Unit,
    backgroundMode: Boolean, onBackgroundModeChange: (Boolean) -> Unit,
    language: String,
    onBack: () -> Unit,
    onKillSwitch: () -> Unit,
    onBackground: () -> Unit,
    onLanguage: () -> Unit,
    onAutoReconnect: () -> Unit = {},
    onProtocols: () -> Unit = {},
    onLogging: () -> Unit = {},
    onOpen: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    val logMode = AppLog.mode(LocalContext.current)
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.app_settings_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.autoreconnect_title),
            subtitle = stringResource(if (autoReconnect) R.string.autoreconnect_enabled_sub else R.string.autoreconnect_disabled_sub),
            onClick = onAutoReconnect
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = "Kill Switch",
            subtitle = stringResource(if (killSwitch) R.string.killswitch_enabled_sub else R.string.killswitch_disabled_sub),
            onClick = onKillSwitch
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.Language, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.lang_label),
            subtitle = stringResource(if (language == "en") R.string.lang_current_en else R.string.lang_current_ru),
            onClick = onLanguage
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.background_title).removeSuffix("."),
            subtitle = stringResource(if (backgroundMode) R.string.background_enabled_sub else R.string.background_disabled_sub),
            onClick = onBackground
        )
        val optCtx = LocalContext.current
        val optPrefs = optCtx.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.Palette, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.theme_title),
            subtitle = stringResource(when (optPrefs.getString(ru.fsociety.vpn.ui.theme.AppTheme.PREF, "dark")) {
                "light" -> R.string.theme_light; "system" -> R.string.theme_system; else -> R.string.theme_dark }),
            onClick = { onOpen("theme") }
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.Dns, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.dns_title),
            subtitle = stringResource(when (VpnOptions.dnsKey(optCtx)) {
                "google" -> R.string.dns_google; "quad9" -> R.string.dns_quad9; "adguard" -> R.string.dns_adguard
                else -> R.string.dns_cloudflare }),
            onClick = { onOpen("dns") }
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.Router, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.lan_title),
            subtitle = stringResource(if (VpnOptions.allowLan(optCtx)) R.string.opt_on else R.string.opt_off),
            onClick = { onOpen("lan") }
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.autostart_title),
            subtitle = stringResource(if (VpnOptions.autostart(optCtx)) R.string.opt_on else R.string.opt_off),
            onClick = { onOpen("autostart") }
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.Tune, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.proto_settings_title),
            subtitle = stringResource(R.string.proto_settings_sub),
            onClick = onProtocols
        )
        SettingsMenuItem(
            icon = { Icon(Icons.Filled.BugReport, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.log_menu_title),
            subtitle = stringResource(when (logMode) {
                LogMode.OFF -> R.string.log_off
                LogMode.MIN -> R.string.log_min
                LogMode.MAX -> R.string.log_max
            }),
            onClick = onLogging
        )
    }
}

@Composable
fun LanguageTab(language: String, onLanguageChange: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.lang_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        listOf("ru" to stringResource(R.string.lang_ru), "en" to stringResource(R.string.lang_en)).forEach { (code, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onLanguageChange(code) }
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (language == code) {
                    Text("✓", color = Accent, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = Border)
        }
    }
}

@Composable
fun KillSwitchTab(killSwitch: Boolean, onKillSwitchChange: (Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.killswitch_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.killswitch_description), color = TextMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().background(Bg2)
                    .clickable { onKillSwitchChange(!killSwitch) }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kill Switch", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(if (killSwitch) R.string.killswitch_switch_enabled else R.string.killswitch_switch_disabled),
                        color = if (killSwitch) Accent else TextMuted, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = killSwitch, onCheckedChange = onKillSwitchChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BgDark, checkedTrackColor = Accent,
                        uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.killswitch_note), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))
            // Системная защита: «Постоянная VPN» + «Блокировать соединения без VPN»
            val ctx = LocalContext.current
            TextButton(onClick = {
                try { ctx.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }
                catch (e: Exception) { AppErrors.log("open vpn settings", e) }
            }) { Text(stringResource(R.string.ks_android_settings), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            // Только DEV-сборка: настоящий обрыв (движок туннеля останавливается сам) — проверить Kill Switch
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { ConnectionManager.debugSimulateDrop() }) {
                    Text("DEV: симулировать обрыв", color = ErrorRed, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun BackgroundModeTab(backgroundMode: Boolean, onBackgroundModeChange: (Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.background_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.background_description), color = TextMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth().background(Bg2)
                    .clickable {
                        val enabled = !backgroundMode
                        onBackgroundModeChange(enabled)
                        if (enabled) {
                            val pm = context.getSystemService(PowerManager::class.java)
                            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                val intent = Intent(
                                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.background_switch_label), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.background_switch_sub), color = TextMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = backgroundMode,
                    onCheckedChange = { enabled ->
                        onBackgroundModeChange(enabled)
                        if (enabled) {
                            val pm = context.getSystemService(PowerManager::class.java)
                            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                val intent = Intent(
                                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BgDark, checkedTrackColor = Accent,
                        uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.background_note), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun AutoReconnectTab(autoReconnect: Boolean, onAutoReconnectChange: (Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.autoreconnect_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.autoreconnect_description), color = TextMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().background(Bg2)
                    .clickable { onAutoReconnectChange(!autoReconnect) }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.autoreconnect_title), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(if (autoReconnect) R.string.autoreconnect_switch_enabled else R.string.autoreconnect_switch_disabled),
                        color = if (autoReconnect) Accent else TextMuted, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoReconnect, onCheckedChange = onAutoReconnectChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BgDark, checkedTrackColor = Accent,
                        uncheckedThumbColor = TextMuted, uncheckedTrackColor = Bg2
                    )
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.autoreconnect_note), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * Описание релиза с GitHub (Markdown) → простой текст для экрана обновления.
 * Если описание двуязычное (RU, затем EN после строки «---»), берётся часть
 * на языке приложения; заголовки/жирный убираются, пункты списка → «•».
 */
fun plainReleaseNotes(markdown: String, context: android.content.Context): String {
    val parts = markdown.replace("\r", "").split(Regex("\n-{3,}\n"))
    val isEn = context.resources.configuration.locales[0].language == "en"
    val section = if (parts.size > 1 && isEn) parts[1] else parts[0]
    return section.lines()
        .map { it.trim() }
        .filterNot { it.startsWith("## ") }            // заголовок релиза «FsocietyNet vX» уже показан выше
        .map { line ->
            line.removePrefix("### ")
                .replace("**", "")
                .replace(Regex("^[-*] "), "• ")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * Установка APK с запросом разрешения «установка из неизвестных источников»: если его нет —
 * открываем системный переключатель, а по возвращении (ON_RESUME) с включённым разрешением
 * установщик запускается сам. Раньше разрешение просил установщик, и после тапа мимо его
 * окна приложение «зависало» в состоянии «Установщик запущен».
 */
@Composable
fun rememberApkInstaller(): (java.io.File) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<java.io.File?>(null) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val apk = pending
                if (apk != null && UpdateManager.canInstall(context)) {
                    pending = null
                    UpdateManager.launchInstaller(context, apk)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return { apk ->
        if (UpdateManager.canInstall(context)) UpdateManager.launchInstaller(context, apk)
        else { pending = apk; UpdateManager.openInstallPermission(context) }
    }
}

const val PREF_AUTO_UPDATE = "auto_update"
const val PREF_AUTO_UPDATE_NET = "auto_update_network"
const val AUTO_UPDATE_WIFI = "wifi"   // по умолчанию: не тратить мобильный трафик (~50 МБ)
const val AUTO_UPDATE_ANY = "any"

/** Есть ли сейчас Wi-Fi (сам Wi-Fi, а не VPN поверх него — VPN Android считает «лимитным»). */
fun isOnWifi(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    return cm.allNetworks.any { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@any false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

@Composable
fun UpdateTab(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE) }
    val currentVersion = BuildConfig.VERSION_NAME
    var tab by remember { mutableStateOf(if (prefs.getBoolean(PREF_AUTO_UPDATE, false)) 1 else 0) }
    var autoUpdate by remember { mutableStateOf(prefs.getBoolean(PREF_AUTO_UPDATE, false)) }
    var autoUpdateNet by remember { mutableStateOf(prefs.getString(PREF_AUTO_UPDATE_NET, AUTO_UPDATE_WIFI) ?: AUTO_UPDATE_WIFI) }
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var isChecking by remember { mutableStateOf(true) }
    var checkError by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf(-1) }   // -1 нет загрузки, 0..99 идёт
    var downloadError by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf<java.io.File?>(null) } // уже скачанный APK новой версии
    val scope = rememberCoroutineScope()
    val install = rememberApkInstaller()

    val strCheckError    = stringResource(R.string.update_error)
    val strDownloadError = stringResource(R.string.update_error_download)

    suspend fun check() {
        isChecking = true; checkError = ""
        UpdateManager.checkForUpdate(currentVersion)
            .onSuccess { info ->
                updateInfo = info
                apk = if (info.hasUpdate) UpdateManager.downloadedApk(context, info.latestVersion) else null
            }
            .onFailure { checkError = strCheckError }
        isChecking = false
    }

    fun startDownload(info: UpdateManager.UpdateInfo) {
        downloadProgress = 0; downloadError = ""
        scope.launch {
            UpdateManager.download(context, info) { p -> downloadProgress = p }
                .onSuccess { file -> apk = file; downloadProgress = -1; install(file) }
                .onFailure { e ->
                    AppErrors.log("update download", e)
                    downloadError = strDownloadError; downloadProgress = -1
                }
        }
    }

    LaunchedEffect(Unit) { check() }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.update_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        // Вкладки: «Вручную» / «Автоматически»
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            listOf(R.string.update_tab_manual, R.string.update_tab_auto).forEachIndexed { i, res ->
                Column(modifier = Modifier.weight(1f).clickable { tab = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(res), color = if (tab == i) Accent else TextMuted, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 10.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) Accent else Border))
                }
            }
        }

        // Прокрутка: длинное описание релиза не должно выталкивать кнопку «Обновить» за экран
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            if (tab == 1) {
                // ── Автоматически ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.update_auto_switch), color = TextPrimary, fontSize = 14.sp,
                        modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoUpdate,
                        onCheckedChange = { on ->
                            autoUpdate = on
                            prefs.edit().putBoolean(PREF_AUTO_UPDATE, on).apply()
                            AutoUpdateScheduler.apply(context)  // включить/выключить ночную задачу
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = Accent)
                    )
                }
                // Через какую сеть скачивать автоматически (активно только при включённом автообновлении)
                listOf(
                    AUTO_UPDATE_WIFI to R.string.update_net_wifi,
                    AUTO_UPDATE_ANY to R.string.update_net_any,
                ).forEach { (value, res) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = autoUpdate) {
                                autoUpdateNet = value
                                prefs.edit().putString(PREF_AUTO_UPDATE_NET, value).apply()
                                AutoUpdateScheduler.apply(context)
                            }
                    ) {
                        RadioButton(
                            selected = autoUpdateNet == value, enabled = autoUpdate,
                            onClick = {
                                autoUpdateNet = value
                                prefs.edit().putString(PREF_AUTO_UPDATE_NET, value).apply()
                                AutoUpdateScheduler.apply(context)
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted)
                        )
                        Text(stringResource(res), color = if (autoUpdate) TextPrimary else TextMuted, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.update_auto_desc), color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsRow(label = stringResource(R.string.update_current_version), value = currentVersion)
            Spacer(modifier = Modifier.height(16.dp))

            when {
                isChecking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.update_checking), color = TextMuted, fontSize = 13.sp)
                    }
                }
                checkError.isNotEmpty() -> {
                    Text(checkError, color = ErrorRed, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { scope.launch { check() } }) {
                        Text(stringResource(R.string.update_retry), color = Accent, fontFamily = FontFamily.Monospace)
                    }
                }
                updateInfo?.hasUpdate == true -> {
                    val info = updateInfo!!
                    SettingsRow(label = stringResource(R.string.update_new_version), value = info.latestVersion)
                    if (tab == 0 && info.releaseNotes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.update_whats_new), color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(plainReleaseNotes(info.releaseNotes, context), color = TextMuted, fontSize = 12.sp)
                    }
                    if (tab == 0 && info.downloadUrl.isNotEmpty()) {
                        // Прямая ссылка на APK последнего релиза (можно скачать браузером)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.update_apk_link), color = Accent, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                try {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(info.downloadUrl)))
                                } catch (e: Exception) { AppErrors.log("open apk link", e) }
                            })
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    if (downloadProgress in 0..99) {
                        Text(stringResource(R.string.update_downloading, downloadProgress),
                            color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Accent, trackColor = Surface
                        )
                    } else {
                        if (downloadError.isNotEmpty()) {
                            Text(downloadError, color = ErrorRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        val ready = apk
                        if (ready != null) {
                            // Уже скачано: установщик можно запускать сколько угодно раз
                            Text(stringResource(R.string.update_ready), color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Button(
                            onClick = { if (ready != null) install(ready) else startDownload(info) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                        ) {
                            Text(
                                if (ready != null) stringResource(R.string.update_btn_install)
                                else stringResource(R.string.update_btn, formatBytes(info.sizeBytes, context)),
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                        if (ready != null) {
                            TextButton(onClick = { apk = null; ready.delete(); startDownload(info) },
                                modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_redownload), color = TextMuted,
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.update_install_hint), color = TextMuted, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                else -> {
                    Text(stringResource(R.string.update_up_to_date), color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun ProtocolSettingsTab(onBack: () -> Unit, onHysteria: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.proto_settings_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        val noSettings = stringResource(R.string.proto_no_settings)
        ProtocolRow("AmneziaWG", noSettings, enabled = false) {}
        ProtocolRow("Hysteria2", stringResource(R.string.proto_settings_sub), enabled = true, onClick = onHysteria)
        ProtocolRow("Trojan", noSettings, enabled = false) {}
        ProtocolRow("VLESS + Reality", noSettings, enabled = false) {}
    }
}

@Composable
fun ProtocolRow(name: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = if (enabled) TextPrimary else TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        if (enabled) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
    HorizontalDivider(color = Border)
}

@Composable
fun Hysteria2SettingsTab(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current

    fun fmt(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    var mode by remember { mutableStateOf(ProtocolSettings.getHysteriaMode(context)) }
    var down by remember { mutableStateOf(fmt(ProtocolSettings.getHysteriaDownMBs(context))) }
    var up by remember { mutableStateOf(fmt(ProtocolSettings.getHysteriaUpMBs(context))) }

    Column(modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text("Hysteria2", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.proto_cc_desc),
                color = TextMuted, fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            CcModeOption(
                title = stringResource(R.string.proto_cc_bbr),
                subtitle = stringResource(R.string.proto_cc_bbr_sub),
                selected = mode == ProtocolSettings.MODE_BBR
            ) {
                mode = ProtocolSettings.MODE_BBR
                ProtocolSettings.setHysteriaMode(context, ProtocolSettings.MODE_BBR)
            }
            Spacer(modifier = Modifier.height(8.dp))
            CcModeOption(
                title = stringResource(R.string.proto_cc_brutal),
                subtitle = stringResource(R.string.proto_cc_brutal_sub),
                selected = mode == ProtocolSettings.MODE_BRUTAL
            ) {
                mode = ProtocolSettings.MODE_BRUTAL
                ProtocolSettings.setHysteriaMode(context, ProtocolSettings.MODE_BRUTAL)
            }

            if (mode == ProtocolSettings.MODE_BRUTAL) {
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = down,
                    onValueChange = { v ->
                        down = v.replace(',', '.')
                        down.toFloatOrNull()?.let { if (it > 0f) ProtocolSettings.setHysteriaDownMBs(context, it) }
                    },
                    label = { Text(stringResource(R.string.proto_speed_down), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = up,
                    onValueChange = { v ->
                        up = v.replace(',', '.')
                        up.toFloatOrNull()?.let { if (it > 0f) ProtocolSettings.setHysteriaUpMBs(context, it) }
                    },
                    label = { Text(stringResource(R.string.proto_speed_up), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.proto_speed_hint),
                    color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.proto_apply_note),
                color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun CcModeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, if (selected) Accent else Border)
            .background(if (selected) Bg2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (selected) Accent else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        if (selected) {
            Text("✓", color = Accent, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * Журнал для поддержки: выключен / минимум / максимум, «Отправить журнал» — файл через «Поделиться».
 * Средний режим не нужен: «минимум» уже даёт события и ошибки, а всё остальное (соединения,
 * системные сообщения) нужно только целиком.
 */
@Composable
fun LoggingTab(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppLog.mode(context)) }
    var size by remember { mutableStateOf(AppLog.sizeBytes(context)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.log_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(stringResource(R.string.log_desc), color = TextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(16.dp))
            listOf(
                Triple(LogMode.OFF, R.string.log_off, R.string.log_off_desc),
                Triple(LogMode.MIN, R.string.log_min, R.string.log_min_desc),
                Triple(LogMode.MAX, R.string.log_max, R.string.log_max_desc),
            ).forEach { (m, title, desc) ->
                CcModeOption(stringResource(title), stringResource(desc), mode == m) {
                    AppLog.setMode(context, m)
                    mode = m
                    size = AppLog.sizeBytes(context)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (mode != LogMode.OFF) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.log_size, formatBytes(size, context)), color = TextMuted,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            try { AppLog.share(context, AppLog.export(context)) }
                            catch (e: Exception) { AppErrors.toast(context, "export log", e) }
                            finally { busy = false; size = AppLog.sizeBytes(context) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                ) {
                    if (busy) CircularProgressIndicator(color = BgDark, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.log_send), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                TextButton(onClick = { AppLog.clear(context); size = AppLog.sizeBytes(context) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.log_clear), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.log_note), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Экран выбора одного варианта (тема, DNS, вкл/выкл); сохраняет вызывающий код в [onSelect]. */
@Composable
fun OptionsTab(
    titleRes: Int, descRes: Int, noteRes: Int?,
    options: List<Triple<String, Int, Int>>,
    initial: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var selected by remember { mutableStateOf(initial) }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.statusBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(titleRes), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(stringResource(descRes), color = TextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(16.dp))
            options.forEach { (key, title, desc) ->
                CcModeOption(stringResource(title), stringResource(desc), selected == key) {
                    selected = key
                    onSelect(key)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            noteRes?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(it), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** DNS внутри VPN: Cloudflare / Google / Quad9 / AdGuard. */
@Composable
fun DnsTab(onBack: () -> Unit) {
    val context = LocalContext.current
    OptionsTab(
        titleRes = R.string.dns_title, descRes = R.string.dns_desc, noteRes = R.string.dns_note,
        options = listOf(
            Triple("cloudflare", R.string.dns_cloudflare, R.string.dns_cloudflare_desc),
            Triple("google", R.string.dns_google, R.string.dns_google_desc),
            Triple("quad9", R.string.dns_quad9, R.string.dns_quad9_desc),
            Triple("adguard", R.string.dns_adguard, R.string.dns_adguard_desc),
        ),
        initial = VpnOptions.dnsKey(context),
        onSelect = { key ->
            context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE).edit()
                .putString(VpnOptions.PREF_DNS, key).apply()
        },
        onBack = onBack
    )
}

/** Вкл / выкл булевой настройки [prefKey]. */
@Composable
fun SwitchOptionTab(prefKey: String, titleRes: Int, descRes: Int, onDescRes: Int, offDescRes: Int, noteRes: Int?, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE) }
    OptionsTab(
        titleRes = titleRes, descRes = descRes, noteRes = noteRes,
        options = listOf(Triple("on", R.string.opt_on, onDescRes), Triple("off", R.string.opt_off, offDescRes)),
        initial = if (prefs.getBoolean(prefKey, false)) "on" else "off",
        onSelect = { prefs.edit().putBoolean(prefKey, it == "on").apply() },
        onBack = onBack
    )
}

/** Тема: тёмная / светлая / как в системе — меняется сразу, без перезапуска. */
@Composable
fun ThemeTab(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE) }
    OptionsTab(
        titleRes = R.string.theme_title, descRes = R.string.theme_desc, noteRes = null,
        options = listOf(
            Triple("dark", R.string.theme_dark, R.string.theme_dark_desc),
            Triple("light", R.string.theme_light, R.string.theme_light_desc),
            Triple("system", R.string.theme_system, R.string.theme_system_desc),
        ),
        initial = prefs.getString(ru.fsociety.vpn.ui.theme.AppTheme.PREF, "dark") ?: "dark",
        onSelect = { mode ->
            prefs.edit().putString(ru.fsociety.vpn.ui.theme.AppTheme.PREF, mode).apply()
            ru.fsociety.vpn.ui.theme.AppTheme.mode.value = mode
            // Значки строки состояния и навигации — под тему
            (context as? androidx.activity.ComponentActivity)?.let { activity ->
                val t = android.graphics.Color.TRANSPARENT
                val bars = if (ru.fsociety.vpn.ui.theme.AppTheme.dark) androidx.activity.SystemBarStyle.dark(t)
                           else androidx.activity.SystemBarStyle.light(t, t)
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
        },
        onBack = onBack
    )
}
