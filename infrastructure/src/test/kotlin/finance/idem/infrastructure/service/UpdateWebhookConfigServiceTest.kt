package finance.idem.infrastructure.service

import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId
import finance.idem.infrastructure.outbox.WebhookUrlValidator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class UpdateWebhookConfigServiceTest {
    @Mock
    private lateinit var tenantRepository: TenantRepository

    @Mock
    private lateinit var webhookUrlValidator: WebhookUrlValidator

    private val service by lazy { UpdateWebhookConfigService(tenantRepository, webhookUrlValidator) }
    private val tenantId = TenantId.generate()
    private val url = "https://example.com/webhook"

    @Test
    fun `execute upserts config and returns it on valid URL`() {
        whenever(webhookUrlValidator.validate(url)).thenReturn(Result.success(Unit))

        val result = service.execute(tenantId, url)

        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals(url, config.webhookUrl)
        assertTrue(config.webhookSecret.length == 64, "secret should be 32 bytes hex = 64 chars")

        val captor = argumentCaptor<TenantWebhookConfig>()
        verify(tenantRepository).upsertWebhookConfig(any(), captor.capture())
        assertEquals(url, captor.firstValue.webhookUrl)
    }

    @Test
    fun `execute returns failure and does not persist when URL is invalid`() {
        whenever(webhookUrlValidator.validate(url))
            .thenReturn(Result.failure(IllegalArgumentException("blocked")))

        val result = service.execute(tenantId, url)

        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        verify(tenantRepository, never()).upsertWebhookConfig(any(), any())
    }

    @Test
    fun `each call generates a different secret`() {
        whenever(webhookUrlValidator.validate(any())).thenReturn(Result.success(Unit))

        val s1 = service.execute(tenantId, url).getOrThrow().webhookSecret
        val s2 = service.execute(tenantId, url).getOrThrow().webhookSecret

        assertTrue(s1 != s2, "secrets should be randomly generated per call")
    }
}
