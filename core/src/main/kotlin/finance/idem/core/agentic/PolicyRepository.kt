package finance.idem.core.agentic

import finance.idem.core.TenantId

/**
 * Port for persisting and retrieving per-tenant [PolicyRule] sets.
 * Implemented in the infrastructure layer; no Spring or JPA imports here.
 *
 * [findEffective] is the hot path — called before every agent-originated transaction.
 * It returns all enabled rules scoped to the tenant, combining tenant-wide rules
 * (agent_key_prefix IS NULL) with rules targeted at a specific agent key prefix.
 */
interface PolicyRepository {
    fun findEffective(
        tenantId: TenantId,
        agentKeyPrefix: String?,
    ): List<PolicyRule>

    fun findAll(tenantId: TenantId): List<PolicyRuleRecord>

    fun save(
        tenantId: TenantId,
        agentKeyPrefix: String?,
        rule: PolicyRule,
    ): PolicyRuleRecord

    fun delete(
        tenantId: TenantId,
        ruleId: PolicyRuleId,
    ): Boolean
}

data class PolicyRuleRecord(
    val id: PolicyRuleId,
    val agentKeyPrefix: String?,
    val rule: PolicyRule,
    val createdAt: java.time.Instant,
)
