package finance.idem.infrastructure.chain

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.chain.FailedChainTransfer
import finance.idem.core.chain.FailedChainTransferRepository
import finance.idem.core.monetary.OnChainEntry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DeadLetterRecorderTest {

    private val tenantId = "00000000-0000-0000-0000-000000000001"
    private val debitAccountId = "00000000-0000-0000-0000-000000000002"
    private val creditAccountId = "00000000-0000-0000-0000-000000000003"

    private val transfer = DetectedTransfer(
        idempotencyKey = "EVM_1:0xhash",
        entry = OnChainEntry(
            amount = MonetaryAmount.of("100.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xhash",
            blockNumber = 100L,
            walletAddress = "0xabc",
            tokenContract = "0xdef",
        ),
        watchedAddress = WatchedAddress(
            chainKey = "EVM_1",
            walletAddress = "0xabc",
            tokenContract = "0xdef",
            token = StablecoinToken.USDC,
            tenantId = tenantId,
            debitAccountId = debitAccountId,
            creditAccountId = creditAccountId,
        ),
    )

    @Test
    fun `record increments the dead-letter counter and saves a FailedChainTransfer`() {
        val failedChainTransferRepository: FailedChainTransferRepository = mock()
        val meterRegistry = SimpleMeterRegistry()
        val recorder = DeadLetterRecorder(failedChainTransferRepository, meterRegistry)
        val error = RuntimeException("conflict")

        recorder.record(transfer, "EVM_1", "chain-recovery", error, logPrefix = "EVM_1")

        val counter = meterRegistry.get(ChainMetrics.DEAD_LETTER_COUNTER)
            .tag(ChainMetrics.TAG_CHAIN_KEY, "EVM_1")
            .tag(ChainMetrics.TAG_SOURCE, "chain-recovery")
            .counter()
        assertEquals(1.0, counter.count())

        val captor = argumentCaptor<FailedChainTransfer>()
        verify(failedChainTransferRepository).save(captor.capture())
        assertEquals(transfer.idempotencyKey, captor.firstValue.idempotencyKey)
        assertEquals("EVM_1", captor.firstValue.chainKey)
        assertEquals("chain-recovery", captor.firstValue.source)
        assertEquals(error.message, captor.firstValue.errorMessage)
    }

    @Test
    fun `record does not throw when the repository save fails`() {
        val failedChainTransferRepository: FailedChainTransferRepository = mock()
        val meterRegistry = SimpleMeterRegistry()
        val recorder = DeadLetterRecorder(failedChainTransferRepository, meterRegistry)
        whenever(failedChainTransferRepository.save(org.mockito.kotlin.any())).thenThrow(RuntimeException("db down"))

        recorder.record(transfer, "EVM_1", "chain-recovery", RuntimeException("conflict"), logPrefix = "EVM_1")

        val counter = meterRegistry.get(ChainMetrics.DEAD_LETTER_COUNTER)
            .tag(ChainMetrics.TAG_CHAIN_KEY, "EVM_1")
            .tag(ChainMetrics.TAG_SOURCE, "chain-recovery")
            .counter()
        assertEquals(1.0, counter.count())
    }
}
