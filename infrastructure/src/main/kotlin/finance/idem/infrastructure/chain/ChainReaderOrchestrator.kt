package finance.idem.infrastructure.chain

import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.chain.ChainCheckpointRepository
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor

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
 *
 * The startup recovery sweep is dispatched to [chainRecoveryExecutor] and runs on a
 * background virtual thread — [onApplicationStarted] returns immediately so
 * `ApplicationReadyEvent` / readiness probes are never delayed by RPC-heavy `poll()` calls.
 *
 * In multi-replica deployments ([lockingTaskExecutor] present), only one replica performs
 * the recovery sweep and only one replica runs [pollTron] at a time: [pollTron] is guarded
 * by `@SchedulerLock` (no-op in standalone), and the recovery sweep is wrapped in
 * [lockingTaskExecutor]`.executeWithLock` *inside* the [chainRecoveryExecutor] task — the
 * lock must be acquired on the background thread that does the actual work, not on the
 * startup thread that merely dispatches it (#89).
 *
 * In standalone mode ([lockingTaskExecutor] is null), the sweep runs directly without any
 * distributed lock — correct, since there is only one replica.
 */
@Component
class ChainReaderOrchestrator(
    private val chainReaders: List<ChainReader>,
    private val chainCheckpointRepository: ChainCheckpointRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
    private val deadLetterRecorder: DeadLetterRecorder,
    @Qualifier(ChainRecoveryExecutorConfig.BEAN_NAME) private val chainRecoveryExecutor: Executor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Null in standalone mode; injected by [SchedulerLockConfig] in multi-replica deployments. */
    @Autowired(required = false)
    internal var lockingTaskExecutor: LockingTaskExecutor? = null

    @EventListener(ApplicationStartedEvent::class)
    fun onApplicationStarted() {
        val sweep = Runnable {
            chainReaders
                .filter { it.chainKey != TRON_CHAIN_KEY }
                .forEach { pollAndPost(it, "chain-recovery") }
        }
        chainRecoveryExecutor.execute {
            val executor = lockingTaskExecutor
            if (executor != null) {
                executor.executeWithLock(
                    sweep,
                    LockConfiguration(Instant.now(), RECOVERY_SWEEP_LOCK_NAME, Duration.ofMinutes(10), Duration.ofSeconds(30)),
                )
            } else {
                sweep.run()
            }
        }
    }

    @Scheduled(fixedDelayString = "\${idem.chain.tron.polling-interval-ms:5000}")
    @SchedulerLock(name = "pollTron", lockAtMostFor = "1m", lockAtLeastFor = "4s")
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
                    deadLetterRecorder.record(transfer, reader.chainKey, createdBy, error, logPrefix = reader.chainKey)
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
        const val RECOVERY_SWEEP_LOCK_NAME = "chainRecoverySweep"
    }
}
