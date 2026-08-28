package finance.idem.infrastructure.service

import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.infrastructure.SharedPostgresTestBase
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean

abstract class PostgresServiceIntegrationTestBase : SharedPostgresTestBase() {
    @Autowired
    protected lateinit var entityManager: EntityManager

    // Not every subclass's @Import list pulls in AgentAuditRepositoryAdapter (which needs
    // this bean) — defined here so the ones that do don't each redeclare it, and unused
    // elsewhere is harmless.
    @MockitoBean
    protected lateinit var tenantConfigRepository: TenantConfigRepository

    @BeforeEach
    fun stubTenantConfigLookup() {
        // Lenient: most tests don't care which HMAC key was used, only that save() succeeds.
        Mockito.lenient().whenever(tenantConfigRepository.findByTenantId(any())).thenReturn(null)
    }

    protected fun outboxCount(eventType: String): Long =
        (
            entityManager
                .createNativeQuery("SELECT COUNT(*) FROM webhook_outbox WHERE event_type = ?")
                .setParameter(1, eventType)
                .singleResult as Number
        ).toLong()
}
