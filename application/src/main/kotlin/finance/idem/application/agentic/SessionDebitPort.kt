package finance.idem.application.agentic

import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId

/**
 * Port for querying historical debit totals, used by [ExecuteWorkflowService] to populate
 * [finance.idem.core.agentic.LedgerIntent] before PolicyGuard evaluation.
 *
 * Both methods return raw [MonetaryAmount] sums with no currency normalization —
 * the same constraint as PolicyGuard itself. All amounts for a given tenant must be
 * in the same unit for [PolicyRule.MaxDebitPerSession] and [PolicyRule.MaxDebitPerHour]
 * to produce meaningful results.
 */
interface SessionDebitPort {
    /** Sum of all DEBIT amounts on journal lines belonging to the given session. */
    fun sumDebitsForSession(tenantId: TenantId, sessionId: String): MonetaryAmount

    /** Sum of all DEBIT amounts on journal lines in the last hour for this tenant and agent key.
     *  Pass null to aggregate across all agents (no agent filter applied). */
    fun sumDebitsLastHour(tenantId: TenantId, agentKeyPrefix: String?): MonetaryAmount
}
