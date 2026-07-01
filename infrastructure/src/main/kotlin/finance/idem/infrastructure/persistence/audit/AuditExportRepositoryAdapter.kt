package finance.idem.infrastructure.persistence.audit

import finance.idem.application.audit.AuditEntryType
import finance.idem.application.audit.AuditExportRecord
import finance.idem.application.audit.ExportAuditLogQuery
import finance.idem.application.port.AuditExportRepository
import finance.idem.core.agentic.AgentAuditStatus
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AuditExportRepositoryAdapter(
    private val auditLogJpaRepository: AuditLogJpaRepository,
    private val agentAuditEventJpaRepository: AgentAuditEventJpaRepository,
    private val entityManager: EntityManager,
) : AuditExportRepository {
    @Transactional(readOnly = true)
    override fun findForExport(query: ExportAuditLogQuery): List<AuditExportRecord> {
        entityManager.setRlsTenantId(query.tenantId)
        val humanRecords =
            when (query.type) {
                AuditEntryType.AGENT -> {
                    emptyList()
                }

                else -> {
                    auditLogJpaRepository
                        .findForExport(
                            query.tenantId.value,
                            query.from,
                            query.to,
                        ).map { it.toExportRecord() }
                }
            }
        val agentRecords =
            when (query.type) {
                AuditEntryType.HUMAN -> {
                    emptyList()
                }

                else -> {
                    agentAuditEventJpaRepository
                        .findForExport(
                            query.tenantId.value,
                            query.from,
                            query.to,
                        ).map { it.toExportRecord() }
                }
            }
        return (humanRecords + agentRecords).sortedBy { it.timestamp }
    }

    private fun AuditLogDataModel.toExportRecord() =
        AuditExportRecord(
            timestamp = occurredAt,
            actor = createdBy,
            action = action,
            entityType = "TRANSACTION",
            entityId = transactionId,
            intentDescription = intent,
            hmacSignature = hmac,
            outcome = null,
        )

    private fun AgentAuditEventDataModel.toExportRecord() =
        AuditExportRecord(
            timestamp = occurredAt,
            actor = agentId,
            action =
                when (AgentAuditStatus.valueOf(status)) {
                    AgentAuditStatus.PENDING -> "AGENT_ACTION_STARTED"
                    AgentAuditStatus.COMPLETED -> "AGENT_ACTION_COMPLETED"
                    AgentAuditStatus.FAILED -> "AGENT_ACTION_FAILED"
                },
            entityType = "WORKFLOW",
            entityId = workflowPlanId,
            intentDescription = intent,
            hmacSignature = hmac,
            outcome = outcome,
        )
}
