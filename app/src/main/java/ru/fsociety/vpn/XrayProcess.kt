package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.UUID

/**
 * Движок xray отдельным процессом — для серверов, которые sing-box не умеет (VLESS XHTTP через Yandex CDN).
 * Библиотеку xray (gomobile AAR) нельзя положить рядом с libbox: одинаковые классы go.Seq.
 * Поэтому официальный бинарник Xray-core 26.3.27 (android-arm64, SHA-256 сверен с релизом) лежит в
 * jniLibs как libxray.so и запускается из nativeLibraryDir.
 *
 * Схема: sing-box (TUN) → outbound «proxy» = socks 127.0.0.1:порт → xray (socks-вход с паролем) → сервер.
 * Сервер присылает конфиг xray внутри конфига sing-box в поле «x_xray»; порт и пароль задаёт приложение.
 * Сокеты xray идут мимо VPN: приложение всегда исключено из TUN (XrayVpnService).
 */
object XrayProcess {
    private const val TAG = "XrayProcess"
    @Volatile private var proc: java.lang.Process? = null

    /**
     * Есть «x_xray» — запускает xray и возвращает конфиг sing-box без этого поля, с портом и паролем
     * локального socks. Нет поля — конфиг как есть. null — xray не запустился.
     */
    fun prepare(context: Context, configJson: String): String? {
        val root = try { JSONObject(configJson) } catch (_: Exception) { stop(); return configJson }
        val x = root.optJSONObject("x_xray") ?: run { stop(); return configJson }   // xray прошлого сервера не нужен
        root.remove("x_xray")
        val port = freeLocalPort()
        if (port <= 0) return null
        val user = UUID.randomUUID().toString()
        val pass = UUID.randomUUID().toString()
        try {
            // Вход xray: socks на 127.0.0.1:port с паролем
            val inbounds = x.optJSONArray("inbounds") ?: JSONArray().also { x.put("inbounds", it) }
            val inb = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }
                .firstOrNull { it.optString("tag") == "local-in" }
                ?: JSONObject().put("tag", "local-in").also { inbounds.put(it) }
            inb.put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            inb.put("settings", JSONObject().put("auth", "password").put("udp", true)
                .put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", pass))))
            // Выход sing-box «proxy» → этот socks
            val outs = root.optJSONArray("outbounds") ?: return null
            val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }
                .firstOrNull { it.optString("tag") == "proxy" } ?: return null
            proxy.put("type", "socks").put("server", "127.0.0.1").put("server_port", port)
                .put("version", "5").put("username", user).put("password", pass)
        } catch (e: Exception) {
            Log.e(TAG, "prepare: ${e.message}")
            return null
        }
        return if (start(context, x.toString(), port)) root.toString() else null
    }

    private fun start(context: Context, xrayConfig: String, port: Int): Boolean {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) { AppLog.w("xray: нет бинарника"); return false }
        val dir = File(context.filesDir, "xray").apply { mkdirs() }
        val cfg = File(dir, "config.json")
        return try {
            cfg.writeText(xrayConfig)
            val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
                .directory(dir).redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
            val p = pb.start()
            proc = p
            // Вывод xray: в logcat, в журнал приложения — только предупреждения и ошибки
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, line)
                        if (line.contains("[Error]") || line.contains("[Warning]") || line.contains("Failed"))
                            AppLog.w("xray: ${line.take(300)}")
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            // Ждём, пока socks-вход начнёт принимать соединения
            var up = false
            for (i in 0 until 50) {
                if (!p.isAlive) break
                up = try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }; true
                } catch (_: Exception) { false }
                if (up) break
                Thread.sleep(100)
            }
            cfg.delete()   // xray прочитал конфиг при старте; ключ не храним на диске
            if (!up) {
                AppLog.w("xray: не запустился (жив: ${p.isAlive})")
                stop()
            } else AppLog.i("xray: запущен")
            up
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}", e)
            AppLog.w("xray: ошибка запуска ${e.javaClass.simpleName}")
            cfg.delete()
            stop()
            false
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

    private fun freeLocalPort(): Int = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) { 0 }
}
