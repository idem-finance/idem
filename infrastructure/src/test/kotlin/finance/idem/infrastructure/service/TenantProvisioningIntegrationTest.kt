package finance.idem.infrastructure.service

import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.persistence.tenant.TenantJpaRepository
import finance.idem.infrastructure.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantProvisioningIntegrationTest : SharedPostgresTestBase() {
    companion object {
        // Redis has no module-wide singleton — mirrors ApiKeyServiceIntegrationTest.
        @Container
        val redis: GenericContainer<*> =
            GenericContainer("redis:7")
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("idem.admin.token") { "test-admin-token" }
        }
    }

    @Autowired
    private lateinit var provisioningService: TenantProvisioningService

    @Autowired
    private lateinit var apiKeyService: ApiKeyService

    @Autowired
    private lateinit var tenantJpaRepository: TenantJpaRepository

    private fun command(adminToken: String? = "test-admin-token") =
        ProvisionTenantCommand(
            adminToken = adminToken,
            organizationName = "Acme Corp",
            contactEmail = "ops@acme.com",
            plan = TenantPlan.CLOUD,
        )

    @Test
    fun `provision persists organization identity and plan without clobbering either column set`() {
        val result = provisioningService.execute(command())

        assertTrue(result.isSuccess)
        val provisioned = result.getOrThrow()

        val row = tenantJpaRepository.findById(provisioned.tenantId.value).orElseThrow()
        assertEquals("Acme Corp", row.organizationName)
        assertEquals("ops@acme.com", row.contactEmail)
        assertEquals("CLOUD", row.plan)
        assertEquals(100, row.rateLimitPerSecond)
        assertEquals(1000, row.rateLimitPerMinute)
    }

    @Test
    fun `the returned raw key validates successfully with the full CLOUD scope set`() {
        val provisioned = provisioningService.execute(command()).getOrThrow()

        val validated = apiKeyService.validate(provisioned.rawApiKey)

        assertNotNull(validated)
        assertEquals(provisioned.tenantId, validated.tenantId)
        assertEquals(ApiScope.entries.toSet(), validated.scopes)
    }

    @Test
    fun `provision fails closed with a missing or wrong admin token`() {
        assertIs<InvalidAdminToken>(provisioningService.execute(command(adminToken = null)).exceptionOrNull())
        assertIs<InvalidAdminToken>(provisioningService.execute(command(adminToken = "wrong")).exceptionOrNull())
    }

    @Test
    fun `suspend on an unknown tenant fails, and suspending a real tenant blocks its key on the very next validate call`() {
        assertIs<TenantNotFound>(provisioningService.execute("test-admin-token", TenantId.generate()).exceptionOrNull())

        val provisioned = provisioningService.execute(command()).getOrThrow()
        assertNotNull(apiKeyService.validate(provisioned.rawApiKey))

        val suspendResult = provisioningService.execute("test-admin-token", provisioned.tenantId)
        assertTrue(suspendResult.isSuccess)

        assertNull(apiKeyService.validate(provisioned.rawApiKey))
    }
}
