package finance.idem.infrastructure.chain

import finance.idem.core.chain.FailedChainTransferRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Shared "dead-letter" handling for a [DetectedTransfer] whose
 * `PostTransactionUseCase.execute()` call returned `Result.failure`.
 *
 * Called identically from [ChainReaderOrchestrator], [AlchemyWebhookService], and
 * [QuickNodeWebhookService] on the `onFailure` branch: logs the failure, increments the
 * `idem.chain.dead_letter` counter (unconditional — in-memory, cannot fail), and writes a
 * `failed_chain_transfers` row. The repository write is wrapped in `runCatching` so a DB
 * outage here is logged but never blocks checkpoint advancement or the remaining transfers
 * in the caller's loop.
 */
@Component
class DeadLetterRecorder(
    private val failedChainTransferRepository: FailedChainTransferRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(transfer: DetectedTransfer, chainKey: String, source: String, error: Throwable, logPrefix: String) {
        log.error("$logPrefix: failed to post transfer idempotencyKey=${transfer.idempotencyKey}: ${error.message}")

        meterRegistry.counter(
            ChainMetrics.DEAD_LETTER_COUNTER,
            ChainMetrics.TAG_CHAIN_KEY, chainKey,
            ChainMetrics.TAG_SOURCE, source,
        ).increment()

        runCatching {
            failedChainTransferRepository.save(transfer.toFailedChainTransfer(chainKey, source, error))
        }.onFailure { e ->
            log.error("$logPrefix: failed to write dead-letter row for idempotencyKey=${transfer.idempotencyKey}", e)
        }
    }
}
