package finance.idem.mcp

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * [McpToolsConfig] wires [IdemMcpServer] through Spring AI's `MethodToolCallbackProvider`,
 * which reflects over the `@Tool`-annotated methods at runtime to build JSON schemas and
 * invoke them — unlike `@RestController` methods, this is not covered by Spring MVC's AOT
 * endpoint-signature inference, so every tool method plus its input/result DTOs need
 * explicit hints.
 */
class McpToolRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        hints.reflection().registerType(
            IdemMcpServer::class.java,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.INTROSPECT_DECLARED_METHODS,
        )

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
            hints.reflection().registerType(
                type,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS,
            )
        }
    }
}
