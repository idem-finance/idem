package finance.idem.infrastructure.service

import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class GetWebhookConfigServiceTest {
    @Mock
    private lateinit var tenantRepository: TenantRepository

    private val service by lazy { GetWebhookConfigService(tenantRepository) }
    private val tenantId = TenantId.generate()

    @Test
    fun `returns config from repository when present`() {
        val config = TenantWebhookConfig("https://example.com/hook", "secret")
        whenever(tenantRepository.findWebhookConfig(any())).thenReturn(config)

        assertEquals(config, service.execute(tenantId))
    }

    @Test
    fun `returns null when repository returns null`() {
        whenever(tenantRepository.findWebhookConfig(any())).thenReturn(null)

        assertNull(service.execute(tenantId))
    }
}
