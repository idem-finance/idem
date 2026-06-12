package finance.idem.infrastructure.outbox

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient

@Configuration
class WebhookOutboxConfig {
    @Bean
    fun webhookHttpClient(): HttpClient = HttpClient.newHttpClient()
}
