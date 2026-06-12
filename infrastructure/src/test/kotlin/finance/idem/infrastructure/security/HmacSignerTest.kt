package finance.idem.infrastructure.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
}
