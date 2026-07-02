package finance.idem.application.settlement

import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Opaque keyset pagination cursor anchoring a page of [finance.idem.core.ledger.Settlement]
 * results to the last row of the previous page.
 *
 * Encoded as base64url("$createdAt:$id"). [Instant.toString] round-trips through
 * [Instant.parse] with full precision, and a [UUID] never contains a colon, so the last
 * colon in the decoded string is always the separator.
 */
data class SettlementCursor(
    val createdAt: Instant,
    val id: UUID,
) {
    fun encode(): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString("$createdAt:$id".toByteArray(Charsets.UTF_8))

    companion object {
        fun decode(token: String): Result<SettlementCursor> =
            runCatching {
                val decoded = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
                val separatorIndex = decoded.lastIndexOf(':')
                require(separatorIndex > 0) { "Malformed cursor" }
                val createdAt = Instant.parse(decoded.substring(0, separatorIndex))
                val id = UUID.fromString(decoded.substring(separatorIndex + 1))
                SettlementCursor(createdAt, id)
            }
    }
}
