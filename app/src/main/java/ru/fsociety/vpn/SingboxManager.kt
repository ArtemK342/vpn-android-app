package ru.fsociety.vpn

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

object SingboxManager {

    private const val TAG = "SingboxManager"

    private var commandServer: CommandServer? = null
    private var setupDone = false
    var connectedServerName: String = ""

    // Трафик: baseline при подключении
    private var baseRx = 0L
    private var baseTx = 0L

    fun isConnected(): Boolean = commandServer != null

    fun connect(
        context: Context,
        service: XrayVpnService,
        configJson: String,
        serverName: String
    ): Boolean {
        return try {
            disconnect()

            // Инициализируем один раз
            if (!setupDone) {
                val opts = SetupOptions()
                opts.basePath    = context.filesDir.absolutePath
                opts.workingPath = context.filesDir.absolutePath
                opts.tempPath    = context.cacheDir.absolutePath
                opts.fixAndroidStack = true
                Libbox.setup(opts)
                setupDone = true
                Log.d(TAG, "Libbox.setup done")
            }

            // Проверяем конфиг до запуска
            try {
                Libbox.checkConfig(configJson)
                Log.d(TAG, "Config OK")
            } catch (e: Exception) {
                Log.e(TAG, "Config invalid: ${e.message}")
                return false
            }

            val server = CommandServer(makeServerHandler(), makePlatformInterface(service))
            server.startOrReloadService(configJson, OverrideOptions())

            commandServer = server
            connectedServerName = serverName
            // Сохраняем baseline трафика в момент подключения
            val uid = Process.myUid()
            baseRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            baseTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            Log.d(TAG, "sing-box started: $serverName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}", e)
            false
        }
    }

    fun disconnect() {
        try {
            commandServer?.closeService()
        } catch (e: Exception) {
            Log.e(TAG, "closeService error: ${e.message}")
        }
        commandServer = null
        connectedServerName = ""
    }

    // Трафик-статистика — дельта от момента подключения
    fun getTrafficStats(): Pair<Long, Long> {
        if (commandServer == null) return Pair(0L, 0L)
        val uid = Process.myUid()
        val rx = (TrafficStats.getUidRxBytes(uid) - baseRx).coerceAtLeast(0L)
        val tx = (TrafficStats.getUidTxBytes(uid) - baseTx).coerceAtLeast(0L)
        return Pair(rx, tx)
    }

    // ── CommandServerHandler ────────────────────────────────────────────────

    private fun makeServerHandler() = object : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().also { it.setAvailable(false); it.setEnabled(false) }
        override fun serviceReload() {}
        override fun serviceStop() { disconnect() }
        override fun setSystemProxyEnabled(enabled: Boolean) {}
        override fun writeDebugMessage(msg: String) { Log.d(TAG, "sb: $msg") }
    }

    // ── PlatformInterface ───────────────────────────────────────────────────

    private fun makePlatformInterface(service: XrayVpnService) = object : PlatformInterface {

        /** sing-box вызывает openTun() при старте — мы поднимаем VPN-интерфейс */
        override fun openTun(options: TunOptions): Int {
            Log.d(TAG, "openTun: mtu=${options.mtu}")
            return service.openTunForSingbox(options)
        }

        /** sing-box просит защитить свои сокеты от зацикливания через TUN */
        override fun autoDetectInterfaceControl(fd: Int) {
            service.protect(fd)
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun useProcFS(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun underNetworkExtension(): Boolean = false

        override fun findConnectionOwner(
            ipProtocol: Int, sourceAddress: String, sourcePort: Int,
            destAddress: String, destPort: Int
        ): ConnectionOwner = ConnectionOwner()

        override fun getInterfaces(): NetworkInterfaceIterator =
            object : NetworkInterfaceIterator {
                override fun hasNext() = false
                override fun next() = throw NoSuchElementException()
            }

        override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")

        override fun startDefaultInterfaceMonitor(l: InterfaceUpdateListener) {}
        override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener) {}
        override fun clearDNSCache() {}

        override fun localDNSTransport(): LocalDNSTransport =
            object : LocalDNSTransport {
                override fun raw() = false
                override fun lookup(ctx: ExchangeContext, network: String, domain: String) {}
                override fun exchange(ctx: ExchangeContext, message: ByteArray) {}
            }

        override fun systemCertificates(): StringIterator =
            object : StringIterator {
                override fun hasNext() = false
                override fun len() = 0
                override fun next() = ""
            }

        override fun sendNotification(n: Notification) {}
    }
}
