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
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat

/**
 * Kill Switch: при неожиданном обрыве рабочего туннеля интернет блокируется, пока приложение не
 * переподключится или пользователь не нажмёт «Разблокировать» (уведомление / главный экран).
 *
 * Как блокируется: поднимается «пустой» VPN-интерфейс с маршрутами на весь IPv4/IPv6, пакеты из
 * которого никто не читает — трафик других приложений никуда не уходит. Само приложение из него
 * исключено, чтобы переподключение и запросы к API работали. Новый туннель (WireGuard / sing-box /
 * OpenVPN) заменяет этот интерфейс, после чего служба останавливается.
 *
 * Раньше переключатель Kill Switch только сохранялся в настройках и ничего не делал.
 * Для защиты и при выгрузке приложения системой — «Постоянная VPN» + «Блокировать соединения
 * без VPN» в настройках Android (кнопка на экране Kill Switch).
 *
 * Все вызовы — из главного потока.
 */
object KillSwitch {
    private const val PREF = "kill_switch"

    /** Блокировка включена — главный экран показывает «Разблокировать интернет». */
    val engagedState = mutableStateOf(false)
    val engaged: Boolean get() = engagedState.value

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("fsociety", Context.MODE_PRIVATE).getBoolean(PREF, false)

    /**
     * Обрыв туннеля: заблокировать трафик (если включено). VpnService.prepare() здесь не вызываем:
     * при сохранённом согласии он молча выключил бы VPN другого приложения. Если VPN сейчас у
     * другого приложения, система просто не даст поднять интерфейс — служба остановится сама.
     */
    fun engage(context: Context) {
        if (!enabled(context) || engaged) return
        val intent = Intent(context, KillSwitchService::class.java).setAction(KillSwitchService.ACTION_ENGAGE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            engagedState.value = true
        } catch (e: Exception) {
            // Android 12+ может запретить запуск службы из фона — без падения приложения
            AppErrors.log("kill switch start", e)
        }
    }

    /** Туннель снова поднят или пользователь сам отключился/разблокировал — снять блокировку. */
    fun release(context: Context) {
        if (!engaged) return
        engagedState.value = false
        // Служба ещё не успела вызвать startForeground — stopService сейчас уронил бы приложение
        // («did not call startForeground»); она сама увидит, что блокировка снята, и остановится.
        if (KillSwitchService.foreground) context.stopService(Intent(context, KillSwitchService::class.java))
    }

    /** Служба не смогла поднять интерфейс (VPN у другого приложения) — блокировки нет. */
    internal fun onServiceFailed() {
        engagedState.value = false
    }
}

class KillSwitchService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(localized(newBase))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Сразу и всегда: служба запущена через startForegroundService
        startForeground(NOTIFICATION_ID, buildNotification())
        foreground = true
        if (intent?.action != ACTION_ENGAGE || !KillSwitch.engaged) {
            stopSelf()
            return START_NOT_STICKY
        }
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
        } catch (e: Exception) {
            Log.w(TAG, "kill switch establish failed: ${e.message}")
        }
        if (pfd == null) {
            KillSwitch.onServiceFailed()
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

    // Другое VPN-приложение забрало VPN — не сопротивляемся (и не переподключаемся)
    override fun onRevoke() {
        KillSwitch.release(this)
        VpnEvents.vpnStopped.tryEmit(Unit)
        stopSelf()
    }

    override fun onDestroy() {
        foreground = false
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENGAGE = "ru.fsociety.vpn.KILL_SWITCH_ENGAGE"
        private const val NOTIFICATION_ID = 1004
        private const val TAG = "KillSwitch"

        /** startForeground уже вызван — службу можно останавливать stopService. */
        @Volatile var foreground = false
            private set
    }
}

/** «Разблокировать» в уведомлении Kill Switch. */
class KillSwitchReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConnectionManager.unblockKillSwitch()
        context.getSystemService(NotificationManager::class.java)?.cancel(1004)
    }
}
