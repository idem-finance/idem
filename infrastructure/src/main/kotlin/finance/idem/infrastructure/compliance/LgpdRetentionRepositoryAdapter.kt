package finance.idem.infrastructure.compliance

import finance.idem.application.port.LgpdRetentionRepository
import finance.idem.core.TenantId
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
class LgpdRetentionRepositoryAdapter(
    private val jpaRepository: LgpdRetentionScheduleJpaRepository,
    private val entityManager: EntityManager,
) : LgpdRetentionRepository {

    @Transactional
    override fun schedule(tenantId: TenantId, entityType: String, entityId: String, retentionYears: Int) {
        entityManager.setRlsTenantId(tenantId)
        val now = Instant.now()
        jpaRepository.save(
            LgpdRetentionScheduleDataModel(
                id = UUID.randomUUID(),
                tenantId = tenantId.value,
                entityType = entityType,
                entityId = entityId,
                retentionYears = retentionYears,
                scheduledAt = now,
                deletionDueAt = now.atOffset(ZoneOffset.UTC).plusYears(retentionYears.toLong()).toInstant(),
            )
        )
    }
}
