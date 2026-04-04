package ru.fsociety.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.StringReader

object VpnManager {
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
            currentTunnel?.let { t ->
                runCatching { backend?.setState(t, Tunnel.State.DOWN, null) }
            }
            val config = Config.parse(BufferedReader(StringReader(configString)))
            val tunnel = WgTunnel("fsociety")
            currentTunnel = tunnel
            backend?.setState(tunnel, Tunnel.State.UP, config)
            backend?.getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
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
            false
        }
    }

    fun getTrafficStats(): Pair<Long, Long> {
        val tunnel = currentTunnel ?: return Pair(0L, 0L)
        return try {
            val stats = backend?.getStatistics(tunnel)
            Pair(stats?.totalRx() ?: 0L, stats?.totalTx() ?: 0L)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }
}

class WgTunnel(private val name: String) : Tunnel {
    override fun getName() = name
    override fun onStateChange(state: Tunnel.State) {}
    override fun isIpv4ResolutionPreferred(): Boolean = false
    override fun isMetered(): Boolean = false
}