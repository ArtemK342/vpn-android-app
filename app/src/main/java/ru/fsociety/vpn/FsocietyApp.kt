package ru.fsociety.vpn

import android.app.Application
import android.content.Intent
import android.os.Build

/**
 * Инициализация на весь процесс — при любом запуске (экран, служба VPN, ресивер уведомления,
 * ночное автообновление): токены и обработка событий подключения не зависят от открытого экрана.
 *
 * Процесс «:awg» (AmneziaWG, см. AwgService) — отдельный: там ничего из основного не нужно, только
 * передать в основной процесс сигнал «Постоянной VPN». Процесс «:ovpn» (OpenVPN + Cloak, OvpnBridge) —
 * тоже только служба.
 */
class FsocietyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)   // журнал для поддержки — во всех процессах
        // OpenVPN + Cloak (OpenVpnService): итоги — в основной процесс через OpenVpnEventReceiver
        if (processName().endsWith(":ovpn")) return
        if (processName().endsWith(":awg")) {
            org.amnezia.awg.backend.AbstractBackend.setAlwaysOnCallback {
                sendBroadcast(Intent(this, AlwaysOnReceiver::class.java))
            }
            return
        }
        TokenStore.init(this)
        OvpnBridge.init(this)
        VpnNotificationHelper.init(this)
        ConnectionManager.init(this)
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName()
        else try {
            java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        } catch (_: Exception) { packageName }
}
