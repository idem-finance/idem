package finance.idem.mcp

import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class McpToolsConfig {
    @Bean
    fun idemToolCallbackProvider(server: IdemMcpServer): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(server).build()
}
