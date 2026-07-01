package finance.idem.infrastructure.persistence.policy

import finance.idem.application.agentic.SessionDebitPort
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class SessionDebitAdapter(
    private val entityManager: EntityManager,
) : SessionDebitPort {
    @Transactional(readOnly = true)
    override fun sumDebitsForSession(
        tenantId: TenantId,
        sessionId: String,
    ): MonetaryAmount {
        entityManager.setRlsTenantId(tenantId)
        val sql =
            """
            SELECT COALESCE(SUM(jl.amount), 0)
            FROM journal_lines jl
            JOIN workflow_steps ws ON ws.transaction_id = jl.transaction_id
            JOIN workflow_plans wp ON wp.id = ws.workflow_plan_id
            WHERE wp.tenant_id = '${tenantId.value}'
              AND wp.session_id = '${sessionId.replace("'", "''")}'
              AND jl.entry_type = 'DEBIT'
            """.trimIndent()
        val result = entityManager.createNativeQuery(sql).singleResult
        return MonetaryAmount.of(result.toString())
    }

    @Transactional(readOnly = true)
    override fun sumDebitsLastHour(
        tenantId: TenantId,
        agentKeyPrefix: String?,
    ): MonetaryAmount {
        entityManager.setRlsTenantId(tenantId)
        val sql =
            if (agentKeyPrefix != null) {
                """
                SELECT COALESCE(SUM(jl.amount), 0)
                FROM journal_lines jl
                JOIN workflow_steps ws ON ws.transaction_id = jl.transaction_id
                JOIN workflow_plans wp ON wp.id = ws.workflow_plan_id
                WHERE wp.tenant_id = '${tenantId.value}'
                  AND wp.api_key_prefix = '${agentKeyPrefix.replace("'", "''")}'
                  AND jl.entry_type = 'DEBIT'
                  AND jl.created_at > now() - INTERVAL '1 hour'
                """.trimIndent()
            } else {
                """
                SELECT COALESCE(SUM(amount), 0)
                FROM journal_lines
                WHERE tenant_id = '${tenantId.value}'
                  AND entry_type = 'DEBIT'
                  AND created_at > now() - INTERVAL '1 hour'
                """.trimIndent()
            }
        val result = entityManager.createNativeQuery(sql).singleResult
        return MonetaryAmount.of(result.toString())
    }
}
