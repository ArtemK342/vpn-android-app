package ru.fsociety.vpn

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.fsociety.vpn.ui.theme.*

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f МБ".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}

suspend fun measurePing(serverId: String, servers: List<ServerResponse>): Int =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val server = servers.firstOrNull { it.id == serverId } ?: return@withContext 999
        val host = server.endpoint?.split(":")?.firstOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return@withContext 999
        try {
            val start = System.currentTimeMillis()
            val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "3", host))
            val exitCode = process.waitFor()
            if (exitCode == 0) (System.currentTimeMillis() - start).toInt() else 999
        } catch (_: Exception) { 999 }
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
    usage: UsageResponse? = null,
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
    var pendingServerType by remember { mutableStateOf("wireguard") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val config = pendingConfig ?: return@rememberLauncherForActivityResult
            val server = pendingServer ?: return@rememberLauncherForActivityResult
            val serverType = pendingServerType
            pendingConfig = null
            pendingServer = null
            scope.launch {
                statusMsg = "Подключение..."
                if (serverType == "vless") {
                    XrayVpnService.start(context, config, server.name)
                    onConnected(server)
                    statusMsg = ""
                } else {
                    val success = VpnManager.connect(context, config)
                    if (success) { onConnected(server); statusMsg = "" }
                    else statusMsg = "Ошибка подключения"
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
                .filter { it.isConnectable && it.allow_auto_connect }
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

    suspend fun connectToServer(server: ServerResponse) {
        isConnecting = true
        statusMsg = "Получение конфигурации..."
        android.util.Log.d("HomeScreen", "connectToServer: ${server.name}, type=${server.server_type}")
        try {
            if (server.server_type == "vless") {
                val response = ApiClient.service.getVlessConfig("Bearer $token", server.id)
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    pendingConfig = response.config
                    pendingServer = server
                    pendingServerType = "vless"
                    vpnPermissionLauncher.launch(intent)
                } else {
                    statusMsg = "Подключение..."
                    XrayVpnService.start(context, response.config, server.name)
                    onConnected(server)
                    statusMsg = ""
                    isConnecting = false
                }
            } else {
                val response = ApiClient.service.getVpnConfig("Bearer $token", server.id)
                if (response.config != null) {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        pendingConfig = response.config
                        pendingServer = server
                        pendingServerType = "wireguard"
                        vpnPermissionLauncher.launch(intent)
                    } else {
                        statusMsg = "Подключение..."
                        val success = VpnManager.connect(context, response.config)
                        if (success) { onConnected(server); statusMsg = "" }
                        else statusMsg = "Ошибка подключения"
                        isConnecting = false
                    }
                } else {
                    statusMsg = response.message ?: "Ошибка"
                    isConnecting = false
                }
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
                // Баннер трафика для бесплатных пользователей
                if (usage != null && usage.is_limited) {
                    val used = usage.bytes_used
                    val limit = usage.limit_bytes
                    val remaining = (limit - used).coerceAtLeast(0L)
                    val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
                    val bannerColor = when {
                        fraction >= 1f -> ErrorRed
                        fraction >= 0.8f -> Color(0xFFFFAA00)
                        else -> TextMuted
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Осталось ${formatBytes(remaining)} из ${formatBytes(limit)}",
                        color = bannerColor, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(2.dp)
                            .background(Border)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(bannerColor)
                        )
                    }
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
                                        if (server.isConnectable && !isConnecting) {
                                            selectedServer = server
                                            if (!isConnected) {
                                                scope.launch { connectToServer(server) }
                                            } else if (!isThisConnected) {
                                                scope.launch {
                                                    isConnecting = true
                                                    statusMsg = "Отключение..."
                                                    if (SingboxManager.isConnected()) {
                                                        XrayVpnService.stop(context)
                                                    } else {
                                                        VpnManager.disconnect()
                                                    }
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
                                    color = if (server.isUnavailable) TextMuted else TextPrimary,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                if (favoriteServers.contains(server.name)) {
                                    Text("★ избранное", color = Accent,
                                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                // Статус-сообщение для degraded/attacked
                                if (server.hasWarning && server.status_message != null) {
                                    Text(
                                        "⚠ ${server.status_message}${if (server.status_message_extra != null) " — ${server.status_message_extra}" else ""}",
                                        color = Color(0xFFF59E0B),
                                        fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Text(
                                text = when {
                                    isThisConnected        -> "● ПОДКЛЮЧЁН"
                                    server.isUnavailable   -> "СКОРО"
                                    !server.isConnectable  -> when (server.status) {
                                        "testing"     -> "⚡ ТЕСТ."
                                        "maintenance" -> "🔧 ОБСЛ."
                                        else          -> "СКОРО"
                                    }
                                    server.hasWarning      -> "⚠ ${ping?.let { if (it >= 999) "—" else "${it}мс" } ?: "..."}"
                                    ping == null           -> "● ..."
                                    ping >= 999            -> "● —"
                                    else                   -> "● ${ping}мс"
                                },
                                color = when {
                                    isThisConnected       -> Accent
                                    server.isUnavailable  -> TextMuted
                                    !server.isConnectable -> TextMuted
                                    server.hasWarning     -> Color(0xFFF59E0B)
                                    ping == null || ping >= 999 -> TextMuted
                                    ping < 100  -> Accent
                                    ping < 200  -> Color(0xFFFFAA00)
                                    else        -> ErrorRed
                                },
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        HorizontalDivider(color = Border, thickness = 1.dp)
                    }
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
                        if (SingboxManager.isConnected()) {
                            XrayVpnService.stop(context)
                        } else {
                            VpnManager.disconnect()
                        }
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
