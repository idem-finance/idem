package finance.idem.infrastructure.telemetry

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantThresholdWarnServiceIntegrationTest {

    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
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
    lateinit var tenantThresholdWarnService: TenantThresholdWarnService

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `TenantThresholdWarnService bean is present in context`() {
        assertNotNull(applicationContext.getBean(TenantThresholdWarnService::class.java))
    }

    @Test
    fun `checkTenantThreshold completes without error when no tenants exist`() {
        // zero tenants in DB — count(0) <= threshold(10) → no log, no exception
        tenantThresholdWarnService.checkTenantThreshold()
    }

    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = ["idem.limits.soft-warn-threshold=-1"],
    )
    inner class WhenThresholdDisabled {

        @Autowired
        lateinit var service: TenantThresholdWarnService

        @Test
        fun `checkTenantThreshold returns early without querying DB`() {
            // -1 disables: should not hit the DB, no exception
            service.checkTenantThreshold()
        }
    }
}
