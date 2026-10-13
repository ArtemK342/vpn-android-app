package ru.fsociety.vpn

import android.content.Context
import android.net.VpnService
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Контекст с языком приложения (строки для служб, ресиверов и фоновых сценариев). */
fun localized(context: Context): Context {
    val lang = context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru"
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(java.util.Locale(lang))
    return context.createConfigurationContext(config)
}

/**
 * Подключение и его состояние — на весь процесс, а не внутри главного экрана.
 *
 * Раньше логика жила в HomeScreen и умирала вместе с ним: подключение отменялось при переходе в
 * настройки, авто-переподключение видело устаревшее состояние экрана и не срабатывало, события из
 * шторки на других вкладках терялись, после смены языка приложение «забывало» туннели sing-box и
 * OpenVPN. Здесь всё это обрабатывается независимо от того, какой экран открыт.
 */
object ConnectionManager {

    /** Сервер, к которому подключены (null — не подключены). */
    val connectedServer = mutableStateOf<ServerResponse?>(null)
    val isConnecting = mutableStateOf(false)
    val statusMsg = mutableStateOf("")

    /** Конфиг ждёт системного разрешения VPN — главный экран показывает системное окно. */
    data class Pending(val server: ServerResponse, val type: String, val config: String)
    val pending = mutableStateOf<Pending?>(null)
    /** Системное окно уже показано (после пересоздания экрана результат придёт в новый лаунчер). */
    @Volatile var permissionRequested = false

    enum class Outcome { CONNECTED, NEEDS_PERMISSION, FAILED }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    @Volatile private var lastServer: ServerResponse? = null
    @Volatile private var userDisconnected = false

    private fun str(id: Int, vararg args: Any): String = localized(app).getString(id, *args)
    private fun prefs() = app.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
    private fun lang() = prefs().getString("language", "ru") ?: "ru"
    private fun bearer() = "Bearer ${TokenStore.access.value}"

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // «Отключить» в шторке / остановка OpenVPN / заблокированная версия — намеренное отключение
        scope.launch {
            VpnEvents.disconnectRequested.collect { userDisconnected = true; onDisconnected(userInitiated = true) }
        }
        scope.launch { VpnEvents.connectSucceeded.collect { onConnected(it) } }
        scope.launch { VpnEvents.vpnDropped.collect { handleDrop() } }
    }

    /** Есть ли сейчас любой туннель (WireGuard / sing-box / OpenVPN). */
    fun anyTunnelUp(): Boolean =
        VpnManager.isConnected() || SingboxManager.isConnected() || OpenVpnService.isRunning

    // ── Подключение ──────────────────────────────────────────────────────────

    /**
     * Получить конфиг и подключиться. Не бросает исключений: ошибки — в [statusMsg].
     * [freshKey] — повтор сторожа WireGuard с новым ключом (второго повтора не будет).
     */
    suspend fun connect(server: ServerResponse, freshKey: Boolean = false): Outcome {
        isConnecting.value = true
        statusMsg.value = str(R.string.home_getting_config)
        userDisconnected = false
        try {
            // Уже подключены к другому серверу — сначала отключаемся (кэш нового сервера не трогаем)
            if (anyTunnelUp()) stopTunnels()

            // «Настройки → Доступ» (техработы / протокол / тип аккаунта / лимит): до подключения —
            // так блокируется и WireGuard из кэша. API недоступен — не мешаем, решит сервер.
            val access = try {
                ApiClient.service.checkAccess(bearer(), server.server_type)
            } catch (e: java.io.IOException) {
                AppErrors.log("access pre-check (fail-open)", e); null
            }
            if (access != null && !access.allowed) {
                return fail(access.text(lang()) ?: str(R.string.home_error_connect))
            }

            val p = when (server.server_type) {
                "bypass" -> {
                    // «Режим РФ»: конфиг локальный — запускаем byedpi и собираем конфиг sing-box
                    val remote = try {
                        ApiClient.service.getAppConfig().access?.bypass_strategy
                    } catch (e: Exception) { AppErrors.log("bypass strategy", e); null }
                    val port = withContext(Dispatchers.IO) { BypassManager.start(app, remote) }
                        ?: return fail(str(R.string.home_error_connect))
                    Pending(server, "bypass", BypassManager.buildConfig(port))
                }
                "vless" -> Pending(server, "vless", ApiClient.service.getVlessConfig(bearer(), server.id).config)
                "hysteria" -> Pending(server, "hysteria", ApiClient.service.getHysteriaConfig(bearer(), server.id).config)
                "trojan" -> Pending(server, "trojan", ApiClient.service.getTrojanConfig(bearer(), server.id).config)
                "openvpn_cloak" -> Pending(server, "openvpn_cloak", fetchCloakBundle(server))
                else -> {
                    val config = ConfigCache.get(app, server.id) ?: run {
                        val response = ApiClient.service.getVpnConfig(bearer(), server.id)
                        val cfg = response.config ?: return fail(response.message ?: str(R.string.home_error_connect))
                        ConfigCache.save(app, server.id, cfg)
                        cfg
                    }
                    Pending(server, "wireguard", config)
                }
            }

            if (VpnService.prepare(app) != null) {
                pending.value = p
                return Outcome.NEEDS_PERMISSION
            }
            return start(p, freshKey)
        } catch (e: java.net.SocketTimeoutException) {
            return fail(str(R.string.home_error_timeout))
        } catch (e: retrofit2.HttpException) {
            val detail = AppErrors.readBody(e) ?: ""
            AppErrors.log("connect ${server.server_type} HTTP ${e.code()}", e)
            val ctx = localized(app)
            return fail(when {
                e.code() == 403 && (detail.contains("подписк", true) || detail.contains("subscri", true)) ->
                    ctx.getString(R.string.home_error_subscription_only)
                e.code() == 403 && AppErrors.serverMessage(ctx, detail) == null ->
                    ctx.getString(R.string.home_limit_reached)
                // 503 «Настройки → Доступ» (сообщение сервера), 5xx, 401 и т.п. — фиксированные тексты
                else -> AppErrors.httpText(ctx, e.code(), detail)
            })
        } catch (e: Exception) {
            AppErrors.log("connect ${server.server_type}", e)
            return fail(AppErrors.text(localized(app), e))
        }
    }

    /** Ответ на системное окно «Разрешить VPN». */
    fun onPermissionResult(granted: Boolean) {
        val p = pending.value
        pending.value = null
        if (!granted || p == null) {
            if (!granted) statusMsg.value = str(R.string.home_vpn_denied)
            BypassManager.stop()   // «Режим РФ»: byedpi уже запущен
            isConnecting.value = false
            return
        }
        scope.launch { start(p, freshKey = false) }
    }

    private suspend fun start(p: Pending, freshKey: Boolean): Outcome {
        statusMsg.value = str(R.string.home_connecting)
        val server = p.server
        val ok = when (p.type) {
            "vless", "hysteria", "trojan", "bypass" -> {
                // «подключено» — только после проверки, что трафик идёт через сервер (probe)
                SingboxManager.startResult.value = null
                XrayVpnService.start(app, p.config, server.displayName(lang()))
                val r = withTimeoutOrNull(60_000) { SingboxManager.startResult.first { it != null } }
                if (r == null) XrayVpnService.stop(app)
                r == true
            }
            "openvpn_cloak" -> {
                // Раньше «подключено» ставилось сразу после запуска службы
                OpenVpnService.startResult.value = null
                OpenVpnService.start(app, p.config, server.displayName(lang()))
                val r = withTimeoutOrNull(60_000) { OpenVpnService.startResult.first { it != null } }
                if (r != true) OpenVpnService.stop(app)
                r == true
            }
            else -> VpnManager.connect(app, p.config)
        }
        if (!ok) {
            if (p.type == "bypass") BypassManager.stop()
            return fail(str(if (p.type == "wireguard") R.string.home_error_connect else R.string.home_error_server_unreachable))
        }
        onConnected(server)
        isConnecting.value = false
        if (p.type == "wireguard") startWatchdog(server, freshKey)
        return Outcome.CONNECTED
    }

    private suspend fun fetchCloakBundle(server: ServerResponse): String {
        var bodyBytes: ByteArray? = null
        var lastEx: Exception? = null
        for (attempt in 1..3) {
            try {
                if (attempt > 1) delay(1500)
                val raw = ApiClient.bundleService.getOpenVpnCloakBundle(bearer(), server.id)
                if (!raw.isSuccessful) throw retrofit2.HttpException(raw)
                bodyBytes = withContext(Dispatchers.IO) { raw.body()?.use { it.bytes() } }
                if (bodyBytes != null) break
            } catch (e: retrofit2.HttpException) {
                throw e   // 403/503 повтор не исправит
            } catch (e: Exception) {
                lastEx = e
            }
        }
        val bytes = bodyBytes ?: throw lastEx ?: java.io.IOException("Empty response from openvpn-cloak-bundle")
        val parsed = org.json.JSONObject(String(bytes, Charsets.UTF_8))
        return org.json.JSONObject().apply {
            put("hostName", parsed.getString("host"))
            put("openvpn_config_data", org.json.JSONObject().put("config", parsed.getString("ovpn_config").reversed()))
            put("cloak_config", parsed.getString("cloak_config"))
        }.toString()
    }

    private fun fail(message: String): Outcome {
        statusMsg.value = message
        isConnecting.value = false
        return Outcome.FAILED
    }

    /**
     * Сторож WireGuard: через 20 с трафик ушёл, а ответа нет → ключ мог быть снят → один раз берём
     * новый. Раньше повторял бесконечно (если провайдер режет UDP — новый ключ каждые ~20 с).
     */
    private fun startWatchdog(server: ServerResponse, freshKey: Boolean) = scope.launch {
        delay(20_000)
        if (connectedServer.value?.id != server.id || !VpnManager.isConnected()) return@launch
        val (rx, tx) = VpnManager.getTrafficStats()
        if (tx == 0L || rx > 0L) return@launch
        ConfigCache.clear(app, server.id)
        VpnManager.disconnect()
        onDisconnected(userInitiated = true)
        if (!freshKey) {
            connect(server, freshKey = true)
        } else {
            statusMsg.value = str(R.string.home_error_server_unreachable)
        }
    }

    // ── Отключение ───────────────────────────────────────────────────────────

    /** Пользователь сам отключается. */
    suspend fun disconnect() {
        isConnecting.value = true
        statusMsg.value = str(R.string.home_disconnecting)
        userDisconnected = true
        stopTunnels()
        onDisconnected(userInitiated = true)
        statusMsg.value = ""
        isConnecting.value = false
    }

    private suspend fun stopTunnels() {
        if (OpenVpnService.isRunning) {
            OpenVpnService.stop(app)
            var waited = 0
            while (OpenVpnService.isRunning && waited < 8000) { delay(100); waited += 100 }
        }
        if (SingboxManager.isConnected()) {
            XrayVpnService.stop(app)
            // Ждём реальной остановки: иначе «стоп» старого туннеля догонял запуск нового
            var waited = 0
            while (SingboxManager.isConnected() && waited < 5000) { delay(100); waited += 100 }
        }
        if (VpnManager.isConnected()) VpnManager.disconnect()
        BypassManager.stop()
    }

    // ── Состояние ────────────────────────────────────────────────────────────

    private fun onConnected(server: ServerResponse) {
        connectedServer.value = server
        lastServer = server
        userDisconnected = false
        statusMsg.value = ""
        VpnManager.connectedServerName = server.displayName(lang())
        KillSwitch.release(app)
        // WireGuard: уведомление — VpnForegroundService; у sing-box / OpenVPN своё уведомление с трафиком
        if (!server.usesSingbox && !server.usesOpenVpn) VpnForegroundService.start(app)
        else VpnForegroundService.stop(app)
    }

    private fun onDisconnected(userInitiated: Boolean) {
        connectedServer.value = null
        if (userInitiated) KillSwitch.release(app)
        // Фоновый режим: вернуть уведомление «Отключено / Подключить»
        if (prefs().getBoolean("background_mode", false)) VpnForegroundService.start(app)
        else VpnForegroundService.stop(app)
    }

    /**
     * Туннель упал сам. Если VPN забрало другое приложение — ничего не делаем. Иначе Kill Switch
     * блокирует трафик и (если включено) до 3 попыток переподключения.
     */
    private suspend fun handleDrop() {
        val server = connectedServer.value ?: lastServer
        onDisconnected(userInitiated = false)
        if (userDisconnected) return
        if (VpnService.prepare(app) != null) { statusMsg.value = ""; return }
        KillSwitch.engage(app)
        if (server == null || !prefs().getBoolean("auto_reconnect", true)) {
            if (KillSwitch.engaged) statusMsg.value = str(R.string.reconnect_failed_killswitch)
            return
        }
        for (attempt in 1..3) {
            statusMsg.value = "${str(R.string.reconnecting)} ($attempt/3)…"
            delay(attempt * 2000L)   // 2, 4, 6 с
            if (userDisconnected || connectedServer.value != null) return
            when (connect(server)) {
                Outcome.CONNECTED -> return
                Outcome.NEEDS_PERMISSION -> { pending.value = null; break }
                Outcome.FAILED -> AppErrors.log("auto-reconnect attempt $attempt", IllegalStateException(statusMsg.value))
            }
        }
        isConnecting.value = false
        statusMsg.value = str(if (KillSwitch.engaged) R.string.reconnect_failed_killswitch else R.string.reconnect_failed)
    }

    /** Выход из аккаунта / заблокированная версия: туннель и кэш принадлежат старому аккаунту. */
    suspend fun resetForLogout() {
        userDisconnected = true
        ConfigCache.clearAll(app)
        stopTunnels()
        onDisconnected(userInitiated = true)
        lastServer = null
        pending.value = null
        permissionRequested = false
        statusMsg.value = ""
        isConnecting.value = false
    }
}
