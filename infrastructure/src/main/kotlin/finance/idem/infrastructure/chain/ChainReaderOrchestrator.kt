package finance.idem.infrastructure.chain

import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.chain.ChainCheckpointRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Central wiring point for all chain event sources.
 *
 * - EVM and Solana readers are recovery-only: polled exactly once on startup to replay
 *   any transfers missed while the app was down. Their primary path is the
 *   Alchemy/QuickNode webhook receivers.
 * - Tron has no webhook API — [TronChainReader] is polled on a fixed schedule and is
 *   its only event source.
 *
 * Never propagates exceptions: a failure for one reader is logged and does not affect
 * any other reader.
 */
@Component
class ChainReaderOrchestrator(
    private val chainReaders: List<ChainReader>,
    private val chainCheckpointRepository: ChainCheckpointRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationStartedEvent::class)
    fun onApplicationStarted() {
        chainReaders
            .filter { it.chainKey != TRON_CHAIN_KEY }
            .forEach { pollAndPost(it, "chain-recovery") }
    }

    @Scheduled(fixedDelayString = "\${idem.chain.tron.polling-interval-ms:5000}")
    fun pollTron() {
        chainReaders
            .filter { it.chainKey == TRON_CHAIN_KEY }
            .forEach { pollAndPost(it, "tron-poller") }
    }

    private fun pollAndPost(reader: ChainReader, createdBy: String) {
        try {
            val checkpoint = chainCheckpointRepository.findByChainKey(reader.chainKey)?.lastBlock ?: 0L
            val transfers = reader.poll(checkpoint)

            transfers.forEach { transfer ->
                postTransactionUseCase.execute(transfer.toCommand(createdBy)).onFailure { error ->
                    log.error(
                        "${reader.chainKey}: failed to post transfer idempotencyKey=${transfer.idempotencyKey}: ${error.message}"
                    )
                }
            }

            val newCheckpoint = transfers.maxOfOrNull { it.entry.blockNumber } ?: checkpoint
            if (newCheckpoint > checkpoint) {
                chainCheckpointRepository.save(reader.chainKey, newCheckpoint)
            }
        } catch (e: Exception) {
            log.error("${reader.chainKey}: poll failed", e)
        }
    }

    companion object {
        private const val TRON_CHAIN_KEY = "TRON"
    }
}
