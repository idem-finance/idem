package finance.idem.infrastructure.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ApiKeyFilterConfig(
    private val apiKeyService: ApiKeyService,
    private val sessionAuthStore: McpSseSessionAuthStore,
) {
    @Bean
    fun apiKeyAuthFilter(): ApiKeyAuthFilter = ApiKeyAuthFilter(apiKeyService)

    @Bean
    fun mcpSseAuthBridgeFilter(): McpSseAuthBridgeFilter = McpSseAuthBridgeFilter(sessionAuthStore)
}
