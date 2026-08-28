package finance.idem.infrastructure.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.infrastructure.security.HmacSigner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class BillingWebhookServiceTest {
    @Mock
    private lateinit var tenantConfigRepository: TenantConfigRepository

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val secret = "billing-webhook-secret"
    private val tenantId = TenantId.generate()

    private fun payload() = """{"tenantId":"${tenantId.value}"}"""

    private fun sign(
        body: String,
        withSecret: String = secret,
    ) = HmacSigner.hexHmacSha256(withSecret, body)

    private fun service(configuredSecret: String = secret) =
        BillingWebhookService(tenantConfigRepository, objectMapper, BillingConfig(webhookSecret = configuredSecret))

    @Test
    fun `handle rejects when webhook secret is not configured — fail closed`() {
        val result = service(configuredSecret = "").handle(signature = "irrelevant", rawBody = payload())

        assertTrue(result.isFailure)
        verify(tenantConfigRepository, never()).invalidate(any())
    }

    @Test
    fun `handle rejects when signature header is missing`() {
        val result = service().handle(signature = null, rawBody = payload())

        assertTrue(result.isFailure)
        verify(tenantConfigRepository, never()).invalidate(any())
    }

    @Test
    fun `handle rejects when signature does not match`() {
        val body = payload()
        val result = service().handle(signature = sign(body, withSecret = "wrong-secret"), rawBody = body)

        assertTrue(result.isFailure)
        verify(tenantConfigRepository, never()).invalidate(any())
    }

    @Test
    fun `handle invalidates the tenant's cached config on a validly signed request`() {
        val body = payload()

        val result = service().handle(signature = sign(body), rawBody = body)

        assertTrue(result.isSuccess)
        verify(tenantConfigRepository).invalidate(tenantId)
    }

    @Test
    fun `handle succeeds without side effects on unparsable payload`() {
        val body = "not json"

        val result = service().handle(signature = sign(body), rawBody = body)

        assertTrue(result.isSuccess)
        verify(tenantConfigRepository, never()).invalidate(any())
    }
}
