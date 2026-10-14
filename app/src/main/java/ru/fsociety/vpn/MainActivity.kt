package ru.fsociety.vpn

import kotlinx.coroutines.launch
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.fsociety.vpn.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(VpnNotificationHelper.EXTRA_AUTO_CONNECT, false) == true) {
            HomeUiState.autoConnectRequested.value = true
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
            HomeUiState.autoConnectRequested.value = true
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(localized(newBase))
}

/**
 * Повтор API-запроса при сетевых сбоях / 5xx (нестабильная сеть, блокировки в РФ).
 * 401/403 не повторяются — их обрабатывает вызывающий код (refresh / logout).
 */
suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
    var last: Exception? = null
    for (i in 0 until attempts) {
        try {
            return block()
        } catch (e: Exception) {
            val code = (e as? retrofit2.HttpException)?.code()
            // 426 = версия заблокирована: повтор бессмыслен, диалог покажет ApiClient.updateRequired
            if (code == 401 || code == 403 || code == 426 || e is kotlinx.coroutines.CancellationException) throw e
            last = e
            android.util.Log.w("withRetry", "attempt ${i + 1}/$attempts failed: ${e.message}")
            if (i < attempts - 1) kotlinx.coroutines.delay(2000L * (i + 1))
        }
    }
    throw last ?: IllegalStateException("withRetry: no attempts")
}

// ── VPN Events (службы → ConnectionManager) ──

object VpnEvents {
    /** Туннель упал сам (служба остановилась / сервер перестал отвечать) — Kill Switch и переподключение. */
    val vpnDropped = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** VPN выключили снаружи (другое VPN-приложение, настройки Android) — только обновить состояние. */
    val vpnStopped = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}

/** Держать ресивер «живым», пока идёт действие, но не дольше 9 с (лимит системы на ресивер). */
private fun BroadcastReceiver.finishWhenDone(job: kotlinx.coroutines.Job) {
    val pending = goAsync()
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    val finish = { if (done.compareAndSet(false, true)) pending.finish() }
    job.invokeOnCompletion { finish() }
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 9_000)
}

/** «Отключить» / «Отмена» в уведомлении — то же, что кнопка в приложении. */
class VpnDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnNotificationHelper.ACTION_DISCONNECT) return
        finishWhenDone(ConnectionManager.requestDisconnect())
    }
}

/**
 * «Подключить» в уведомлении (фоновый режим): подключение без открытия приложения. Уведомление
 * шлёт сюда, только если VPN уже разрешали; иначе оно само открывает приложение — из ресивера
 * Android 12+ открыть его не даёт («трамплин» уведомлений), поэтому здесь — только подсказка.
 */
class VpnConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnNotificationHelper.ACTION_CONNECT) return
        val ctx = localized(context)   // тексты — на языке приложения, а не системы
        if (TokenStore.access.value.isEmpty()) {
            android.widget.Toast.makeText(context, ctx.getString(R.string.notif_sign_in), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Пользователь сам нажал «Подключить» — prepare() можно (он и выключит чужой VPN, как любое подключение)
        if (VpnService.prepare(context) != null) {
            context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).edit()
                .putBoolean(ConnectionManager.PREF_VPN_CONSENT, false).apply()
            android.widget.Toast.makeText(context, ctx.getString(R.string.notif_open_app), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        finishWhenDone(ConnectionManager.requestNotificationConnect())
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
                CHANNEL_ID, localized(context).getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(false) }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun formatBytes(context: Context, bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f ${context.getString(R.string.unit_gb)}".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f ${context.getString(R.string.unit_mb)}".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.0f ${context.getString(R.string.unit_kb)}".format(bytes / 1024.0)
        else -> "$bytes ${context.getString(R.string.unit_b)}"
    }
}


@Composable
fun App(context: android.content.Context) {
    val prefs = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
    // Токен — из общего хранилища: обновляется сам (ApiClient на 401) и одинаков для всего процесса
    val token by TokenStore.access.collectAsState()
    var showRegister by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var forceUpdateUrl by remember { mutableStateOf<String?>(null) }

    // Выход: токены, профиль, выбор сервера и туннель старого аккаунта не переносим на следующий
    // (иначе следующий аккаунт подключился бы чужим ключом WireGuard в обход API)
    val logout: () -> Unit = {
        TokenStore.clear()
        ProfileCache.clear(context)
        HomeUiState.reset()
        ConnectionManager.requestLogoutReset()
    }

    // Сервер отклонил refresh (сессия кончилась / аккаунт заблокирован) — на экран входа
    LaunchedEffect(Unit) { TokenStore.sessionExpired.collect { logout() } }

    // Любой ответ 426 от API (не только проверка при старте) → блокирующий диалог
    // + разрыв активного туннеля (диалог перекрывает кнопку отключения).
    LaunchedEffect(Unit) {
        ApiClient.updateRequired.collect { url ->
            if (url != null) {
                forceUpdateUrl = url
                ConnectionManager.requestLogoutReset()
            }
        }
    }

    // On startup: check minimum required version + silent token refresh
    LaunchedEffect(Unit) {
        try {
            val cfg = ApiClient.service.getAppConfig()
            ApiClient.maintenance.value = cfg.access?.maintenance
            if (BuildConfig.VERSION_CODE < cfg.android_min_version) {
                // через updateRequired — тот же путь, что и для 426 (диалог + отключение VPN)
                ApiClient.updateRequired.value = cfg.update_url.ifEmpty { "https://fsociety-vpn.org/download" }
            }
        } catch (e: Exception) { AppErrors.log("app-config", e) }

        // Есть refresh, но нет access-токена (старые версии) — тихо получаем новый
        if (token.isEmpty() && TokenStore.refresh.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TokenStore.refreshBlocking("") }
        }
    }

    // Автообновление (Настройки → Обновление → «Автоматически»): при запуске проверяем GitHub
    // (не чаще раза в 6 ч), скачиваем молча и предлагаем установить.
    var autoUpdateApk by remember { mutableStateOf<Pair<String, java.io.File>?>(null) }
    val installApk = rememberApkInstaller()
    LaunchedEffect(Unit) {
        AutoUpdateScheduler.apply(context)  // ночная задача (или её отмена) по текущим настройкам
        if (!prefs.getBoolean(PREF_AUTO_UPDATE, false)) return@LaunchedEffect
        // «Только через Wi-Fi» (по умолчанию): на мобильном интернете не проверяем и не качаем
        val net = prefs.getString(PREF_AUTO_UPDATE_NET, AUTO_UPDATE_WIFI)
        if (net != AUTO_UPDATE_ANY && !isOnWifi(context)) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("auto_update_checked_at", 0L) < 6 * 3600_000L) {
            // недавно проверяли — только предложить уже скачанное
            UpdateManager.newestDownloadedApk(context)?.let { autoUpdateApk = it }
            return@LaunchedEffect
        }
        prefs.edit().putLong("auto_update_checked_at", now).apply()
        UpdateManager.checkForUpdate(BuildConfig.VERSION_NAME).onSuccess { info ->
            if (!info.hasUpdate || info.downloadUrl.isEmpty()) return@onSuccess
            val apk = UpdateManager.downloadedApk(context, info.latestVersion)
                ?: UpdateManager.download(context, info) { }.getOrElse { e ->
                    AppErrors.log("auto update download", e); null
                }
            if (apk != null) autoUpdateApk = info.latestVersion to apk
        }.onFailure { e -> AppErrors.log("auto update check", e) }
    }
    autoUpdateApk?.let { (version, apk) ->
        AlertDialog(
            onDismissRequest = { autoUpdateApk = null },
            containerColor = Bg2,
            title = { Text(stringResource(R.string.update_auto_dialog_title, version), color = TextPrimary) },
            text = { Text(stringResource(R.string.update_auto_dialog_text), color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { autoUpdateApk = null; installApk(apk) }) {
                    Text(stringResource(R.string.update_btn_install), color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { autoUpdateApk = null }) {
                    Text(stringResource(R.string.update_later), color = TextMuted)
                }
            }
        )
    }

    // Force update — блокирует всё; «Обновить» открывает экран обновления в приложении
    // (скачать/установить), а не сайт. Назад — снова к блокирующему окну.
    var showForcedUpdateScreen by remember { mutableStateOf(false) }
    forceUpdateUrl?.let { _ ->
        if (showForcedUpdateScreen) {
            UpdateTab(onBack = { showForcedUpdateScreen = false })
            return
        }
        AlertDialog(
            onDismissRequest = { /* non-dismissible */ },
            title = { Text(stringResource(R.string.update_required_title), color = TextPrimary) },
            text  = { Text(stringResource(R.string.update_required_message), color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { showForcedUpdateScreen = true }) {
                    Text(stringResource(R.string.update_required_button), color = Accent)
                }
            },
            containerColor = Bg2,
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        )
        return  // block everything below while update required
    }

    when {
        token.isNotEmpty() -> AppNavigation(token = token, onLogout = logout)
        showRegister -> RegisterScreen(onBack = { showRegister = false })
        else -> LoginScreen(
            onLogin = { accessToken, refreshToken ->
                ConfigCache.clearAll(context)  // на случай старого кэша от другого аккаунта
                TokenStore.save(accessToken, refreshToken)
            },
            onRegister = { showRegister = true }
        )
    }
}


@Composable
fun AppNavigation(token: String, onLogout: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    var servers by remember { mutableStateOf<List<ServerResponse>>(emptyList()) }
    var serverPings by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // Сохранённый профиль — сразу, без «Загрузка…»; ниже обновляется с сервера
    var user by remember { mutableStateOf(ProfileCache.user(appContext)) }
    var subscription by remember { mutableStateOf(ProfileCache.subscription(appContext)) }
    var isLoadingServers by remember { mutableStateOf(true) }
    var isLoadingSettings by remember { mutableStateOf(user == null) }
    var isRefreshingPings by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<UsageResponse?>(null) }
    // Подключение — общее на процесс (ConnectionManager): переживает смену языка/поворот экрана
    val connectedServer by ConnectionManager.connectedServer
    val isConnected = connectedServer != null
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
    var backgroundMode by remember { mutableStateOf(prefs.getBoolean("background_mode", false)) }
    var killSwitch by remember { mutableStateOf(prefs.getBoolean("kill_switch", false)) }
    var autoReconnect by remember { mutableStateOf(prefs.getBoolean("auto_reconnect", true)) }
    var language by remember { mutableStateOf(prefs.getString("language", "ru") ?: "ru") }
    var splitSettings by remember { mutableStateOf(SplitTunnelingManager.load(prefs)) }

    LaunchedEffect(splitSettings) { VpnManager.splitSettings = splitSettings }

    // Разрешение на уведомления (Android 13+) — один раз, а не при каждом запуске
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) {
            if (!prefs.getBoolean("notif_permission_asked", false)) {
                prefs.edit().putBoolean("notif_permission_asked", true).apply()
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Токен не принят и обновить не вышло (ApiClient сам пробует refresh) / аккаунт заблокирован. */
    fun handleAuthError(e: Exception) {
        val code = (e as? retrofit2.HttpException)?.code()
        if (code == 401 || code == 403) onLogout()
        // Прочее (таймаут / 5xx / сеть) — НЕ разлогиниваем и НЕ обнуляем уже загруженные данные
    }

    // Список серверов, плашка техработ, аккаунт и остаток трафика обновляются и при подключённом
    // VPN (раз в минуту и по свайпу) — не пингуем только серверы.
    val refreshPings: () -> Unit = {
        if (!isRefreshingPings) {
            isRefreshingPings = true
            scope.launch {
                try {
                    try {
                        servers = withRetry(2) { ApiClient.service.getServers("Bearer $token") }
                    } catch (e: Exception) {
                        android.util.Log.w("AppNavigation", "refresh servers failed: ${e.message}")
                    }
                    try {
                        ApiClient.maintenance.value = ApiClient.service.getAppConfig().access?.maintenance
                    } catch (e: Exception) { AppErrors.log("refresh maintenance", e) }
                    try {
                        if (user == null) {
                            user = ApiClient.service.getMe("Bearer $token")
                            subscription = ApiClient.service.getSubscription("Bearer $token")
                            ProfileCache.save(appContext, user, subscription)
                        }
                        // Остаток трафика: раньше загружался один раз за запуск
                        usage = ApiClient.service.getUsage("Bearer $token")
                    } catch (e: Exception) {
                        android.util.Log.w("AppNavigation", "refresh account failed: ${e.message}")
                    }
                    if (!isConnected) serverPings = measureAllPings(servers)
                } finally {
                    isRefreshingPings = false
                }
            }
        }
    }

    LaunchedEffect(token) {
        scope.launch {
            try {
                val result = withRetry { ApiClient.service.getServers("Bearer $token") }
                servers = result
                // Туннель поднят, а состояние пустое (подключение из шторки до запуска процесса UI) —
                // показываем последний сервер из списка по сохранённому имени
                if (ConnectionManager.connectedServer.value == null && ConnectionManager.anyTunnelUp()) {
                    result.firstOrNull { it.displayName(language) == VpnManager.connectedServerName }
                        ?.let { ConnectionManager.connectedServer.value = it }
                }
                scope.launch { serverPings = measureAllPings(result) }
            } catch (e: Exception) {
                handleAuthError(e)
            } finally { isLoadingServers = false }
        }
        scope.launch {
            try {
                user = withRetry { ApiClient.service.getMe("Bearer $token") }
                subscription = withRetry { ApiClient.service.getSubscription("Bearer $token") }
                ProfileCache.save(appContext, user, subscription)
                try { usage = ApiClient.service.getUsage("Bearer $token") } catch (e: Exception) { AppErrors.log("load usage", e) }
            } catch (e: Exception) {
                handleAuthError(e)
            } finally { isLoadingSettings = false }
        }
    }

    LaunchedEffect(Unit) {
        // Don't resurrect the background notification on relaunch if a sing-box /
        // OpenVPN tunnel is already running its own foreground notification.
        if (backgroundMode && !SingboxManager.isConnected() && !OpenVpnService.isRunning) {
            VpnForegroundService.start(context)
        }
    }

    // Открыли из уведомления «Подключить» без разрешения VPN — главный экран подключится сам
    val autoConnectRequested by HomeUiState.autoConnectRequested
    LaunchedEffect(autoConnectRequested) { if (autoConnectRequested) selectedTab = 0 }

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(containerColor = Bg2, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text(stringResource(R.string.nav_home), fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        selectedIconColor = Accent, unselectedIconColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
                NavigationBarItem(
                    selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text(stringResource(R.string.nav_rules), fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        selectedIconColor = Accent, unselectedIconColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
                NavigationBarItem(
                    selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text(stringResource(R.string.nav_settings), fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = Accent, unselectedTextColor = TextMuted,
                        selectedIconColor = Accent, unselectedIconColor = TextMuted,
                        indicatorColor = Color.Transparent)
                )
            }
        }
    ) { innerPadding ->
        // Отступ под клавиатуру (приложение рисуется от края до края — сама система окно не сжимает):
        // иначе поле сообщения в тикете, смена пароля и правила оказывались под клавиатурой.
        // consumeWindowInsets — чтобы не прибавлять к нему высоту нижней панели.
        Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()) {
            when (selectedTab) {
                0 -> HomeScreen(
                    servers = servers,
                    serverPings = serverPings,
                    isLoadingServers = isLoadingServers,
                    isConnected = isConnected,
                    connectedServer = connectedServer,
                    isRefreshingPings = isRefreshingPings,
                    usage = usage,
                    onRefreshPings = refreshPings
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
                    onReloadAccount = refreshPings,
                    backgroundMode = backgroundMode,
                    onBackgroundModeChange = { enabled ->
                        backgroundMode = enabled
                        prefs.edit().putBoolean("background_mode", enabled).apply()
                        if (enabled && !SingboxManager.isConnected() && !OpenVpnService.isRunning)
                            VpnForegroundService.start(context)
                        else if (!isConnected) VpnForegroundService.stop(context)
                    },
                    killSwitch = killSwitch,
                    onKillSwitchChange = { enabled ->
                        killSwitch = enabled
                        prefs.edit().putBoolean("kill_switch", enabled).apply()
                        if (!enabled) ConnectionManager.unblockKillSwitch()  // выключили — снять блокировку
                    },
                    autoReconnect = autoReconnect,
                    onAutoReconnectChange = { enabled ->
                        autoReconnect = enabled
                        prefs.edit().putBoolean("auto_reconnect", enabled).apply()
                    },
                    language = language,
                    onLanguageChange = { lang ->
                        language = lang
                        prefs.edit().putString("language", lang).apply()
                        VpnNotificationHelper.init(context)   // название канала уведомлений — на новом языке
                        (context as? android.app.Activity)?.recreate()
                    }
                )
            }
        }
    }
}
