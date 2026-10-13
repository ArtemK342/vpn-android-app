package ru.fsociety.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ночное автообновление (Настройки → Обновление → «Автоматически»).
 *
 * Раз в сутки, когда телефон заряжается и не используется (обычно ночью), по Wi-Fi или любой
 * сети — как выбрал пользователь: проверяем GitHub, скачиваем, на Android 12+ ставим без
 * участия пользователя (PackageInstaller, USER_ACTION_NOT_REQUIRED). Если VPN включён — не
 * ставим (не рвём туннель ночью). Если система всё же требует подтверждения или Android старый —
 * APK остаётся скачанным, и при открытии приложения появляется «Установить?».
 */
object AutoUpdateScheduler {
    private const val WORK = "auto_update"

    fun apply(context: Context) {
        val prefs = context.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
        val wm = WorkManager.getInstance(context)
        if (!prefs.getBoolean(PREF_AUTO_UPDATE, false)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val wifiOnly = prefs.getString(PREF_AUTO_UPDATE_NET, AUTO_UPDATE_WIFI) != AUTO_UPDATE_ANY
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class AutoUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_AUTO_UPDATE, false)) return Result.success()
        val info = UpdateManager.checkForUpdate(BuildConfig.VERSION_NAME).getOrElse {
            AppErrors.log("night update check", it); return Result.retry()
        }
        if (!info.hasUpdate || info.downloadUrl.isEmpty()) return Result.success()
        val apk = UpdateManager.downloadedApk(ctx, info.latestVersion)
            ?: UpdateManager.download(ctx, info) { }.getOrElse {
                AppErrors.log("night update download", it); return Result.retry()
            }
        val vpnOn = VpnManager.isConnected() || SingboxManager.isConnected() || OpenVpnService.isRunning
        if (vpnOn || !UpdateManager.canInstall(ctx)) {
            Log.i("AutoUpdate", "downloaded ${info.latestVersion}, install on next app launch (vpn=$vpnOn)")
            return Result.success()
        }
        SilentInstaller.install(ctx, apk)
        return Result.success()
    }
}

object SilentInstaller {
    /** Тихая установка своего обновления (Android 12+). false — не поддерживается. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val pending = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            AppErrors.log("silent install", e)
            false
        }
    }
}

/** Результат тихой установки. Нужно подтверждение — ночью не показываем: APK остаётся скачанным,
 *  при открытии приложения появится «Установить?». */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("AutoUpdate", "silent install status=$status $msg")
    }
}
