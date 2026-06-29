package finance.idem.infrastructure.compliance

import finance.idem.core.TenantId
import finance.idem.core.compliance.TravelRuleData
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class LgpdRetentionService(
    private val scheduleRepo: LgpdRetentionScheduleJpaRepository,
    private val travelRuleDataRepo: TravelRuleDataJpaRepository,
    private val entityManager: EntityManager,
) {
    @Scheduled(cron = "0 0 2 1 * *")
    @SchedulerLock(name = "lgpdRetentionSweep", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    @Transactional
    fun processExpiredData() {
        val now = Instant.now()
        val expired = scheduleRepo.findByDeletionDueAtBeforeAndProcessedAtIsNull(now)
        expired.forEach { entry ->
            when (entry.entityType) {
                TravelRuleData::class.simpleName -> {
                    entityManager.setRlsTenantId(TenantId(entry.tenantId))
                    travelRuleDataRepo.deleteByTransferIdAndTenantId(entry.entityId, entry.tenantId)
                }
                else -> {
                    log.error("Unknown entityType '{}' in LGPD retention sweep — skipping row {}", entry.entityType, entry.id)
                    return@forEach
                }
            }
            entry.processedAt = now
            scheduleRepo.save(entry)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LgpdRetentionService::class.java)
    }
}
