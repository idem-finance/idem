package finance.idem.infrastructure.persistence.idempotency

import finance.idem.application.port.SettlementIdempotencyStore
import finance.idem.core.TenantId
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class PostgresSettlementIdempotencyStore(
    private val jpaRepository: SettlementIdempotencyKeyJpaRepository,
    private val entityManager: EntityManager,
) : SettlementIdempotencyStore {
    @Transactional(readOnly = true)
    override fun find(
        key: String,
        tenantId: TenantId,
    ): UUID? {
        entityManager.setRlsTenantId(tenantId)

        return jpaRepository.findActiveByKeyAndTenantId(key, tenantId.value)?.settlementId
    }

    @Transactional
    override fun tryRecord(
        key: String,
        tenantId: TenantId,
        settlementId: UUID,
    ): Boolean {
        entityManager.setRlsTenantId(tenantId)
        val affected =
            entityManager
                .createNativeQuery(
                    """
            INSERT INTO settlement_idempotency_keys (tenant_id, key, settlement_id, expires_at)
            VALUES (CAST(:tenantId AS uuid), :key, CAST(:settlementId AS uuid), now() + interval '24 hours')
            ON CONFLICT (tenant_id, key) DO NOTHING
        """,
                ).setParameter("tenantId", tenantId.value.toString())
                .setParameter("key", key)
                .setParameter("settlementId", settlementId.toString())
                .executeUpdate()
        return affected == 1
    }
}
