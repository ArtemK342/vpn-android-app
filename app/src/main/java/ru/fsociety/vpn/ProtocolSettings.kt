package ru.fsociety.vpn

import android.content.Context
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Per-device protocol tuning, stored locally (no server / admin involved).
 *
 * Currently only Hysteria2 congestion control is configurable:
 *  - BBR    : adaptive, safe on any link, but slow ramp-up (the default).
 *  - Brutal : instant full speed, but the client must declare its real
 *             bandwidth — set too high it overshoots and induces packet loss.
 *
 * Speeds are entered by the user in MB/s and converted to Mbps (×8) for the
 * sing-box hysteria2 outbound (up_mbps / down_mbps).
 */
object ProtocolSettings {
    private const val PREFS = "fsociety"

    const val MODE_BBR = "bbr"
    const val MODE_BRUTAL = "brutal"

    // Prefilled when the user first switches to Brutal (in MB/s).
    const val DEFAULT_DOWN_MBS = 3.0f   // ≈ 24 Mbps
    const val DEFAULT_UP_MBS = 1.0f     // ≈ 8 Mbps

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getHysteriaMode(ctx: Context): String =
        prefs(ctx).getString("hysteria_cc_mode", MODE_BBR) ?: MODE_BBR

    fun setHysteriaMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString("hysteria_cc_mode", mode).apply()
    }

    fun getHysteriaDownMBs(ctx: Context): Float =
        prefs(ctx).getFloat("hysteria_down_mbs", DEFAULT_DOWN_MBS)

    fun getHysteriaUpMBs(ctx: Context): Float =
        prefs(ctx).getFloat("hysteria_up_mbs", DEFAULT_UP_MBS)

    fun setHysteriaDownMBs(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat("hysteria_down_mbs", v).apply()
    }

    fun setHysteriaUpMBs(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat("hysteria_up_mbs", v).apply()
    }

    /**
     * Adjust the hysteria2 outbound's congestion control inside a sing-box
     * config according to the user's settings. No-op for configs without a
     * hysteria2 outbound (VLESS / Trojan), and falls back to the original
     * string on any parse error.
     */
    fun patchHysteriaConfig(ctx: Context, configJson: String): String {
        return try {
            val root = JSONObject(configJson)
            val outbounds = root.optJSONArray("outbounds") ?: return configJson
            var touched = false
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                if (ob.optString("type") != "hysteria2") continue
                touched = true
                if (getHysteriaMode(ctx) == MODE_BRUTAL) {
                    val down = (getHysteriaDownMBs(ctx) * 8).roundToInt().coerceAtLeast(1)
                    val up = (getHysteriaUpMBs(ctx) * 8).roundToInt().coerceAtLeast(1)
                    ob.put("down_mbps", down)
                    ob.put("up_mbps", up)
                } else {
                    // BBR: drop the bandwidth hints so Hysteria2 ramps adaptively.
                    ob.remove("down_mbps")
                    ob.remove("up_mbps")
                }
            }
            if (touched) root.toString() else configJson
        } catch (_: Exception) {
            configJson
        }
    }
}
