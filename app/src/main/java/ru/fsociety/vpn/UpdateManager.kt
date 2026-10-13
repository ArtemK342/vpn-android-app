package ru.fsociety.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.File

// ── GitHub API модели ──

data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String?,
    val assets: List<GitHubAsset>
)

data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long
)

// ── GitHub API интерфейс ──

interface GitHubApiService {
    @GET
    suspend fun getLatestRelease(@Url url: String): GitHubRelease
}

object GitHubClient {
    val service: GitHubApiService by lazy {
        val okHttp = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Accept", "application/vnd.github+json")
                        .build()
                )
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubApiService::class.java)
    }
}

// ── Менеджер обновлений ──

object UpdateManager {
    private const val REPO = "ArtemK342/vpn-android-app"
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val hasUpdate: Boolean,
        val releaseNotes: String,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val release = GitHubClient.service.getLatestRelease(RELEASES_URL)
            val latestVersion = release.tag_name.trimStart('v')
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                hasUpdate = isNewerVersion(latestVersion, currentVersion),
                releaseNotes = release.body ?: "",
                downloadUrl = apkAsset?.browser_download_url ?: "",
                sizeBytes = apkAsset?.size ?: 0L
            )
        }
    }

    // Скачивание и установка разделены: скачанный APK лежит по версии, поэтому если установщик
    // отменили (тап мимо, выдача разрешения «неизвестные источники»), «Установить» запускает его
    // снова без повторного скачивания и без перезапуска приложения.

    private fun apkDir(context: Context) = File(context.cacheDir, "apk").apply { mkdirs() }

    /** Уже скачанный APK этой версии (полный — .part переименовывается только после загрузки). */
    fun downloadedApk(context: Context, version: String): File? =
        File(apkDir(context), "fsociety-$version.apk").takeIf { it.exists() && it.length() > 0 }

    /** Скачанный ранее APK версии новее установленной (версия, файл) — предложить установить. */
    fun newestDownloadedApk(context: Context): Pair<String, File>? =
        apkDir(context).listFiles()
            ?.mapNotNull { f ->
                Regex("fsociety-(.+)\\.apk").matchEntire(f.name)?.groupValues?.get(1)?.let { it to f }
            }
            ?.filter { (v, f) -> f.length() > 0 && isNewerVersion(v, BuildConfig.VERSION_NAME) }
            ?.maxWithOrNull { a, b -> if (isNewerVersion(a.first, b.first)) 1 else if (isNewerVersion(b.first, a.first)) -1 else 0 }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = apkDir(context)
            val target = File(dir, "fsociety-${info.latestVersion}.apk")
            val part = File(dir, target.name + ".part")
            var downloaded = 0L
            okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(info.downloadUrl).build()
            ).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Пустой ответ")
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) onProgress(((downloaded * 100) / total).toInt().coerceAtMost(99))
                        }
                    }
                }
            }
            if (info.sizeBytes > 0 && downloaded != info.sizeBytes) {
                part.delete(); error("Скачано $downloaded из ${info.sizeBytes} байт")
            }
            // старые версии больше не нужны
            dir.listFiles()?.filter { it.name.endsWith(".apk") && it != target }?.forEach { it.delete() }
            if (!part.renameTo(target)) error("Не удалось сохранить файл")
            onProgress(100)
            target
        }
    }

    /** Разрешено ли приложению ставить APK («установка из неизвестных источников»). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Экран системы с переключателем «Разрешить установку» именно для нашего приложения. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    // Сравниваем версии формата "0.0.1"
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }  // «0.0.3.6-dev» = 0.0.3.6
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
