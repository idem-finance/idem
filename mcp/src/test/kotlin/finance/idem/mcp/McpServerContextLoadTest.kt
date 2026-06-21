package finance.idem.mcp

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerContextLoadTest {

    @Test
    fun `Spring AI MCP server autoconfiguration activates without error`() {
        // context load is the assertion — verifies Spring AI MCP server boots with zero tools registered
    }
}
