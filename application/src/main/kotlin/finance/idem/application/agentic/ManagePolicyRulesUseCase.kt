package finance.idem.application.agentic

import finance.idem.core.TenantId
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyRuleId
import finance.idem.core.agentic.PolicyRuleRecord

interface ManagePolicyRulesUseCase {
    fun create(
        tenantId: TenantId,
        agentKeyPrefix: String?,
        rule: PolicyRule,
    ): PolicyRuleRecord

    fun findAll(tenantId: TenantId): List<PolicyRuleRecord>

    fun delete(
        tenantId: TenantId,
        ruleId: PolicyRuleId,
    ): Boolean
}
