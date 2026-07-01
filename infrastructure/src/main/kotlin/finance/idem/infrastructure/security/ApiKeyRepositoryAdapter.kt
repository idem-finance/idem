package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiKeyRepository
import finance.idem.core.security.ApiScope
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ApiKeyRepositoryAdapter(
    private val jpaRepository: ApiKeyJpaRepository,
) : ApiKeyRepository {
    @Transactional
    override fun save(apiKey: ApiKey): ApiKey {
        jpaRepository.save(apiKey.toEntity())
        return apiKey
    }

    @Transactional(readOnly = true)
    override fun findByPrefix(prefix: String): ApiKey? = jpaRepository.findByPrefix(prefix)?.toDomain()

    @Transactional(readOnly = true)
    override fun findById(
        id: ApiKeyId,
        tenantId: TenantId,
    ): ApiKey? = jpaRepository.findByIdAndTenantId(id.value, tenantId.value)?.toDomain()

    @Transactional(readOnly = true)
    override fun findAllByTenantId(tenantId: TenantId): List<ApiKey> = jpaRepository.findAllByTenantId(tenantId.value).map { it.toDomain() }
}

private fun ApiKeyDataModel.toDomain(): ApiKey =
    ApiKey(
        id = ApiKeyId(id),
        tenantId = TenantId(tenantId),
        keyHash = keyHash,
        prefix = prefix,
        scopes =
            scopes
                .split(",")
                .filter { it.isNotEmpty() }
                .mapTo(mutableSetOf()) { ApiScope.valueOf(it) },
        createdAt = createdAt,
        revokedAt = revokedAt,
    )

private fun ApiKey.toEntity(): ApiKeyDataModel =
    ApiKeyDataModel(
        id = id.value,
        tenantId = tenantId.value,
        keyHash = keyHash,
        prefix = prefix,
        scopes = scopes.joinToString(",") { it.name },
        createdAt = createdAt,
        revokedAt = revokedAt,
    )
