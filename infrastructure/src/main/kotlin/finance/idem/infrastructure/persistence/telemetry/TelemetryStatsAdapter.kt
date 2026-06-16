package finance.idem.infrastructure.persistence.telemetry

import finance.idem.application.telemetry.TelemetryStatsPort
import finance.idem.infrastructure.persistence.JournalLineJpaRepository
import finance.idem.infrastructure.persistence.tenant.TenantJpaRepository
import org.springframework.stereotype.Component

@Component
class TelemetryStatsAdapter(
    private val tenantJpaRepository: TenantJpaRepository,
    private val journalLineJpaRepository: JournalLineJpaRepository,
) : TelemetryStatsPort {

    override fun tenantCount(): Long = tenantJpaRepository.count()

    override fun journalLineCount(): Long = journalLineJpaRepository.count()
}
