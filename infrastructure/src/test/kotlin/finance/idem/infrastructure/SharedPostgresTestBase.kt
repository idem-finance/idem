package finance.idem.infrastructure

import finance.idem.infrastructure.service.PostgresTestContainers
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Points the subclass's Spring context at the module-wide singleton Postgres
 * container instead of a per-class one, so the whole module boots one container
 * (and runs Flyway once) rather than one per test class.
 *
 * Safe only for tests whose writes roll back (e.g. `@DataJpaTest`) or are scoped
 * to a per-class random `TenantId`. Tests that commit rows a scheduler or another
 * class could observe (poller/orchestrator/telemetry-ping) and tests that assert
 * fresh-database state (FlywayMigrationTest) must keep their own container.
 */
@Suppress("UtilityClassWithPublicConstructor") // subclassed so Spring inherits the @DynamicPropertySource
abstract class SharedPostgresTestBase {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun sharedPostgresProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", PostgresTestContainers.postgres::getJdbcUrl)
            registry.add("spring.datasource.username", PostgresTestContainers.postgres::getUsername)
            registry.add("spring.datasource.password", PostgresTestContainers.postgres::getPassword)
            // Cached contexts each hold a live Hikari pool against the ONE shared
            // container — at the default pool size (10) ~25 cached contexts exceed
            // Postgres max_connections. Two connections suffice for slice tests.
            registry.add("spring.datasource.hikari.maximum-pool-size") { "2" }
        }
    }
}
