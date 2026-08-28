package finance.idem.infrastructure.billing

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(BillingConfig::class)
class BillingWebhookConfig
