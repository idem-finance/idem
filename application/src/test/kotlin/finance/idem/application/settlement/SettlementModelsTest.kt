package finance.idem.application.settlement

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettlementModelsTest {

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.now()
    private val id = UUID.randomUUID()

    @Test
    fun `GetSettlementQuery holds id and tenantId`() {
        val query = GetSettlementQuery(id, tenantId)
        assertEquals(id, query.id)
        assertEquals(tenantId, query.tenantId)
        assertEquals(query, query.copy())
    }

    @Test
    fun `ListSettlementsQuery holds all fields and defaults cursor to null`() {
        val query = ListSettlementsQuery(tenantId, EntryStatus.PENDING, now, now, 20, null)
        assertEquals(tenantId, query.tenantId)
        assertEquals(EntryStatus.PENDING, query.status)
        assertEquals(now, query.from)
        assertEquals(now, query.to)
        assertEquals(20, query.limit)
        assertNull(query.cursor)
        assertEquals(query, query.copy())
    }

    @Test
    fun `ListSettlementsQuery with cursor`() {
        val cursor = "some-opaque-cursor"
        val query = ListSettlementsQuery(tenantId, null, null, null, 50, cursor)
        assertNull(query.status)
        assertNull(query.from)
        assertNull(query.to)
        assertEquals(cursor, query.cursor)
    }

    @Test
    fun `SettlementPage holds settlements and nextCursor`() {
        val settlement = Settlement(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            accountId = accountId,
            amount = MonetaryAmount.of("100.00"),
            token = StablecoinToken.USDC,
            chainId = ChainId.SOLANA,
            walletAddress = "wallet",
            status = EntryStatus.PENDING,
            createdAt = now,
            createdBy = "test",
        )
        val page = SettlementPage(listOf(settlement), "cursor-token")
        assertEquals(1, page.settlements.size)
        assertEquals(settlement, page.settlements[0])
        assertEquals("cursor-token", page.nextCursor)
        assertEquals(page, page.copy())
    }

    @Test
    fun `SettlementPage with no nextCursor`() {
        val page = SettlementPage(emptyList(), null)
        assertEquals(emptyList(), page.settlements)
        assertNull(page.nextCursor)
    }

    @Test
    fun `CancelSettlementCommand holds id and tenantId`() {
        val cmd = CancelSettlementCommand(id, tenantId)
        assertEquals(id, cmd.id)
        assertEquals(tenantId, cmd.tenantId)
        assertEquals(cmd, cmd.copy())
    }

    @Test
    fun `SettlementIdempotencyConflict carries key and message`() {
        val error = SettlementIdempotencyConflict("key-xyz")
        assertEquals("key-xyz", error.key)
        assertEquals(
            "Idempotency conflict — a request with key 'key-xyz' is already in progress",
            error.message,
        )
    }
}
