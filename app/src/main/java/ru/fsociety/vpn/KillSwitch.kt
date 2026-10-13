package ru.fsociety.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Kill Switch: при неожиданном обрыве туннеля интернет блокируется, пока приложение не
 * переподключится или пользователь не нажмёт «Разблокировать» в уведомлении.
 *
 * Как блокируется: поднимается «пустой» VPN-интерфейс с маршрутами на весь IPv4/IPv6, пакеты из
 * которого никто не читает — трафик других приложений никуда не уходит. Само приложение из него
 * исключено, чтобы переподключение и запросы к API работали. Новый туннель (WireGuard / sing-box /
 * OpenVPN) заменяет этот интерфейс, после чего служба останавливается.
 *
 * Раньше переключатель Kill Switch только сохранялся в настройках и ничего не делал.
 * Для защиты и при выгрузке приложения системой — «Постоянная VPN» + «Блокировать соединения
 * без VPN» в настройках Android (кнопка на экране Kill Switch).
 */
object KillSwitch {
    private const val PREF = "kill_switch"

    @Volatile var engaged = false
        private set

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getBoolean(PREF, false)

    /** Обрыв туннеля: заблокировать трафик (если включено и VPN не забрало другое приложение). */
    fun engage(context: Context) {
        if (!enabled(context)) return
        if (VpnService.prepare(context) != null) return   // разрешение VPN у другого приложения
        val intent = Intent(context, KillSwitchService::class.java).setAction(KillSwitchService.ACTION_ENGAGE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            engaged = true
        } catch (e: Exception) {
            // Android 12+ может запретить запуск службы из фона — без падения приложения
            AppErrors.log("kill switch start", e)
        }
    }

    /** Туннель снова поднят или пользователь сам отключился/разблокировал — снять блокировку. */
    fun release(context: Context) {
        if (!engaged) return
        engaged = false
        context.stopService(Intent(context, KillSwitchService::class.java))
    }
}

class KillSwitchService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(localized(newBase))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_ENGAGE) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            if (pfd == null) {
                pfd = Builder()
                    .setSession("FsocietyKillSwitch")
                    .addAddress("10.255.255.1", 32)
                    .addAddress("fd00:ffff::1", 128)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDisallowedApplication(packageName)   // само приложение — переподключение и API
                    .establish()
            }
            if (pfd == null) stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "kill switch establish failed: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val openPi = PendingIntent.getActivity(
            this, 31, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val unblockPi = PendingIntent.getBroadcast(
            this, 32, Intent(this, KillSwitchReleaseReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, VpnNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.ks_notif_title))
            .setContentText(getString(R.string.ks_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.ks_unblock), unblockPi)
            .build()
    }

    // Другое VPN-приложение забрало VPN — не сопротивляемся
    override fun onRevoke() {
        KillSwitch.release(this)
        stopSelf()
    }

    override fun onDestroy() {
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENGAGE = "ru.fsociety.vpn.KILL_SWITCH_ENGAGE"
        private const val NOTIFICATION_ID = 1004
        private const val TAG = "KillSwitch"
    }
}

/** «Разблокировать» в уведомлении Kill Switch. */
class KillSwitchReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KillSwitch.release(context)
        context.getSystemService(NotificationManager::class.java)?.cancel(1004)
    }
}
