package finance.idem.application.ledger

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryCursorTest {
    @Test
    fun `encode then decode round-trips createdAt and id exactly`() {
        val cursor = EntryCursor(Instant.parse("2026-01-15T10:30:45.123456789Z"), UUID.randomUUID())

        val decoded = EntryCursor.decode(cursor.encode())

        assertTrue(decoded.isSuccess)
        assertEquals(cursor, decoded.getOrThrow())
    }

    @Test
    fun `encode produces a url-safe token without padding`() {
        val cursor = EntryCursor(Instant.now(), UUID.randomUUID())

        val token = cursor.encode()

        assertTrue(token.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `decode of invalid base64 returns failure`() {
        val decoded = EntryCursor.decode("not-valid-base64!!!")

        assertTrue(decoded.isFailure)
    }

    @Test
    fun `decode of well-formed base64 with wrong shape returns failure`() {
        val token =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("no-separator-here".toByteArray(Charsets.UTF_8))

        val decoded = EntryCursor.decode(token)

        assertTrue(decoded.isFailure)
    }
}
