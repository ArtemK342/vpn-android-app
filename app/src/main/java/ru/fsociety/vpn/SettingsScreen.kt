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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    killSwitch: Boolean, onKillSwitchChange: (Boolean) -> Unit
) {
    var currentScreen by remember { mutableStateOf("main") }

    BackHandler(enabled = currentScreen != "main") {
        currentScreen = "main"
    }

    when (currentScreen) {
        "account" -> AccountTab(token = token, user = user, subscription = subscription, isLoading = isLoading, onLogout = onLogout, onBack = { currentScreen = "main" })
        "support" -> TicketsTab(token = token, onBack = { currentScreen = "main" })
        "background" -> BackgroundModeTab(
            backgroundMode = backgroundMode,
            onBackgroundModeChange = onBackgroundModeChange,
            onBack = { currentScreen = "main" }
        )
        "killswitch" -> KillSwitchTab(
            killSwitch = killSwitch,
            onKillSwitchChange = onKillSwitchChange,
            onBack = { currentScreen = "main" }
        )
        "update" -> UpdateTab(onBack = { currentScreen = "main" })
        "subscription" -> ComingSoonTab(title = "Подписка", onBack = { currentScreen = "main" })
        "payments" -> ComingSoonTab(title = "История платежей", onBack = { currentScreen = "main" })
        else -> Column(modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(24.dp).padding(top = 48.dp)) {
                Text("// НАСТРОЙКИ", color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Настройки.", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = "Аккаунт",
                subtitle = user?.email ?: "Загрузка...",
                onClick = { currentScreen = "account" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.SupportAgent, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = "Поддержка",
                subtitle = "Сообщить о проблеме или задать вопрос",
                onClick = { currentScreen = "support" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = "Kill Switch",
                subtitle = if (killSwitch) "Включён — интернет блокируется при обрыве VPN" else "Выключен",
                onClick = { currentScreen = "killswitch" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = "Фоновый режим",
                subtitle = if (backgroundMode) "Включён" else "Выключен",
                onClick = { currentScreen = "background" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = "Подписка",
                subtitle = "В разработке",
                onClick = { currentScreen = "subscription" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = "История платежей",
                subtitle = "В разработке",
                onClick = { currentScreen = "payments" }
            )
            SettingsMenuItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) },
                title = "Обновление",
                subtitle = "Версия ${BuildConfig.VERSION_NAME}",
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
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border)
            .padding(16.dp)
    ) {
        Text(label.uppercase(), color = TextMuted, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, color = TextPrimary, fontSize = 14.sp,
            fontWeight = FontWeight.Bold)
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

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text("Аккаунт.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                }
            } else {
                SettingsRow(label = "Email", value = user?.email ?: "—")
                SettingsRow(label = "Подписка", value = if (subscription?.is_active == true)
                    "${subscription.plan} · до ${subscription.expires_at?.take(10)}"
                else "Нет активной подписки")
                if (user?.role != "user" && user?.role != null) {
                    SettingsRow(label = "Роль", value = user.role)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("// СМЕНА ПАРОЛЯ", color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = oldPassword, onValueChange = { oldPassword = it; pwError = ""; pwSuccess = "" },
                    label = { Text("Текущий пароль", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                    label = { Text("Новый пароль", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                    label = { Text("Повторите новый пароль", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                            pwError = "Заполните все поля"; return@Button
                        }
                        if (newPassword != newPassword2) {
                            pwError = "Пароли не совпадают"; return@Button
                        }
                        if (newPassword.length < 8) {
                            pwError = "Минимум 8 символов"; return@Button
                        }
                        scope.launch {
                            isSaving = true
                            pwError = ""
                            try {
                                val resp = ApiClient.service.changePassword(
                                    "Bearer $token",
                                    ChangePasswordRequest(oldPassword, newPassword)
                                )
                                if (resp.isSuccessful) {
                                    pwSuccess = "Пароль изменён"
                                    oldPassword = ""; newPassword = ""; newPassword2 = ""
                                } else {
                                    pwError = "Неверный текущий пароль"
                                }
                            } catch (_: Exception) {
                                pwError = "Ошибка сети"
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
                    else Text("СМЕНИТЬ ПАРОЛЬ →", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = ErrorRed),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed)
                ) {
                    Text("ВЫЙТИ →", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

fun formatMsgTime(raw: String): String {
    return try {
        val s = raw.replace("T", " ").take(16)
        s
    } catch (_: Exception) { raw.take(16) }
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
            title = { Text("Новый тикет", color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newSubject, onValueChange = { newSubject = it },
                    label = { Text("Тема", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                            showCreateDialog = false
                            newSubject = ""
                        } catch (_: Exception) {}
                        finally { isSending = false }
                    }
                }) { Text("СОЗДАТЬ", color = Accent, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newSubject = "" }) {
                    Text("ОТМЕНА", color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    if (showLimitDialog) {
        AlertDialog(
            onDismissRequest = { showLimitDialog = false },
            containerColor = Bg2,
            title = { Text("Лимит тикетов", color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = { Text("У вас уже 3 открытых тикета. Закройте или удалите один из них перед созданием нового.", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = { showLimitDialog = false }) {
                    Text("ПОНЯТНО", color = Accent, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    ticketToDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { ticketToDelete = null },
            containerColor = Bg2,
            title = { Text("Удалить тикет?", color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = { Text("«${t.subject}» будет удалён без возможности восстановления.", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            ApiClient.service.deleteTicket("Bearer $token", t.id)
                            tickets = tickets.filter { it.id != t.id }
                        } catch (_: Exception) {}
                        ticketToDelete = null
                    }
                }) { Text("УДАЛИТЬ", color = ErrorRed, fontFamily = FontFamily.Monospace) }
            },
            dismissButton = {
                TextButton(onClick = { ticketToDelete = null }) {
                    Text("ОТМЕНА", color = TextMuted, fontFamily = FontFamily.Monospace)
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
                    Text(if (detail.ticket.status == "open") "● открыт" else "● закрыт",
                        color = if (detail.ticket.status == "open") Accent else TextMuted,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
                    }) { Text("ЗАКРЫТЬ", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
            HorizontalDivider(color = Border)

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (detail.messages.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Нет сообщений", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .background(if (isUser) Accent else Bg2)
                                .padding(12.dp)
                        ) {
                            Text(msg.message, color = if (isUser) BgDark else TextPrimary, fontSize = 13.sp)
                        }
                        Text(
                            formatMsgTime(msg.created_at),
                            color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (detail.ticket.status == "open") {
                HorizontalDivider(color = Border)
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newMessage, onValueChange = { newMessage = it },
                        placeholder = { Text("Сообщение...", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted) },
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

    // Список тикетов
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 52.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text("Поддержка.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        if (isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
            }
        } else if (tickets.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Нет обращений", color = TextMuted, fontFamily = FontFamily.Monospace)
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
                            if (ticket.status == "open") "● открыт" else "● закрыт",
                            color = if (ticket.status == "open") Accent else TextMuted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(color = Border)
                }
            }
        }

        val context = androidx.compose.ui.platform.LocalContext.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                Text("Срочный вопрос?", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Написать в Telegram", color = TextMuted, fontSize = 12.sp,
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
            Text("+ НОВОЕ ОБРАЩЕНИЕ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                Text("// В РАЗРАБОТКЕ", color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Раздел будет доступен\nв следующих версиях",
                    color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
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
            Text("Kill Switch.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Если VPN-соединение неожиданно обрывается — весь интернет блокируется до повторного подключения. Защищает от утечки реального IP.",
                color = TextMuted, fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg2)
                    .clickable { onKillSwitchChange(!killSwitch) }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kill Switch", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (killSwitch) "Включён" else "Выключен",
                        color = if (killSwitch) Accent else TextMuted, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = killSwitch,
                    onCheckedChange = onKillSwitchChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BgDark, checkedTrackColor = Accent,
                        uncheckedThumbColor = TextMuted, uncheckedTrackColor = Surface
                    )
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "// При обрыве VPN приложение отключит туннель и покажет уведомление.",
                color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun BackgroundModeTab(backgroundMode: Boolean, onBackgroundModeChange: (Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text("Фоновый режим.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Когда включён — уведомление в шторке всегда видно. Кнопка «ОТКЛЮЧИТЬСЯ» доступна без открытия приложения.",
                color = TextMuted, fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg2)
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
                    Text("Работа в фоне", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Показывать уведомление всегда", color = TextMuted, fontSize = 12.sp)
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
                        checkedThumbColor = BgDark,
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = Surface
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "После включения телефон покажет диалог с запросом разрешения — нажмите «Разрешить».",
                color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun UpdateTab(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentVersion = BuildConfig.VERSION_NAME
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var isChecking by remember { mutableStateOf(true) }
    var checkError by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf(-1) }
    var downloadError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val result = UpdateManager.checkForUpdate(currentVersion)
        result.onSuccess { updateInfo = it }
        result.onFailure { checkError = "Не удалось проверить обновления" }
        isChecking = false
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text("Обновление.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsRow(label = "Текущая версия", value = currentVersion)

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isChecking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Проверяем обновления...", color = TextMuted, fontSize = 13.sp)
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
                            result.onFailure { checkError = "Не удалось проверить обновления" }
                            isChecking = false
                        }
                    }) { Text("Попробовать снова", color = Accent, fontFamily = FontFamily.Monospace) }
                }
                updateInfo?.hasUpdate == true -> {
                    val info = updateInfo!!
                    SettingsRow(label = "Новая версия", value = info.latestVersion)
                    if (info.releaseNotes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("// ЧТО НОВОГО", color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(info.releaseNotes, color = TextMuted, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    if (downloadProgress in 0..99) {
                        Column {
                            Text("Загрузка... $downloadProgress%", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Accent,
                                trackColor = Surface
                            )
                        }
                    } else if (downloadProgress == 100) {
                        Text("✓ Установщик запущен", color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
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
                                        downloadError = "Ошибка загрузки"
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
                                "ОБНОВИТЬ → ${VpnNotificationHelper.formatBytes(info.sizeBytes)}",
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                    }
                }
                else -> {
                    Text("✓ Установлена актуальная версия", color = Accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
