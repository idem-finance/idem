package finance.idem.infrastructure.security

import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.ratelimit.RateLimitFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertTrue

/**
 * Pins the filter-chain order contract that PR #289's review flagged as undocumented (#273):
 * RateLimitFilter must run strictly after BOTH auth filters that can resolve a tenant onto
 * the SecurityContext (ApiKeyAuthFilter for the X-API-Key header path, McpSseAuthBridgeFilter
 * for the headerless POST /mcp/messages path) — not merely after ApiKeyAuthFilter, which left
 * the order relative to McpSseAuthBridgeFilter to Spring Security's undocumented tie-break
 * between filters sharing the same addFilterAfter anchor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class WebSecurityConfigFilterOrderTest : SharedPostgresTestBase() {
    companion object {
        @Container
        val redis: GenericContainer<*> =
            GenericContainer("redis:7")
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("idem.ratelimit.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var securityFilterChain: SecurityFilterChain

    @Test
    fun `RateLimitFilter runs after both ApiKeyAuthFilter and McpSseAuthBridgeFilter`() {
        val filterClasses = securityFilterChain.filters.map { it::class.java }

        val apiKeyAuthIndex = filterClasses.indexOf(ApiKeyAuthFilter::class.java)
        val mcpBridgeIndex = filterClasses.indexOf(McpSseAuthBridgeFilter::class.java)
        val rateLimitIndex = filterClasses.indexOf(RateLimitFilter::class.java)

        assertTrue(apiKeyAuthIndex >= 0, "ApiKeyAuthFilter not found in the chain")
        assertTrue(mcpBridgeIndex >= 0, "McpSseAuthBridgeFilter not found in the chain")
        assertTrue(rateLimitIndex >= 0, "RateLimitFilter not found in the chain — is idem.ratelimit.enabled wired?")
        assertTrue(
            apiKeyAuthIndex < mcpBridgeIndex && mcpBridgeIndex < rateLimitIndex,
            "expected order ApiKeyAuthFilter($apiKeyAuthIndex) < McpSseAuthBridgeFilter($mcpBridgeIndex) " +
                "< RateLimitFilter($rateLimitIndex)",
        )
    }
}
