package finance.idem.infrastructure.chain

import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalResult
import finance.idem.application.reconciliation.ReorgReversalUseCase
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
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class SettlementFinalityPollerTest {
    @Mock lateinit var reader: EvmChainReader

    @Mock lateinit var watchedAddressRepository: WatchedAddressRepository

    @Mock lateinit var settlementRepository: SettlementRepository

    @Mock lateinit var settlementPromotionService: SettlementPromotionService

    @Mock lateinit var reorgReversalUseCase: ReorgReversalUseCase

    private lateinit var poller: SettlementFinalityPoller

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.now()
    private val txHash = "0xabc123"

    @BeforeEach
    fun setUp() {
        poller =
            SettlementFinalityPoller(
                listOf(reader),
                watchedAddressRepository,
                settlementRepository,
                settlementPromotionService,
                reorgReversalUseCase,
            )
        Mockito.lenient().`when`(reader.chainKey).thenReturn("EVM_1")
        Mockito
            .lenient()
            .`when`(watchedAddressRepository.findByChainKey("EVM_1"))
            .thenReturn(
                listOf(
                    WatchedAddress(
                        "EVM_1",
                        "0xwallet",
                        "0xcontract",
                        StablecoinToken.USDC,
                        tenantId.value.toString(),
                        "debit-1",
                        "credit-1",
                    ),
                ),
            )
    }

    private fun watchingSettlement(logIndex: Int? = 2) =
        Settlement(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            accountId = accountId,
            amount = MonetaryAmount.of("100.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            walletAddress = "0xwallet",
            status = EntryStatus.WATCHING,
            matchedTransactionId = TransactionId.generate(),
            txHash = txHash,
            blockNumber = 100L,
            chainKey = "EVM_1",
            logIndex = logIndex,
            createdAt = now,
            createdBy = "system",
        )

    @Test
    fun `promotes a settlement whose log is still present on-chain`() {
        val settlement = watchingSettlement()
        whenever(reader.resolveScanBound()).thenReturn(EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(settlementRepository.findPendingFinalitySweep(tenantId, "EVM_1", 150L)).thenReturn(listOf(settlement))
        whenever(reader.verifyLogStillPresent(txHash, 2, 100L)).thenReturn(LogVerification.Present)

        poller.poll()

        val boundCaptor = argumentCaptor<EvmScanBound>()
        verify(settlementPromotionService).promote(eq(settlement), boundCaptor.capture())
        assertEquals(150L, boundCaptor.firstValue.blockNumber)
        verify(reorgReversalUseCase, never()).execute(any())
    }

    @Test
    fun `confirms an UNMATCHED settlement without promoting it to SETTLED`() {
        val settlement = watchingSettlement().copy(status = EntryStatus.UNMATCHED)
        whenever(reader.resolveScanBound()).thenReturn(EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(settlementRepository.findPendingFinalitySweep(tenantId, "EVM_1", 150L)).thenReturn(listOf(settlement))
        whenever(reader.verifyLogStillPresent(txHash, 2, 100L)).thenReturn(LogVerification.Present)

        poller.poll()

        verify(settlementPromotionService, never()).promote(any(), any())
        verify(settlementPromotionService).confirmUnmatched(eq(settlement), any())
        verify(reorgReversalUseCase, never()).execute(any())
    }

    @Test
    fun `an RPC verification failure leaves the settlement pending instead of reversing it`() {
        val settlement = watchingSettlement()
        whenever(reader.resolveScanBound()).thenReturn(EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(settlementRepository.findPendingFinalitySweep(tenantId, "EVM_1", 150L)).thenReturn(listOf(settlement))
        whenever(reader.verifyLogStillPresent(txHash, 2, 100L))
            .thenReturn(LogVerification.VerificationFailed(RuntimeException("Alchemy timeout")))

        poller.poll()

        verify(settlementPromotionService, never()).promote(any(), any())
        verify(settlementPromotionService, never()).confirmUnmatched(any(), any())
        verify(reorgReversalUseCase, never()).execute(any())
    }

    @Test
    fun `routes to ReorgReversalUseCase when the log is no longer present (missed webhook backstop)`() {
        val settlement = watchingSettlement()
        whenever(reader.resolveScanBound()).thenReturn(EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(settlementRepository.findPendingFinalitySweep(tenantId, "EVM_1", 150L)).thenReturn(listOf(settlement))
        whenever(reader.verifyLogStillPresent(txHash, 2, 100L)).thenReturn(LogVerification.Absent)
        whenever(reorgReversalUseCase.execute(any())).thenReturn(Result.success(ReorgReversalResult.NoMatchingSettlement))

        poller.poll()

        verify(settlementPromotionService, never()).promote(any(), any())
        val cmdCaptor = argumentCaptor<ReorgReversalCommand>()
        verify(reorgReversalUseCase).execute(cmdCaptor.capture())
        assertEquals(txHash, cmdCaptor.firstValue.txHash)
        assertEquals(2, cmdCaptor.firstValue.logIndex)
        assertEquals("EVM_1", cmdCaptor.firstValue.chainKey)
    }

    @Test
    fun `skips a WATCHING row with a null logIndex without throwing`() {
        val settlement = watchingSettlement(logIndex = null)
        whenever(reader.resolveScanBound()).thenReturn(EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(settlementRepository.findPendingFinalitySweep(tenantId, "EVM_1", 150L)).thenReturn(listOf(settlement))

        poller.poll()

        verify(settlementPromotionService, never()).promote(any(), any())
        verify(reorgReversalUseCase, never()).execute(any())
        verify(reader, never()).verifyLogStillPresent(any(), any(), any())
    }

    @Test
    fun `a failure evaluating one settlement does not prevent evaluating the next`() {
        val failing = watchingSettlement()
        val healthy = watchingSettlement().copy(id = UUID.randomUUID(), txHash = "0xdef456")
        whenever(reader.resolveScanBound()).thenReturn(EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(settlementRepository.findPendingFinalitySweep(tenantId, "EVM_1", 150L)).thenReturn(listOf(failing, healthy))
        whenever(reader.verifyLogStillPresent(txHash, 2, 100L)).thenThrow(RuntimeException("RPC blew up"))
        whenever(reader.verifyLogStillPresent("0xdef456", 2, 100L)).thenReturn(LogVerification.Present)

        poller.poll()

        verify(settlementPromotionService).promote(eq(healthy), any())
    }

    @Test
    fun `a failure sweeping one chain does not prevent sweeping another`() {
        val otherReader = mock<EvmChainReader>()
        whenever(otherReader.chainKey).thenReturn("EVM_8453")
        whenever(reader.resolveScanBound()).thenThrow(RuntimeException("RPC down"))
        whenever(otherReader.resolveScanBound()).thenReturn(EvmScanBound(50L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(watchedAddressRepository.findByChainKey("EVM_8453")).thenReturn(emptyList())

        val multiChainPoller =
            SettlementFinalityPoller(
                listOf(reader, otherReader),
                watchedAddressRepository,
                settlementRepository,
                settlementPromotionService,
                reorgReversalUseCase,
            )

        multiChainPoller.poll()

        verify(otherReader).resolveScanBound()
    }

    @Test
    fun `promotion sweep is scoped per tenant derived from watched addresses`() {
        val settlement = watchingSettlement()
        whenever(reader.resolveScanBound()).thenReturn(EvmScanBound(150L, ConfirmationSource.FINALIZED_TAG, null))
        whenever(settlementRepository.findPendingFinalitySweep(tenantId, "EVM_1", 150L)).thenReturn(listOf(settlement))
        whenever(reader.verifyLogStillPresent(any(), any(), any())).thenReturn(LogVerification.Present)

        poller.poll()

        verify(settlementRepository).findPendingFinalitySweep(tenantId, "EVM_1", 150L)
    }

    @Test
    fun `non-EVM readers are ignored`() {
        val tronReader = mock<TronChainReader>()
        val onlyTronPoller =
            SettlementFinalityPoller(
                listOf(tronReader),
                watchedAddressRepository,
                settlementRepository,
                settlementPromotionService,
                reorgReversalUseCase,
            )

        onlyTronPoller.poll()

        verify(settlementRepository, never()).findPendingFinalitySweep(any(), any(), any())
    }
}
