package finance.idem.infrastructure.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HmacSignerTest {
    @Test
    fun `hexHmacSha256 matches a known test vector`() {
        // echo -n "hello" | openssl dgst -sha256 -hmac "secret"
        val signature = HmacSigner.hexHmacSha256("secret", "hello")

        assertEquals("88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b", signature)
    }

    @Test
    fun `different secrets produce different signatures for the same body`() {
        val a = HmacSigner.hexHmacSha256("secret-a", "payload")
        val b = HmacSigner.hexHmacSha256("secret-b", "payload")

        assertNotEquals(a, b)
    }

    @Test
    fun `different bodies produce different signatures for the same secret`() {
        val a = HmacSigner.hexHmacSha256("secret", "payload-a")
        val b = HmacSigner.hexHmacSha256("secret", "payload-b")

        assertNotEquals(a, b)
    }

    @Test
    fun `verify returns true for a matching signature`() {
        val signature = HmacSigner.hexHmacSha256("secret", "payload")

        assertTrue(HmacSigner.verify("secret", "payload", signature))
    }

    @Test
    fun `verify returns false for a mismatched signature`() {
        val signature = HmacSigner.hexHmacSha256("secret", "payload")

        assertFalse(HmacSigner.verify("secret", "payload", signature.dropLast(1) + "0"))
        assertFalse(HmacSigner.verify("wrong-secret", "payload", signature))
        assertFalse(HmacSigner.verify("secret", "different-payload", signature))
    }

    @Test
    fun `verify supports signing a concatenation of multiple fields, matching QuickNode's scheme`() {
        val signature = HmacSigner.hexHmacSha256("secret", "nonce123" + "1700000000" + "{}")

        assertTrue(HmacSigner.verify("secret", "nonce123" + "1700000000" + "{}", signature))
    }
}
