package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File

object XrayManager {

    private const val TAG = "XrayManager"
    private var v2rayPoint: V2RayPoint? = null
    var connectedServerName: String = ""

    private fun createSupportSet(service: XrayVpnService): V2RayVPNServiceSupportsSet {
        return object : V2RayVPNServiceSupportsSet {
            override fun shutdown(): Long {
                service.stopSelf()
                return 0
            }
            override fun prepare(): Long = 0
            override fun protect(l: Long): Boolean = service.protect(l.toInt())
            override fun onEmitStatus(l: Long, s: String?): Long {
                Log.d(TAG, "Status: $s")
                return 0
            }
            override fun setup(s: String?): Long {
                service.setupVpnInterface(s ?: "")
                return 0
            }
        }
    }

    fun isConnected(): Boolean = v2rayPoint?.isRunning == true

    suspend fun connect(
        context: Context,
        service: XrayVpnService,
        configJson: String,
        serverName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            // Сохраняем конфиг во временный файл
            val configFile = File(context.filesDir, "xray_config.json")
            configFile.writeText(configJson)

            val point = Libv2ray.newV2RayPoint(createSupportSet(service), false)
            point.configureFileLocation = configFile.absolutePath
            point.runLoop(false)

            v2rayPoint = point
            connectedServerName = serverName
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}", e)
            false
        }
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        try {
            v2rayPoint?.stopLoop()
            v2rayPoint = null
            connectedServerName = ""
            true
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed: ${e.message}", e)
            false
        }
    }

    // Трафик-статистика (xray предоставляет через API)
    fun getTrafficStats(): Pair<Long, Long> {
        return try {
            val rx = v2rayPoint?.queryStats("inbound>>>socks>>>traffic>>>downlink") ?: 0L
            val tx = v2rayPoint?.queryStats("inbound>>>socks>>>traffic>>>uplink") ?: 0L
            Pair(rx, tx)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }
}
