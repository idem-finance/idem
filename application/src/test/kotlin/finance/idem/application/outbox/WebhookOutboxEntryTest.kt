package finance.idem.application.outbox

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WebhookOutboxEntryTest {
    private val tenantId = TenantId.generate()
    private val txId = TransactionId(UUID.randomUUID())
    private val now = Instant.parse("2025-10-01T00:00:00Z")

    private fun stubTx() =
        object {
            val id = txId
            val tenantId = this@WebhookOutboxEntryTest.tenantId
            val occurredAt = now
        }

    private fun ledgerTx(): finance.idem.core.ledger.Transaction {
        val debitLine =
            finance.idem.core.ledger.JournalLine(
                id = UUID.randomUUID(),
                transactionId = txId,
                accountId = AccountId.generate(),
                tenantId = tenantId,
                entryType = finance.idem.core.EntryType.DEBIT,
                monetaryEntry =
                    finance.idem.core.monetary.FiatEntry(
                        amount = MonetaryAmount.of("100"),
                        currency = finance.idem.core.FiatCurrency.USD,
                        rail = finance.idem.core.PaymentRail.WIRE,
                    ),
                description = null,
                createdAt = now,
                createdBy = "test",
            )
        val creditLine =
            debitLine.copy(
                id = UUID.randomUUID(),
                accountId = AccountId.generate(),
                entryType = finance.idem.core.EntryType.CREDIT,
            )
        return finance.idem.core.ledger.Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = "key-1",
            lines = listOf(debitLine, creditLine),
            createdBy = "test",
            occurredAt = now,
            createdAt = now,
        )
    }

    @Test
    fun `transactionCommitted creates entry with correct eventType and ids`() {
        val tx = ledgerTx()
        val entry = WebhookOutboxEntry.transactionCommitted(tx)

        assertEquals("transaction.committed", entry.eventType)
        assertEquals(tenantId, entry.tenantId)
        assertEquals(txId, entry.transactionId)
        assertEquals(now, entry.occurredAt)
        assertNotNull(entry.id)
    }

    @Test
    fun `transactionSettled from Transaction creates entry with settled eventType`() {
        val tx = ledgerTx()
        val entry = WebhookOutboxEntry.transactionSettled(tx)

        assertEquals("transaction.settled", entry.eventType)
        assertEquals(tenantId, entry.tenantId)
        assertEquals(txId, entry.transactionId)
    }

    @Test
    fun `transactionSettled from Settlement creates entry with matched transactionId`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("50"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.SETTLED,
                matchedTransactionId = matchedTxId,
                confirmedAt = now,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.transactionSettled(settlement)

        assertEquals("transaction.settled", entry.eventType)
        assertEquals(tenantId, entry.tenantId)
        assertEquals(matchedTxId, entry.transactionId)
    }

    @Test
    fun `settlementReorged creates entry with reorged eventType and reversalTransactionId`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val reversalTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("50"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.REORGED,
                matchedTransactionId = matchedTxId,
                reversalTransactionId = reversalTxId,
                reorgedAt = now,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.settlementReorged(settlement)

        assertEquals("settlement.reorged", entry.eventType)
        assertEquals(tenantId, entry.tenantId)
        assertEquals(reversalTxId, entry.transactionId)
        assertEquals(now, entry.occurredAt)
    }

    @Test
    fun `settlementReorged falls back to Instant now when reorgedAt is null`() {
        val reversalTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("50"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.REORGED,
                reversalTransactionId = reversalTxId,
                reorgedAt = null,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.settlementReorged(settlement)

        assertNotNull(entry.occurredAt)
    }

    @Test
    fun `settlementReorged throws when Settlement has null reversalTransactionId`() {
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("50"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.REORGED,
                reversalTransactionId = null,
                createdAt = now,
                createdBy = "test",
            )
        assertFailsWith<IllegalArgumentException> {
            WebhookOutboxEntry.settlementReorged(settlement)
        }
    }

    @Test
    fun `reconciliationUnmatched creates entry with unmatched eventType`() {
        val tx = ledgerTx()
        val entry = WebhookOutboxEntry.reconciliationUnmatched(tx)

        assertEquals("reconciliation.unmatched", entry.eventType)
        assertEquals(tenantId, entry.tenantId)
        assertEquals(txId, entry.transactionId)
    }

    @Test
    fun `reconciliationUnmatched from Settlement creates entry with matched transactionId`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("50"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = matchedTxId,
                confirmedAt = now,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.reconciliationUnmatched(settlement)

        assertEquals("reconciliation.unmatched", entry.eventType)
        assertEquals(tenantId, entry.tenantId)
        assertEquals(matchedTxId, entry.transactionId)
        assertEquals(now, entry.occurredAt)
    }

    @Test
    fun `reconciliationUnmatched from Settlement falls back to Instant now when confirmedAt is null`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("50"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = matchedTxId,
                confirmedAt = null,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.reconciliationUnmatched(settlement)

        assertNotNull(entry.occurredAt)
    }

    @Test
    fun `reconciliationUnmatched from Settlement throws when matchedTransactionId is null`() {
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("50"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xabc",
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = null,
                createdAt = now,
                createdBy = "test",
            )
        assertFailsWith<IllegalArgumentException> {
            WebhookOutboxEntry.reconciliationUnmatched(settlement)
        }
    }

    @Test
    fun `reconciliationException creates entry with exception eventType`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("75"),
                token = StablecoinToken.USDT,
                chainId = ChainId.TRON,
                walletAddress = "T9xyz",
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = matchedTxId,
                confirmedAt = now,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.reconciliationException(settlement)

        assertEquals("reconciliation.exception", entry.eventType)
        assertEquals(matchedTxId, entry.transactionId)
    }

    @Test
    fun `transactionSettled from Settlement falls back to Instant now when confirmedAt is null`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("10"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0xdef",
                status = EntryStatus.SETTLED,
                matchedTransactionId = matchedTxId,
                confirmedAt = null,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.transactionSettled(settlement)

        assertEquals("transaction.settled", entry.eventType)
        assertNotNull(entry.occurredAt)
    }

    @Test
    fun `transactionSettled throws when Settlement has null matchedTransactionId`() {
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("10"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = "0x1",
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = null,
                createdAt = now,
                createdBy = "test",
            )
        assertFailsWith<IllegalArgumentException> {
            WebhookOutboxEntry.transactionSettled(settlement)
        }
    }

    @Test
    fun `reconciliationException throws when Settlement has null matchedTransactionId`() {
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("5"),
                token = StablecoinToken.USDT,
                chainId = ChainId.TRON,
                walletAddress = "T1",
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = null,
                createdAt = now,
                createdBy = "test",
            )
        assertFailsWith<IllegalArgumentException> {
            WebhookOutboxEntry.reconciliationException(settlement)
        }
    }

    @Test
    fun `workflowCommitted throws when WorkflowPlan has null completedAt`() {
        val plan =
            WorkflowPlan.create(
                id = WorkflowPlanId.generate(),
                tenantId = tenantId,
                agentContext = AgentContext(agentId = "a", sessionId = "s"),
                stepDescriptions = listOf("step-0"),
                createdAt = now,
            )
        assertFailsWith<IllegalArgumentException> {
            WebhookOutboxEntry.workflowCommitted(plan)
        }
    }

    @Test
    fun `reconciliationException falls back to Instant now when confirmedAt is null`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val settlement =
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = AccountId.generate(),
                amount = MonetaryAmount.of("20"),
                token = StablecoinToken.BRZ,
                chainId = ChainId.SOLANA,
                walletAddress = "solana-addr",
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = matchedTxId,
                confirmedAt = null,
                createdAt = now,
                createdBy = "test",
            )
        val entry = WebhookOutboxEntry.reconciliationException(settlement)

        assertEquals("reconciliation.exception", entry.eventType)
        assertNotNull(entry.occurredAt)
    }
}
