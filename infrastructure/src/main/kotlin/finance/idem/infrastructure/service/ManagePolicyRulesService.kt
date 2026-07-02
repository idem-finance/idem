package finance.idem.infrastructure.service

import finance.idem.application.agentic.ManagePolicyRulesUseCase
import finance.idem.core.TenantId
import finance.idem.core.agentic.PolicyRepository
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyRuleId
import finance.idem.core.agentic.PolicyRuleRecord
import org.springframework.stereotype.Service

@Service
class ManagePolicyRulesService(
    private val policyRepository: PolicyRepository,
) : ManagePolicyRulesUseCase {
    override fun create(
        tenantId: TenantId,
        agentKeyPrefix: String?,
        rule: PolicyRule,
    ): PolicyRuleRecord = policyRepository.save(tenantId, agentKeyPrefix, rule)

    override fun findAll(tenantId: TenantId): List<PolicyRuleRecord> = policyRepository.findAll(tenantId)

    override fun delete(
        tenantId: TenantId,
        ruleId: PolicyRuleId,
    ): Boolean = policyRepository.delete(tenantId, ruleId)
}
