package ru.fsociety.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri

// Play Store flavor: обновления через Google Play, не через GitHub
object UpdateManager {

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val hasUpdate: Boolean,
        val releaseNotes: String,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    // В Play Store версии обновления управляет сам Google Play
    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo> {
        return Result.success(
            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = currentVersion,
                hasUpdate = false,
                releaseNotes = "",
                downloadUrl = "",
                sizeBytes = 0L
            )
        )
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> {
        // Открываем страницу приложения в Play Store
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return Result.success(Unit)
    }
}
