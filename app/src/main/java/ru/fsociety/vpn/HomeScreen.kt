package ru.fsociety.vpn

import android.app.Activity
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
        .filter { !it.isReserve }   // резервные — только если пользователь сам выбрал (юзер 2026-11-09)
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
    // Что выдаётся для сторонних приложений (AmneziaVPN, Happ…) — пункт «Для другого приложения» в меню сервера
    var externalInfo by remember { mutableStateOf<ExternalOverview?>(null) }
    var externalServer by remember { mutableStateOf<ServerResponse?>(null) }
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

    LaunchedEffect(servers) {
        if (servers.isEmpty() || TokenStore.access.value.isEmpty()) return@LaunchedEffect
        externalInfo = try {
            ApiClient.service.getExternal("Bearer ${TokenStore.access.value}")
        } catch (e: Exception) { AppErrors.log("external overview", e); null }
    }

    val displayedServers = if (serverListTab == 0) servers
    else servers.filter { favoriteServers.contains(it.id) }

    // Меню сервера по долгому нажатию: избранное и (если сервер выдаётся в сторонние приложения) строка для них
    showFavoriteDialog?.let { server ->
        val isFav = favoriteServers.contains(server.id)
        val ext = externalInfo
        val forOtherApps = ext != null && (ext.isAwg(server.id) || ext.isVless(server.id))
        AlertDialog(
            onDismissRequest = { showFavoriteDialog = null },
            containerColor = Bg2,
            title = { Text(server.displayName(lang), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    ServerMenuItem(stringResource(if (isFav) R.string.home_menu_unfavorite else R.string.home_menu_favorite), Accent) {
                        favoriteServers = if (isFav) favoriteServers - server.id else favoriteServers + server.id
                        Favorites.save(context, favoriteServers)
                        showFavoriteDialog = null
                    }
                    if (forOtherApps) {
                        HorizontalDivider(color = Border)
                        ServerMenuItem(
                            stringResource(R.string.home_menu_other_app) +
                                if (ext?.entitled == false) " · " + stringResource(R.string.ext_by_sub) else "",
                            TextPrimary
                        ) {
                            externalServer = server
                            showFavoriteDialog = null
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFavoriteDialog = null }) {
                    Text(stringResource(R.string.home_btn_cancel), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        )
    }

    externalServer?.let { server ->
        externalInfo?.let { ext ->
            ExternalConfigDialog(server, ext, lang, onUpdated = { externalInfo = it }, onDismiss = { externalServer = null })
        }
    }

    // Карточка подключения: заголовок, сервер и круглая кнопка (заменила нижнюю кнопку, логика та же)
    val ksBlocked = killSwitchEngaged && !isConnected && !isConnecting
    val powerState = when {
        isConnecting || isDisconnecting -> PowerState.Busy
        isConnected -> PowerState.On
        ksBlocked -> PowerState.Blocked
        else -> PowerState.Off
    }
    val powerEnabled = !isDisconnecting && (isConnecting || isConnected || selectedFresh?.canConnect == true)
    val onPower = {
        when {
            // Во время подключения / переподключения кнопка — «Отмена»
            isConnecting || isConnected -> ConnectionManager.requestDisconnect()
            else -> { selectedFresh?.takeIf { it.canConnect }?.let { connect(it) } }
        }
        Unit
    }

    // Свайп вниз — с полосой «Обновляем…»; автоматическое обновление раз в минуту — молча: пинги просто меняются
    var userRefresh by remember { mutableStateOf(false) }
    val refreshingNow by rememberUpdatedState(isRefreshingPings)
    LaunchedEffect(isRefreshingPings) { if (!isRefreshingPings) userRefresh = false }
    LaunchedEffect(userRefresh) {
        // Обновление не началось (уже шло и закончилось) — полосу не держим
        if (userRefresh) { delay(600); if (!refreshingNow) userRefresh = false }
    }
    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    // Экран прокручивается целиком: блоки серверов по высоте списка, длинный уходит вниз
    PullToRefreshBox(
        isRefreshing = userRefresh,
        onRefresh = { userRefresh = true; onRefreshPings() },
        state = pullState,
        modifier = Modifier.fillMaxSize().background(BgDark),
        indicator = {
            // Своя полоса вместо круглой кнопки Material: экран съезжает вниз и открывает её
            RefreshHeader(
                state = pullState,
                refreshing = userRefresh,
                pullHint = stringResource(R.string.home_pull_hint),
                releaseHint = stringResource(R.string.home_pull_release),
                refreshingHint = stringResource(R.string.home_pull_refreshing),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = pullState.distanceFraction.coerceIn(0f, 1.5f) * RefreshHeaderHeight.toPx() }
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {

        // Плашка технических работ (админка → Настройки → Доступ), из /app-config
        val maintenance by ApiClient.maintenance.collectAsState()
        maintenance?.takeIf { it.enabled }?.let { m ->
            Text(
                "⚠ " + (m.text(lang) ?: stringResource(R.string.home_maintenance_default)),
                color = Warn, fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RowShape)
                    .background(WarnSoft)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ═══ Подключение ═══
        Column(modifier = Modifier.fillMaxWidth().card().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    BrandLogo()
                    Spacer(modifier = Modifier.height(10.dp))
                    SectionLabel(stringResource(R.string.home_card_label))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(
                            when {
                                isDisconnecting -> R.string.home_disconnecting
                                isConnecting -> R.string.home_connecting
                                isConnected -> R.string.home_title_on
                                ksBlocked -> R.string.home_title_blocked
                                else -> R.string.home_title_off
                            }
                        ),
                        color = if (ksBlocked) ErrorRed else TextPrimary,
                        fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (isConnected && connectedServer != null) {
                        val connectedPing = serverPings[connectedServer.id]
                        Text(
                            if (connectedPing == null || connectedPing >= 999) "● ${connectedServer.displayName(lang)}"
                            else "● ${connectedServer.displayName(lang)} · ${connectedPing}$strMs",
                            color = Accent, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        // Флаг мог включиться после подключения — берём из свежего списка
                        val logsOn = servers.find { it.id == connectedServer.id }?.logs_enabled
                            ?: connectedServer.logs_enabled
                        if (logsOn) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Chip(stringResource(R.string.home_logs_on), ErrorRed, ErrorSoft)
                        }
                    } else {
                        // Куда подключит кнопка (или куда подключаемся)
                        val target = if (isConnecting) connectingTo ?: selectedFresh else selectedFresh
                        Text(
                            target?.let { "→ " + it.displayName(lang) } ?: stringResource(R.string.home_sub_off),
                            color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                PowerButton(
                    state = powerState,
                    label = stringResource(
                        when {
                            isConnecting -> R.string.home_btn_cancel
                            isConnected || isDisconnecting -> R.string.home_power_disconnect
                            else -> R.string.home_power_connect
                        }
                    ),
                    enabled = powerEnabled,
                    onClick = onPower
                )
            }

            if (statusMsg.isNotEmpty()) {
                // Длинные ошибки переносятся, а не упираются в край экрана
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    statusMsg, color = TextMuted, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 4, overflow = TextOverflow.Ellipsis
                )
            }
            // Kill Switch держит интернет заблокированным — снять можно и отсюда, не только из шторки
            if (ksBlocked) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { ConnectionManager.unblockKillSwitch() },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorSoft, contentColor = ErrorRed),
                    shape = BtnShape
                ) {
                    Text(stringResource(R.string.ks_unblock_internet), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            // Правила туннелирования есть, но этот протокол их не поддерживает (OpenVPN, Режим РФ)
            if (isConnected && connectedServer != null && rulesActive &&
                !SplitTunnelingManager.supports(connectedServer.server_type)) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(stringResource(R.string.home_rules_not_applied), color = Warn,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            if (usage != null && usage.is_limited && usage.limit_bytes > 0) {
                val used = usage.bytes_used
                val limit = usage.limit_bytes
                val remaining = (limit - used).coerceAtLeast(0L)
                val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
                val barColor = when {
                    fraction >= 1f -> ErrorRed
                    fraction >= 0.8f -> Warn
                    else -> AccentFill
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        stringResource(R.string.home_traffic_remaining,
                            formatBytes(remaining, context), formatBytes(limit, context)),
                        color = if (fraction >= 0.8f) barColor else TextPrimary, fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(Border)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp)).background(barColor)
                        )
                    }
                    // Ограничение скорости бесплатных есть только на AmneziaWG
                    if (connectedServer?.server_type == "wireguard") {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(stringResource(R.string.home_free_speed), color = TextMuted,
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══ Серверы: основные и резервные (админка → Серверы; порядок оттуда же) ═══
        val mainServers = displayedServers.filter { !it.isReserve }
        val reserveServers = displayedServers.filter { it.isReserve }
        val onServerClick: (ServerResponse) -> Unit = { server ->
            // Уже подключены (или подключаемся) к этому серверу — ничего не делаем
            // (раньше переподключал); к другому — ConnectionManager сам отменит
            // текущее подключение / погасит текущий туннель
            val isThisConnected = connectedServer?.id == server.id && isConnected
            val busyWithThis = isConnecting && connectingTo?.id == server.id
            if (!isThisConnected && !busyWithThis && !isDisconnecting) {
                selectedServer = server
                userPickedServer = true
                connect(server)
            }
        }
        val rows: @Composable (List<ServerResponse>) -> Unit = { list ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
                list.forEach { server ->
                    key(server.id) {
                        ServerRow(
                            server = server,
                            ping = serverPings[server.id],
                            isThisConnected = connectedServer?.id == server.id && isConnected,
                            isSelected = selectedServer?.id == server.id,
                            isFavorite = favoriteServers.contains(server.id),
                            lang = lang,
                            strMs = strMs,
                            onClick = { onServerClick(server) },
                            onLongClick = { showFavoriteDialog = server }
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().card()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionLabel(stringResource(R.string.home_servers_label))
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(stringResource(R.string.home_servers_title), color = TextPrimary,
                        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Segmented(
                    listOf(stringResource(R.string.home_tab_all), "★ " + stringResource(R.string.home_tab_favorites)),
                    serverListTab
                ) { serverListTab = it }
            }
            when {
                isLoadingServers -> Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                }
                displayedServers.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
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
                // Все серверы сейчас в резерве (или избранные — только среди резервных)
                mainServers.isEmpty() -> Text(
                    stringResource(R.string.home_main_empty), color = TextMuted, fontSize = 11.sp,
                    lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp)
                )
                else -> rows(mainServers)
            }
        }

        if (!isLoadingServers && reserveServers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.fillMaxWidth().card()) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
                    SectionLabel(stringResource(R.string.home_reserve_label))
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(stringResource(R.string.home_reserve_title), color = TextPrimary,
                        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.home_reserve_sub), color = TextMuted,
                        fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
                }
                rows(reserveServers)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    }
}

/** Строка сервера: флаг, имя, плашки протокола и LOGS ON, статус / пинг, звезда избранного. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: ServerResponse,
    ping: Int?,
    isThisConnected: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    lang: String,
    strMs: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RowShape)
            .background(
                when {
                    isThisConnected -> AccentSoft
                    isSelected -> Surface
                    else -> Color.Transparent
                }
            )
            .border(1.dp, if (isThisConnected) AccentLine else Color.Transparent, RowShape)
            // Недоступные (по подписке / закрытые в «Доступе» / статус из админки) не нажимаются
            .combinedClickable(enabled = server.canConnect, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Режим РФ — без флага страны (локальный обход, не сервер)
        Flag(server.country, bypass = server.server_type == "bypass")
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                server.displayName(lang),
                color = if (server.canConnect || isThisConnected) TextPrimary else TextMuted,
                fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    when (server.server_type) {
                        "vless"         -> "VLESS+Reality"
                        "hysteria"      -> "Hysteria2"
                        "trojan"        -> "Trojan"
                        "openvpn_cloak" -> "OpenVPN+Cloak"
                        "bypass"        -> stringResource(R.string.home_proto_bypass)
                        else            -> "AmneziaWG"
                    }
                )
                // Сервис без логов; если на доступном сервере их временно включили — видно сразу
                if (server.logs_enabled && (server.canConnect || isThisConnected)) {
                    Chip(stringResource(R.string.home_logs_on), ErrorRed, ErrorSoft)
                }
            }
            // Причина закрытия протокола / типа аккаунта (техработы уже на общей плашке)
            server.access_block?.takeIf { it.code != "maintenance" }?.text(lang)?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text(it, color = TextMuted, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace)
            }
            if (server.hasWarning && server.status_message != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "⚠ ${statusMessageText(context, server.status_message)}${if (server.status_message_extra != null) " — ${server.status_message_extra}" else ""}",
                    color = Warn, fontSize = 9.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
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
                server.hasWarning    -> Warn
                ping == null || ping >= 999 -> TextMuted
                ping < 100  -> Accent
                ping < 200  -> Warn
                else        -> ErrorRed
            },
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        if (isFavorite) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(Icons.Rounded.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun ServerMenuItem(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)
    )
}
