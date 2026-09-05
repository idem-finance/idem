package finance.idem.infrastructure.persistence.events

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DomainEventJpaRepository : JpaRepository<DomainEventDataModel, UUID>
