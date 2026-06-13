package finance.idem.infrastructure.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ApiKeyFilterConfig(
    private val apiKeyService: ApiKeyService,
) {
    @Bean
    fun apiKeyAuthFilter(): ApiKeyAuthFilter = ApiKeyAuthFilter(apiKeyService)
}
