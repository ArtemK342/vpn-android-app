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

    /** Проверяет TCP-доступность сервера до запуска xray */
    private fun testServerReachability(host: String, port: Int): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 5000)
            socket.close()
            Log.d(TAG, "Server $host:$port is reachable ✓")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Server $host:$port NOT reachable: ${e.message}")
            false
        }
    }

    /** Читает первый пакет из TUN fd и логирует его — для диагностики */
    private fun logFirstTunPacket(tunFd: Long) {
        Thread {
            try {
                val fis = java.io.FileInputStream(
                    java.io.FileDescriptor().also {
                        val f = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                        f.isAccessible = true
                        f.set(it, tunFd.toInt())
                    }
                )
                val buf = ByteArray(4096)
                val n = fis.read(buf)
                if (n > 4) {
                    val ver = (buf[0].toInt() and 0xF0) shr 4
                    val proto = buf[9].toInt() and 0xFF
                    val dst = "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}.${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"
                    Log.d(TAG, "TUN first packet: IPv$ver proto=$proto dst=$dst len=$n — TUN is receiving traffic ✓")
                } else {
                    Log.d(TAG, "TUN read $n bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "TUN read failed: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
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

            // Тест доступности сервера ДО запуска xray
            // (addDisallowedApplication гарантирует что наш трафик идёт напрямую)
            try {
                val cfg = org.json.JSONObject(configJson)
                val vless = cfg.getJSONArray("outbounds").getJSONObject(0)
                val vnext = vless.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
                val host = vnext.getString("address")
                val port = vnext.getInt("port")
                testServerReachability(host, port)
            } catch (e: Exception) {
                Log.w(TAG, "Could not test reachability: ${e.message}")
            }

            // Запускаем слушатель первого TUN-пакета для диагностики
            logFirstTunPacket(tunFd)

            Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
            Log.d(TAG, "initCoreEnv done")

            val ctrl = Libv2ray.newCoreController(makeHandler(service))
            Log.d(TAG, "Controller created, calling startLoop with tunFd=$tunFd...")
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
