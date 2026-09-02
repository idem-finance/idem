package finance.idem.ratelimit

import finance.idem.TestcontainersConfiguration
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.security.ApiKeyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import java.time.Instant

private const val HMAC_SECRET = "test-only-insecure-hmac-secret"

/**
 * Proves the real filter chain wiring end to end (#273): ApiKeyAuthFilter resolves the
 * tenant, RateLimitFilter enforces the Redis-backed bucket, and an exceeded request comes
 * back as a real HTTP 429 with a Retry-After header — not just each piece tested in isolation.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "idem.audit.hmac-secret=$HMAC_SECRET",
        "idem.chain.alchemy-webhook-signing-key=unused-in-ratelimit-test",
        "idem.ratelimit.enabled=true",
    ],
)
class RateLimitFilterEndToEndTest {
    @Autowired lateinit var restTemplate: TestRestTemplate

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var apiKeyService: ApiKeyService

    @Autowired lateinit var tenantConfigRepository: TenantConfigRepository

    @Test
    fun `Nth request beyond a tenant's configured limit returns 429 with Retry-After`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(
            TenantConfig(
                tenantId = tenantId,
                plan = TenantPlan.CLOUD,
                rateLimitPerSecond = 2,
                rateLimitPerMinute = 1000,
                featureFlags = emptySet(),
                hmacKey = null,
                billingCustomerId = null,
                createdAt = Instant.now(),
                suspendedAt = null,
            ),
        )
        val (rawKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.ACCOUNTS_READ))
        val headers = HttpHeaders().apply { set("X-API-Key", rawKey) }
        val request = HttpEntity<Void>(headers)

        val responses =
            (1..3).map {
                restTemplate.exchange(
                    "http://localhost:$port/api/v1/accounts",
                    org.springframework.http.HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

        assertThat(responses[0].statusCode).isEqualTo(HttpStatus.OK)
        assertThat(responses[1].statusCode).isEqualTo(HttpStatus.OK)
        assertThat(responses[2].statusCode.value()).isEqualTo(429)
        assertThat(responses[2].headers.getFirst("Retry-After")).isNotNull()
        assertThat(responses[2].body).contains("rate_limit_exceeded")
    }
}
