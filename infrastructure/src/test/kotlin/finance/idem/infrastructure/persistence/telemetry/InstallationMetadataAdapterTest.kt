package finance.idem.infrastructure.persistence.telemetry

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(InstallationMetadataAdapter::class)
class InstallationMetadataAdapterTest {
    companion object {
        @Container
        val postgres =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var adapter: InstallationMetadataAdapter

    @Autowired
    lateinit var jpaRepository: InstallationMetadataJpaRepository

    @Test
    fun `getOrCreateId creates exactly one row on first call and returns a UUID`() {
        val id = adapter.getOrCreateId()

        assertNotNull(id)
        assertEquals(1, jpaRepository.count())
        assertEquals(id, jpaRepository.findById(1).orElseThrow().id)
    }

    @Test
    fun `getOrCreateId returns the same UUID on repeated calls without creating a second row`() {
        val first = adapter.getOrCreateId()
        val second = adapter.getOrCreateId()
        val third = adapter.getOrCreateId()

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, jpaRepository.count())
    }
}
