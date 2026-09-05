package finance.idem.infrastructure.persistence.events

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.events.DomainEvent
import finance.idem.application.port.DomainEventRepository
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class DomainEventRepositoryAdapter(
    private val jpaRepository: DomainEventJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : DomainEventRepository {
    @Transactional
    override fun save(event: DomainEvent) {
        entityManager.setRlsTenantId(event.tenantId)
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "referenceId" to event.referenceId.toString(),
                    "referenceType" to event.referenceType.name,
                    "eventType" to event.eventType.name,
                    "correlationId" to event.correlationId,
                ),
            )
        jpaRepository.save(
            DomainEventDataModel(
                id = event.id,
                tenantId = event.tenantId.value,
                eventType = event.eventType.name,
                referenceId = event.referenceId,
                referenceType = event.referenceType.name,
                correlationId = event.correlationId,
                payload = payload,
                occurredAt = event.occurredAt,
                createdAt = Instant.now(),
            ),
        )
    }
}
