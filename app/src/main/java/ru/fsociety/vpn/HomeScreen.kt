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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
 * Выбор сервера на главном экране живёт дольше самого экрана (вкладки переключаются через
 * `when (selectedTab)`, и HomeScreen при переходе в настройки уничтожается). Само подключение и
 * его состояние — в [ConnectionManager].
 */
object HomeUiState {
    val selectedServer = mutableStateOf<ServerResponse?>(null)
    val userPickedServer = mutableStateOf(false)
    /** Приложение открыли из уведомления «Подключить» (не было разрешения VPN) — подключиться. */
    val autoConnectRequested = mutableStateOf(false)

    /** Выход из аккаунта: выбор сервера прошлого аккаунта не переносим. */
    fun reset() {
        selectedServer.value = null; userPickedServer.value = false; autoConnectRequested.value = false
    }
}

/**
 * Сообщения статуса серверов из админки — фиксированные русские шаблоны (STATUS_MSG_TEMPLATES в
 * admin/index.html). В английском интерфейсе показываем перевод; свой текст — как есть.
 */
private val STATUS_MESSAGES = mapOf(
    "Нестабильная работа" to R.string.status_msg_unstable,
    "Высокая нагрузка" to R.string.status_msg_high_load,
    "Потери пакетов" to R.string.status_msg_packet_loss,
    "Сервер под DDoS атакой" to R.string.status_msg_ddos,
    "Блокировки на стороне провайдера" to R.string.status_msg_isp_blocking,
    "Технические работы" to R.string.status_msg_maintenance,
    "Обновление конфигурации" to R.string.status_msg_config_update,
)

fun statusMessageText(context: Context, message: String): String =
    STATUS_MESSAGES[message.trim()]?.let { context.getString(it) } ?: message

/** Избранные серверы — по id (раньше по имени: 5 немецких серверов с одним именем шли разом) и в памяти. */
private object Favorites {
    private const val KEY = "favorite_servers"
    fun load(context: Context): Set<String> =
        context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    fun save(context: Context, ids: Set<String>) =
        context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).edit().putStringSet(KEY, ids).apply()
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
        .filter { it.canConnect && it.allow_auto_connect && it.server_type != "bypass" }  // режим РФ — не VPN, только вручную
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
    servers: List<ServerResponse>,
    serverPings: Map<String, Int>,
    isLoadingServers: Boolean,
    isConnected: Boolean,
    connectedServer: ServerResponse?,
    isRefreshingPings: Boolean,
    usage: UsageResponse? = null,
    rulesActive: Boolean = false,
    onRefreshPings: () -> Unit
) {
    var selectedServer by HomeUiState.selectedServer
    // Пока пользователь сам не выбрал сервер, автовыбор пересчитывается по мере прихода пингов
    var userPickedServer by HomeUiState.userPickedServer
    var autoConnectRequested by HomeUiState.autoConnectRequested
    val isConnecting by ConnectionManager.isConnecting
    val isDisconnecting by ConnectionManager.isDisconnecting
    val connectingTo by ConnectionManager.connectingTo
    val killSwitchEngaged by KillSwitch.engagedState
    val statusMsg by ConnectionManager.statusMsg
    val pending by ConnectionManager.pending
    val context = LocalContext.current
    var serverListTab by remember { mutableStateOf(0) }
    var favoriteServers by remember { mutableStateOf(Favorites.load(context)) }
    var showFavoriteDialog by remember { mutableStateOf<ServerResponse?>(null) }
    val lang = context.getSharedPreferences("fsociety", android.content.Context.MODE_PRIVATE)
        .getString("language", "ru") ?: "ru"
    val strMs = stringResource(R.string.unit_ms)

    fun connect(server: ServerResponse) {
        ConnectionManager.requestConnect(server)
    }

    // Системное окно «Разрешить VPN»: конфиг ждёт в ConnectionManager.pending (переживает
    // пересоздание экрана — раньше после поворота кнопка зависала в «подключении»)
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ConnectionManager.permissionRequested = false
        ConnectionManager.onPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(pending) {
        if (pending == null || ConnectionManager.permissionRequested) return@LaunchedEffect
        val intent = VpnService.prepare(context)
        if (intent == null) {
            ConnectionManager.onPermissionResult(true)
        } else {
            ConnectionManager.permissionRequested = true
            vpnPermissionLauncher.launch(intent)
        }
    }

    // Сервер для кнопки «Подключить»: выбранный вручную; если не выбирали (подключались из шторки или
    // автоподключением) — тот, к которому были подключены, иначе автовыбор. Берётся из свежего списка:
    // сервер мог стать недоступным (техработы, подписка) — тогда кнопка гаснет.
    val selectedFresh = (selectedServer ?: connectedServer ?: autoSelectServer(servers, serverPings))
        ?.let { s -> servers.find { it.id == s.id } ?: s }

    LaunchedEffect(servers, serverPings) {
        // Пока пользователь не выбрал сервер вручную, выбор пересчитывается по мере прихода пингов
        if (!userPickedServer && servers.isNotEmpty() && !isConnected) {
            autoSelectServer(servers, serverPings)?.let { selectedServer = it }
        }
    }

    // Открыли из уведомления «Подключить» (не было разрешения VPN) — подключаемся, когда есть список
    LaunchedEffect(autoConnectRequested, servers) {
        if (!autoConnectRequested || servers.isEmpty()) return@LaunchedEffect
        autoConnectRequested = false
        if (isConnected || isConnecting) return@LaunchedEffect
        selectedFresh?.takeIf { it.canConnect }?.let { connect(it) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            onRefreshPings()
        }
    }

    val displayedServers = if (serverListTab == 0) servers
    else servers.filter { favoriteServers.contains(it.id) }

    showFavoriteDialog?.let { server ->
        val isFav = favoriteServers.contains(server.id)
        AlertDialog(
            onDismissRequest = { showFavoriteDialog = null },
            containerColor = Bg2,
            title = { Text(server.displayName(lang), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(if (isFav) R.string.home_remove_favorite else R.string.home_add_favorite),
                    color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    favoriteServers = if (isFav) favoriteServers - server.id else favoriteServers + server.id
                    Favorites.save(context, favoriteServers)
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
                    // Длинные ошибки переносятся с отступами от краёв, а не упираются в край экрана
                    Text(statusMsg, color = TextMuted, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp))
                    // Kill Switch держит интернет заблокированным — снять можно и отсюда, не только из шторки
                    if (killSwitchEngaged && !isConnected && !isConnecting) {
                        TextButton(onClick = { ConnectionManager.unblockKillSwitch() }) {
                            Text(stringResource(R.string.ks_unblock_internet), color = ErrorRed,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else if (isConnected && connectedServer != null) {
                    val connectedPing = serverPings[connectedServer.id]
                    val pingText = when {
                        connectedPing == null || connectedPing >= 999 -> "● ${connectedServer.displayName(lang)}"
                        else -> "● ${connectedServer.displayName(lang)} · ${connectedPing}$strMs"
                    }
                    Text(pingText, color = Accent, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    // Флаг мог включиться после подключения — берём из свежего списка
                    val logsOn = servers.find { it.id == connectedServer.id }?.logs_enabled
                        ?: connectedServer.logs_enabled
                    if (logsOn) {
                        Text(stringResource(R.string.home_logs_on), color = ErrorRed, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    // Правила туннелирования есть, но этот протокол их не поддерживает (OpenVPN, Режим РФ)
                    if (rulesActive && !SplitTunnelingManager.supports(connectedServer.server_type)) {
                        Text(stringResource(R.string.home_rules_not_applied), color = Color(0xFFF59E0B),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp))
                    }
                }
                if (usage != null && usage.is_limited && usage.limit_bytes > 0) {
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
                    // Ограничение скорости бесплатных есть только на AmneziaWG
                    if (connectedServer?.server_type == "wireguard") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.home_free_speed), color = TextMuted,
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
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
                    items(displayedServers, key = { it.id }) { server ->
                        val ping = serverPings[server.id]
                        val isThisConnected = connectedServer?.id == server.id && isConnected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Недоступные (по подписке / закрытые в «Доступе» / статус из админки) не нажимаются
                                .combinedClickable(
                                    enabled = server.canConnect,
                                    onClick = {
                                        // Уже подключены (или подключаемся) к этому серверу — ничего не делаем
                                        // (раньше переподключал); к другому — ConnectionManager сам отменит
                                        // текущее подключение / погасит текущий туннель
                                        val busyWithThis = isConnecting && connectingTo?.id == server.id
                                        if (!isThisConnected && !busyWithThis && !isDisconnecting) {
                                            selectedServer = server
                                            userPickedServer = true
                                            connect(server)
                                        }
                                    },
                                    onLongClick = { showFavoriteDialog = server }
                                )
                                .background(if (isThisConnected || selectedServer?.id == server.id) Surface else BgDark)
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                // Режим РФ — без флага страны (локальный обход, не сервер)
                                text = if (server.server_type == "bypass") "⚡" else when (server.country) {
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
                                        "bypass"        -> stringResource(R.string.home_proto_bypass)
                                        else            -> "AmneziaWG"
                                    },
                                    color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                )
                                if (favoriteServers.contains(server.id)) {
                                    Text(stringResource(R.string.home_favorite_label),
                                        color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                // Причина закрытия протокола / типа аккаунта (техработы уже на общей плашке)
                                server.access_block?.takeIf { it.code != "maintenance" }?.text(lang)?.let {
                                    Text(it, color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                }
                                if (server.hasWarning && server.status_message != null) {
                                    Text(
                                        "⚠ ${statusMessageText(context, server.status_message)}${if (server.status_message_extra != null) " — ${server.status_message_extra}" else ""}",
                                        color = Color(0xFFF59E0B),
                                        fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
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
                                        // без сервера — пинговать нечего
                                        server.server_type == "bypass" -> stringResource(R.string.home_status_bypass)
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
                                // Сервис без логов; если на доступном сервере их временно включили — видно сразу
                                if (server.logs_enabled && (server.canConnect || isThisConnected)) {
                                    Text(stringResource(R.string.home_logs_on), color = ErrorRed,
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        HorizontalDivider(color = Border, thickness = 1.dp)
                    }
                }
            }
        }

        HorizontalDivider(color = Border, thickness = 1.dp)
        Button(
            onClick = {
                when {
                    // Во время подключения / переподключения кнопка — «Отмена»
                    isConnecting || isConnected -> ConnectionManager.requestDisconnect()
                    else -> selectedFresh?.takeIf { it.canConnect }?.let { connect(it) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !isDisconnecting && (isConnecting || isConnected || selectedFresh?.canConnect == true),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) ErrorRed else Accent,
                contentColor = BgDark
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
        ) {
            if (isConnecting || isDisconnecting) {
                CircularProgressIndicator(color = BgDark,
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                if (isConnecting) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.home_btn_cancel), fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
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
