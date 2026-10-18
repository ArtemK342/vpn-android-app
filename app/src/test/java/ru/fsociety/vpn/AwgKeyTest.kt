package ru.fsociety.vpn

import org.amnezia.awg.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ключ AmneziaWG с телефона: сервер (vpn.py WG_PUBLIC_KEY_RE) должен принимать любой наш публичный ключ. */
class AwgKeyTest {
    private val serverRe = Regex("^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$")   // как в vpn.py

    @Test fun publicKeysPassServerCheck() {
        val seen = HashSet<String>()
        repeat(100) {
            val pub = KeyPair().publicKey.toBase64()
            assertTrue(pub, serverRe.matches(pub))
            assertTrue("ключи не повторяются", seen.add(pub))
        }
    }

    @Test fun publicKeyMatchesPrivate() {
        val pair = KeyPair()
        assertEquals(pair.publicKey, KeyPair(pair.privateKey).publicKey)
    }

    @Test fun placeholderReplaced() {
        val pair = KeyPair()
        val raw = "[Interface]\nPrivateKey = $AWG_CLIENT_KEY_PLACEHOLDER\nAddress = 10.8.0.5/32\n"
        val cfg = raw.replace(AWG_CLIENT_KEY_PLACEHOLDER, pair.privateKey.toBase64())
        assertTrue(cfg.contains("PrivateKey = ${pair.privateKey.toBase64()}"))
        assertFalse(cfg.contains(AWG_CLIENT_KEY_PLACEHOLDER))
    }
}
