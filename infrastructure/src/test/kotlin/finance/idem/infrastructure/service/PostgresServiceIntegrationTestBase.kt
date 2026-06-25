package finance.idem.infrastructure.service

import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

abstract class PostgresServiceIntegrationTestBase {

    @Autowired
    protected lateinit var entityManager: EntityManager

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", PostgresTestContainers.postgres::getJdbcUrl)
            registry.add("spring.datasource.username", PostgresTestContainers.postgres::getUsername)
            registry.add("spring.datasource.password", PostgresTestContainers.postgres::getPassword)
        }
    }

    protected fun outboxCount(eventType: String): Long =
        (entityManager.createNativeQuery("SELECT COUNT(*) FROM webhook_outbox WHERE event_type = ?")
            .setParameter(1, eventType)
            .singleResult as Number).toLong()
}
