package ru.fsociety.vpn

import kotlinx.coroutines.launch
import android.Manifest
import android.app.Activity
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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

object AutoConnectRequest {
    val trigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnNotificationHelper.init(this)
        if (intent?.getBooleanExtra(VpnNotificationHelper.EXTRA_AUTO_CONNECT, false) == true) {
            AutoConnectRequest.trigger.tryEmit(Unit)
        }
        enableEdgeToEdge()
        setContent {
            VpnappTheme {
                App(context = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(VpnNotificationHelper.EXTRA_AUTO_CONNECT, false)) {
            AutoConnectRequest.trigger.tryEmit(Unit)
        }
    }
}

// ── VPN Events (для отключения из уведомления) ──

object VpnEvents {
    val disconnectRequested = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val connectSucceeded = MutableSharedFlow<ServerResponse>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    var isConnecting: Boolean = false
}

class VpnDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == VpnNotificationHelper.ACTION_DISCONNECT) {
            val pending = goAsync()
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    VpnManager.disconnect()
                    VpnEvents.disconnectRequested.tryEmit(Unit)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

class VpnConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnNotificationHelper.ACTION_CONNECT) return

        // Если разрешение ещё не выдано — открываем приложение
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            val openApp = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(VpnNotificationHelper.EXTRA_AUTO_CONNECT, true)
            }
            context.startActivity(openApp)
            return
        }

        // Разрешение есть — подключаем без открытия приложения
        val pending = goAsync()
        VpnEvents.isConnecting = true
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
                val token = prefs.getString("token", "") ?: return@launch
                // Получаем список серверов
                val servers = ApiClient.service.getServers("Bearer $token")
                    .filter { it.is_active }
                if (servers.isEmpty()) return@launch
                // Пингуем последовательно и берём лучший
                var best: ServerResponse? = null
                var bestPing = Int.MAX_VALUE
                for (server in servers) {
                    val ping = measurePing(server.id, servers)
                    if (ping < bestPing) { bestPing = ping; best = server }
                }
                best ?: return@launch
                // Подключаемся
                val response = ApiClient.service.getVpnConfig("Bearer $token", best.id)
                if (response.config != null) {
                    val success = VpnManager.connect(context, response.config)
                    if (success) {
                        VpnManager.connectedServerName = best.name
                        VpnEvents.connectSucceeded.tryEmit(best)
                    }
                }
            } catch (_: Exception) {
            } finally {
                VpnEvents.isConnecting = false
                pending.finish()
            }
        }
    }
}

object VpnNotificationHelper {
    const val CHANNEL_ID = "vpn_status_v2"
    const val NOTIFICATION_ID = 1001
    const val ACTION_DISCONNECT = "ru.fsociety.vpn.DISCONNECT"
    const val ACTION_CONNECT = "ru.fsociety.vpn.CONNECT"
    const val EXTRA_AUTO_CONNECT = "auto_connect"

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Статус VPN",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(false) }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun show(context: Context, serverName: String, rx: Long, tx: Long) {
        val disconnectIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, VpnDisconnectReceiver::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("[f]society VPN — Подключён")
            .setContentText("$serverName · ↓ ${formatBytes(rx)} ↑ ${formatBytes(tx)}")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "ОТКЛЮЧИТЬСЯ", disconnectIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f МБ".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.0f КБ".format(bytes / 1024.0)
        else -> "$bytes Б"
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
    var isRefreshingPings by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var connectedServer by remember { mutableStateOf<ServerResponse?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
    var backgroundMode by remember { mutableStateOf(prefs.getBoolean("background_mode", false)) }

    // Запрашиваем разрешение на уведомления (Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* результат не важен */ }
        LaunchedEffect(Unit) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Обновление пингов (только когда VPN не подключён)
    val refreshPings: () -> Unit = {
        if (!isRefreshingPings && servers.isNotEmpty() && !isConnected) {
            isRefreshingPings = true
            scope.launch {
                val jobs = servers.map { server ->
                    scope.launch {
                        val ping = measurePing(server.id, servers)
                        serverPings = serverPings + (server.id to ping)
                    }
                }
                jobs.forEach { it.join() }
                isRefreshingPings = false
            }
        }
    }

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

    // Запуск фонового сервиса при старте если включён фоновый режим
    LaunchedEffect(Unit) {
        if (backgroundMode) VpnForegroundService.start(context)
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
                    isRefreshingPings = isRefreshingPings,
                    onRefreshPings = refreshPings,
                    onConnected = { server ->
                        isConnected = true
                        connectedServer = server
                        VpnManager.connectedServerName = server.name
                        VpnForegroundService.start(context)
                    },
                    onDisconnected = {
                        isConnected = false
                        connectedServer = null
                        if (!backgroundMode) VpnForegroundService.stop(context)
                    }
                )
                1 -> RulesScreen()
                2 -> SettingsScreen(
                    token = token, user = user, subscription = subscription,
                    isLoading = isLoadingSettings, onLogout = onLogout,
                    backgroundMode = backgroundMode,
                    onBackgroundModeChange = { enabled ->
                        backgroundMode = enabled
                        prefs.edit().putBoolean("background_mode", enabled).apply()
                        if (enabled) VpnForegroundService.start(context)
                        else if (!isConnected) VpnForegroundService.stop(context)
                    }
                )
            }
        }
    }
}

// Измеряем TCP пинг до сервера (порт 51820 UDP недоступен из Android без root,
// поэтому меряем через сокет до порта 443/80)
suspend fun measurePing(serverId: String, servers: List<ServerResponse>): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    servers.firstOrNull { it.id == serverId } ?: return@withContext 999
    val samples = mutableListOf<Int>()
    repeat(3) {
        try {
            val start = System.currentTimeMillis()
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("fsociety-vpn.org", 443), 3000)
            socket.close()
            samples.add((System.currentTimeMillis() - start).toInt())
        } catch (_: Exception) {}
        delay(300)
    }
    if (samples.isEmpty()) 999 else samples.average().toInt()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    token: String,
    servers: List<ServerResponse>,
    serverPings: Map<String, Int>,
    isLoadingServers: Boolean,
    isConnected: Boolean,
    connectedServer: ServerResponse?,
    isRefreshingPings: Boolean,
    onRefreshPings: () -> Unit,
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

    // Авто-обновление пингов раз в 60 секунд
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            onRefreshPings()
        }
    }

    // Отключение по нажатию кнопки в уведомлении
    LaunchedEffect(Unit) {
        VpnEvents.disconnectRequested.collect {
            onDisconnected()
            statusMsg = ""
        }
    }

    // Подключение из уведомления без открытия приложения
    LaunchedEffect(Unit) {
        VpnEvents.connectSucceeded.collect { server ->
            onConnected(server)
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

        // Заголовок
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
                } else if (isConnected && connectedServer != null) {
                    val connectedPing = serverPings[connectedServer.id]
                    val pingText = when {
                        connectedPing == null || connectedPing >= 999 -> "● ${connectedServer.name}"
                        else -> "● ${connectedServer.name} · ${connectedPing}мс"
                    }
                    Text(pingText, color = Accent, fontSize = 11.sp,
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

        // Список серверов с pull-to-refresh
        PullToRefreshBox(
            isRefreshing = isRefreshingPings && !isLoadingServers,
            onRefresh = onRefreshPings,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
        if (isLoadingServers) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
            }
        } else if (displayedServers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (serverListTab == 1) "Удерживайте сервер\nчтобы добавить в избранное"
                    else "Нет доступных серверов",
                    color = TextMuted, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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
        } // end PullToRefreshBox

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
fun SettingsScreen(
    token: String, user: UserResponse?, subscription: SubscriptionResponse?,
    isLoading: Boolean, onLogout: () -> Unit,
    backgroundMode: Boolean, onBackgroundModeChange: (Boolean) -> Unit
) {
    var currentScreen by remember { mutableStateOf("main") }

    BackHandler(enabled = currentScreen != "main") {
        currentScreen = "main"
    }

    when (currentScreen) {
        "account" -> AccountTab(user = user, subscription = subscription, isLoading = isLoading, onLogout = onLogout, onBack = { currentScreen = "main" })
        "support" -> TicketsTab(token = token, onBack = { currentScreen = "main" })
        "background" -> BackgroundModeTab(
            backgroundMode = backgroundMode,
            onBackgroundModeChange = onBackgroundModeChange,
            onBack = { currentScreen = "main" }
        )
        else -> Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
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
                title = "Фоновый режим",
                subtitle = if (backgroundMode) "Включён" else "Выключен",
                onClick = { currentScreen = "background" }
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
fun AccountTab(user: UserResponse?, subscription: SubscriptionResponse?, isLoading: Boolean, onLogout: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.padding(24.dp).padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = Accent, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onBack() })
            Spacer(modifier = Modifier.width(16.dp))
            Text("Аккаунт.", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)
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

    // Системная кнопка "назад": из тикета → список, из списка → настройки
    BackHandler {
        if (selectedTicket != null) selectedTicket = null
        else onBack()
    }

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

    // Диалог лимита
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

    // Диалог удаления тикета
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

    // Экран тикета
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
                            // Запрашиваем исключение из оптимизации батареи
                            val pm = context.getSystemService(PowerManager::class.java)
                            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
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
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
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