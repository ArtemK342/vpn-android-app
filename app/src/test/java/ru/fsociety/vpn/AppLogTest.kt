package ru.fsociety.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Из журнала для поддержки вырезаются секреты. */
class AppLogTest {
    private fun r(s: String) = AppLog.redact(s)

    @Test fun secretsRemoved() {
        assertFalse(r("Authorization: Bearer abc.def-ghi_123").contains("abc.def"))
        assertFalse(r("token eyJhbGciOi.eyJzdWIiOi.SflKxwRJSM").contains("eyJ"))
        assertEquals("PrivateKey=***", r("PrivateKey = kJ8sd+/x="))
        assertEquals("password=***", r("password: hunter2"))
        assertEquals("user <uuid> ok", r("user 123e4567-e89b-12d3-a456-426614174000 ok"))
        assertEquals("mail <email>", r("mail test.user@example.com"))
        assertEquals("code <code>", r("code 1234567812345678"))
    }

    @Test fun normalTextKept() {
        val line = "подключение: wireguard 3f2a (новый ключ), bypass strategy: IOException"
        assertEquals(line, r(line))
        assertTrue(r("open connection to 149.154.167.50:443").contains("149.154.167.50:443"))
    }
}
