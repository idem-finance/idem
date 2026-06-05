package finance.idem.infrastructure.security

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ApiKeyJpaRepository : JpaRepository<ApiKeyDataModel, UUID> {
    fun findByPrefix(prefix: String): ApiKeyDataModel?
    fun findByIdAndTenantId(id: UUID, tenantId: UUID): ApiKeyDataModel?
}
