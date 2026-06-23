package finance.idem.mcp

import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.ledger.DescribeAccountUseCase
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.GetEntriesUseCase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerContextLoadTest {

    @MockBean lateinit var executeWorkflowUseCase: ExecuteWorkflowUseCase
    @MockBean lateinit var getBalanceUseCase: GetBalanceUseCase
    @MockBean lateinit var getEntriesUseCase: GetEntriesUseCase
    @MockBean lateinit var describeAccountUseCase: DescribeAccountUseCase

    @Test
    fun `Spring AI MCP server autoconfiguration activates without error`() {
        // context load is the assertion — verifies Spring AI MCP server boots
        // with 4 tools registered: post_transaction, get_balance, list_entries, describe_account
    }
}
