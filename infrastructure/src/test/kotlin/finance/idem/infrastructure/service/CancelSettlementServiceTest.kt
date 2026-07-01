package finance.idem.infrastructure.service

import finance.idem.application.settlement.CancelSettlementCommand
import finance.idem.application.settlement.SettlementAlreadyTerminal
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CancelSettlementServiceTest {
    private val settlementRepository: SettlementRepository = mock()
    private val service = CancelSettlementService(settlementRepository)

    private val tenantId = TenantId.generate()
    private val id = UUID.randomUUID()

    private fun pending() =
        Settlement(
            id = id,
            tenantId = tenantId,
            accountId = AccountId.generate(),
            amount = MonetaryAmount.of("100.00"),
            token = StablecoinToken.USDC,
            chainId = ChainId.SOLANA,
            walletAddress = "wallet",
            status = EntryStatus.PENDING,
            createdAt = Instant.now(),
            createdBy = "test",
        )

    @Test
    fun `returns SettlementNotFound when settlement does not exist`() {
        whenever(settlementRepository.findById(id, tenantId)).thenReturn(null)

        val result = service.execute(CancelSettlementCommand(id, tenantId))

        assertTrue(result.isFailure)
        assertIs<SettlementNotFound>(result.exceptionOrNull())
        verify(settlementRepository, never()).save(any())
    }

    @Test
    fun `cancels a PENDING settlement`() {
        whenever(settlementRepository.findById(id, tenantId)).thenReturn(pending())
        val captor = argumentCaptor<Settlement>()
        whenever(settlementRepository.save(captor.capture())).thenAnswer { captor.firstValue }

        val result = service.execute(CancelSettlementCommand(id, tenantId))

        assertTrue(result.isSuccess)
        assertEquals(EntryStatus.CANCELLED, captor.firstValue.status)
        assertEquals(id, captor.firstValue.id)
    }

    @Test
    fun `returns SettlementAlreadyTerminal for each terminal status`() {
        listOf(EntryStatus.SETTLED, EntryStatus.UNMATCHED, EntryStatus.CANCELLED).forEach { status ->
            whenever(settlementRepository.findById(id, tenantId)).thenReturn(pending().copy(status = status))

            val result = service.execute(CancelSettlementCommand(id, tenantId))

            assertTrue(result.isFailure, "Expected failure for $status")
            val error = result.exceptionOrNull()
            assertIs<SettlementAlreadyTerminal>(error)
            assertEquals(status, error.status)
        }
    }
}
