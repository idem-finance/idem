package finance.idem.infrastructure.security

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ApiKeyJpaRepository : JpaRepository<ApiKeyDataModel, UUID> {
    fun findAllByPrefix(prefix: String): List<ApiKeyDataModel>

    fun findByIdAndTenantId(
        id: UUID,
        tenantId: UUID,
    ): ApiKeyDataModel?

    fun findAllByTenantId(tenantId: UUID): List<ApiKeyDataModel>
}
