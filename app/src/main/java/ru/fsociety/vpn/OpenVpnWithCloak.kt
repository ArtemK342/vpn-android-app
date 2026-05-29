package ru.fsociety.vpn

import android.system.Os
import android.util.Base64
import net.openvpn.ovpn3.ClientAPI_Config
import org.amnezia.vpn.protocol.openvpn.OpenVpn
import org.amnezia.vpn.protocol.openvpn.OpenVpnConfig
import org.amnezia.vpn.util.LibraryLoader.loadSharedLibrary
import org.json.JSONObject

class OpenVpnWithCloak : OpenVpn() {

    // eval_config() in C++ always overwrites CLOAK_CONFIG from the .ovpn file options —
    // if the .ovpn has no "cloak" directive, it sets CLOAK_CONFIG="" which causes a crash.
    // We store the base64 here and apply it in configPluggableTransport() which runs
    // AFTER eval_config() and BEFORE connect().
    private var pendingCloakBase64: String? = null

    override fun internalInit() {
        super.internalInit()
        loadSharedLibrary(context, "ck-ovpn-plugin")
    }

    override fun parseConfig(config: JSONObject): ClientAPI_Config {
        val openVpnConfig = super.parseConfig(config)
        val cloakJson = config.optString("cloak_config", "")
        if (cloakJson.isNotEmpty()) {
            pendingCloakBase64 = Base64.encodeToString(
                cloakJson.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            openVpnConfig.usePluggableTransports = true
        }
        return openVpnConfig
    }

    // Called by OpenVpn.startVpn() AFTER eval_config() and BEFORE connect().
    // This is the right place to set CLOAK_CONFIG so fw.hpp::CloakTransport sees it.
    override fun configPluggableTransport(configBuilder: OpenVpnConfig.Builder, config: JSONObject) {
        val base64 = pendingCloakBase64 ?: return
        Os.setenv("CLOAK_CONFIG", base64, true)
    }
}
