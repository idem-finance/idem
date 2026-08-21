package finance.idem.infrastructure.chain

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Confirms a single WATCHING or webhook-sourced UNMATCHED settlement as past its chain's
 * finality bound, writing the finality evidence and the deferred outbox entry in one commit —
 * same one-`@Transactional` rule as every other side-effecting write in this codebase (no event
 * bus). A separate bean (not a private method on [SettlementFinalityPoller]) so `@Transactional`
 * actually applies: Spring's proxy only intercepts cross-bean calls, not self-invocation within
 * the same class.
 */
@Service
class SettlementPromotionService(
    private val settlementRepository: SettlementRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
) {
    /** WATCHING → SETTLED: the deferred `transaction.settled` webhook now fires. */
    @Transactional
    internal fun promote(
        settlement: Settlement,
        bound: EvmScanBound,
    ): Settlement {
        val promoted =
            settlementRepository.save(
                settlement.copy(
                    status = EntryStatus.SETTLED,
                    confirmedAt = Instant.now(),
                    observedBlockHeight = bound.blockNumber,
                    confirmationSource = bound.source,
                    confirmationsRequired = bound.confirmationsUsed,
                ),
            )
        webhookOutboxRepository.save(WebhookOutboxEntry.transactionSettled(promoted))
        return promoted
    }

    /** UNMATCHED stays UNMATCHED — there was never a PENDING match to settle against — but its
     * finality evidence is stamped and the deferred `reconciliation.unmatched` webhook now
     * fires, since verified-present here means the mismatch is real, not an artifact of a
     * transfer that might still reorg out. */
    @Transactional
    internal fun confirmUnmatched(
        settlement: Settlement,
        bound: EvmScanBound,
    ): Settlement {
        val confirmed =
            settlementRepository.save(
                settlement.copy(
                    confirmedAt = Instant.now(),
                    observedBlockHeight = bound.blockNumber,
                    confirmationSource = bound.source,
                    confirmationsRequired = bound.confirmationsUsed,
                ),
            )
        webhookOutboxRepository.save(WebhookOutboxEntry.reconciliationUnmatched(confirmed))
        return confirmed
    }
}
