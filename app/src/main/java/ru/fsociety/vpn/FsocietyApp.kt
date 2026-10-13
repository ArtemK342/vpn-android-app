package ru.fsociety.vpn

import android.app.Application

/**
 * Инициализация на весь процесс — при любом запуске (экран, служба VPN, ресивер уведомления,
 * ночное автообновление): токены и обработка событий подключения не зависят от открытого экрана.
 */
class FsocietyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TokenStore.init(this)
        VpnNotificationHelper.init(this)
        ConnectionManager.init(this)
    }
}
