package ru.fsociety.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/** Разметка новостей — те же правила, что в vpn-site assets/js/news-format.js. */
class NewsFormatTest {
    private fun p(s: String) = NewsFormat.parse(s)
    private fun plain(s: String) = p(s).joinToString("") { it.text }

    @Test fun allFourStyles() {
        val r = p("**ж** *к* __п__ ~~з~~")
        assertEquals(listOf("ж", " ", "к", " ", "п", " ", "з"), r.map { it.text })
        assertEquals(true, r[0].bold); assertEquals(true, r[2].italic)
        assertEquals(true, r[4].underline); assertEquals(true, r[6].strike)
        assertEquals(false, r[1].bold || r[1].italic || r[1].underline || r[1].strike)
    }

    @Test fun nested() {
        val r = p("**жирный *и курсив* жирный**")
        assertEquals("жирный и курсив жирный", r.joinToString("") { it.text })
        assertEquals(true, r.all { it.bold })
        assertEquals(listOf(false, true, false), r.map { it.italic })
    }

    @Test fun literalCases() {
        assertEquals("* пункт 1\n* пункт 2", plain("* пункт 1\n* пункт 2"))
        assertEquals(true, p("* пункт 1\n* пункт 2").none { it.italic })
        assertEquals("**без пары", plain("**без пары"))
        assertEquals(true, p("**без пары").none { it.bold })
        assertEquals("*не курсив*", plain("\\*не курсив\\*"))
        assertEquals(true, p("\\*не курсив\\*").none { it.italic })
        assertEquals("2 ** 3", plain("2 ** 3"))
    }

    @Test fun linksUntouched() {
        val r = p("см. https://x.com/a__b__c, и __тут__")
        val link = r.first { it.url != null }
        assertEquals("https://x.com/a__b__c", link.url)
        assertEquals(false, link.underline)
        assertEquals(true, r.last { it.text == "тут" }.underline)
        assertEquals("см. https://x.com/a__b__c, и тут", r.joinToString("") { it.text })
    }

    @Test fun emojiKeptAsIs() {
        val s = "🚀 **Вышло 👨‍👩‍👧 🇷🇺** ❤️"
        val r = p(s)
        assertEquals("🚀 Вышло 👨‍👩‍👧 🇷🇺 ❤️", r.joinToString("") { it.text })
        assertEquals(true, r.first { it.text.contains("👨‍👩‍👧") }.bold)
    }
}
