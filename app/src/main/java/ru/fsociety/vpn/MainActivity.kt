package ru.fsociety.vpn

import kotlinx.coroutines.launch
import android.Manifest
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fsociety.vpn.ui.theme.*

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
                    if (SingboxManager.isConnected()) {
                        XrayVpnService.stop(context) // service сам вызовет SingboxManager.disconnect()
                    } else {
                        VpnManager.disconnect()
                    }
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
                val servers = ApiClient.service.getServers("Bearer $token")
                    .filter { it.is_active && it.allow_auto_connect }
                if (servers.isEmpty()) return@launch
                var best: ServerResponse? = null
                var bestPing = Int.MAX_VALUE
                for (server in servers) {
                    val ping = measurePing(server.id, servers)
                    if (ping < bestPing) { bestPing = ping; best = server }
                }
                best ?: return@launch
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
    val scope = rememberCoroutineScope()

    // On startup: if no access token but refresh token exists, try to refresh silently
    LaunchedEffect(Unit) {
        if (token.isEmpty()) {
            val savedRefresh = prefs.getString("refresh_token", null)
            if (!savedRefresh.isNullOrEmpty()) {
                try {
                    val result = ApiClient.service.refresh(RefreshRequest(savedRefresh))
                    if (result.access_token.isNotEmpty()) {
                        prefs.edit()
                            .putString("token", result.access_token)
                            .putString("refresh_token", result.refresh_token.ifEmpty { savedRefresh })
                            .apply()
                        token = result.access_token
                    }
                } catch (_: Exception) {
                    prefs.edit().remove("refresh_token").apply()
                }
            }
        }
    }

    when {
        token.isNotEmpty() -> AppNavigation(
            token = token,
            onLogout = {
                prefs.edit().remove("token").remove("refresh_token").apply()
                token = ""
            },
            onSessionExpired = { newToken, newRefresh ->
                prefs.edit()
                    .putString("token", newToken)
                    .putString("refresh_token", newRefresh)
                    .apply()
                token = newToken
            }
        )
        showRegister -> RegisterScreen(onBack = { showRegister = false })
        else -> LoginScreen(
            onLogin = { accessToken, refreshToken ->
                prefs.edit()
                    .putString("token", accessToken)
                    .putString("refresh_token", refreshToken)
                    .apply()
                token = accessToken
            },
            onRegister = { showRegister = true }
        )
    }
}


@Composable
fun AppNavigation(token: String, onLogout: () -> Unit, onSessionExpired: (String, String) -> Unit = { _, _ -> }) {
    var selectedTab by remember { mutableStateOf(0) }

    var servers by remember { mutableStateOf<List<ServerResponse>>(emptyList()) }
    var serverPings by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var user by remember { mutableStateOf<UserResponse?>(null) }
    var subscription by remember { mutableStateOf<SubscriptionResponse?>(null) }
    var isLoadingServers by remember { mutableStateOf(true) }
    var isLoadingSettings by remember { mutableStateOf(true) }
    var isRefreshingPings by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<UsageResponse?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var connectedServer by remember { mutableStateOf<ServerResponse?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
    var backgroundMode by remember { mutableStateOf(prefs.getBoolean("background_mode", false)) }
    var killSwitch by remember { mutableStateOf(prefs.getBoolean("kill_switch", false)) }
    var splitSettings by remember { mutableStateOf(SplitTunnelingManager.load(prefs)) }

    LaunchedEffect(killSwitch) { VpnManager.killSwitchEnabled = killSwitch }
    LaunchedEffect(splitSettings) { VpnManager.splitSettings = splitSettings }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                // Синхронизируем состояние VPN при открытии приложения —
                // на случай если подключение произошло из уведомления в фоне
                if (VpnManager.isConnected() && VpnManager.connectedServerName.isNotEmpty()) {
                    val connected = result.firstOrNull { it.name == VpnManager.connectedServerName }
                    if (connected != null) {
                        isConnected = true
                        connectedServer = connected
                    }
                }
                result.forEach { server ->
                    scope.launch {
                        val ping = measurePing(server.id, servers)
                        serverPings = serverPings + (server.id to ping)
                    }
                }
            } catch (e: Exception) {
                if ((e as? retrofit2.HttpException)?.code() == 401) {
                    val savedRefresh = prefs.getString("refresh_token", null)
                    if (!savedRefresh.isNullOrEmpty()) {
                        try {
                            val r = ApiClient.service.refresh(RefreshRequest(savedRefresh))
                            if (r.access_token.isNotEmpty()) onSessionExpired(r.access_token, r.refresh_token)
                            return@launch
                        } catch (_: Exception) {}
                    }
                    onLogout()
                }
            } finally { isLoadingServers = false }
        }
        scope.launch {
            try {
                user = ApiClient.service.getMe("Bearer $token")
                subscription = ApiClient.service.getSubscription("Bearer $token")
                try { usage = ApiClient.service.getUsage("Bearer $token") } catch (_: Exception) {}
            } catch (e: Exception) {
                if ((e as? retrofit2.HttpException)?.code() == 401) {
                    val savedRefresh = prefs.getString("refresh_token", null)
                    if (!savedRefresh.isNullOrEmpty()) {
                        try {
                            val r = ApiClient.service.refresh(RefreshRequest(savedRefresh))
                            if (r.access_token.isNotEmpty()) onSessionExpired(r.access_token, r.refresh_token)
                            return@launch
                        } catch (_: Exception) {}
                    }
                    onLogout()
                }
            } finally { isLoadingSettings = false }
        }
    }

    LaunchedEffect(Unit) {
        if (backgroundMode) VpnForegroundService.start(context)
    }

    // Авто-подключение когда приложение открылось из уведомления без VPN-разрешения
    LaunchedEffect(Unit) {
        AutoConnectRequest.trigger.collect {
            if (!isConnected && servers.isNotEmpty()) {
                val best = servers
                    .filter { it.is_active && it.allow_auto_connect }
                    .minByOrNull { serverPings[it.id] ?: 999 }
                if (best != null) {
                    selectedTab = 0  // переключаемся на главный экран
                    // HomeScreen подхватит выбранный сервер и подключится
                    // через существующий механизм авто-выбора
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        VpnManager.tunnelDropped.collect {
            isConnected = false
            connectedServer = null
            if (!backgroundMode) VpnForegroundService.stop(context)
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
                    isRefreshingPings = isRefreshingPings,
                    usage = usage,
                    onRefreshPings = refreshPings,
                    onConnected = { server ->
                        isConnected = true
                        connectedServer = server
                        VpnManager.connectedServerName = server.name
                        // VpnForegroundService только для WireGuard/AmneziaWG.
                        // Для VLESS уведомление управляется XrayVpnService (sing-box).
                        if (server.server_type != "vless") {
                            VpnForegroundService.start(context)
                        }
                    },
                    onDisconnected = {
                        isConnected = false
                        connectedServer = null
                        if (!backgroundMode) VpnForegroundService.stop(context)
                    }
                )
                1 -> RulesScreen(
                    settings = splitSettings,
                    onSettingsChange = { newSettings ->
                        splitSettings = newSettings
                        SplitTunnelingManager.save(prefs, newSettings)
                    }
                )
                2 -> SettingsScreen(
                    token = token, user = user, subscription = subscription,
                    isLoading = isLoadingSettings, onLogout = onLogout,
                    backgroundMode = backgroundMode,
                    onBackgroundModeChange = { enabled ->
                        backgroundMode = enabled
                        prefs.edit().putBoolean("background_mode", enabled).apply()
                        if (enabled) VpnForegroundService.start(context)
                        else if (!isConnected) VpnForegroundService.stop(context)
                    },
                    killSwitch = killSwitch,
                    onKillSwitchChange = { enabled ->
                        killSwitch = enabled
                        prefs.edit().putBoolean("kill_switch", enabled).apply()
                    }
                )
            }
        }
    }
}
