package ru.fsociety.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Контекст с языком приложения (для attachBaseContext экранов и служб). */
fun localized(context: Context): Context {
    val lang = context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru"
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(java.util.Locale(lang))
    return context.createConfigurationContext(config)
}

private var stringsLang: String? = null
private var stringsCtx: Context? = null

/** Контекст для текстов на текущем языке приложения — кэшируется до смены языка (уведомления каждую секунду). */
@Synchronized
fun appStrings(context: Context): Context {
    val app = context.applicationContext ?: context
    val lang = app.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru"
    stringsCtx?.let { if (lang == stringsLang) return it }
    return localized(app).also { stringsCtx = it; stringsLang = lang }
}

/**
 * Подключение и его состояние — на весь процесс, а не внутри главного экрана.
 *
 * Раньше логика жила в HomeScreen и умирала вместе с ним: подключение отменялось при переходе в
 * настройки, авто-переподключение видело устаревшее состояние экрана и не срабатывало, события из
 * шторки на других вкладках терялись, после смены языка приложение «забывало» туннели sing-box и
 * OpenVPN. Здесь всё это обрабатывается независимо от того, какой экран открыт.
 *
 * Действия (подключение, переподключение после обрыва, отключение) идут строго по одному: новое
 * отменяет текущее и ждёт, пока оно погасит то, что успело запустить. Поэтому «Отмена» во время
 * подключения и «Отключить» во время переподключения срабатывают, а два подключения не идут разом.
 */
object ConnectionManager {

    /** Сервер, к которому подключены (null — не подключены). */
    val connectedServer = mutableStateOf<ServerResponse?>(null)
    /** Сервер, к которому сейчас подключаемся. */
    val connectingTo = mutableStateOf<ServerResponse?>(null)
    /** Идёт подключение / переподключение — кнопка работает как «Отмена». */
    val isConnecting = mutableStateOf(false)
    val isDisconnecting = mutableStateOf(false)
    val statusMsg = mutableStateOf("")

    /** Конфиг ждёт системного разрешения VPN — главный экран показывает системное окно. */
    /**
     * [rules] — правила туннелирования на момент подключения (пустые для протоколов без поддержки);
     * [fromCache] — конфиг WireGuard из кэша (ключ на сервере могли снять).
     */
    data class Pending(
        val server: ServerResponse, val type: String, val config: String,
        val rules: SplitTunnelingSettings = SplitTunnelingSettings(), val fromCache: Boolean = false
    )

    /** Отпечаток правил, с которыми подключены (null — не подключены / протокол без правил). */
    val appliedRules = mutableStateOf<String?>(null)
    val pending = mutableStateOf<Pending?>(null)
    /** Системное окно уже показано (после пересоздания экрана результат придёт в новый лаунчер). */
    @Volatile var permissionRequested = false

    enum class Outcome { CONNECTED, NEEDS_PERMISSION, FAILED }

    /** Пользователь хоть раз разрешил VPN (уведомление выбирает, как подключать). */
    const val PREF_VPN_CONSENT = "vpn_consent"
    /** Последний подключённый сервер и «пользователь хочет быть подключён» — для «Постоянной VPN». */
    private const val PREF_LAST_SERVER = "last_server"
    private const val PREF_WANT_CONNECTED = "want_connected"

    // Непойманная ошибка не должна ронять приложение — а вместе с ним и туннель
    private val errors = CoroutineExceptionHandler { _, e ->
        AppErrors.log("connection", e)
        idle(str(R.string.home_error_connect))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private lateinit var app: Context
    @Volatile private var lastServer: ServerResponse? = null
    /** Текущее действие; следующее отменяет его и дожидается завершения ([launchOp]). */
    private var op: Job? = null
    /** Сторож подключённого туннеля (ключ WireGuard, «сервер перестал отвечать»). */
    private var monitor: Job? = null
    /**
     * Подключение реально работало (трафик через сервер шёл). Kill Switch включается только при обрыве
     * такой сессии: если сервер не ответил с самого начала, защищать нечего, а блокировка интернета
     * после неудачной попытки только запутала бы.
     */
    @Volatile private var sessionVerified = false

    private fun str(id: Int, vararg args: Any): String = appStrings(app).getString(id, *args)
    private fun prefs() = app.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
    private fun lang() = prefs().getString("language", "ru") ?: "ru"
    private fun bearer() = "Bearer ${TokenStore.access.value}"

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        scope.launch { VpnEvents.vpnDropped.collect { onDrop() } }
        scope.launch { VpnEvents.vpnStopped.collect { onExternalStop() } }
    }

    /**
     * Android запустил VPN сам: «Постоянная VPN» после перезагрузки или перезапуск службы после
     * выгрузки процесса (сигнал приходит из процесса «:awg» — AlwaysOnReceiver). Раньше ничего не
     * происходило — с «Блокировать без VPN» интернета не было, пока не откроешь приложение.
     */
    fun onAlwaysOnTriggered() {
        scope.launch { onAlwaysOn() }
    }

    /** Подключиться к последнему серверу, если пользователь не отключался сам. */
    private fun onAlwaysOn() {
        val p = prefs()
        if (!p.getBoolean(PREF_WANT_CONNECTED, false) || TokenStore.access.value.isEmpty()) return
        if (op?.isActive == true || connectedServer.value != null) return
        val server = try {
            com.google.gson.Gson().fromJson(p.getString(PREF_LAST_SERVER, null), ServerResponse::class.java)
        } catch (e: Exception) { null } ?: return
        AppErrors.log("always-on", IllegalStateException("connecting to ${server.id}"))
        requestConnect(server)
    }

    /** Пользователь сам отключился / вышел / VPN выключили снаружи — «Постоянная VPN» не переподключает. */
    private fun setWantConnected(want: Boolean) {
        prefs().edit().putBoolean(PREF_WANT_CONNECTED, want).apply()
    }

    /** Есть ли сейчас любой туннель (WireGuard / sing-box / OpenVPN). */
    fun anyTunnelUp(): Boolean =
        VpnManager.isConnected() || SingboxManager.isConnected() || OpenVpnService.isRunning

    // ── Действия ─────────────────────────────────────────────────────────────

    private fun launchOp(block: suspend () -> Unit): Job {
        // Состояние меняется — сторож прежнего туннеля больше не нужен и не должен вмешаться
        stopMonitor()
        val prev = op
        val job = scope.launch(start = CoroutineStart.LAZY) {
            prev?.cancelAndJoin()
            block()
        }
        op = job
        job.start()
        return job
    }

    /** Подключиться к серверу (кнопка, список, автоподключение). Идущее действие отменяется. */
    fun requestConnect(server: ServerResponse): Job = launchOp {
        sessionVerified = false
        connect(server)
    }

    /** «Отключить» и «Отмена» — в приложении и в уведомлении. */
    fun requestDisconnect(): Job = launchOp { disconnect() }

    /** «Подключить» в уведомлении: лучший по пингу WireGuard-сервер, без открытия приложения. */
    fun requestNotificationConnect(): Job = launchOp {
        sessionVerified = false
        connectFromNotification()
    }

    /** Выход из аккаунта / заблокированная версия: туннель и кэш принадлежат старому аккаунту. */
    fun requestLogoutReset(): Job = launchOp {
        setWantConnected(false)
        prefs().edit().remove(PREF_LAST_SERVER).apply()
        ConfigCache.clearAll(app)
        pending.value = null
        permissionRequested = false
        stopTunnels()
        KillSwitch.release(app)
        lastServer = null
        sessionVerified = false
        setDisconnected()
        idle("")
    }

    /** «Разблокировать» (уведомление Kill Switch / кнопка на главном экране). */
    fun unblockKillSwitch() {
        KillSwitch.release(app)
        if (statusMsg.value == str(R.string.reconnect_failed_killswitch)) {
            val autoReconnect = prefs().getBoolean("auto_reconnect", true)
            statusMsg.value = str(if (autoReconnect) R.string.reconnect_failed else R.string.vpn_connection_lost)
        }
    }

    /** Ответ на системное окно «Разрешить VPN». */
    fun onPermissionResult(granted: Boolean) {
        val p = pending.value
        pending.value = null
        launchOp {
            if (granted && p != null) {
                start(p, freshKey = false, auto = false)
            } else {
                stopBypass()   // «Режим РФ»: byedpi уже запущен
                idle(if (!granted) str(R.string.home_vpn_denied) else "")
            }
        }
    }

    // ── Подключение ──────────────────────────────────────────────────────────

    /**
     * Получить конфиг и подключиться. Не бросает исключений (кроме отмены): ошибки — в [statusMsg].
     * [freshKey] — повтор сторожа WireGuard с новым ключом (второго не будет).
     * [auto] — без участия пользователя (переподключение): системное окно VPN не показываем.
     */
    private suspend fun connect(server: ServerResponse, freshKey: Boolean = false, auto: Boolean = false): Outcome {
        AppLog.i("подключение: ${server.server_type} ${server.id}${if (auto) " (авто)" else ""}${if (freshKey) " (новый ключ)" else ""}")
        connectingTo.value = server
        isConnecting.value = true
        statusMsg.value = str(R.string.home_getting_config)
        try {
            // Уже подключены (к этому или другому серверу) — сначала гасим туннель; кэш нового
            // сервера не трогаем. Раньше при неудачном переключении на экране оставался старый сервер.
            stopTunnels()
            connectedServer.value = null
            appliedRules.value = null

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

            // Правила туннелирования (Правила → Сайты / Приложения) — на момент подключения
            val rules = if (SplitTunnelingManager.supports(server.server_type)) SplitTunnelingManager.load(app)
                        else SplitTunnelingSettings()

            val p = when (server.server_type) {
                "bypass" -> {
                    // «Режим РФ»: конфиг локальный — запускаем byedpi и собираем конфиг sing-box
                    val remote = try {
                        ApiClient.service.getAppConfig().access?.bypass_strategy
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) { AppErrors.log("bypass strategy", e); null }
                    val dns = BypassManager.pickDns(app)
                    val port = withContext(Dispatchers.IO) { BypassManager.start(app, remote) }
                        ?: return fail(str(R.string.bypass_error_start))
                    Pending(server, "bypass", BypassManager.buildConfig(port, dns))
                }
                "vless" -> Pending(server, "vless", ApiClient.service.getVlessConfig(bearer(), server.id).config, rules)
                "hysteria" -> Pending(server, "hysteria", ApiClient.service.getHysteriaConfig(bearer(), server.id).config, rules)
                "trojan" -> Pending(server, "trojan", ApiClient.service.getTrojanConfig(bearer(), server.id).config, rules)
                "openvpn_cloak" -> Pending(server, "openvpn_cloak", fetchCloakBundle(server))
                else -> {
                    // Кэш зашифрован ключом Keystore — расшифровка не на главном потоке
                    val cached = withContext(Dispatchers.IO) { ConfigCache.get(app, server.id) }
                    val config = cached ?: run {
                        val response = ApiClient.service.getVpnConfig(bearer(), server.id)
                        val cfg = response.config ?: return fail(response.message ?: str(R.string.home_error_connect))
                        withContext(Dispatchers.IO) { ConfigCache.save(app, server.id, cfg) }
                        cfg
                    }
                    // С правилами сайтов AmneziaWG работает как локальный прокси под sing-box
                    Pending(server, if (rules.sitesActive) "wireguard_proxy" else "wireguard", config, rules,
                        fromCache = cached != null)
                }
            }

            // Без участия пользователя системное окно не показываем
            if (VpnService.prepare(app) != null) {
                if (auto) return fail(str(R.string.reconnect_failed))
                pending.value = p
                return Outcome.NEEDS_PERMISSION
            }
            return start(p, freshKey, auto)
        } catch (e: CancellationException) {
            throw e
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
        } finally {
            // byedpi работает, только пока «Режим РФ» подключён или ждёт разрешения VPN
            if (server.server_type == "bypass" && connectedServer.value?.id != server.id && pending.value == null) {
                stopBypass()
            }
        }
    }

    private suspend fun start(p: Pending, freshKey: Boolean, auto: Boolean): Outcome {
        val server = p.server
        connectingTo.value = server
        isConnecting.value = true
        statusMsg.value = str(R.string.home_connecting)
        val startedAt = System.currentTimeMillis()
        val ok = try {
            when (p.type) {
                "bypass" -> startSingbox(p, p.config)
                "vless", "hysteria", "trojan" -> startSingbox(p,
                    if (p.rules.sitesActive) SplitTunnelingManager.applySiteRules(p.config, p.rules, BypassManager.directDns(app))
                    else p.config)
                "wireguard_proxy" -> startWireGuardProxy(p)
                "openvpn_cloak" -> startOpenVpn(p)
                else -> startWireGuard(p)
            }
        } catch (e: CancellationException) {
            if (p.type == "bypass") stopBypass()
            throw e
        } catch (e: Exception) {
            // напр. Android запретил запуск службы из фона
            AppErrors.log("start ${p.type}", e); false
        }
        if (!ok) {
            if (p.type == "bypass") stopBypass()
            // AmneziaWG через прокси проверяется сразу при запуске: не прошёл с сохранённым ключом — его
            // могли снять (лимит подключений, «Разорвать подключения») — один раз берём новый
            if (p.type == "wireguard_proxy" && p.fromCache && !freshKey) {
                ConfigCache.clear(app, server.id)
                return connect(server, freshKey = true, auto = auto)
            }
            return fail(str(when (p.type) {
                "wireguard" -> R.string.home_error_connect
                "bypass" -> R.string.bypass_error_start
                else -> R.string.home_error_server_unreachable
            }))
        }
        // sing-box и OpenVPN проверены при запуске (запрос через сервер / CONNECTED); WireGuard проверит
        // сторож по рукопожатию. «Режим РФ» — без сервера: IP и так свой, Kill Switch ему не нужен.
        if (p.type != "wireguard" && p.type != "bypass") sessionVerified = true
        onConnected(server, p.type)
        AppLog.i("подключено: ${server.server_type} ${server.id} (${p.type}), правила: сайты=${if (p.rules.sitesActive) "${p.rules.sitesMode}/${p.rules.sites.size}" else "нет"} приложения=${if (p.rules.appsActive) "${p.rules.appsMode}/${p.rules.apps.size}" else "нет"}")
        appliedRules.value = if (SplitTunnelingManager.supports(server.server_type)) p.rules.fingerprint() else null
        startMonitor(server, p.type, freshKey, startedAt)
        if (auto) AppErrors.log("auto-reconnect", IllegalStateException("reconnected to ${server.id}"))
        return Outcome.CONNECTED
    }

    /**
     * sing-box: «подключено» — только после проверки, что трафик идёт через сервер (probe).
     * [config] — с правилами сайтов; правила приложений применяет служба при поднятии интерфейса.
     */
    private suspend fun startSingbox(p: Pending, config: String): Boolean {
        SingboxManager.startResult.value = null
        val r = p.rules
        XrayVpnService.start(app, config, p.server.displayName(lang()),
            appsMode = if (r.appsActive) r.appsMode else null,
            apps = if (r.appsActive) r.apps else emptyList())
        var result: Boolean? = null
        try {
            result = withTimeoutOrNull(60_000) { SingboxManager.startResult.first { it != null } }
        } finally {
            // Таймаут или «Отмена»: служба ещё поднимает туннель — гасим, иначе он поднялся бы сам
            if (result == null) withContext(NonCancellable) { stopXray() }
        }
        return result == true
    }

    /** OpenVPN: «подключено» — только когда OpenVPN сообщил CONNECTED (раньше — сразу после запуска). */
    private suspend fun startOpenVpn(p: Pending): Boolean {
        OpenVpnService.startResult.value = null
        OpenVpnService.start(app, p.config, p.server.displayName(lang()))
        var result: Boolean? = null
        try {
            result = withTimeoutOrNull(60_000) { OpenVpnService.startResult.first { it != null } }
        } finally {
            if (result == null) withContext(NonCancellable) { stopOpenVpn() }
        }
        return result == true
    }

    private suspend fun startWireGuard(p: Pending): Boolean {
        var ok = false
        try {
            // Правила приложений (без правил сайтов) применяет сама библиотека
            val config = SplitTunnelingManager.applyAppRulesToWg(p.config, p.rules) {
                SplitTunnelingManager.isInstalled(app, it)
            }
            ok = VpnManager.connect(app, config)
        } finally {
            // Отменили, пока туннель поднимался, — не оставляем его висеть
            if (!ok) withContext(NonCancellable) { VpnManager.disconnect() }
        }
        return ok
    }

    /**
     * AmneziaWG с правилами сайтов: он — локальный прокси (SOCKS5 на 127.0.0.1, случайный порт и
     * пароль), а VPN-интерфейс и выбор «через VPN / напрямую» по имени сайта — у sing-box.
     */
    private suspend fun startWireGuardProxy(p: Pending): Boolean {
        val port = withContext(Dispatchers.IO) {
            try {
                java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            } catch (_: Exception) { 0 }
        }
        if (port <= 0) return false
        val user = java.util.UUID.randomUUID().toString()
        val pass = java.util.UUID.randomUUID().toString()
        var ok = false
        try {
            if (!VpnManager.connectProxy(app, p.config, port, user, pass)) return false
            val config = SplitTunnelingManager.awgProxyConfig(port, user, pass, p.rules, BypassManager.directDns(app))
            ok = startSingbox(p, config)
        } finally {
            if (!ok) withContext(NonCancellable) { VpnManager.disconnect() }
        }
        return ok
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
            } catch (e: CancellationException) {
                throw e
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

    /** «Подключить» из уведомления: как раньше — лучший по пингу WireGuard с авто-подключением. */
    private suspend fun connectFromNotification() {
        connectingTo.value = null
        isConnecting.value = true
        statusMsg.value = str(R.string.home_getting_config)
        val best = try {
            val servers = ApiClient.service.getServers(bearer())
            val candidates = servers.filter { it.server_type == "wireguard" && it.canConnect && it.allow_auto_connect }
            autoSelectServer(candidates, measureAllPings(candidates), wireguardOnly = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppErrors.log("notification connect", e)
            val text = AppErrors.text(localized(app), e)
            fail(text); toast(text)
            return
        }
        if (best == null) {
            val text = str(R.string.notif_no_server)
            fail(text); toast(text)
            return
        }
        when (connect(best)) {
            Outcome.FAILED -> toast(statusMsg.value)
            Outcome.NEEDS_PERMISSION -> {
                // Разрешение VPN отозвали: системное окно из уведомления не показать (Android 12+
                // не даёт открыть приложение из ресивера) — просим открыть приложение
                pending.value = null
                prefs().edit().putBoolean(PREF_VPN_CONSENT, false).apply()
                idle("")
                syncNotification()
                toast(str(R.string.notif_open_app))
            }
            Outcome.CONNECTED -> {}
        }
    }

    private fun toast(text: String) {
        if (text.isNotEmpty()) android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun fail(message: String): Outcome {
        AppLog.w("не подключились: $message")
        idle(message)
        // Переключались между серверами: старый туннель уже погашен — уведомление «Отключено»
        if (connectedServer.value == null) syncNotification()
        return Outcome.FAILED
    }

    /** Ничего не делаем: кнопка снова «Подключить» / «Отключить». */
    private fun idle(message: String) {
        statusMsg.value = message
        isConnecting.value = false
        isDisconnecting.value = false
        connectingTo.value = null
    }

    // ── Сторож ───────────────────────────────────────────────────────────────

    private fun startMonitor(server: ServerResponse, type: String, freshKey: Boolean, startedAt: Long) {
        stopMonitor()
        monitor = when (type) {
            "wireguard", "wireguard_proxy" -> scope.launch { watchWireGuard(server, freshKey, startedAt) }
            "vless", "hysteria", "trojan" -> scope.launch { watchSingbox() }
            else -> null   // OpenVPN следит сам (OpenVpnService); «Режим РФ» — без сервера
        }
    }

    private fun stopMonitor() {
        monitor?.cancel()
        monitor = null
    }

    /**
     * Сторож WireGuard.
     * 1) Через 20 с: пакеты уходят, а рукопожатия с сервером так и не было — ключ сняли (лимит
     *    подключений, «Разорвать подключения») или провайдер режет UDP. Один раз берём новый ключ,
     *    потом — ошибка. Раньше повторял бесконечно: новый ключ каждые ~20 с.
     * 2) Дальше раз в 30 с: при keepalive 25 с рукопожатие идёт каждые 2 мин; если оно старше ~3 мин
     *    три проверки подряд (минута без сна телефона: после глубокого сна WireGuard переподключается
     *    сам за секунды) при рабочей сети — сервер перестал отвечать. Раньше такой обрыв не замечался
     *    вовсе: «Подключено», а интернета нет. Теперь — Kill Switch и переподключение.
     */
    private suspend fun watchWireGuard(server: ServerResponse, freshKey: Boolean, startedAt: Long) {
        delay(20_000)
        if (!VpnManager.isConnected()) return
        val (rx, tx, firstHandshake) = VpnManager.stats()
        if (firstHandshake >= startedAt - 5_000 || rx > 0L) {
            sessionVerified = true
        } else if (tx > 0L) {
            rekeyOrGiveUp(server, freshKey)
            return
        }
        var strikes = 0
        while (true) {
            delay(30_000)
            if (!VpnManager.isConnected()) return
            val handshake = VpnManager.stats().third
            if (handshake >= startedAt - 5_000) sessionVerified = true
            val stale = System.currentTimeMillis() - handshake > 200_000
            strikes = if (stale && hasInternet()) strikes + 1 else 0
            if (strikes >= 3) {
                AppErrors.log("wireguard watchdog", IllegalStateException("no handshake since $handshake"))
                VpnEvents.vpnDropped.tryEmit(Unit)
                return
            }
        }
    }

    private fun rekeyOrGiveUp(server: ServerResponse, freshKey: Boolean) {
        AppErrors.log("wireguard watchdog", IllegalStateException("no handshake in 20 s, freshKey=$freshKey"))
        launchOp {
            // Переподключение после обрыва рабочей сессии — интернет не течёт, пока берём новый ключ
            if (sessionVerified) KillSwitch.engage(app)
            ConfigCache.clear(app, server.id)
            if (!freshKey) {
                connect(server, freshKey = true, auto = true)
            } else {
                stopTunnels()
                setDisconnected()
                idle(str(if (KillSwitch.engaged) R.string.reconnect_failed_killswitch else R.string.home_error_server_unreachable))
            }
        }
    }

    /**
     * Сторож sing-box (VLESS / Trojan / Hysteria2): раз в 30 с смотрим трафик. Пакеты уходят, а в
     * ответ ничего — проверяем запросом через сервер; два провала подряд при рабочей сети — сервер
     * перестал отвечать. Без трафика ничего не проверяем (не тратим батарею).
     */
    private suspend fun watchSingbox() {
        var (lastRx, lastTx) = SingboxManager.getTrafficStats()
        while (true) {
            delay(30_000)
            if (!SingboxManager.isConnected()) return
            val (rx, tx) = SingboxManager.getTrafficStats()
            val silent = tx > lastTx && rx == lastRx
            lastRx = rx; lastTx = tx
            if (!silent) continue
            var dead = !withContext(Dispatchers.IO) { SingboxManager.probeAlive() }
            if (dead) {
                delay(10_000)
                dead = !withContext(Dispatchers.IO) { SingboxManager.probeAlive() } && hasInternet()
            }
            SingboxManager.getTrafficStats().let { (r, t) -> lastRx = r; lastTx = t }
            if (dead && SingboxManager.isConnected()) {
                AppErrors.log("sing-box watchdog", IllegalStateException("server stopped answering"))
                VpnEvents.vpnDropped.tryEmit(Unit)
                return
            }
        }
    }

    /** Рабочая сеть телефона (не VPN): без неё «сервер молчит» — не повод переподключаться. */
    private fun hasInternet(): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm?.allNetworks?.any { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: true
    } catch (_: Exception) { true }

    /**
     * Включён VPN другого приложения. Наши туннели исключают само приложение (а WireGuard к этому
     * моменту погашен), так что VPN в его сети — чужой; на Android 10+ видно и владельца сети.
     */
    private fun otherVpnActive(): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm == null) false
        else {
            val activeIsVpn = cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            @Suppress("DEPRECATION")
            activeIsVpn || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm.allNetworks.any { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.ownerUid != android.os.Process.myUid()
            })
        }
    } catch (_: Exception) { false }

    // ── Отключение ───────────────────────────────────────────────────────────

    private suspend fun disconnect() {
        AppLog.i("отключение пользователем")
        setWantConnected(false)
        sessionVerified = false
        pending.value = null
        isConnecting.value = false
        connectingTo.value = null
        isDisconnecting.value = true
        statusMsg.value = str(R.string.home_disconnecting)
        try {
            stopTunnels()
        } finally {
            KillSwitch.release(app)
            setDisconnected()
            idle("")
        }
    }

    /** Погасить всё, что запущено (туннели, byedpi, сторож). Kill Switch не трогает. */
    private suspend fun stopTunnels() {
        stopMonitor()
        if (OpenVpnService.isRunning) stopOpenVpn()
        if (XrayVpnService.isRunning || SingboxManager.isConnected()) stopXray()
        if (VpnManager.isConnected()) VpnManager.disconnect()
        stopBypass()
    }

    private suspend fun stopXray() {
        try { XrayVpnService.stop(app) } catch (e: Exception) { AppErrors.log("sing-box stop", e) }
        // Ждём реальной остановки: иначе «стоп» старого туннеля догонял запуск нового
        waitUntil(15_000) { !XrayVpnService.isRunning }
    }

    private suspend fun stopOpenVpn() {
        try { OpenVpnService.stop(app) } catch (e: Exception) { AppErrors.log("openvpn stop", e) }
        waitUntil(10_000) { !OpenVpnService.isRunning }
    }

    /** byedpi останавливается с ожиданием процесса — не на главном потоке. */
    private suspend fun stopBypass() = withContext(NonCancellable + Dispatchers.IO) { BypassManager.stop() }

    private suspend fun waitUntil(timeoutMs: Long, done: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) { while (!done()) delay(100) }
    }

    // ── Состояние ────────────────────────────────────────────────────────────

    /**
     * Чьё уведомление показывать: у обычного AmneziaWG — VpnForegroundService, у sing-box (в т.ч.
     * AmneziaWG через прокси с правилами сайтов) и OpenVPN — уведомление их служб.
     */
    @Volatile private var ownNotification = false

    private fun onConnected(server: ServerResponse, type: String) {
        ownNotification = type != "wireguard"
        connectedServer.value = server
        lastServer = server
        // Разрешение VPN есть — «Подключить» в уведомлении может подключать без открытия приложения;
        // сервер запоминаем для «Постоянной VPN»
        prefs().edit()
            .putBoolean(PREF_VPN_CONSENT, true)
            .putBoolean(PREF_WANT_CONNECTED, true)
            .putString(PREF_LAST_SERVER, com.google.gson.Gson().toJson(server))
            .apply()
        VpnManager.connectedServerName = server.displayName(lang())
        KillSwitch.release(app)
        idle("")
        syncNotification()
    }

    private fun setDisconnected() {
        connectedServer.value = null
        appliedRules.value = null
        syncNotification()
    }

    /** WireGuard — уведомление VpnForegroundService (у sing-box / OpenVPN своё); без VPN — фоновый режим. */
    private fun syncNotification() {
        val s = connectedServer.value
        try {
            when {
                s != null && ownNotification -> VpnForegroundService.stop(app)
                s != null || prefs().getBoolean("background_mode", false) -> VpnForegroundService.start(app)
                else -> VpnForegroundService.stop(app)
            }
        } catch (e: Exception) {
            AppErrors.log("status notification", e)   // Android 12+ может запретить запуск из фона
        }
    }

    /** Туннель упал сам: служба остановилась или сторож решил, что сервер перестал отвечать. */
    private fun onDrop() {
        AppLog.w("обрыв туннеля")
        if (op?.isActive == true) return   // идёт подключение/отключение — оно само наведёт порядок
        val server = connectedServer.value ?: lastServer ?: return
        launchOp { recover(server) }
    }

    /**
     * Kill Switch (если сессия была рабочей) и до 3 попыток переподключения. Если VPN тем временем
     * включили в другом приложении — не отбираем его.
     */
    private suspend fun recover(server: ServerResponse) {
        if (sessionVerified) KillSwitch.engage(app)
        stopTunnels()   // WireGuard после сторожа ещё «поднят», хотя сервер молчит
        setDisconnected()
        if (!prefs().getBoolean("auto_reconnect", true)) {
            idle(str(if (KillSwitch.engaged) R.string.reconnect_failed_killswitch else R.string.vpn_connection_lost))
            return
        }
        for (attempt in 1..3) {
            connectingTo.value = server
            isConnecting.value = true
            statusMsg.value = "${str(R.string.reconnecting)} ($attempt/3)…"
            delay(attempt * 2000L)   // 2, 4, 6 с
            if (otherVpnActive()) {
                KillSwitch.release(app)
                idle("")
                return
            }
            if (connect(server, auto = true) == Outcome.CONNECTED) return
            AppErrors.log("auto-reconnect attempt $attempt", IllegalStateException(statusMsg.value))
        }
        idle(str(if (KillSwitch.engaged) R.string.reconnect_failed_killswitch else R.string.reconnect_failed))
    }

    /**
     * VPN выключили снаружи: другое VPN-приложение, «Отключить» в настройках Android. Это не обрыв —
     * без Kill Switch и переподключения. Раньше WireGuard в этом случае переподключался (а вызов
     * VpnService.prepare() при этом молча выключал VPN другого приложения), а VLESS/OpenVPN
     * продолжали показывать «Подключено».
     */
    private fun onExternalStop() {
        AppLog.i("VPN выключен снаружи (другое приложение / настройки Android)")
        val wasActive = connectedServer.value != null || isConnecting.value || KillSwitch.engaged
        setWantConnected(false)
        launchOp {
            stopTunnels()
            KillSwitch.release(app)
            sessionVerified = false
            pending.value = null
            setDisconnected()
            idle(if (wasActive) str(R.string.vpn_stopped_externally) else "")
        }
    }
}
