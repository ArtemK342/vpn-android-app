package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.StringReader

object VpnManager {
    private const val TAG = "VpnManager"
    private var backend: GoBackend? = null
    private var currentTunnel: WgTunnel? = null

    fun init(context: Context) {
        if (backend == null) {
            backend = GoBackend(context, object : TunnelActionHandler {
                override fun runPreUp(commands: MutableCollection<String>?) {}
                override fun runPostUp(commands: MutableCollection<String>?) {}
                override fun runPreDown(commands: MutableCollection<String>?) {}
                override fun runPostDown(commands: MutableCollection<String>?) {}
            })
        }
    }

    suspend fun connect(context: Context, configString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            init(context)
            // Сбрасываем предыдущий туннель если есть
            currentTunnel?.let { t ->
                runCatching { backend?.setState(t, Tunnel.State.DOWN, null) }
            }
            val config = Config.parse(BufferedReader(StringReader(configString)))
            val tunnel = WgTunnel("fsociety")
            currentTunnel = tunnel
            Log.d(TAG, "setState UP start")
            val start = System.currentTimeMillis()
            backend?.setState(tunnel, Tunnel.State.UP, config)
            Log.d(TAG, "setState UP done in ${System.currentTimeMillis() - start}ms")
            val state = backend?.getState(tunnel)
            Log.d(TAG, "tunnel state: $state")
            state == Tunnel.State.UP
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            currentTunnel = null
            false
        }
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        try {
            currentTunnel?.let { tunnel ->
                backend?.setState(tunnel, Tunnel.State.DOWN, null)
            }
            currentTunnel = null
            true
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed", e)
            false
        }
    }

    fun isConnected(): Boolean {
        return currentTunnel != null &&
                backend?.getState(currentTunnel!!) == Tunnel.State.UP
    }
}

class WgTunnel(private val name: String) : Tunnel {
    override fun getName() = name
    override fun onStateChange(state: Tunnel.State) {
        Log.d("WgTunnel", "State changed: $state")
    }
    override fun isIpv4ResolutionPreferred(): Boolean = false
    override fun isMetered(): Boolean = false
}