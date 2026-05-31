package finance.idem.core.ledger

import finance.idem.core.EntryType
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.monetary.MonetaryEntry
import java.time.Instant

data class Transaction internal constructor(
    val id: TransactionId,
    val tenantId: TenantId,
    val idempotencyKey: String,
    val lines: List<JournalLine>,
    val status: TransactionStatus,
    val agentContext: AgentContext? = null,
    val metadata: Map<String, String> = emptyMap(),
    val occurredAt: Instant,
    val createdAt: Instant,
    val createdBy: String,
) {
    fun validate() {
        if (lines.size < 2) throw LedgerInvariantViolation(
            "Transaction must have at least 2 journal lines, got ${lines.size}"
        )

        val crossTenant = lines.filter { it.tenantId != tenantId }
        if (crossTenant.isNotEmpty()) throw LedgerInvariantViolation(
            "All journal lines must belong to tenant ${tenantId.value}; " +
                "${crossTenant.size} line(s) belong to a different tenant"
        )

        // Per-currency: sum of debits must equal sum of credits.
        // Fiat and on-chain are keyed separately (FIAT:BRL ≠ ONCHAIN:USDC).
        // On-chain key includes chainId — USDC on EVM ≠ USDC on Solana.
        lines
            .groupBy { it.monetaryEntry.currencyKey() }
            .forEach { (currencyKey, currencyLines) ->
                val debits = currencyLines.sumAmounts(EntryType.DEBIT)
                val credits = currencyLines.sumAmounts(EntryType.CREDIT)
                if (debits != credits) throw LedgerInvariantViolation(
                    "Unbalanced transaction for $currencyKey: debits=$debits, credits=$credits"
                )
            }
    }

    companion object {
        fun create(
            id: TransactionId,
            tenantId: TenantId,
            idempotencyKey: String,
            lines: List<JournalLine>,
            occurredAt: Instant,
            createdAt: Instant,
            createdBy: String,
            agentContext: AgentContext? = null,
            metadata: Map<String, String> = emptyMap(),
        ): Transaction {
            val tx = Transaction(
                id = id,
                tenantId = tenantId,
                idempotencyKey = idempotencyKey,
                lines = lines.toList(),
                status = TransactionStatus.PENDING,
                agentContext = agentContext,
                metadata = metadata.toMap(),
                occurredAt = occurredAt,
                createdAt = createdAt,
                createdBy = createdBy,
            )
            tx.validate()
            return tx
        }
    }
}

private fun MonetaryEntry.currencyKey(): String = when (this) {
    is MonetaryEntry.FiatEntry -> "FIAT:${currency.name}"
    is MonetaryEntry.OnChainEntry -> "ONCHAIN:${chainId.name}:${token.name}"
}

private fun List<JournalLine>.sumAmounts(type: EntryType): MonetaryAmount =
    filter { it.entryType == type }
        .fold(MonetaryAmount.ZERO) { acc, line -> acc + line.monetaryEntry.amount }
