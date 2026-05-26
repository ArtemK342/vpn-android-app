package ru.fsociety.vpn

import android.system.Os
import android.util.Base64
import net.openvpn.ovpn3.ClientAPI_Config
import org.amnezia.vpn.protocol.openvpn.OpenVpn
import org.amnezia.vpn.util.LibraryLoader.loadSharedLibrary
import org.json.JSONObject

class OpenVpnWithCloak : OpenVpn() {

    override fun internalInit() {
        super.internalInit()
        loadSharedLibrary(context, "ck-ovpn-plugin")
    }

    override fun parseConfig(config: JSONObject): ClientAPI_Config {
        val openVpnConfig = super.parseConfig(config)
        val cloakJson = config.optString("cloak_config", "")
        if (cloakJson.isNotEmpty()) {
            val base64 = Base64.encodeToString(
                cloakJson.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            Os.setenv("CLOAK_CONFIG", base64, true)
            openVpnConfig.usePluggableTransports = true
        }
        return openVpnConfig
    }
}
