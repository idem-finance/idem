package finance.idem.application.tenant

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TenantWebhookConfigTest {

    @Test
    fun `TenantWebhookConfig holds webhookUrl and webhookSecret`() {
        val config = TenantWebhookConfig(
            webhookUrl = "https://tenant.example.com/webhooks/idem",
            webhookSecret = "whsec_test",
        )

        assertEquals("https://tenant.example.com/webhooks/idem", config.webhookUrl)
        assertEquals("whsec_test", config.webhookSecret)
        assertEquals(config, config.copy())
    }
}
