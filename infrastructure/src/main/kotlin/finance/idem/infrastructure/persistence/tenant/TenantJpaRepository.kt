package finance.idem.infrastructure.persistence.tenant

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TenantJpaRepository : JpaRepository<TenantDataModel, UUID>
