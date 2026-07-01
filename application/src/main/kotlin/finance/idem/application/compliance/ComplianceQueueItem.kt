package finance.idem.application.compliance

import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import java.time.Instant
import java.util.UUID

data class ComplianceQueueItem(
    val id: UUID,
    val tenantId: TenantId,
    val txHash: String,
    val chainId: ChainId,
    val entryAmount: MonetaryAmount,
    val reason: ComplianceReason,
    val missingFields: List<String>,
    val enqueuedAt: Instant,
) {
    companion object {
        fun from(
            result: TravelRuleValidationResult.MissingData,
            tenantId: TenantId,
        ): ComplianceQueueItem =
            ComplianceQueueItem(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                txHash = result.entry.txHash,
                chainId = result.entry.chainId,
                entryAmount = result.entry.amount,
                reason = ComplianceReason.MISSING_DATA,
                missingFields = emptyList(),
                enqueuedAt = Instant.now(),
            )

        fun from(
            result: TravelRuleValidationResult.IncompleteData,
            tenantId: TenantId,
        ): ComplianceQueueItem =
            ComplianceQueueItem(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                txHash = result.entry.txHash,
                chainId = result.entry.chainId,
                entryAmount = result.entry.amount,
                reason = ComplianceReason.INCOMPLETE_DATA,
                missingFields = result.missingFields,
                enqueuedAt = Instant.now(),
            )
    }
}
