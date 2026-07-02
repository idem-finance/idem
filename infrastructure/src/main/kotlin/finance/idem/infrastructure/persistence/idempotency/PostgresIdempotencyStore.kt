package finance.idem.infrastructure.persistence.idempotency

import finance.idem.application.port.IdempotencyStore
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PostgresIdempotencyStore(
    private val jpaRepository: IdempotencyKeyJpaRepository,
    private val entityManager: EntityManager,
) : IdempotencyStore {
    @Transactional(readOnly = true)
    override fun find(
        key: String,
        tenantId: TenantId,
    ): TransactionId? {
        entityManager.setRlsTenantId(tenantId)

        return jpaRepository
            .findActiveByKeyAndTenantId(key, tenantId.value)
            ?.transactionId
            ?.let { TransactionId(it) }
    }

    @Transactional
    override fun tryRecord(
        key: String,
        tenantId: TenantId,
        transactionId: TransactionId,
    ): Boolean {
        entityManager.setRlsTenantId(tenantId)
        val affected =
            entityManager
                .createNativeQuery(
                    """
            INSERT INTO idempotency_keys (tenant_id, key, transaction_id, expires_at)
            VALUES (CAST(:tenantId AS uuid), :key, CAST(:transactionId AS uuid), now() + interval '24 hours')
            ON CONFLICT (tenant_id, key) DO NOTHING
        """,
                ).setParameter("tenantId", tenantId.value.toString())
                .setParameter("key", key)
                .setParameter("transactionId", transactionId.value.toString())
                .executeUpdate()
        return affected == 1
    }

    @Transactional
    override fun release(
        key: String,
        tenantId: TenantId,
    ) {
        entityManager.setRlsTenantId(tenantId)
        jpaRepository.deleteByKeyAndTenantId(key, tenantId.value)
    }
}
