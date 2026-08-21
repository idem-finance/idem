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
 * Promotes a single `WATCHING` settlement to `SETTLED`, writing the finality evidence and the
 * `transaction.settled` outbox entry in one commit — same one-`@Transactional` rule as every
 * other side-effecting write in this codebase (no event bus). A separate bean (not a private
 * method on [SettlementFinalityPoller]) so `@Transactional` actually applies: Spring's proxy
 * only intercepts cross-bean calls, not self-invocation within the same class.
 */
@Service
class SettlementPromotionService(
    private val settlementRepository: SettlementRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
) {
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
                    finalityPolicyVersion = FinalityPolicy.VERSION,
                ),
            )
        webhookOutboxRepository.save(WebhookOutboxEntry.transactionSettled(promoted))
        return promoted
    }
}
