package finance.idem.mcp

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints

class McpToolRuntimeHintsTest {
    @Test
    fun `registers method reflection hints for the tool server`() {
        val hints = RuntimeHints()

        McpToolRuntimeHints().registerHints(hints, javaClass.classLoader)

        assertNotNull(hints.reflection().getTypeHint(IdemMcpServer::class.java))
    }

    @Test
    fun `registers reflection hints for every tool input and result DTO`() {
        val hints = RuntimeHints()

        McpToolRuntimeHints().registerHints(hints, javaClass.classLoader)

        listOf(
            McpJournalLineInput::class.java,
            PostTransactionResult::class.java,
            BalanceResult::class.java,
            EntryListResult::class.java,
            EntryItem::class.java,
            AccountDescriptionResult::class.java,
            RollbackWorkflowResult::class.java,
            CompensatedStepItem::class.java,
            ReconcileBatchResult::class.java,
            AuditLogResult::class.java,
            AuditEventItem::class.java,
        ).forEach { type ->
            assertNotNull(hints.reflection().getTypeHint(type), "expected a reflection hint for ${type.name}")
        }
    }
}
