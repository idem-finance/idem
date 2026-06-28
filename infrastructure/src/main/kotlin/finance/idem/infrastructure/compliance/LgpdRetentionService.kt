package finance.idem.infrastructure.compliance

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class LgpdRetentionService(
    private val scheduleRepo: LgpdRetentionScheduleJpaRepository,
    private val travelRuleDataRepo: TravelRuleDataJpaRepository,
) {
    @Scheduled(cron = "0 0 2 1 * *")
    @Transactional
    fun processExpiredData() {
        val expired = scheduleRepo.findByDeletionDueAtBeforeAndProcessedAtIsNull(Instant.now())
        val now = Instant.now()
        expired.forEach { entry ->
            when (entry.entityType) {
                "TravelRuleData" ->
                    travelRuleDataRepo.deleteByTransferIdAndTenantId(entry.entityId, entry.tenantId)
            }
            entry.processedAt = now
            scheduleRepo.save(entry)
        }
    }
}
