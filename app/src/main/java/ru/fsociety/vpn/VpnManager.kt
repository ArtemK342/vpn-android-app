package ru.fsociety.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    var connectedServerName: String = ""
    var killSwitchEnabled: Boolean = false

    // Эмитит когда туннель упал неожиданно (не через disconnect())
    val tunnelDropped = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun isConnected(): Boolean {
        val tunnel = currentTunnel ?: return false
        return try { backend?.getState(tunnel) == Tunnel.State.UP } catch (_: Exception) { false }
    }

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

    var splitSettings: SplitTunnelingSettings? = null

    suspend fun connect(context: Context, configString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            init(context)
            currentTunnel?.let { t ->
                runCatching { backend?.setState(t, Tunnel.State.DOWN, null) }
            }
            // Применяем раздельное туннелирование для сайтов
            val effectiveConfig = splitSettings?.let {
                SplitTunnelingManager.applyToConfig(configString, it)
            } ?: configString
            val config = Config.parse(BufferedReader(StringReader(effectiveConfig)))
            val tunnel = WgTunnel("fsociety") { state ->
                // Туннель упал не через наш disconnect() — kill switch
                if (state == Tunnel.State.DOWN && killSwitchEnabled && currentTunnel != null) {
                    currentTunnel = null
                    connectedServerName = ""
                    tunnelDropped.tryEmit(Unit)
                }
            }
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
            val tunnel = currentTunnel
            currentTunnel = null  // обнуляем ДО setState чтобы onStateChange не сработал как дроп
            connectedServerName = ""
            tunnel?.let { backend?.setState(it, Tunnel.State.DOWN, null) }
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

class WgTunnel(
    private val name: String,
    private val onStateChanged: ((Tunnel.State) -> Unit)? = null
) : Tunnel {
    override fun getName() = name
    override fun onStateChange(state: Tunnel.State) { onStateChanged?.invoke(state) }
    override fun isIpv4ResolutionPreferred(): Boolean = false
    override fun isMetered(): Boolean = false
}