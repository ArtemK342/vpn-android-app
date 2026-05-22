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

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = File(context.cacheDir, "apk").apply { mkdirs() }
            val apkFile = File(cacheDir, "update.apk")

            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            val body = response.body ?: error("Пустой ответ")
            val total = body.contentLength()
            var downloaded = 0L

            apkFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                    }
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(install)
        }
    }

    // Сравниваем версии формата "0.0.1"
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
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
