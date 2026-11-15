package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * «Белый список №2»: AmneziaWG через TURN-серверы звонков VK (юзер 2026-11-16).
 * Клиент cacggghp/vk-turn-proxy v1.8.3 (GPL-3.0, официальный client-android-arm64, SHA-256 сверен с релизом;
 * исходники: https://github.com/cacggghp/vk-turn-proxy/tree/v1.8.3) лежит в jniLibs как libvkturn.so и
 * запускается отдельным процессом (как xray). Он слушает UDP на 127.0.0.1:порт, AmneziaWG шлёт туда,
 * а клиент гонит пакеты (DTLS) через TURN звонка VK на сервер (vk-turn-proxy server → wg0).
 * Логин и пароль TURN он получает сам из ссылки на звонок (WhitelistLinks.vk).
 * Сокеты клиента идут мимо VPN: приложение исключено из туннеля (ConnectionManager).
 */
object TurnProcess {
    private const val TAG = "TurnProcess"
    @Volatile private var proc: java.lang.Process? = null

    /** Запускает клиент; возвращает локальный UDP-порт для Endpoint AmneziaWG или null. */
    fun start(context: Context, peer: String, vkLink: String): Int? {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libvkturn.so")
        if (!bin.exists()) { AppLog.w("vk-turn: нет бинарника"); return null }
        val port = try { DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort } } catch (_: Exception) { 0 }
        if (port <= 0) return null
        return try {
            val p = ProcessBuilder(bin.absolutePath, "-listen", "127.0.0.1:$port", "-peer", peer, "-vk-link", vkLink)
                .directory(context.filesDir).redirectErrorStream(true).start()
            proc = p
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        // ссылку на звонок в журнал не пишем
                        val safe = line.replace(vkLink, "<vk-link>")
                        Log.d(TAG, safe)
                        if (Regex("(?i)error|fail|panic|denied|forbidden").containsMatchIn(safe))
                            AppLog.w("vk-turn: ${safe.take(300)}")
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            Thread.sleep(800)
            if (!p.isAlive) { AppLog.w("vk-turn: процесс завершился сразу"); stop(); return null }
            AppLog.i("vk-turn: запущен")
            port
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}", e)
            AppLog.w("vk-turn: ошибка запуска ${e.javaClass.simpleName}")
            stop()
            null
        }
    }

    fun isRunning() = proc?.isAlive == true

    fun stop() {
        val p = proc ?: return
        proc = null
        try {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Exception) {}
    }

    /**
     * Конфиг AmneziaWG для TURN: Endpoint → 127.0.0.1:[port], MTU 1280 (накладные расходы DTLS/TURN),
     * наше приложение — мимо туннеля (иначе пакеты клиента TURN ушли бы в сам туннель). В режиме
     * «только эти приложения» нас и так нет в списке — ничего не добавляем.
     */
    fun patchWgConfig(config: String, port: Int, packageName: String): String {
        val out = mutableListOf<String>()
        var hasExcluded = false
        val includeMode = config.lines().any { it.trim().startsWith("IncludedApplications") }
        for (line in config.lines()) {
            val t = line.trim()
            when {
                t.startsWith("Endpoint", ignoreCase = true) -> out.add("Endpoint = 127.0.0.1:$port")
                t.startsWith("MTU", ignoreCase = true) -> out.add("MTU = 1280")
                t.startsWith("ExcludedApplications") && !includeMode -> {
                    hasExcluded = true
                    out.add(if (packageName in t) line else "$line, $packageName")
                }
                else -> out.add(line)
            }
        }
        if (!hasExcluded && !includeMode) {
            val idx = out.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
            if (idx >= 0) out.add(idx + 1, "ExcludedApplications = $packageName")
        }
        return out.joinToString("\n")
    }
}
