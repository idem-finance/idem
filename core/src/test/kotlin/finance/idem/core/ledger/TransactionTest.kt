package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.monetary.MonetaryEntry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TransactionTest {

    private val now = Instant.now()
    private val tenantId = TenantId.generate()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun brlFiat(amount: String) = MonetaryEntry.FiatEntry(
        amount = MonetaryAmount.of(amount),
        currency = FiatCurrency.BRL,
        rail = PaymentRail.PIX,
    )

    private fun usdcOnChain(amount: String) = MonetaryEntry.OnChainEntry(
        amount = MonetaryAmount.of(amount),
        token = StablecoinToken.USDC,
        chainId = ChainId.EVM,
        txHash = "0xabc123",
        blockNumber = 19_000_000L,
        walletAddress = "0xWallet",
        tokenContract = "0xContract",
    )

    private fun line(entryType: EntryType, monetaryEntry: MonetaryEntry) = JournalLine(
        id = UUID.randomUUID(),
        transactionId = TransactionId.generate(),
        accountId = AccountId.generate(),
        entryType = entryType,
        monetaryEntry = monetaryEntry,
        createdAt = now,
        createdBy = "system",
    )

    private fun createTx(lines: List<JournalLine>, tenantId: TenantId = this.tenantId) =
        Transaction.create(
            id = TransactionId.generate(),
            tenantId = tenantId,
            idempotencyKey = UUID.randomUUID().toString(),
            lines = lines,
            occurredAt = now,
            createdAt = now,
            createdBy = "sk_live_xxxx",
        )

    // ── Valid transactions ────────────────────────────────────────────────────

    @Test
    fun `valid 2-line BRL transaction`() {
        val tx = createTx(listOf(
            line(EntryType.DEBIT, brlFiat("1000")),
            line(EntryType.CREDIT, brlFiat("1000")),
        ))
        assertEquals(TransactionStatus.PENDING, tx.status)
        assertEquals(2, tx.lines.size)
    }

    @Test
    fun `valid 4-line USDC to BRL offramp`() {
        // Debit USDC + Credit USDC bridge (onchain leg)
        // Debit BRL payable + Credit BRL nostro (fiat leg)
        val tx = createTx(listOf(
            line(EntryType.DEBIT,  usdcOnChain("180.00")),
            line(EntryType.CREDIT, usdcOnChain("180.00")),
            line(EntryType.DEBIT,  brlFiat("1000.00")),
            line(EntryType.CREDIT, brlFiat("1000.00")),
        ))
        assertEquals(4, tx.lines.size)
        assertEquals(TransactionStatus.PENDING, tx.status)
    }

    @Test
    fun `agentContext is optional`() {
        val ctx = AgentContext(agentId = "agent-1", sessionId = "sess-abc")
        val txWithAgent = Transaction.create(
            id = TransactionId.generate(),
            tenantId = tenantId,
            idempotencyKey = "idem-001",
            lines = listOf(
                line(EntryType.DEBIT, brlFiat("500")),
                line(EntryType.CREDIT, brlFiat("500")),
            ),
            occurredAt = now,
            createdAt = now,
            createdBy = "sk_agent_xxxx",
            agentContext = ctx,
        )
        assertNotNull(txWithAgent.agentContext)
        assertEquals("agent-1", txWithAgent.agentContext!!.agentId)

        val txNoAgent = createTx(listOf(
            line(EntryType.DEBIT, brlFiat("500")),
            line(EntryType.CREDIT, brlFiat("500")),
        ))
        assertNull(txNoAgent.agentContext)
    }

    @Test
    fun `occurredAt and createdAt can differ`() {
        val pastEvent = now.minusSeconds(86400)
        val tx = Transaction.create(
            id = TransactionId.generate(),
            tenantId = tenantId,
            idempotencyKey = "backdated-001",
            lines = listOf(
                line(EntryType.DEBIT, brlFiat("200")),
                line(EntryType.CREDIT, brlFiat("200")),
            ),
            occurredAt = pastEvent,
            createdAt = now,
            createdBy = "system",
        )
        assertEquals(pastEvent, tx.occurredAt)
        assertEquals(now, tx.createdAt)
    }

    // ── Invariant 1: minimum 2 lines ──────────────────────────────────────────

    @Test
    fun `rejects empty lines`() {
        assertThrows<LedgerInvariantViolation> {
            createTx(emptyList())
        }
    }

    @Test
    fun `rejects single line`() {
        assertThrows<LedgerInvariantViolation> {
            createTx(listOf(line(EntryType.DEBIT, brlFiat("100"))))
        }
    }

    // ── Invariant 2: per-currency balance ─────────────────────────────────────

    @Test
    fun `rejects unbalanced BRL transaction`() {
        assertThrows<LedgerInvariantViolation> {
            createTx(listOf(
                line(EntryType.DEBIT, brlFiat("1000")),
                line(EntryType.CREDIT, brlFiat("999")),
            ))
        }
    }

    @Test
    fun `rejects transaction balanced overall but not per currency`() {
        // BRL debit 1000 + USDC credit 180 — these are different currencies,
        // so neither BRL nor USDC is balanced independently.
        assertThrows<LedgerInvariantViolation> {
            createTx(listOf(
                line(EntryType.DEBIT, brlFiat("1000")),
                line(EntryType.CREDIT, usdcOnChain("180")),
            ))
        }
    }

    @Test
    fun `rejects 4-line where one currency is unbalanced`() {
        // USDC is balanced, BRL is not
        assertThrows<LedgerInvariantViolation> {
            createTx(listOf(
                line(EntryType.DEBIT,  usdcOnChain("180.00")),
                line(EntryType.CREDIT, usdcOnChain("180.00")),
                line(EntryType.DEBIT,  brlFiat("1000.00")),
                line(EntryType.CREDIT, brlFiat("999.99")),   // off by 0.01
            ))
        }
    }

    // ── TransactionCommitted event ────────────────────────────────────────────

    @Test
    fun `TransactionCommitted carries correct fields`() {
        val tx = createTx(listOf(
            line(EntryType.DEBIT, brlFiat("500")),
            line(EntryType.CREDIT, brlFiat("500")),
        ))
        val event = TransactionCommitted(
            transactionId = tx.id,
            tenantId = tx.tenantId,
            occurredAt = tx.occurredAt,
            lineCount = tx.lines.size,
        )
        assertEquals(tx.id, event.transactionId)
        assertEquals(tx.tenantId, event.tenantId)
        assertEquals(2, event.lineCount)
    }
}
