package finance.idem.infrastructure.compliance

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface LgpdRetentionScheduleJpaRepository : JpaRepository<LgpdRetentionScheduleDataModel, UUID> {
    fun findByDeletionDueAtBeforeAndProcessedAtIsNull(cutoff: Instant): List<LgpdRetentionScheduleDataModel>
}
