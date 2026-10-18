package ru.fsociety.vpn

/**
 * Разметка текста новостей — тот же разбор, что на сайте и в админке (vpn-site assets/js/news-format.js,
 * меняйте вместе):  **жирный**  *курсив*  __подчёркнутый__  ~~зачёркнутый~~   \* — просто звёздочка.
 * Маркер открывает стиль, только если сразу за ним не пробел и дальше есть закрывающий (перед ним не
 * пробел): «* пункт списка» и одиночные звёздочки остаются текстом. Ссылки http(s) разметкой не трогаются.
 */
object NewsFormat {
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val url: String? = null,
    )

    private enum class Style { BOLD, UNDERLINE, STRIKE, ITALIC }
    private val MARKERS = listOf("**" to Style.BOLD, "__" to Style.UNDERLINE, "~~" to Style.STRIKE, "*" to Style.ITALIC)
    private val URL_RE = Regex("^https?://[^\\s<>\"]+")
    private val TRAILING = Regex("[.,!?:;)\\]]+$")

    private fun isSpace(c: Char?) = c == null || c.isWhitespace()

    private fun closerAt(text: String, mk: String, from: Int): Int {
        var j = text.indexOf(mk, from)
        while (j != -1 && isSpace(text.getOrNull(j - 1))) j = text.indexOf(mk, j + 1)
        return j
    }

    fun parse(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        val on = mutableSetOf<Style>()
        val buf = StringBuilder()
        fun run(t: String, url: String? = null) = Run(t, Style.BOLD in on, Style.ITALIC in on,
            Style.UNDERLINE in on, Style.STRIKE in on, url)
        fun flush() { if (buf.isNotEmpty()) { runs += run(buf.toString()); buf.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if (c == '\\' && next != null && next in "*_~\\") { buf.append(next); i += 2; continue }
            if (text.startsWith("http://", i) || text.startsWith("https://", i)) {
                val url = URL_RE.find(text.substring(i))!!.value.replace(TRAILING, "")
                flush()
                runs += run(url, url)
                i += url.length
                continue
            }
            var matched = false
            for ((mk, style) in MARKERS) {
                if (!text.startsWith(mk, i)) continue
                if (style in on) {
                    if (!isSpace(text.getOrNull(i - 1))) { flush(); on -= style; i += mk.length; matched = true }
                } else if (!isSpace(text.getOrNull(i + mk.length)) && closerAt(text, mk, i + mk.length + 1) != -1) {
                    flush(); on += style; i += mk.length; matched = true
                }
                if (matched) break   // «**» не подошёл — дальше проверяется одиночная «*»
            }
            if (!matched) { buf.append(c); i++ }
        }
        flush()
        return runs
    }
}
