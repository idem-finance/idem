package finance.idem.infrastructure.service

import finance.idem.application.settlement.GetSettlementQuery
import finance.idem.application.settlement.SettlementNotFound
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GetSettlementServiceTest {
    private val settlementRepository: SettlementRepository = mock()
    private val service = GetSettlementService(settlementRepository)

    private val tenantId = TenantId.generate()

    private fun settlement(id: UUID = UUID.randomUUID()) =
        Settlement(
            id = id,
            tenantId = tenantId,
            accountId = AccountId.generate(),
            amount = MonetaryAmount.of("100.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.SOLANA,
            walletAddress = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS",
            status = EntryStatus.PENDING,
            createdAt = Instant.now(),
            createdBy = "api-user",
        )

    @Test
    fun `returns SettlementNotFound when repository returns null`() {
        val id = UUID.randomUUID()
        whenever(settlementRepository.findById(id, tenantId)).thenReturn(null)

        val result = service.execute(GetSettlementQuery(id = id, tenantId = tenantId))

        assertTrue(result.isFailure)
        val ex = assertIs<SettlementNotFound>(result.exceptionOrNull())
        assertEquals(id, ex.id)
    }

    @Test
    fun `returns settlement when found`() {
        val id = UUID.randomUUID()
        val expected = settlement(id)
        whenever(settlementRepository.findById(id, tenantId)).thenReturn(expected)

        val result = service.execute(GetSettlementQuery(id = id, tenantId = tenantId))

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrThrow())
    }
}
