package finance.idem.infrastructure.service

import org.testcontainers.containers.PostgreSQLContainer

internal object PostgresTestContainers {
    val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")
            // Headroom for the Hikari pools of every cached Spring context in the
            // module (each holds its own pool against this one shared instance).
            .withCommand("postgres", "-c", "max_connections=300")
            .also { it.start() }
}
