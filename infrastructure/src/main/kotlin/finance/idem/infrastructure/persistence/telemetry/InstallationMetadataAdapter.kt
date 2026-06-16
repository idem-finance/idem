package finance.idem.infrastructure.persistence.telemetry

import finance.idem.application.telemetry.InstallationMetadataPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class InstallationMetadataAdapter(
    private val jpaRepository: InstallationMetadataJpaRepository,
) : InstallationMetadataPort {

    @Transactional
    override fun getOrCreateId(): UUID {
        val existing = jpaRepository.findById(SINGLETON_KEY).orElse(null)
        if (existing != null) return existing.id
        return try {
            jpaRepository.saveAndFlush(InstallationMetadataDataModel(SINGLETON_KEY, UUID.randomUUID(), Instant.now())).id
        } catch (_: DataIntegrityViolationException) {
            // Another replica inserted concurrently — read the winner.
            jpaRepository.findById(SINGLETON_KEY).orElseThrow().id
        }
    }

    private companion object {
        const val SINGLETON_KEY = 1
    }
}
