package finance.idem.infrastructure.persistence.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.agentic.PolicyRepository
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyRuleId
import finance.idem.core.agentic.PolicyRuleRecord
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class PolicyRepositoryAdapter(
    private val jpaRepository: PolicyRuleJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : PolicyRepository {
    @Transactional(readOnly = true)
    override fun findEffective(
        tenantId: TenantId,
        agentKeyPrefix: String?,
    ): List<PolicyRule> {
        entityManager.setRlsTenantId(tenantId)
        val agentFilter =
            if (agentKeyPrefix != null) {
                " OR agent_key_prefix = '${agentKeyPrefix.replace("'", "''")}'"
            } else {
                ""
            }
        val sql =
            "SELECT * FROM policy_rules WHERE tenant_id = '${tenantId.value}'" +
                " AND enabled = true AND (agent_key_prefix IS NULL$agentFilter)"

        @Suppress("UNCHECKED_CAST")
        val rows =
            entityManager
                .createNativeQuery(sql, PolicyRuleDataModel::class.java)
                .resultList as List<PolicyRuleDataModel>
        return rows.map { it.toRule() }
    }

    @Transactional(readOnly = true)
    override fun findAll(tenantId: TenantId): List<PolicyRuleRecord> {
        entityManager.setRlsTenantId(tenantId)
        val sql = "SELECT * FROM policy_rules WHERE tenant_id = '${tenantId.value}' ORDER BY created_at"

        @Suppress("UNCHECKED_CAST")
        val rows =
            entityManager
                .createNativeQuery(sql, PolicyRuleDataModel::class.java)
                .resultList as List<PolicyRuleDataModel>
        return rows.map { it.toRecord() }
    }

    @Transactional
    override fun save(
        tenantId: TenantId,
        agentKeyPrefix: String?,
        rule: PolicyRule,
    ): PolicyRuleRecord {
        entityManager.setRlsTenantId(tenantId)
        val id = UUID.randomUUID()
        val now = Instant.now()
        jpaRepository.save(
            PolicyRuleDataModel(
                id = id,
                tenantId = tenantId.value,
                agentKeyPrefix = agentKeyPrefix,
                ruleType = rule.typeName(),
                params = objectMapper.writeValueAsString(rule.params()),
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return PolicyRuleRecord(id = PolicyRuleId(id), agentKeyPrefix = agentKeyPrefix, rule = rule, createdAt = now)
    }

    @Transactional
    override fun delete(
        tenantId: TenantId,
        ruleId: PolicyRuleId,
    ): Boolean {
        entityManager.setRlsTenantId(tenantId)
        val existing =
            jpaRepository.findByIdAndTenantId(ruleId.value, tenantId.value)
                ?: return false
        jpaRepository.delete(existing)
        return true
    }

    private fun PolicyRuleDataModel.toRecord(): PolicyRuleRecord =
        PolicyRuleRecord(id = PolicyRuleId(id), agentKeyPrefix = agentKeyPrefix, rule = toRule(), createdAt = createdAt)

    private fun PolicyRuleDataModel.toRule(): PolicyRule {
        val params: Map<String, Any> = objectMapper.readValue(this.params)
        return when (ruleType) {
            "MAX_DEBIT_PER_SESSION" -> {
                PolicyRule.MaxDebitPerSession(
                    limit = MonetaryAmount.of(params["amount"] as String),
                )
            }

            "MAX_DEBIT_PER_HOUR" -> {
                PolicyRule.MaxDebitPerHour(
                    limit = MonetaryAmount.of(params["amount"] as String),
                )
            }

            "REQUIRE_HUMAN_APPROVAL_ABOVE" -> {
                PolicyRule.RequireHumanApprovalAbove(
                    threshold = MonetaryAmount.of(params["amount"] as String),
                )
            }

            "FORBIDDEN_ACCOUNT_PAIR" -> {
                PolicyRule.ForbiddenAccountPair(
                    debitAccount = AccountId.of(params["debitAccountId"] as String),
                    creditAccount = AccountId.of(params["creditAccountId"] as String),
                )
            }

            "ALLOWED_TOKENS" -> {
                PolicyRule.AllowedTokens(
                    tokens = (params["tokens"] as List<*>).map { StablecoinToken.valueOf(it as String) }.toSet(),
                )
            }

            "ALLOWED_CHAINS" -> {
                PolicyRule.AllowedChains(
                    chains = (params["chains"] as List<*>).map { ChainId.valueOf(it as String) }.toSet(),
                )
            }

            else -> {
                throw IllegalStateException("Unknown rule_type: $ruleType")
            }
        }
    }
}
