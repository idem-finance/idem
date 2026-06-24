package finance.idem.mcp

import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.agentic.RollbackWorkflowUseCase
import finance.idem.application.ledger.DescribeAccountUseCase
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.GetEntriesUseCase
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.reconciliation.ReconcileEntriesUseCase
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerContextLoadTest {

    @MockBean lateinit var executeWorkflowUseCase: ExecuteWorkflowUseCase
    @MockBean lateinit var getBalanceUseCase: GetBalanceUseCase
    @MockBean lateinit var getEntriesUseCase: GetEntriesUseCase
    @MockBean lateinit var describeAccountUseCase: DescribeAccountUseCase
    @MockBean lateinit var rollbackWorkflowUseCase: RollbackWorkflowUseCase
    @MockBean lateinit var reconcileEntriesUseCase: ReconcileEntriesUseCase
    @MockBean lateinit var agentAuditRepository: AgentAuditRepository

    @Autowired lateinit var toolCallbackProvider: ToolCallbackProvider

    @Test
    fun `Spring AI MCP server autoconfiguration activates with 7 tools`() {
        val names = toolCallbackProvider.toolCallbacks.map { it.toolDefinition.name() }.toSet()
        assertEquals(
            setOf(
                "postTransaction", "getBalance", "listEntries", "describeAccount",
                "rollbackWorkflow", "reconcileBatch", "getAgentAuditLog",
            ),
            names,
        )
    }
}
