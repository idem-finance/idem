package finance.idem.application.settlement

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettlementCursorTest {

    @Test
    fun `encode and decode round-trip`() {
        val cursor = SettlementCursor(Instant.parse("2025-06-15T12:00:00.123456789Z"), UUID.randomUUID())
        val encoded = cursor.encode()
        val decoded = SettlementCursor.decode(encoded).getOrThrow()
        assertEquals(cursor, decoded)
    }

    @Test
    fun `encoded string is base64url (no padding, no plus or slash)`() {
        val cursor = SettlementCursor(Instant.now(), UUID.randomUUID())
        val encoded = cursor.encode()
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `decode returns failure for garbage input`() {
        val result = SettlementCursor.decode("not-a-real-cursor!!")
        assertTrue(result.isFailure)
    }

    @Test
    fun `decode returns failure for missing separator`() {
        val noCursor = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("nodivider".toByteArray())
        assertTrue(SettlementCursor.decode(noCursor).isFailure)
    }
}
