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
        // параметры с сервера (api/openflux_tune.json) — подбор без пересборки приложения
        val env = x.optJSONObject("env")?.let { e -> e.keys().asSequence().filter { it.startsWith("OPENFLUX_") }
            .associateWith { e.optString(it) } } ?: emptyMap()
        return if (start(context, url, key, port, env)) root.toString() else null
    }

    private fun start(context: Context, url: String, key: String, port: Int, env: Map<String, String>): Boolean {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libopenflux.so")
        if (!bin.exists()) { AppLog.w("openflux: нет бинарника"); return false }
        val dir = File(context.filesDir, "openflux").apply { mkdirs() }
        val keyFile = File(dir, "key").apply { writeText(key + "\n") }
        val cookieFile = File(dir, "cookies.json")
        try { seedCookies(context, cookieFile, url) } catch (e: Exception) { AppLog.w("openflux: cookies ${e.javaClass.simpleName}") }
        return try {
            val p = ProcessBuilder(bin.absolutePath, "--role=client", "--inbound=socks5", "--socks5=127.0.0.1:$port",
                "--transport=mailru", "--url=$url", "--encryption-key-file=${keyFile.absolutePath}",
                "--cookie-store=${cookieFile.absolutePath}")
                .directory(dir).redirectErrorStream(true)
                .apply {
                    // 2026-11-16: залогиненное соединение с документом Mail.ru рвётся (4004 «access deny») уже после
                    // ~31-42 сообщений (у анонимного ~80, под него умолчания OpenFlux) — меняем соединение раньше.
                    environment()["OPENFLUX_MAILRU_ROTATE_MSGS"] = "15"
                    environment()["OPENFLUX_MAILRU_ROTATE_MS"] = "15000"
                    environment()["OPENFLUX_MAILRU_MSG_INTERVAL_MS"] = "400"
                    environment().putAll(env)
                    if (env.isNotEmpty()) AppLog.i("openflux: параметры с сервера ${env.entries.joinToString { "${it.key.removePrefix("OPENFLUX_")}=${it.value}" }}")
                }.start()
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

    /**
     * Mail.ru открывает документ только залогиненным: кладём cookies входа (окно «Войти в Mail.ru») в cookie-store
     * OpenFlux под ключом-ссылкой документа. Если там уже та же сессия (OpenFlux мог обновить cookies) — не трогаем.
     */
    private fun seedCookies(context: Context, file: File, url: String) {
        val jar = WhitelistLinks.mailruCookies(context) ?: return
        val root = try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
        val cur = root.optJSONObject(url)
        val same = cur != null && listOf("Mpop", "sdcs").all { cur.optString(it) == (jar[it] ?: "") }
        if (same) return
        root.put(url, JSONObject(jar as Map<*, *>))
        file.writeText(root.toString())
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
