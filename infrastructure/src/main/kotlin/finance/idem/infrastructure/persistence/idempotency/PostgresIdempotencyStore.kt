package finance.idem.infrastructure.persistence.idempotency

import finance.idem.application.port.IdempotencyStore
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class PostgresIdempotencyStore(
    private val jpaRepository: IdempotencyKeyJpaRepository,
    private val entityManager: EntityManager,
) : IdempotencyStore {

    private fun setTenantId(tenantId: TenantId) {
        // UUID contains only hex digits and dashes — safe to interpolate without binding
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional(readOnly = true)
    override fun find(key: String, tenantId: TenantId): TransactionId? {
        setTenantId(tenantId)
        return jpaRepository.findActiveByKeyAndTenantId(key, tenantId.value, Instant.now())
            ?.transactionId?.let { TransactionId(it) }
    }

    @Transactional
    override fun tryRecord(key: String, tenantId: TenantId, transactionId: TransactionId): Boolean {
        setTenantId(tenantId)
        val affected = entityManager.createNativeQuery("""
            INSERT INTO idempotency_keys (id, tenant_id, idempotency_key, transaction_id, expires_at)
            VALUES (gen_random_uuid(), CAST(:tenantId AS uuid), :key, CAST(:transactionId AS uuid), now() + interval '24 hours')
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
        """)
            .setParameter("tenantId", tenantId.value.toString())
            .setParameter("key", key)
            .setParameter("transactionId", transactionId.value.toString())
            .executeUpdate()
        return affected == 1
    }

    @Transactional
    override fun release(key: String, tenantId: TenantId) {
        setTenantId(tenantId)
        jpaRepository.deleteByIdempotencyKeyAndTenantId(key, tenantId.value)
    }
}
