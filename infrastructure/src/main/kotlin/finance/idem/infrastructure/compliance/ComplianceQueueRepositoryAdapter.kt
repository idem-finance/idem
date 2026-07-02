package finance.idem.infrastructure.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.compliance.ComplianceQueueItem
import finance.idem.application.port.ComplianceQueueRepository
import finance.idem.core.TenantId
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ComplianceQueueRepositoryAdapter(
    private val jpaRepository: ComplianceQueueJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : ComplianceQueueRepository {
    @Transactional
    override fun enqueue(item: ComplianceQueueItem) {
        entityManager.setRlsTenantId(item.tenantId)
        jpaRepository.save(
            ComplianceQueueDataModel(
                id = item.id,
                tenantId = item.tenantId.value,
                txHash = item.txHash,
                chainId = item.chainId.name,
                entryAmount = item.entryAmount.value,
                reason = item.reason.name,
                missingFields = objectMapper.writeValueAsString(item.missingFields),
                status = "PENDING",
                createdAt = item.enqueuedAt,
            ),
        )
    }
}
