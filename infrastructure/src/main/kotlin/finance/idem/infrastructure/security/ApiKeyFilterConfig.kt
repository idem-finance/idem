package finance.idem.infrastructure.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ApiKeyFilterConfig(
    private val apiKeyService: ApiKeyService,
    private val sessionAuthStore: McpSseSessionAuthStore,
    private val apiCallCounter: ApiCallCounter,
) {
    @Bean
    fun apiKeyAuthFilter(): ApiKeyAuthFilter = ApiKeyAuthFilter(apiKeyService, apiCallCounter)

    @Bean
    fun mcpSseAuthBridgeFilter(): McpSseAuthBridgeFilter = McpSseAuthBridgeFilter(sessionAuthStore, apiCallCounter)
}
