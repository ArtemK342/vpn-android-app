package ru.fsociety.vpn

import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fsociety.vpn.ui.theme.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding

// Модель сервера
data class Server(
    val name: String,
    val country: String,
    val flag: String,
    val isActive: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VpnappTheme {
                App(context = this)
            }
        }
    }
}


@Composable
fun App(context: android.content.Context) {
    val prefs = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var showRegister by remember { mutableStateOf(false) }

    when {
        token.isNotEmpty() -> AppNavigation(
            token = token,
            onLogout = {
                prefs.edit().remove("token").apply()
                token = ""
            }
        )
        showRegister -> RegisterScreen(onBack = { showRegister = false })
        else -> LoginScreen(
            onLogin = {
                prefs.edit().putString("token", it).apply()
                token = it
            },
            onRegister = { showRegister = true }
        )
    }
}


@Composable
fun LoginScreen(onLogin: (String) -> Unit, onRegister: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()) // скролл
                .padding(24.dp)
                .padding(top = 48.dp)
        ) {
            Row {
                Text("[f]", color = Accent, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("// ВХОД В АККАУНТ", color = TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

            Spacer(modifier = Modifier.height(40.dp))

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("EMAIL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("ПАРОЛЬ", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Забыл пароль?",
                color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.fillMaxWidth().clickable { }
            )

            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorMsg, color = ErrorRed, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty()) {
                        errorMsg = "Заполните все поля"
                        return@Button
                    }
                    // Реальный API запрос
                    scope.launch {
                        isLoading = true
                        errorMsg = ""
                        try {
                            val response = ApiClient.service.login(email, password)
                            onLogin(response.access_token)
                        } catch (e: java.net.SocketTimeoutException) {
                            errorMsg = "Ошибка сети, попробуйте ещё раз"
                        } catch (e: Exception) {
                            errorMsg = "Неверный email или пароль"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = BgDark,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("ВОЙТИ →", fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Поддержка в Telegram",
                color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { }
            )

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(24.dp))

            Text("Нет аккаунта?", color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent, contentColor = TextPrimary),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Text("ЗАРЕГИСТРИРОВАТЬСЯ →", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun RegisterScreen(onBack: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var successMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding(), // отступ от клавиатуры
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 24.dp)
        ) {
            // Логотип
            Row {
                Text("[f]", color = Accent, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("society", color = TextPrimary, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("// РЕГИСТРАЦИЯ", color = TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.15.sp)

            Spacer(modifier = Modifier.height(40.dp))

            // Email
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("EMAIL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Пароль
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("ПАРОЛЬ", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Повтор пароля
            OutlinedTextField(
                value = password2, onValueChange = { password2 = it },
                label = { Text("ПОВТОРИТЕ ПАРОЛЬ", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Border,
                    focusedLabelColor = Accent, unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                )
            )

            // Ошибка
            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(errorMsg, color = ErrorRed, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }

            // Успех
            if (successMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(successMsg, color = Accent, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Кнопка регистрации
            Button(
                onClick = {
                    if (email.isEmpty() || password.isEmpty() || password2.isEmpty()) {
                        errorMsg = "Заполните все поля"
                        return@Button
                    }
                    if (password != password2) {
                        errorMsg = "Пароли не совпадают"
                        return@Button
                    }
                    if (password.length < 8) {
                        errorMsg = "Пароль минимум 8 символов"
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        errorMsg = ""
                        try {
                            ApiClient.service.register(RegisterRequest(email, password))
                            successMsg = "Письмо отправлено на $email — подтвердите аккаунт"
                        } catch (e: Exception) {
                            errorMsg = "Email уже зарегистрирован или ошибка сервера"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = BgDark,
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("ЗАРЕГИСТРИРОВАТЬСЯ →", fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Назад к входу
            Text(
                text = "← Уже есть аккаунт? Войти",
                color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onBack() }
            )
        }
    }
}


@Composable
fun AppNavigation(token: String, onLogout: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    // Загружаем всё один раз параллельно
    var servers by remember { mutableStateOf<List<ServerResponse>>(emptyList()) }
    var serverPings by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var user by remember { mutableStateOf<UserResponse?>(null) }
    var subscription by remember { mutableStateOf<SubscriptionResponse?>(null) }
    var isLoadingServers by remember { mutableStateOf(true) }
    var isLoadingSettings by remember { mutableStateOf(true) }
    var isConnected by remember { mutableStateOf(false) }
    var connectedServer by remember { mutableStateOf<ServerResponse?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token) {
        scope.launch {
            try {
                val result = ApiClient.service.getServers("Bearer $token")
                servers = result
                // Измеряем пинг параллельно для каждого сервера
                result.forEach { server ->
                    scope.launch {
                        val ping = measurePing(server.id, servers)
                        serverPings = serverPings + (server.id to ping)
                    }
                }
            } catch (_: Exception) {}
            finally { isLoadingServers = false }
        }
        scope.launch {
            try {
                user = ApiClient.service.getMe("Bearer $token")
                subscription = ApiClient.service.getSubscription("Bearer $token")
            } catch (_: Exception) {}
            finally { isLoadingSettings = false }
        }
    }

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(containerColor = Bg2, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("ГЛАВНАЯ", fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        selectedIconColor = Accent, unselectedIconColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("ПРАВИЛА", fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        selectedIconColor = Accent, unselectedIconColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
                NavigationBarItem(
                    selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("НАСТРОЙКИ", fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        selectedIconColor = Accent, unselectedIconColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> HomeScreen(
                    token = token,
                    servers = servers,
                    serverPings = serverPings,
                    isLoadingServers = isLoadingServers,
                    isConnected = isConnected,
                    connectedServer = connectedServer,
                    onConnected = { server -> isConnected = true; connectedServer = server },
                    onDisconnected = { isConnected = false; connectedServer = null }
                )
                1 -> RulesScreen()
                2 -> SettingsScreen(token = token, user = user, subscription = subscription, isLoading = isLoadingSettings, onLogout = onLogout)
            }
        }
    }
}

// Измеряем TCP пинг до сервера (порт 51820 UDP недоступен из Android без root,
// поэтому меряем через сокет до порта 443/80)
suspend fun measurePing(serverId: String, servers: List<ServerResponse>): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val start = System.currentTimeMillis()
        val socket = java.net.Socket()
        // Берём endpoint сервера из списка — пингуем по IP на порту 443
        val server = servers.firstOrNull { it.id == serverId } ?: return@withContext 999
        // Используем IP из endpoint если есть, иначе 999
        socket.connect(java.net.InetSocketAddress("fsociety-vpn.org", 443), 3000)
        socket.close()
        (System.currentTimeMillis() - start).toInt()
    } catch (_: Exception) { 999 }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    token: String,
    servers: List<ServerResponse>,
    serverPings: Map<String, Int>,
    isLoadingServers: Boolean,
    isConnected: Boolean,
    connectedServer: ServerResponse?,
    onConnected: (ServerResponse) -> Unit,
    onDisconnected: () -> Unit
) {
    var selectedServer by remember { mutableStateOf<ServerResponse?>(null) }
    var serverListTab by remember { mutableStateOf(0) }
    var favoriteServers by remember { mutableStateOf(setOf<String>()) }
    var showFavoriteDialog by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var pendingConfig by remember { mutableStateOf<String?>(null) }
    var pendingServer by remember { mutableStateOf<ServerResponse?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val config = pendingConfig ?: return@rememberLauncherForActivityResult
            val server = pendingServer ?: return@rememberLauncherForActivityResult
            pendingConfig = null
            pendingServer = null
            scope.launch {
                statusMsg = "Подключение..."
                val success = VpnManager.connect(context, config)
                if (success) {
                    onConnected(server)
                    statusMsg = ""
                } else {
                    statusMsg = "Ошибка подключения"
                }
                isConnecting = false
            }
        } else {
            statusMsg = "Разрешение VPN отклонено"
            isConnecting = false
        }
    }

    // Автовыбор сервера с лучшим пингом когда список загрузился
    LaunchedEffect(servers, serverPings) {
        if (selectedServer == null && servers.isNotEmpty() && !isConnected) {
            selectedServer = servers
                .filter { it.is_active }
                .minByOrNull { serverPings[it.id] ?: 999 }
        }
    }

    // Функция подключения к серверу
    suspend fun connectToServer(server: ServerResponse) {
        isConnecting = true
        statusMsg = "Получение конфигурации..."
        try {
            val response = ApiClient.service.getVpnConfig("Bearer $token", server.id)
            if (response.config != null) {
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    pendingConfig = response.config
                    pendingServer = server
                    vpnPermissionLauncher.launch(intent)
                } else {
                    statusMsg = "Подключение..."
                    val success = VpnManager.connect(context, response.config)
                    if (success) {
                        onConnected(server)
                        statusMsg = ""
                    } else {
                        statusMsg = "Ошибка подключения"
                    }
                    isConnecting = false
                }
            } else {
                statusMsg = response.message ?: "Ошибка"
                isConnecting = false
            }
        } catch (e: java.net.SocketTimeoutException) {
            statusMsg = "Таймаут, попробуйте ещё раз"
            isConnecting = false
        } catch (e: Exception) {
            statusMsg = "Ошибка: ${e.message}"
            isConnecting = false
        }
    }

    val displayedServers = if (serverListTab == 0) servers
    else servers.filter { favoriteServers.contains(it.name) }

    // Диалог избранного
    showFavoriteDialog?.let { serverName ->
        AlertDialog(
            onDismissRequest = { showFavoriteDialog = null },
            containerColor = Bg2,
            title = { Text(serverName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (favoriteServers.contains(serverName)) "Убрать из избранного?" else "Добавить в избранное?",
                    color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    favoriteServers = if (favoriteServers.contains(serverName))
                        favoriteServers - serverName else favoriteServers + serverName
                    showFavoriteDialog = null
                }) {
                    Text(
                        if (favoriteServers.contains(serverName)) "УБРАТЬ" else "ДОБАВИТЬ",
                        color = Accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showFavoriteDialog = null }) {
                    Text("ОТМЕНА", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {

        // Картинка — заглушка
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp).background(Bg2),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("[f]", color = Accent, fontSize = 48.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (statusMsg.isNotEmpty()) {
                    Text(statusMsg, color = TextMuted, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        HorizontalDivider(color = Border, thickness = 1.dp)

        // Вкладки + статус
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ВСЕ", color = if (serverListTab == 0) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (serverListTab == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { serverListTab = 0 })
            Text("ИЗБРАННЫЕ", color = if (serverListTab == 1) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (serverListTab == 1) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { serverListTab = 1 })
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (isConnected) "● ${connectedServer?.name ?: ""}" else "○ Отключён",
                color = if (isConnected) Accent else TextMuted,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }

        HorizontalDivider(color = Border, thickness = 1.dp)

        // Список серверов
        if (isLoadingServers) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
            }
        } else if (displayedServers.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (serverListTab == 1) "Удерживайте сервер\nчтобы добавить в избранное"
                    else "Нет доступных серверов",
                    color = TextMuted, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(displayedServers) { server ->
                    val ping = serverPings[server.id]
                    val isThisConnected = connectedServer?.id == server.id && isConnected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (server.is_active && !isConnecting) {
                                        selectedServer = server
                                        if (!isConnected) {
                                            scope.launch { connectToServer(server) }
                                        } else if (!isThisConnected) {
                                            scope.launch {
                                                isConnecting = true
                                                statusMsg = "Отключение..."
                                                VpnManager.disconnect()
                                                onDisconnected()
                                                connectToServer(server)
                                            }
                                        }
                                    }
                                },
                                onLongClick = { showFavoriteDialog = server.name }
                            )
                            .background(if (isThisConnected || selectedServer?.id == server.id) Surface else BgDark)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (server.country) {
                                "Finland" -> "🇫🇮"
                                "Switzerland" -> "🇨🇭"
                                "Russia" -> "🇷🇺"
                                "Germany" -> "🇩🇪"
                                "Netherlands" -> "🇳🇱"
                                else -> "🌍"
                            }, fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name,
                                color = if (server.is_active) TextPrimary else TextMuted,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            if (favoriteServers.contains(server.name)) {
                                Text("★ избранное", color = Accent,
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text(
                            text = when {
                                isThisConnected -> "● ПОДКЛЮЧЁН"
                                !server.is_active -> "СКОРО"
                                ping == null -> "● ..."
                                ping >= 999 -> "● —"
                                else -> "● ${ping}мс"
                            },
                            color = when {
                                isThisConnected -> Accent
                                !server.is_active -> TextMuted
                                ping == null || ping >= 999 -> TextMuted
                                ping < 100 -> Accent
                                ping < 200 -> Color(0xFFFFAA00)
                                else -> ErrorRed
                            },
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(color = Border, thickness = 1.dp)
                }
            }
        }

        // Кнопка подключения
        HorizontalDivider(color = Border, thickness = 1.dp)
        Button(
            onClick = {
                scope.launch {
                    if (isConnected) {
                        isConnecting = true
                        statusMsg = "Отключение..."
                        VpnManager.disconnect()
                        onDisconnected()
                        statusMsg = ""
                        isConnecting = false
                    } else {
                        val server = selectedServer ?: return@launch
                        connectToServer(server)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !isConnecting && (isConnected || selectedServer != null),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) ErrorRed else Accent,
                contentColor = BgDark
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
        ) {
            if (isConnecting) {
                CircularProgressIndicator(color = BgDark,
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    if (isConnected) "● ОТКЛЮЧИТЬСЯ" else "○ ПОДКЛЮЧИТЬСЯ",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
            }
        }
    }
}




@Composable
fun RulesScreen() {
    var selectedTab by remember { mutableStateOf(0) } // 0=Приложения, 1=Сайты

    Column(
        modifier = Modifier.fillMaxSize().background(BgDark)
    ) {
        // Заголовок
        Column(modifier = Modifier.padding(24.dp).padding(top = 48.dp)) {
            Text("// ПРАВИЛА", color = Accent, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Раздельный туннель.", color = TextPrimary,
                fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        // Вкладки Приложения / Сайты
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "ПРИЛОЖЕНИЯ",
                color = if (selectedTab == 0) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 0 }
            )
            Text(
                text = "САЙТЫ",
                color = if (selectedTab == 1) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 1 }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = Border, thickness = 1.dp)

        // Заглушка
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selectedTab == 0)
                    "Раздельное туннелирование\nдля приложений будет скоро"
                else "Исключения для сайтов\nбудут скоро",
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
fun SettingsScreen(token: String, user: UserResponse?, subscription: SubscriptionResponse?, isLoading: Boolean, onLogout: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Column(modifier = Modifier.padding(24.dp).padding(top = 48.dp)) {
            Text("// НАСТРОЙКИ", color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text(if (selectedTab == 0) "Аккаунт." else "Поддержка.", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("АККАУНТ", color = if (selectedTab == 0) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 0 })
            Text("ПОДДЕРЖКА", color = if (selectedTab == 1) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { selectedTab = 1 })
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = Border, thickness = 1.dp)

        when (selectedTab) {
            0 -> AccountTab(user = user, subscription = subscription, isLoading = isLoading, onLogout = onLogout)
            1 -> TicketsTab(token = token)
        }
    }
}

@Composable
fun AccountTab(user: UserResponse?, subscription: SubscriptionResponse?, isLoading: Boolean, onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
            SettingsRow(label = "Версия", value = "1.0.0")
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



@Composable
fun TicketsTab(token: String) {
    var tickets by remember { mutableStateOf<List<TicketResponse>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedTicket by remember { mutableStateOf<TicketDetailResponse?>(null) }
    var newSubject by remember { mutableStateOf("") }
    var newMessage by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token) {
        try { tickets = ApiClient.service.getTickets("Bearer $token") }
        catch (_: Exception) {}
        finally { isLoading = false }
    }

    // Диалог создания тикета
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

    // Экран тикета (диалог сообщений)
    selectedTicket?.let { detail ->
        Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp).padding(top = 8.dp),
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
                                selectedTicket = selectedTicket?.copy(ticket = detail.ticket.copy(status = "closed"))
                                tickets = tickets.map { if (it.id == detail.ticket.id) it.copy(status = "closed") else it }
                            } catch (_: Exception) {}
                        }
                    }) { Text("ЗАКРЫТЬ", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
            HorizontalDivider(color = Border)

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(detail.messages) { msg ->
                    val isUser = msg.sender == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .background(if (isUser) Accent else Bg2)
                                .padding(12.dp)
                        ) {
                            Text(msg.message, color = if (isUser) BgDark else TextPrimary, fontSize = 13.sp)
                        }
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
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (tickets.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Нет обращений", color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(tickets) { ticket ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        try {
                                            val detail = ApiClient.service.getTicket("Bearer $token", ticket.id)
                                            selectedTicket = detail
                                        } catch (_: Exception) {}
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
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
            HorizontalDivider(color = Border)
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgDark),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            ) {
                Text("+ НОВОЕ ОБРАЩЕНИЕ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
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