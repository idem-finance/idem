package finance.idem

import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.persistence.tenant.TenantConfigRepositoryAdapter
import finance.idem.infrastructure.security.HmacSigner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant

private const val BILLING_SECRET = "test-billing-secret"

/**
 * Runs the billing webhook through the real filter chain, real HMAC verification, and real
 * cache-invalidation wiring — closing the coverage gap left by BillingWebhookControllerTest,
 * which disables Spring Security (@AutoConfigureMockMvc(addFilters = false)) and mocks the
 * use case, so it never proves this path actually works end-to-end.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "idem.billing.webhook-secret=$BILLING_SECRET",
        // app/src/main/resources/application.yaml resolves idem.audit.hmac-secret to an empty
        // string when IDEM_AUDIT_HMAC_SECRET is unset, which fails SecretKeySpec elsewhere.
        "idem.audit.hmac-secret=test-only-insecure-hmac-secret",
    ],
)
class BillingWebhookIntegrationTest {
    @Autowired lateinit var restTemplate: TestRestTemplate

    @LocalServerPort var port: Int = 0

    @MockitoSpyBean lateinit var tenantConfigRepository: TenantConfigRepositoryAdapter

    private fun config(tenantId: TenantId) =
        TenantConfig(
            tenantId = tenantId,
            plan = TenantPlan.CLOUD,
            rateLimitPerSecond = 10,
            rateLimitPerMinute = 600,
            featureFlags = setOf("compliance_export"),
            hmacKey = "tenant-specific-hmac-key",
            billingCustomerId = "cus_test",
            createdAt = Instant.now(),
            suspendedAt = null,
        )

    /** Null-safe `any()` matcher for a non-null Kotlin parameter (no mockito-kotlin here). */
    private fun anyTenantId(): TenantId {
        ArgumentMatchers.any(TenantId::class.java)
        return TenantId.generate()
    }

    private fun payload(tenantIdValue: String) = """{"tenantId":"$tenantIdValue"}"""

    private fun sign(
        body: String,
        secret: String = BILLING_SECRET,
    ) = HmacSigner.hexHmacSha256(secret, body)

    private fun postWebhook(
        body: String,
        signature: String?,
    ): ResponseEntity<Void> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                if (signature != null) set("X-Idem-Signature", signature)
            }
        return restTemplate.postForEntity(
            "http://localhost:$port/internal/webhooks/billing",
            HttpEntity(body, headers),
            Void::class.java,
        )
    }

    @Test
    fun `valid signature invalidates the tenant's cached config`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(config(tenantId))
        tenantConfigRepository.findByTenantId(tenantId) // warm the cache
        Mockito.clearInvocations(tenantConfigRepository)

        val body = payload(tenantId.value.toString())
        val response = postWebhook(body, sign(body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        verify(tenantConfigRepository).invalidate(tenantId)
    }

    @Test
    fun `missing or invalid signature is rejected and nothing is invalidated`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(config(tenantId))
        val body = payload(tenantId.value.toString())
        Mockito.clearInvocations(tenantConfigRepository)

        val missingSigResponse = postWebhook(body, signature = null)
        assertThat(missingSigResponse.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val wrongSigResponse = postWebhook(body, signature = "deadbeef0000")
        assertThat(wrongSigResponse.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        verify(tenantConfigRepository, never()).invalidate(anyTenantId())
    }

    @Test
    fun `well-formed but unknown tenantId returns 200 without invalidating anything`() {
        val unknownTenantId = TenantId.generate() // never persisted
        val body = payload(unknownTenantId.value.toString())
        Mockito.clearInvocations(tenantConfigRepository)

        val response = postWebhook(body, sign(body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        verify(tenantConfigRepository, never()).invalidate(anyTenantId())
    }

    @Test
    fun `malformed tenantId returns 200 without invalidating anything`() {
        val body = payload("not-a-uuid")
        Mockito.clearInvocations(tenantConfigRepository)

        val response = postWebhook(body, sign(body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        verify(tenantConfigRepository, never()).invalidate(anyTenantId())
    }
}
