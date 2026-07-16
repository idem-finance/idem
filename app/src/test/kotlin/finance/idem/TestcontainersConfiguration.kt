package finance.idem

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    companion object {
        // JVM-wide singletons: every @SpringBootTest context that imports this
        // configuration shares one Postgres + Redis pair instead of booting its
        // own. Held here (not as context-managed lifecycle) so a context close
        // never stops the containers; Ryuk reaps them at JVM exit.
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16")).also { it.start() }

        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379).also { it.start() }
    }

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> = postgres

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> = redis
}
