package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * «Белый список №3»: туннель OpenFlux через документ Облака Mail.ru пользователя (юзер 2026-11-16).
 * Клиент p1neappleXpress/OpenFlux (GPL-3.0) — отдельным процессом (libopenflux.so в jniLibs), как xray:
 * SOCKS5 на 127.0.0.1:порт → документ Mail.ru → выход OpenFlux на сервере (свой на каждый документ).
 * Сервер присылает в конфиге sing-box поле «x_openflux» {url, key}; sing-box ходит в SOCKS5 клиента.
 */
object OpenfluxProcess {
    private const val TAG = "OpenfluxProcess"
    @Volatile private var proc: java.lang.Process? = null

    /** Есть «x_openflux» — запускает клиент и возвращает конфиг sing-box без поля; нет — как есть; null — ошибка. */
    fun prepare(context: Context, configJson: String): String? {
        val root = try { JSONObject(configJson) } catch (_: Exception) { stop(); return configJson }
        val x = root.optJSONObject("x_openflux") ?: run { stop(); return configJson }
        root.remove("x_openflux")
        val url = x.optString("url"); val key = x.optString("key")
        if (url.isBlank() || key.isBlank()) return null
        val port = try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } } catch (_: Exception) { 0 }
        if (port <= 0) return null
        val outs = root.optJSONArray("outbounds") ?: return null
        val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.optString("tag") == "proxy" } ?: return null
        proxy.put("type", "socks").put("server", "127.0.0.1").put("server_port", port).put("version", "5")
        return if (start(context, url, key, port)) root.toString() else null
    }

    private fun start(context: Context, url: String, key: String, port: Int): Boolean {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libopenflux.so")
        if (!bin.exists()) { AppLog.w("openflux: нет бинарника"); return false }
        val dir = File(context.filesDir, "openflux").apply { mkdirs() }
        val keyFile = File(dir, "key").apply { writeText(key + "\n") }
        return try {
            val p = ProcessBuilder(bin.absolutePath, "--role=client", "--inbound=socks5", "--socks5=127.0.0.1:$port",
                "--transport=mailru", "--url=$url", "--encryption-key-file=${keyFile.absolutePath}",
                "--cookie-store=${File(dir, "cookies.json").absolutePath}")
                .directory(dir).redirectErrorStream(true).start()
            proc = p
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        val safe = line.replace(url, "<doc>")
                        Log.d(TAG, safe)
                        if (Regex("(?i)error|fail|panic|captcha|forbidden|401|409").containsMatchIn(safe))
                            AppLog.w("openflux: ${safe.take(300)}")
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            var up = false
            for (i in 0 until 50) {
                if (!p.isAlive) break
                up = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }; true } catch (_: Exception) { false }
                if (up) break
                Thread.sleep(100)
            }
            if (!up) { AppLog.w("openflux: не запустился (жив: ${p.isAlive})"); stop() } else AppLog.i("openflux: запущен")
            up
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}", e)
            AppLog.w("openflux: ошибка запуска ${e.javaClass.simpleName}")
            stop(); false
        }
    }

    fun stop() {
        val p = proc ?: return
        proc = null
        try {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Exception) {}
    }
}
