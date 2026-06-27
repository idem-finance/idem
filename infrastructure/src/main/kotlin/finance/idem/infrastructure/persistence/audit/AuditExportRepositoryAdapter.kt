package finance.idem.infrastructure.persistence.audit

import finance.idem.application.audit.AuditEntryType
import finance.idem.application.audit.AuditExportRecord
import finance.idem.application.audit.ExportAuditLogQuery
import finance.idem.application.port.AuditExportRepository
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
        val humanRecords = if (query.type == AuditEntryType.AGENT) emptyList()
        else auditLogJpaRepository.findByTenantIdAndOccurredAtBetween(
            query.tenantId.value, query.from, query.to,
        ).map { it.toExportRecord() }
        val agentRecords = if (query.type == AuditEntryType.HUMAN) emptyList()
        else agentAuditEventJpaRepository.findByTenantIdAndOccurredAtBetween(
            query.tenantId.value, query.from, query.to,
        ).map { it.toExportRecord() }
        return (humanRecords + agentRecords).sortedBy { it.timestamp }
    }

    private fun AuditLogDataModel.toExportRecord() = AuditExportRecord(
        timestamp = occurredAt,
        actor = createdBy,
        action = action,
        entityType = "TRANSACTION",
        entityId = transactionId,
        intentDescription = intent,
        hmacSignature = hmac,
        outcome = null,
    )

    private fun AgentAuditEventDataModel.toExportRecord() = AuditExportRecord(
        timestamp = occurredAt,
        actor = agentId,
        action = when (status) {
            "PENDING" -> "AGENT_ACTION_STARTED"
            "COMPLETED" -> "AGENT_ACTION_COMPLETED"
            "FAILED" -> "AGENT_ACTION_FAILED"
            else -> status
        },
        entityType = "WORKFLOW",
        entityId = workflowPlanId,
        intentDescription = intent,
        hmacSignature = hmac,
        outcome = outcome,
    )
}
