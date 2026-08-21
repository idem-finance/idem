package finance.idem.infrastructure.chain

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@ExtendWith(MockitoExtension::class)
class SettlementPromotionServiceTest {
    @Mock lateinit var settlementRepository: SettlementRepository

    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository

    private lateinit var service: SettlementPromotionService

    private val tenantId = TenantId.generate()

    @BeforeEach
    fun setUp() {
        service = SettlementPromotionService(settlementRepository, webhookOutboxRepository)
    }

    private fun watchingSettlement() =
        Settlement(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            accountId = AccountId.generate(),
            amount = MonetaryAmount.of("100.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            walletAddress = "0xwallet",
            status = EntryStatus.WATCHING,
            matchedTransactionId = TransactionId.generate(),
            txHash = "0xabc",
            blockNumber = 100L,
            chainKey = "EVM_1",
            logIndex = 2,
            createdAt = Instant.now(),
            createdBy = "system",
        )

    @Test
    fun `promote sets SETTLED with finality evidence and writes transaction-settled outbox entry`() {
        val settlement = watchingSettlement()
        val bound = EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, confirmationsUsed = null)
        whenever(settlementRepository.save(any())).thenAnswer { it.getArgument(0) }

        val result = service.promote(settlement, bound)

        assertEquals(EntryStatus.SETTLED, result.status)
        assertEquals(150L, result.observedBlockHeight)
        assertEquals(ConfirmationSource.FINALIZED_TAG, result.confirmationSource)
        assertEquals(null, result.confirmationsRequired)
        assertNotNull(result.confirmedAt)

        val savedCaptor = argumentCaptor<Settlement>()
        verify(settlementRepository).save(savedCaptor.capture())
        assertEquals(EntryStatus.SETTLED, savedCaptor.firstValue.status)

        val outboxCaptor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(outboxCaptor.capture())
        assertEquals("transaction.settled", outboxCaptor.firstValue.eventType)
    }

    @Test
    fun `promote records confirmationsUsed when the bound came from the block-depth heuristic`() {
        val settlement = watchingSettlement()
        val bound = EvmScanBound(150L, ConfirmationSource.BLOCK_DEPTH_HEURISTIC, confirmationsUsed = 12L)
        whenever(settlementRepository.save(any())).thenAnswer { it.getArgument(0) }

        val result = service.promote(settlement, bound)

        assertEquals(ConfirmationSource.BLOCK_DEPTH_HEURISTIC, result.confirmationSource)
        assertEquals(12L, result.confirmationsRequired)
    }

    @Test
    fun `confirmUnmatched stamps finality evidence, keeps UNMATCHED, and writes deferred reconciliation-unmatched outbox entry`() {
        val settlement = watchingSettlement().copy(status = EntryStatus.UNMATCHED)
        val bound = EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, confirmationsUsed = null)
        whenever(settlementRepository.save(any())).thenAnswer { it.getArgument(0) }

        val result = service.confirmUnmatched(settlement, bound)

        assertEquals(EntryStatus.UNMATCHED, result.status)
        assertEquals(150L, result.observedBlockHeight)
        assertEquals(ConfirmationSource.FINALIZED_TAG, result.confirmationSource)
        assertNotNull(result.confirmedAt)

        val outboxCaptor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(outboxCaptor.capture())
        assertEquals("reconciliation.unmatched", outboxCaptor.firstValue.eventType)
    }
}
