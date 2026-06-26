package finance.idem.infrastructure.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.compliance.ComplianceQueueItem
import finance.idem.application.port.ComplianceQueueRepository
import finance.idem.core.TenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ComplianceQueueRepositoryAdapter(
    private val jpaRepository: ComplianceQueueJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : ComplianceQueueRepository {

    private fun setTenantId(tenantId: TenantId) {
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional
    override fun enqueue(item: ComplianceQueueItem, tenantId: TenantId) {
        setTenantId(tenantId)
        jpaRepository.save(
            ComplianceQueueDataModel(
                id = item.id,
                tenantId = tenantId.value,
                txHash = item.txHash,
                chainId = item.chainId.name,
                entryAmount = item.entryAmount.value,
                reason = item.reason,
                missingFields = objectMapper.writeValueAsString(item.missingFields),
                status = "PENDING",
                createdAt = item.enqueuedAt,
            )
        )
    }
}
