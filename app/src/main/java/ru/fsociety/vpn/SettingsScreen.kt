package ru.fsociety.vpn

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.WorkspacePremium
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
    language: String, onLanguageChange: (String) -> Unit
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
            onLanguage = { currentScreen = "language" }
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
        "update" -> UpdateTab(onBack = { currentScreen = "main" })
        "subscription" -> ComingSoonTab(title = stringResource(R.string.settings_subscription), onBack = { currentScreen = "main" })
        "payments" -> ComingSoonTab(title = stringResource(R.string.settings_payments), onBack = { currentScreen = "main" })
        else -> Column(modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(24.dp).padding(top = 48.dp)) {
                Text(stringResource(R.string.settings_section), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.settings_title), color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = stringResource(R.string.settings_account),
                subtitle = user?.email ?: stringResource(R.string.settings_loading),
                onClick = { currentScreen = "account" }
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
    BackHandler { onBack() }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPassword2 by remember { mutableStateOf("") }
    var pwError by remember { mutableStateOf("") }
    var pwSuccess by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val strErrorEmpty    = stringResource(R.string.account_error_empty)
    val strErrorMismatch = stringResource(R.string.account_error_mismatch)
    val strErrorLength   = stringResource(R.string.account_error_length)
    val strPasswordChanged = stringResource(R.string.account_password_changed)
    val strErrorWrongPw  = stringResource(R.string.account_error_wrong_password)
    val strErrorNetwork  = stringResource(R.string.account_error_network)

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
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
                SettingsRow(label = "Email", value = user?.email ?: "—")
                SettingsRow(
                    label = stringResource(R.string.account_label_subscription),
                    value = if (subscription?.is_active == true)
                        stringResource(R.string.account_sub_active, subscription.plan ?: "", subscription.expires_at?.take(10) ?: "")
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
                                    if (resp.isSuccessful) {
                                        pwSuccess = strPasswordChanged
                                        oldPassword = ""; newPassword = ""; newPassword2 = ""
                                    } else {
                                        pwError = strErrorWrongPw
                                    }
                                } catch (_: Exception) {
                                    pwError = strErrorNetwork
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
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = ErrorRed),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed)
                ) {
                    Text(stringResource(R.string.account_btn_logout), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

fun formatMsgTime(raw: String): String {
    return try { raw.replace("T", " ").take(16) } catch (_: Exception) { raw.take(16) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TicketsTab(token: String, onBack: () -> Unit) {
    var tickets by remember { mutableStateOf<List<TicketResponse>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var selectedTicket by remember { mutableStateOf<TicketDetailResponse?>(null) }
    var newSubject by remember { mutableStateOf("") }
    var newMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var ticketToDelete by remember { mutableStateOf<TicketResponse?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler {
        if (selectedTicket != null) selectedTicket = null
        else onBack()
    }

    LaunchedEffect(token) {
        try { tickets = ApiClient.service.getTickets("Bearer $token") }
        catch (_: Exception) {}
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
                TextButton(onClick = {
                    if (newSubject.isBlank()) return@TextButton
                    scope.launch {
                        isSending = true
                        try {
                            val t = ApiClient.service.createTicket("Bearer $token", TicketCreateRequest(newSubject))
                            tickets = listOf(t) + tickets
                            showCreateDialog = false; newSubject = ""
                        } catch (_: Exception) {}
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
            text = { Text(stringResource(R.string.tickets_limit_msg), color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
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
                        } catch (_: Exception) {}
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 52.dp, bottom = 12.dp),
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
                            } catch (_: Exception) {}
                        }
                    }) { Text(stringResource(R.string.tickets_btn_close), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
            HorizontalDivider(color = Border)

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (detail.messages.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.tickets_no_messages), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                                    selectedTicket = detail.copy(messages = detail.messages + msg)
                                    newMessage = ""
                                } catch (_: Exception) {}
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
        Row(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 52.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                    scope.launch {
                                        try {
                                            val detail = ApiClient.service.getTicket("Bearer $token", ticket.id)
                                            selectedTicket = detail
                                        } catch (_: Exception) {}
                                    }
                                },
                                onLongClick = { ticketToDelete = ticket }
                            )
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ticket.subject, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(ticket.updated_at.take(10), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
                val openCount = tickets.count { it.status == "open" }
                if (openCount >= 3) showLimitDialog = true
                else showCreateDialog = true
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
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
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
    onAutoReconnect: () -> Unit = {}
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
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
    }
}

@Composable
fun LanguageTab(language: String, onLanguageChange: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
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
        }
    }
}

@Composable
fun BackgroundModeTab(backgroundMode: Boolean, onBackgroundModeChange: (Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
fun UpdateTab(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val currentVersion = BuildConfig.VERSION_NAME
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var isChecking by remember { mutableStateOf(true) }
    var checkError by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf(-1) }
    var downloadError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val strCheckError    = stringResource(R.string.update_error)
    val strDownloadError = stringResource(R.string.update_error_download)

    LaunchedEffect(Unit) {
        val result = UpdateManager.checkForUpdate(currentVersion)
        result.onSuccess { updateInfo = it }
        result.onFailure { checkError = strCheckError }
        isChecking = false
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.update_title), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
                    TextButton(onClick = {
                        isChecking = true; checkError = ""
                        scope.launch {
                            val result = UpdateManager.checkForUpdate(currentVersion)
                            result.onSuccess { updateInfo = it }
                            result.onFailure { checkError = strCheckError }
                            isChecking = false
                        }
                    }) { Text(stringResource(R.string.update_retry), color = Accent, fontFamily = FontFamily.Monospace) }
                }
                updateInfo?.hasUpdate == true -> {
                    val info = updateInfo!!
                    SettingsRow(label = stringResource(R.string.update_new_version), value = info.latestVersion)
                    if (info.releaseNotes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.update_whats_new), color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(info.releaseNotes, color = TextMuted, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    if (downloadProgress in 0..99) {
                        Column {
                            Text(stringResource(R.string.update_downloading, downloadProgress),
                                color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Accent, trackColor = Surface
                            )
                        }
                    } else if (downloadProgress == 100) {
                        Text(stringResource(R.string.update_installed), color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        if (downloadError.isNotEmpty()) {
                            Text(downloadError, color = ErrorRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Button(
                            onClick = {
                                downloadProgress = 0; downloadError = ""
                                scope.launch {
                                    UpdateManager.downloadAndInstall(context, info.downloadUrl) { p ->
                                        downloadProgress = p
                                    }.onFailure {
                                        downloadError = strDownloadError
                                        downloadProgress = -1
                                    }.onSuccess {
                                        downloadProgress = 100
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                        ) {
                            Text(
                                stringResource(R.string.update_btn, formatBytes(info.sizeBytes, context)),
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                    }
                }
                else -> {
                    Text(stringResource(R.string.update_up_to_date), color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
