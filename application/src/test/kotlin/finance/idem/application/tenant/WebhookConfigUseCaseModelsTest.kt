package finance.idem.application.tenant

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WebhookConfigUseCaseModelsTest {
    @Test
    fun `TenantWebhookConfig holds all fields`() {
        val config = TenantWebhookConfig("https://example.com/hook", "my-secret")

        assertEquals("https://example.com/hook", config.webhookUrl)
        assertEquals("my-secret", config.webhookSecret)
        assertEquals(config, config.copy())
    }
}
