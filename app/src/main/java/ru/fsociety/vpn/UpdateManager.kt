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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private const val MIRROR = "https://fsociety-vpn.org/download/files/"
    private val http = okhttp3.OkHttpClient()
    private val downloadLock = Mutex()
    private val releaseVersion = Regex("[0-9]+([.][0-9]+)+")

    private fun mirroredRelease(): GitHubRelease {
        val request = okhttp3.Request.Builder().url(MIRROR + "latest.json").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val entry = org.json.JSONObject(response.body!!.string()).getJSONObject("android")
            val version = entry.getString("version")
            require(version.matches(Regex("[0-9]+([.][0-9]+)+")))
            val file = entry.getString("file")
            require(file == "fsociety-net-$version.apk")
            val size = entry.getLong("size")
            require(size > 0)
            return GitHubRelease(version, version, null, listOf(GitHubAsset(file, MIRROR + file, size)))
        }
    }

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val hasUpdate: Boolean,
        val releaseNotes: String,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    suspend fun checkForUpdate(currentVersion: String, automatic: Boolean = false): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val release = if (automatic) {
                runCatching { mirroredRelease() }.getOrElse { GitHubClient.service.getLatestRelease(RELEASES_URL) }
            } else {
                try {
                    GitHubClient.service.getLatestRelease(RELEASES_URL)
                } catch (e: java.io.IOException) {
                    mirroredRelease()
                } catch (e: retrofit2.HttpException) {
                    mirroredRelease()
                }
            }
            val latestVersion = release.tag_name.trimStart('v')
            require(releaseVersion.matches(latestVersion))
            val apkAsset = release.assets.first { it.name == "fsociety-net.apk" || it.name == "fsociety-net-$latestVersion.apk" }
            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                hasUpdate = isNewerVersion(latestVersion, currentVersion),
                releaseNotes = release.body ?: "",
                downloadUrl = apkAsset.browser_download_url,
                sizeBytes = apkAsset.size
            )
        }
    }

    // Скачивание и установка разделены: скачанный APK лежит по версии, поэтому если установщик
    // отменили (тап мимо, выдача разрешения «неизвестные источники»), «Установить» запускает его
    // снова без повторного скачивания и без перезапуска приложения.

    private fun apkDir(context: Context) = File(context.cacheDir, "apk").apply { mkdirs() }

    /** Уже скачанный APK этой версии (полный — .part переименовывается только после загрузки). */
    fun downloadedApk(context: Context, version: String): File? {
        if (!releaseVersion.matches(version)) return null
        val file = File(apkDir(context), "fsociety-$version.apk")
        if (!file.isFile) return null
        if (validApk(context, file, version)) return file
        file.delete()
        return null
    }

    @Suppress("DEPRECATION")
    private fun validApk(context: Context, file: File, version: String): Boolean = runCatching {
        val pm = context.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveSigners = archive.signatures.orEmpty().toSet()
        archive.packageName == context.packageName && archive.versionName == version &&
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive) >
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed) &&
            archiveSigners.isNotEmpty() && archiveSigners == installed.signatures.orEmpty().toSet()
    }.getOrDefault(false)

    fun discardDownloadedApk(context: Context, name: String?) {
        val fileName = name ?: return
        val version = fileName.removePrefix("fsociety-").removeSuffix(".apk")
        if (releaseVersion.matches(version) && fileName == "fsociety-$version.apk") {
            File(apkDir(context), fileName).delete()
        }
    }

    /** Скачанный ранее APK версии новее установленной (версия, файл) — предложить установить. */
    fun newestDownloadedApk(context: Context): Pair<String, File>? =
        apkDir(context).listFiles()
            ?.mapNotNull { f ->
                Regex("fsociety-(.+)\\.apk").matchEntire(f.name)?.groupValues?.get(1)?.let { it to f }
            }
            ?.filter { (v, _) -> isNewerVersion(v, BuildConfig.VERSION_NAME) && downloadedApk(context, v) != null }
            ?.maxWithOrNull { a, b -> if (isNewerVersion(a.first, b.first)) 1 else if (isNewerVersion(b.first, a.first)) -1 else 0 }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            downloadedApk(context, info.latestVersion)?.let { return@withLock Result.success(it) }
            runCatching { downloadFile(context, info, onProgress) }
        }
    }

    private fun downloadFile(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = apkDir(context)
        val target = File(dir, "fsociety-${info.latestVersion}.apk")
        val part = File(dir, target.name.removeSuffix(".apk") + ".part.apk")
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
        if (!validApk(context, part, info.latestVersion)) {
            part.delete()
            error("Неверный пакет, версия или подпись APK")
        }
        // старые версии больше не нужны
        dir.listFiles()?.filter { it.name.endsWith(".apk") && it != target && it != part }?.forEach { it.delete() }
        if (!part.renameTo(target)) error("Не удалось сохранить файл")
        onProgress(100)
        return target
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
