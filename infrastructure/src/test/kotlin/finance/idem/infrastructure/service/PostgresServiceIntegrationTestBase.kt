package finance.idem.infrastructure.service

import finance.idem.infrastructure.SharedPostgresTestBase
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired

abstract class PostgresServiceIntegrationTestBase : SharedPostgresTestBase() {
    @Autowired
    protected lateinit var entityManager: EntityManager

    protected fun outboxCount(eventType: String): Long =
        (
            entityManager
                .createNativeQuery("SELECT COUNT(*) FROM webhook_outbox WHERE event_type = ?")
                .setParameter(1, eventType)
                .singleResult as Number
        ).toLong()
}
