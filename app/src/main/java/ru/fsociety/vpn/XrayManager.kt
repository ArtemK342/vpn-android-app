package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

object XrayManager {

    private const val TAG = "XrayManager"
    private var controller: CoreController? = null
    var connectedServerName: String = ""

    fun isConnected(): Boolean = controller?.isRunning == true

    private fun makeHandler(service: XrayVpnService): CoreCallbackHandler {
        return object : CoreCallbackHandler {
            override fun startup(): Long {
                // TUN уже создан до startLoop и передан через tunFd параметр.
                // startup() — это lifecycle-колбэк, просто сигнализирует что xray готов.
                Log.d(TAG, "startup() lifecycle callback — xray core ready")
                return 0
            }
            override fun shutdown(): Long {
                Log.d(TAG, "shutdown() called")
                service.stopSelf()
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

            // Сначала создаём TUN-интерфейс — fd нужен до вызова startLoop
            Log.d(TAG, "Setting up TUN interface before startLoop...")
            val tunFd = service.setupVpnInterface()
            Log.d(TAG, "TUN fd=$tunFd")
            if (tunFd <= 0) {
                Log.e(TAG, "Failed to create TUN interface, fd=$tunFd")
                return@withContext false
            }

            Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
            Log.d(TAG, "initCoreEnv done")

            val ctrl = Libv2ray.newCoreController(makeHandler(service))
            Log.d(TAG, "Controller created, calling startLoop with tunFd=$tunFd...")
            // Второй параметр — tunFd (не prefIPv6!). 0 = не использовать TUN.
            ctrl.startLoop(configJson, tunFd.toInt())
            Log.d(TAG, "startLoop returned, isRunning=${ctrl.isRunning}")

            controller = ctrl
            connectedServerName = serverName
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}", e)
            false
        }
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        try {
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
            // TUN-трафик идёт через outbound proxy, не через socks inbound
            val rx = ctrl.queryStats("outbound>>>proxy>>>traffic", "downlink")
            val tx = ctrl.queryStats("outbound>>>proxy>>>traffic", "uplink")
            Pair(rx, tx)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }
}
