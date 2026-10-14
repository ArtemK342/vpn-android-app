package ru.fsociety.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал для поддержки (Настройки → Приложение → Журнал). По умолчанию выключен, хранится только
 * на телефоне; отправляет сам пользователь через «Поделиться».
 *  • MIN — события подключения и ошибки приложения (без адресов сайтов);
 *  • MAX — плюс журнал соединений sing-box (в нём видны адреса сайтов) и системные сообщения
 *    приложения (logcat: только наше приложение — читать журнал всего телефона Android не даёт).
 * Токены, ключи, пароли, UUID, email и 16-значные коды из журнала вырезаются.
 * Режим — в файле, а не в настройках: его видят оба процесса (основной и «:awg»).
 */
enum class LogMode { OFF, MIN, MAX }

object AppLog {
    private const val FILE_LIMIT = 400_000L   // ~3000 строк; дальше обрезаем старую половину
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    private fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }
    private fun modeFile(context: Context) = File(dir(context), "mode")
    private fun appFile(context: Context) = File(dir(context), "app.log")
    /** Журнал соединений sing-box (пишет сам sing-box, путь — в его конфиге). */
    fun singboxFile(context: Context) = File(dir(context), "singbox.log")

    fun mode(context: Context? = app): LogMode {
        val ctx = context ?: return LogMode.OFF
        return try {
            LogMode.valueOf(modeFile(ctx).takeIf { it.exists() }?.readText()?.trim().orEmpty())
        } catch (_: Exception) { LogMode.OFF }
    }

    fun setMode(context: Context, mode: LogMode) {
        if (mode == LogMode.OFF) {
            clear(context)
            modeFile(context).delete()
        } else {
            modeFile(context).writeText(mode.name)
            i("журнал включён: $mode")
        }
    }

    fun clear(context: Context) {
        appFile(context).delete()
        singboxFile(context).delete()
        File(context.cacheDir, "logs").listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(context: Context): Long = appFile(context).length() + singboxFile(context).length()

    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)

    @Synchronized
    private fun write(level: String, msg: String) {
        val ctx = app ?: return
        if (mode(ctx) == LogMode.OFF) return
        try {
            val f = appFile(ctx)
            if (f.length() > FILE_LIMIT) {
                val lines = f.readLines()
                f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
            f.appendText("${stamp()} $level ${redact(msg)}\n")
        } catch (_: Exception) {}
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private val REDACT = listOf(
        Regex("Bearer\\s+[A-Za-z0-9._\\-]+") to "Bearer ***",
        Regex("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+") to "***",
        Regex("(?i)\\b(private_?key|password|passwd|secret|token)\\b\\s*[=:]\\s*\\S+") to "$1=***",
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") to "<uuid>",
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}") to "<email>",
        Regex("(?<!\\d)\\d{16}(?!\\d)") to "<code>",
    )

    fun redact(s: String): String = REDACT.fold(s) { acc, (re, rep) -> re.replace(acc, rep) }

    private fun tail(file: File, lines: Int): List<String> =
        if (!file.exists()) emptyList() else try { file.readLines().takeLast(lines) } catch (_: Exception) { emptyList() }

    /** Только сообщения нашего приложения (оба процесса): чужие Android приложению не показывает. */
    private fun logcat(lines: Int): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", lines.toString()).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readLines().also { p.waitFor() }
    } catch (e: Exception) { listOf("logcat: ${e.message}") }

    /** Собрать файл для отправки (~3000 строк максимум). */
    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val mode = mode(context)
        val prefs = context.getSharedPreferences("fsociety", Context.MODE_PRIVATE)
        val rules = SplitTunnelingManager.load(prefs)
        val out = File(context.cacheDir, "logs").apply { mkdirs() }
            .resolve("fsociety-log-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        val text = buildString {
            appendLine("FsocietyNet ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), журнал: $mode")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Время: ${stamp()}, язык: ${prefs.getString("language", "ru")}")
            appendLine("Подключение: ${ConnectionManager.connectedServer.value?.let { "${it.server_type} ${it.id}" } ?: "нет"}")
            appendLine("Kill Switch: ${prefs.getBoolean("kill_switch", false)}, авто-переподключение: " +
                "${prefs.getBoolean("auto_reconnect", true)}, фоновый режим: ${prefs.getBoolean("background_mode", false)}")
            appendLine("Правила: сайты ${if (rules.sitesEnabled) "${rules.sitesMode} ${rules.sites.size}" else "выкл"}, " +
                "приложения ${if (rules.appsEnabled) "${rules.appsMode} ${rules.apps.size}" else "выкл"}")
            appendLine()
            appendLine("===== События приложения =====")
            tail(appFile(context), 800).forEach { appendLine(it) }
            if (mode == LogMode.MAX) {
                appendLine()
                appendLine("===== Соединения sing-box =====")
                tail(singboxFile(context), 800).forEach { appendLine(it) }
                appendLine()
                appendLine("===== Системные сообщения приложения (logcat) =====")
                logcat(1400).forEach { appendLine(it) }
            }
        }
        out.writeText(redact(text))
        out
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
