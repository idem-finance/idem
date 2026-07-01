package finance.idem.infrastructure.service

import org.testcontainers.containers.PostgreSQLContainer

internal object PostgresTestContainers {
    val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")
            .also { it.start() }
}
