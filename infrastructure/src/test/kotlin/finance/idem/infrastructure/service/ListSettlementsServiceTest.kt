package finance.idem.infrastructure.service

import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.settlement.ListSettlementsQuery
import finance.idem.application.settlement.SettlementCursor
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListSettlementsServiceTest {

    private val settlementRepository: SettlementRepository = mock()
    private val service = ListSettlementsService(settlementRepository)

    private val tenantId = TenantId.generate()

    private fun settlement(createdAt: Instant = Instant.now()) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = AccountId.generate(),
        amount = MonetaryAmount.of("100.00"),
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = "wallet",
        status = EntryStatus.PENDING,
        createdAt = createdAt,
        createdBy = "test",
    )

    @Test
    fun `returns InvalidCursor for malformed cursor string`() {
        val query = ListSettlementsQuery(tenantId, null, null, null, 10, "bad-cursor!!!")
        whenever(settlementRepository.findPage(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = service.execute(query)

        assertTrue(result.isFailure)
        assertIs<InvalidCursor>(result.exceptionOrNull())
    }

    @Test
    fun `returns empty page when no rows`() {
        whenever(settlementRepository.findPage(any(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(10)))
            .thenReturn(emptyList())

        val result = service.execute(ListSettlementsQuery(tenantId, null, null, null, 10, null))

        assertTrue(result.isSuccess)
        val page = result.getOrThrow()
        assertTrue(page.settlements.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `nextCursor is set when result size equals limit`() {
        val rows = (1..5).map { settlement() }
        whenever(settlementRepository.findPage(any(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(5)))
            .thenReturn(rows)

        val result = service.execute(ListSettlementsQuery(tenantId, null, null, null, 5, null))

        assertTrue(result.isSuccess)
        assertNotNull(result.getOrThrow().nextCursor)
    }

    @Test
    fun `nextCursor is absent when result size is less than limit`() {
        val rows = (1..3).map { settlement() }
        whenever(settlementRepository.findPage(any(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(5)))
            .thenReturn(rows)

        val result = service.execute(ListSettlementsQuery(tenantId, null, null, null, 5, null))

        assertNull(result.getOrThrow().nextCursor)
    }

    @Test
    fun `cursor is decoded and forwarded to repository`() {
        val anchorCreatedAt = Instant.parse("2025-06-15T10:00:00Z")
        val anchorId = UUID.randomUUID()
        val encodedCursor = SettlementCursor(anchorCreatedAt, anchorId).encode()

        whenever(settlementRepository.findPage(
            eq(tenantId), isNull(), isNull(), isNull(),
            eq(anchorCreatedAt), eq(anchorId), eq(10),
        )).thenReturn(emptyList())

        val result = service.execute(ListSettlementsQuery(tenantId, null, null, null, 10, encodedCursor))

        assertTrue(result.isSuccess)
    }
}
