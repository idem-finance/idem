package finance.idem.infrastructure.chain

import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalUseCase
import finance.idem.core.TenantId
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Promotes `WATCHING` settlements to `SETTLED` once they've cleared their chain's finality
 * bound — and is the *primary* reorg-detection mechanism, not just a promoter: before
 * promoting, it actively re-verifies the log is still present on-chain
 * ([EvmChainReader.verifyLogStillPresent]). If Alchemy's `removed:true` webhook was missed
 * (network blip, provider outage), this sweep still catches the reorg on its next tick and
 * routes to [ReorgReversalUseCase] — the webhook's fast path is a latency optimization, not
 * the only source of truth.
 *
 * `settlements` keeps `FORCE ROW LEVEL SECURITY` (unlike `webhook_outbox`), so this never reads
 * cross-tenant. The tenant set for a chain is derived from [WatchedAddressRepository]
 * (unscoped, no RLS — used the same way by the webhook receivers), then each tenant's
 * `WATCHING` rows are queried with RLS correctly scoped.
 *
 * Never propagates exceptions — a failure for one chain or one settlement is logged and does
 * not affect any other, matching [ChainReaderOrchestrator]'s convention.
 */
@Component
class SettlementFinalityPoller(
    private val chainReaders: List<ChainReader>,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val settlementRepository: SettlementRepository,
    private val settlementPromotionService: SettlementPromotionService,
    private val reorgReversalUseCase: ReorgReversalUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${idem.chain.finality-check-interval-ms:15000}")
    @SchedulerLock(name = "settlementFinalityPoll", lockAtMostFor = "1m", lockAtLeastFor = "4s")
    fun poll() {
        chainReaders.filterIsInstance<EvmChainReader>().forEach { reader ->
            runCatching { sweepChain(reader) }
                .onFailure { log.error("${reader.chainKey}: settlement finality sweep failed", it) }
        }
    }

    private fun sweepChain(reader: EvmChainReader) {
        val bound = reader.resolveScanBound()
        val tenantIds = watchedAddressRepository.findByChainKey(reader.chainKey).map { TenantId.of(it.tenantId) }.distinct()

        tenantIds.forEach { tenantId ->
            val watching = settlementRepository.findWatchingByChainKey(tenantId, reader.chainKey, bound.blockNumber)
            watching.forEach { settlement ->
                runCatching { evaluate(reader, settlement, bound) }
                    .onFailure { log.error("${reader.chainKey}: failed to evaluate settlement=${settlement.id}", it) }
            }
        }
    }

    private fun evaluate(
        reader: EvmChainReader,
        settlement: Settlement,
        bound: EvmScanBound,
    ) {
        val txHash = settlement.txHash
        val logIndex = settlement.logIndex
        val blockNumber = settlement.blockNumber
        if (txHash == null || logIndex == null || blockNumber == null) {
            log.warn("${reader.chainKey}: WATCHING settlement=${settlement.id} missing txHash/logIndex/blockNumber — skipping")
            return
        }

        if (reader.verifyLogStillPresent(txHash, logIndex, blockNumber)) {
            settlementPromotionService.promote(settlement, bound)
        } else {
            reorgReversalUseCase
                .execute(
                    ReorgReversalCommand(
                        settlement.tenantId,
                        txHash,
                        logIndex,
                        reader.chainKey,
                        "finality poller: log no longer present at expected block $blockNumber",
                    ),
                ).onFailure { error ->
                    log.error("${reader.chainKey}: reorg reversal failed for settlement=${settlement.id}", error)
                }
        }
    }
}
