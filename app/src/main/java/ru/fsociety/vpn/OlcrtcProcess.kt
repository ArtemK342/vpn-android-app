package ru.fsociety.vpn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * «Белый список №4»: olcRTC — туннель внутри видеозвонка в комнате Яндекс Телемоста / WB Stream пользователя
 * (юзер 2026-11-17). Клиент openlibrecommunity/olcrtc (WTFPL), собран из исходников под Android
 * (third_party/olcrtc), отдельным процессом (libolcrtc.so), как xray и OpenFlux:
 * SOCKS5 на 127.0.0.1:порт (с паролем) → видеопоток vp8 → olcRTC-сервер в той же комнате на нашем сервере.
 * Сервер присылает в конфиге sing-box поле «x_olcrtc» {provider, room, key, transport}.
 */
object OlcrtcProcess {
    private const val TAG = "OlcrtcProcess"
    @Volatile private var proc: java.lang.Process? = null

    fun prepare(context: Context, configJson: String): String? {
        val root = try { JSONObject(configJson) } catch (_: Exception) { stop(); return configJson }
        val x = root.optJSONObject("x_olcrtc") ?: run { stop(); return configJson }
        root.remove("x_olcrtc")
        val port = try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } } catch (_: Exception) { 0 }
        if (port <= 0) return null
        val user = UUID.randomUUID().toString().replace("-", "")
        val pass = UUID.randomUUID().toString().replace("-", "")
        val outs = root.optJSONArray("outbounds") ?: return null
        val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.optString("tag") == "proxy" } ?: return null
        proxy.put("type", "socks").put("server", "127.0.0.1").put("server_port", port).put("version", "5")
            .put("username", user).put("password", pass)
        // YAML-конфиг клиента (строки — в JSON-кавычках, YAML их принимает)
        fun q(s: String) = JSONObject.quote(s)
        val yaml = buildString {
            append("mode: cnc\n")
            append("auth:\n  provider: ${q(x.optString("provider"))}\n")
            append("room:\n  id: ${q(x.optString("room"))}\n")
            append("crypto:\n  key: ${q(x.optString("key"))}\n")
            append("net:\n  transport: ${q(x.optString("transport", "vp8channel"))}\n  dns: \"77.88.8.8:53\"\n")
            append("vp8:\n  fps: 30\n  batch_size: 64\n")
            append("socks:\n  host: \"127.0.0.1\"\n  port: $port\n  user: ${q(user)}\n  pass: ${q(pass)}\n")
        }
        return if (start(context, yaml, port)) root.toString() else null
    }

    private fun start(context: Context, yaml: String, port: Int): Boolean {
        stop()
        val bin = File(context.applicationInfo.nativeLibraryDir, "libolcrtc.so")
        if (!bin.exists()) { AppLog.w("olcrtc: нет бинарника"); return false }
        val dir = File(context.filesDir, "olcrtc").apply { mkdirs() }
        val cfg = File(dir, "client.yaml").apply { writeText(yaml) }
        return try {
            val p = ProcessBuilder(bin.absolutePath, cfg.absolutePath).directory(dir).redirectErrorStream(true).start()
            proc = p
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, line)
                        if (Regex("(?i)error|fail|panic|denied|forbidden").containsMatchIn(line))
                            AppLog.w("olcrtc: ${line.take(300)}")
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            var up = false
            for (i in 0 until 80) {
                if (!p.isAlive) break
                up = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }; true } catch (_: Exception) { false }
                if (up) break
                Thread.sleep(100)
            }
            cfg.delete()   // ключ не храним на диске — клиент прочитал конфиг при старте
            if (!up) { AppLog.w("olcrtc: не запустился (жив: ${p.isAlive})"); stop() } else AppLog.i("olcrtc: запущен")
            up
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}", e)
            AppLog.w("olcrtc: ошибка запуска ${e.javaClass.simpleName}")
            cfg.delete(); stop(); false
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
