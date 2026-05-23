package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

object XrayManager {

    private const val TAG = "XrayManager"
    private var controller: CoreController? = null
    private var forwarder: TunSocksForwarder? = null
    var connectedServerName: String = ""

    fun isConnected(): Boolean = controller?.isRunning == true

    private fun makeHandler(): CoreCallbackHandler {
        return object : CoreCallbackHandler {
            override fun startup(): Long {
                // lifecycle callback only — TUN managed by TunSocksForwarder
                Log.d(TAG, "startup() — xray core ready")
                return 0
            }
            override fun shutdown(): Long {
                Log.d(TAG, "shutdown() called")
                return 0
            }
            override fun onEmitStatus(level: Long, msg: String?): Long {
                Log.d(TAG, "onEmitStatus[$level]: $msg")
                return 0
            }
        }
    }

    suspend fun connect(
        context: Context,
        service: XrayVpnService,
        configJson: String,
        serverName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            // 1. Создаём TUN
            Log.d(TAG, "Setting up TUN interface...")
            val tunFd = service.setupVpnInterface()
            Log.d(TAG, "TUN fd=$tunFd")
            if (tunFd <= 0) {
                Log.e(TAG, "TUN setup failed (fd=$tunFd)")
                return@withContext false
            }

            // 2. Запускаем xray как SOCKS5-прокси (tunFd=0 — xray не трогает TUN)
            Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
            Log.d(TAG, "initCoreEnv done")

            val ctrl = Libv2ray.newCoreController(makeHandler())
            Log.d(TAG, "startLoop (xray as SOCKS proxy)...")
            ctrl.startLoop(configJson, 0)
            Log.d(TAG, "startLoop done, isRunning=${ctrl.isRunning}")

            if (!ctrl.isRunning) {
                Log.e(TAG, "xray failed to start")
                return@withContext false
            }

            // 3. Запускаем Kotlin tun2socks: TUN → xray SOCKS5 (127.0.0.1:10808)
            val fwd = TunSocksForwarder(tunFd.toInt())
            fwd.start()
            forwarder = fwd

            controller = ctrl
            connectedServerName = serverName
            Log.d(TAG, "Connected via VLESS — tun2socks running")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}", e)
            false
        }
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        try {
            forwarder?.stop()
            forwarder = null
            controller?.stopLoop()
            controller = null
            connectedServerName = ""
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getTrafficStats(): Pair<Long, Long> {
        return try {
            val ctrl = controller ?: return Pair(0L, 0L)
            // TUN-трафик считается на уровне outbound proxy
            val rx = ctrl.queryStats("outbound>>>proxy>>>traffic", "downlink")
            val tx = ctrl.queryStats("outbound>>>proxy>>>traffic", "uplink")
            Pair(rx, tx)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }
}
