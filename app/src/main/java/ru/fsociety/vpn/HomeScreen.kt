package ru.fsociety.vpn

import android.app.Activity
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.fsociety.vpn.ui.theme.*

fun formatBytes(bytes: Long, context: Context): String {
    val gb = context.getString(R.string.unit_gb)
    val mb = context.getString(R.string.unit_mb)
    val kb = context.getString(R.string.unit_kb)
    val b  = context.getString(R.string.unit_b)
    return when {
        bytes >= 1_073_741_824L -> "%.1f $gb".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f $mb".format(bytes / 1_048_576.0)
        bytes >= 1024L          -> "%.0f $kb".format(bytes / 1024.0)
        else                    -> "$bytes $b"
    }
}

suspend fun measurePing(serverId: String, servers: List<ServerResponse>): Int =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val server = servers.firstOrNull { it.id == serverId } ?: return@withContext 999
        val parts = server.endpoint?.split(":") ?: return@withContext 999
        val host = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext 999
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 443
        fun tcpPing(p: Int): Int? = try {
            val start = System.currentTimeMillis()
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, p), 2000) }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) { null }
        // Все порты проверяются одновременно (раньше по очереди, до 3×2 с на сервер),
        // результат берётся в порядке приоритета: порт сервера → 443 → 22.
        kotlinx.coroutines.coroutineScope {
            val main = async { tcpPing(port) }
            val https = if (port != 443) async { tcpPing(443) } else null
            val ssh = async { tcpPing(22) }
            main.await() ?: https?.await() ?: ssh.await() ?: 999
        }
    }

/**
 * Автовыбор сервера: доступный, с разрешённым авто-подключением (флаги из админки)
 * и не «только по подписке» для бесплатного пользователя; среди них — с минимальным пингом.
 * [wireguardOnly] — для фонового автоподключения, которое умеет только WireGuard.
 */
fun autoSelectServer(
    servers: List<ServerResponse>,
    pings: Map<String, Int>,
    wireguardOnly: Boolean = false
): ServerResponse? =
    servers
        .filter { it.canConnect && it.allow_auto_connect }
        .filter { !wireguardOnly || it.server_type == "wireguard" }
        .minByOrNull { pings[it.id] ?: 999 }

/** Пинги всех серверов параллельно; возвращаются разом, чтобы в списке появлялись одновременно. */
suspend fun measureAllPings(servers: List<ServerResponse>): Map<String, Int> =
    kotlinx.coroutines.coroutineScope {
        servers.map { server -> async { server.id to measurePing(server.id, servers) } }
            .awaitAll()
            .toMap()
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
    autoReconnect: Boolean = true,
    onRefreshPings: () -> Unit,
    onConnected: (ServerResponse) -> Unit,
    onDisconnected: () -> Unit
) {
    var selectedServer by remember { mutableStateOf<ServerResponse?>(null) }
    // Пока пользователь сам не выбрал сервер, автовыбор пересчитывается по мере прихода пингов
    var userPickedServer by remember { mutableStateOf(false) }
    var serverListTab by remember { mutableStateOf(0) }
    var favoriteServers by remember { mutableStateOf(setOf<String>()) }
    var showFavoriteDialog by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var pendingConfig by remember { mutableStateOf<String?>(null) }
    var pendingServer by remember { mutableStateOf<ServerResponse?>(null) }
    var pendingServerType by remember { mutableStateOf("wireguard") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lang = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
        .getString("language", "ru") ?: "ru"

    val strConnecting   = stringResource(R.string.home_connecting)
    val strGettingConfig = stringResource(R.string.home_getting_config)
    val strDisconnecting = stringResource(R.string.home_disconnecting)
    val strErrorConnect = stringResource(R.string.home_error_connect)
    val strVpnDenied    = stringResource(R.string.home_vpn_denied)
    val strTimeout      = stringResource(R.string.home_error_timeout)
    val strServerUnreachable = stringResource(R.string.home_error_server_unreachable)
    val strMs           = stringResource(R.string.unit_ms)

    // Break circular deps: connectToServer ↔ watchdogReconnect ↔ vpnPermissionLauncher
    var launchVpnIntent: ((android.content.Intent) -> Unit)? = null
    var watchdogReconnectRef: (suspend (ServerResponse) -> Unit)? = null

    // sing-box (VLESS/Hysteria2/Trojan): «подключено» только после того, как
    // XrayVpnService проверил, что трафик реально идёт через сервер.
    suspend fun startSingbox(config: String, server: ServerResponse) {
        statusMsg = strConnecting
        SingboxManager.startResult.value = null
        XrayVpnService.start(context, config, server.displayName(lang))
        val ok = kotlinx.coroutines.withTimeoutOrNull(60_000) {
            SingboxManager.startResult.first { it != null }
        }
        if (ok == true) {
            onConnected(server)
            statusMsg = ""
        } else {
            if (ok == null) XrayVpnService.stop(context)
            statusMsg = strServerUnreachable
        }
        isConnecting = false
    }

    suspend fun connectToServer(server: ServerResponse) {
        isConnecting = true
        statusMsg = strGettingConfig
        try {
            // Настройки → Доступ (техработы / протокол / тип аккаунта): спрашиваем до
            // подключения — так блокируется и WireGuard из кэша конфигов. Если API
            // недоступен (сеть, блокировки), не мешаем: решит сервер при выдаче конфига.
            val access = try {
                ApiClient.service.checkAccess("Bearer $token", server.server_type)
            } catch (e: java.io.IOException) { null }
            if (access != null && !access.allowed) {
                statusMsg = access.text(lang) ?: strErrorConnect
                isConnecting = false
                return
            }
            if (server.server_type == "vless") {
                val response = ApiClient.service.getVlessConfig("Bearer $token", server.id)
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    pendingConfig = response.config
                    pendingServer = server
                    pendingServerType = "vless"
                    launchVpnIntent?.invoke(intent)
                } else {
                    startSingbox(response.config, server)
                }
            } else if (server.server_type == "hysteria") {
                val response = ApiClient.service.getHysteriaConfig("Bearer $token", server.id)
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    pendingConfig = response.config
                    pendingServer = server
                    pendingServerType = "hysteria"
                    launchVpnIntent?.invoke(intent)
                } else {
                    startSingbox(response.config, server)
                }
            } else if (server.server_type == "trojan") {
                val response = ApiClient.service.getTrojanConfig("Bearer $token", server.id)
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    pendingConfig = response.config
                    pendingServer = server
                    pendingServerType = "trojan"
                    launchVpnIntent?.invoke(intent)
                } else {
                    startSingbox(response.config, server)
                }
            } else if (server.server_type == "openvpn_cloak") {
                var bodyBytes: ByteArray? = null
                var lastEx: Exception? = null
                for (attempt in 1..3) {
                    try {
                        if (attempt > 1) kotlinx.coroutines.delay(1500)
                        val raw = ApiClient.bundleService.getOpenVpnCloakBundle("Bearer $token", server.id)
                        bodyBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            raw.body()?.use { it.bytes() }
                        }
                        if (bodyBytes != null) break
                    } catch (e: Exception) {
                        lastEx = e
                    }
                }
                if (bodyBytes == null) throw lastEx ?: Exception("Empty response from openvpn-cloak-bundle")
                val parsed = org.json.JSONObject(String(bodyBytes, Charsets.UTF_8))
                val ovpnConfig  = parsed.getString("ovpn_config").reversed()
                val cloakConfig = parsed.getString("cloak_config")
                val host        = parsed.getString("host")
                val bundle = org.json.JSONObject().apply {
                    put("hostName", host)
                    put("openvpn_config_data", org.json.JSONObject().put("config", ovpnConfig))
                    put("cloak_config", cloakConfig)
                }.toString()
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    pendingConfig = bundle
                    pendingServer = server
                    pendingServerType = "openvpn_cloak"
                    statusMsg = strConnecting
                    launchVpnIntent?.invoke(intent)
                } else {
                    statusMsg = strConnecting
                    OpenVpnService.start(context, bundle, server.displayName(lang))
                    onConnected(server)
                    statusMsg = ""
                    isConnecting = false
                }
            } else {
                val cachedConfig = ConfigCache.get(context, server.id)
                val wgConfig: String? = if (cachedConfig != null) {
                    cachedConfig
                } else {
                    val response = ApiClient.service.getVpnConfig("Bearer $token", server.id)
                    if (response.config != null) {
                        ConfigCache.save(context, server.id, response.config)
                        response.config
                    } else {
                        statusMsg = response.message ?: context.getString(R.string.home_error_connect)
                        isConnecting = false
                        null
                    }
                }
                if (wgConfig != null) {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        pendingConfig = wgConfig
                        pendingServer = server
                        pendingServerType = "wireguard"
                        launchVpnIntent?.invoke(intent)
                    } else {
                        statusMsg = strConnecting
                        val success = VpnManager.connect(context, wgConfig)
                        if (success) {
                            onConnected(server); statusMsg = ""
                            scope.launch { watchdogReconnectRef?.invoke(server) }
                        } else statusMsg = strErrorConnect
                        isConnecting = false
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            statusMsg = strTimeout
            isConnecting = false
        } catch (e: retrofit2.HttpException) {
            // 403 бывает по разным причинам: сервер только по подписке / лимит исчерпан
            val detail = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null } ?: ""
            statusMsg = when {
                // 503 {detail: {code, message, message_en}} — блокировка из «Настройки → Доступ»
                e.code() == 503 -> try {
                    val d = org.json.JSONObject(detail).getJSONObject("detail")
                    d.optString(if (lang == "en") "message_en" else "message").ifBlank { null }
                } catch (_: Exception) { null } ?: strErrorConnect
                e.code() == 403 && (detail.contains("подписк", true) || detail.contains("subscri", true)) ->
                    context.getString(R.string.home_error_subscription_only)
                e.code() == 403 -> context.getString(R.string.home_limit_reached)
                else -> context.getString(R.string.home_error_generic, "HTTP ${e.code()}: ${e.message()}")
            }
            isConnecting = false
        } catch (e: Exception) {
            statusMsg = context.getString(R.string.home_error_generic, e.message ?: "")
            isConnecting = false
        }
    }

    suspend fun watchdogReconnect(server: ServerResponse) {
        delay(20_000)
        if (!VpnManager.isConnected()) return
        val (rx, tx) = VpnManager.getTrafficStats()
        if (tx > 0L && rx == 0L) {
            ConfigCache.clear(context, server.id)
            VpnManager.disconnect()
            onDisconnected()
            delay(500)
            connectToServer(server)
        }
    }
    watchdogReconnectRef = ::watchdogReconnect

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
                statusMsg = strConnecting
                if (serverType == "vless" || serverType == "hysteria" || serverType == "trojan") {
                    startSingbox(config, server)
                } else if (serverType == "openvpn_cloak") {
                    OpenVpnService.start(context, config, server.displayName(lang))
                    onConnected(server)
                    statusMsg = ""
                } else {
                    val success = VpnManager.connect(context, config)
                    if (success) {
                        onConnected(server); statusMsg = ""
                        scope.launch { watchdogReconnect(server) }
                    } else statusMsg = strErrorConnect
                }
                isConnecting = false
            }
        } else {
            statusMsg = strVpnDenied
            isConnecting = false
        }
    }
    launchVpnIntent = { vpnPermissionLauncher.launch(it) }

    // Выбранный сервер в актуальном виде: список обновляется раз в минуту, и сервер
    // мог стать недоступным (техработы, подписка) — тогда кнопка «Подключить» гаснет.
    val selectedFresh = selectedServer?.let { s -> servers.find { it.id == s.id } ?: s }

    LaunchedEffect(servers, serverPings) {
        // Раньше выбор делался один раз — до прихода пингов (все 999) → всегда первый
        // сервер в списке. Теперь пересчитывается, когда пинги готовы, пока
        // пользователь не выбрал сервер вручную.
        if (!userPickedServer && servers.isNotEmpty() && !isConnected) {
            autoSelectServer(servers, serverPings)?.let { selectedServer = it }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            onRefreshPings()
        }
    }

    LaunchedEffect(Unit) {
        VpnEvents.disconnectRequested.collect {
            onDisconnected()
            statusMsg = ""
        }
    }

    LaunchedEffect(Unit) {
        VpnEvents.connectSucceeded.collect { server ->
            onConnected(server)
        }
    }

    // Auto-reconnect: when VPN drops unexpectedly, retry up to 3 times
    val strReconnecting = stringResource(R.string.reconnecting)
    val strReconnectFailed = stringResource(R.string.reconnect_failed)
    LaunchedEffect(Unit) {
        VpnEvents.vpnDropped.collect {
            val serverToReconnect = connectedServer ?: run { onDisconnected(); return@collect }
            if (!autoReconnect) { onDisconnected(); return@collect }
            onDisconnected()
            var reconnected = false
            for (attempt in 1..3) {
                statusMsg = "$strReconnecting ($attempt/3)…"
                delay(attempt * 2000L) // 2s, 4s, 6s
                try {
                    connectToServer(serverToReconnect)
                    reconnected = true
                    break
                } catch (_: Exception) { }
            }
            if (!reconnected) {
                statusMsg = strReconnectFailed
                isConnecting = false
            }
        }
    }

    val displayedServers = if (serverListTab == 0) servers
    else servers.filter { favoriteServers.contains(it.name) }

    showFavoriteDialog?.let { serverName ->
        val isFav = favoriteServers.contains(serverName)
        AlertDialog(
            onDismissRequest = { showFavoriteDialog = null },
            containerColor = Bg2,
            title = { Text(serverName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(if (isFav) R.string.home_remove_favorite else R.string.home_add_favorite),
                    color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    favoriteServers = if (isFav) favoriteServers - serverName else favoriteServers + serverName
                    showFavoriteDialog = null
                }) {
                    Text(
                        stringResource(if (isFav) R.string.home_btn_remove else R.string.home_btn_add),
                        color = Accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showFavoriteDialog = null }) {
                    Text(stringResource(R.string.home_btn_cancel), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {

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
                        connectedPing == null || connectedPing >= 999 -> "● ${connectedServer.displayName(lang)}"
                        else -> "● ${connectedServer.displayName(lang)} · ${connectedPing}$strMs"
                    }
                    Text(pingText, color = Accent, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
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
                        stringResource(R.string.home_traffic_remaining,
                            formatBytes(remaining, context), formatBytes(limit, context)),
                        color = bannerColor, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(0.6f).height(2.dp).background(Border)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(bannerColor)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.home_free_speed), color = TextMuted,
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        HorizontalDivider(color = Border, thickness = 1.dp)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.home_tab_all),
                color = if (serverListTab == 0) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (serverListTab == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { serverListTab = 0 })
            Text(stringResource(R.string.home_tab_favorites),
                color = if (serverListTab == 1) Accent else TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (serverListTab == 1) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { serverListTab = 1 })
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (isConnected) "● ${connectedServer?.displayName(lang) ?: ""}"
                       else stringResource(R.string.home_disconnected),
                color = if (isConnected) Accent else TextMuted,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }

        HorizontalDivider(color = Border, thickness = 1.dp)

        // Плашка технических работ (админка → Настройки → Доступ), из /app-config
        val maintenance by ApiClient.maintenance.collectAsState()
        maintenance?.takeIf { it.enabled }?.let { m ->
            Text(
                "⚠ " + (m.text(lang) ?: stringResource(R.string.home_maintenance_default)),
                color = Color(0xFFF59E0B), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x14F59E0B))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
            HorizontalDivider(color = Border, thickness = 1.dp)
        }

        val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshingPings && !isLoadingServers,
            onRefresh = onRefreshPings,
            state = pullState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            indicator = {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = isRefreshingPings && !isLoadingServers,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = Accent,
                    containerColor = Bg2
                )
            }
        ) {
            if (isLoadingServers) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                }
            } else if (displayedServers.isEmpty()) {
                // Прокручиваемый контейнер — иначе pull-to-refresh на пустом списке не срабатывает
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(
                            if (serverListTab == 1) R.string.home_hint_long_press
                            else R.string.home_no_servers
                        ),
                        color = TextMuted, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                    )
                    if (serverListTab == 0) {
                        TextButton(onClick = onRefreshPings, enabled = !isRefreshingPings) {
                            Text(stringResource(R.string.home_refresh), color = Accent, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(displayedServers) { server ->
                        val ping = serverPings[server.id]
                        val isThisConnected = connectedServer?.id == server.id && isConnected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Недоступные (по подписке / закрытые в «Доступе» / статус из админки) не нажимаются
                                .combinedClickable(
                                    enabled = server.canConnect,
                                    onClick = {
                                        if (!isConnecting) {
                                            selectedServer = server
                                            userPickedServer = true
                                            if (!isConnected) {
                                                scope.launch { connectToServer(server) }
                                            } else {
                                                scope.launch {
                                                    isConnecting = true
                                                    statusMsg = strDisconnecting
                                                    ConfigCache.clear(context, server.id)
                                                    if (OpenVpnService.isRunning) {
                                                        OpenVpnService.stop(context)
                                                        var waited = 0
                                                        while (OpenVpnService.isRunning && waited < 5000) {
                                                            delay(200); waited += 200
                                                        }
                                                        onDisconnected()
                                                    } else if (SingboxManager.isConnected()) {
                                                        XrayVpnService.stop(context)
                                                        onDisconnected()
                                                    } else {
                                                        VpnManager.disconnect()
                                                        onDisconnected()
                                                    }
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
                                    "Finland"     -> "🇫🇮"
                                    "Switzerland" -> "🇨🇭"
                                    "Russia"      -> "🇷🇺"
                                    "Germany"     -> "🇩🇪"
                                    "Netherlands" -> "🇳🇱"
                                    else          -> "🌍"
                                }, fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(server.displayName(lang),
                                    color = if (server.canConnect || isThisConnected) TextPrimary else TextMuted,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "· " + when (server.server_type) {
                                        "vless"         -> "VLESS+Reality"
                                        "hysteria"      -> "Hysteria2"
                                        "trojan"        -> "Trojan"
                                        "openvpn_cloak" -> "OpenVPN+Cloak"
                                        else            -> "AmneziaWG"
                                    },
                                    color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                )
                                if (favoriteServers.contains(server.name)) {
                                    Text(stringResource(R.string.home_favorite_label),
                                        color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                // Причина закрытия протокола / типа аккаунта (техработы уже на общей плашке)
                                server.access_block?.takeIf { it.code != "maintenance" }?.text(lang)?.let {
                                    Text(it, color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                }
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
                                    isThisConnected      -> stringResource(R.string.home_status_connected)
                                    server.isUnavailable -> stringResource(R.string.home_status_soon)
                                    !server.connectable  -> when (server.status) {
                                        "testing"     -> stringResource(R.string.home_status_test)
                                        "maintenance" -> stringResource(R.string.home_status_maintenance)
                                        else          -> stringResource(R.string.home_status_soon)
                                    }
                                    server.access_block != null -> stringResource(
                                        if (server.access_block.code == "maintenance") R.string.home_status_maintenance
                                        else R.string.home_status_blocked)
                                    server.subscription_required -> stringResource(R.string.home_status_subscription)
                                    server.hasWarning    -> "⚠ ${ping?.let { if (it >= 999) "—" else "$it$strMs" } ?: "..."}"
                                    ping == null         -> "● ..."
                                    ping >= 999          -> "● —"
                                    else                 -> "● $ping$strMs"
                                },
                                color = when {
                                    isThisConnected      -> Accent
                                    server.isUnavailable -> TextMuted
                                    !server.canConnect   -> TextMuted
                                    server.hasWarning    -> Color(0xFFF59E0B)
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

        HorizontalDivider(color = Border, thickness = 1.dp)
        Button(
            onClick = {
                scope.launch {
                    if (isConnected) {
                        isConnecting = true
                        statusMsg = strDisconnecting
                        if (OpenVpnService.isRunning) {
                            OpenVpnService.stop(context)
                            var waited = 0
                            while (OpenVpnService.isRunning && waited < 8000) {
                                delay(100); waited += 100
                            }
                            onDisconnected()
                            statusMsg = ""
                            isConnecting = false
                        } else if (SingboxManager.isConnected()) {
                            XrayVpnService.stop(context)
                            onDisconnected()
                            statusMsg = ""
                            isConnecting = false
                        } else {
                            VpnManager.disconnect()
                            onDisconnected()
                            statusMsg = ""
                            isConnecting = false
                        }
                    } else {
                        val server = selectedFresh?.takeIf { it.canConnect } ?: return@launch
                        connectToServer(server)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !isConnecting && (isConnected || selectedFresh?.canConnect == true),
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
                    stringResource(if (isConnected) R.string.home_btn_disconnect else R.string.home_btn_connect),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
            }
        }
    }
}
