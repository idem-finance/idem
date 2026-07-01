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
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.MonetaryEntry
import finance.idem.core.monetary.OnChainEntry
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

    private fun brlFiat(amount: String) =
        FiatEntry(
            amount = MonetaryAmount.of(amount),
            currency = FiatCurrency.BRL,
            rail = PaymentRail.PIX,
        )

    private fun usdcOnChain(amount: String) =
        OnChainEntry(
            amount = MonetaryAmount.of(amount),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xabc123",
            blockNumber = 19_000_000L,
            walletAddress = "0xWallet",
            tokenContract = "0xContract",
        )

    private fun line(
        entryType: EntryType,
        monetaryEntry: MonetaryEntry,
        tenantId: TenantId = this.tenantId,
    ) = JournalLine(
        id = UUID.randomUUID(),
        transactionId = TransactionId.generate(),
        accountId = AccountId.generate(),
        tenantId = tenantId,
        entryType = entryType,
        monetaryEntry = monetaryEntry,
        createdAt = now,
        createdBy = "system",
    )

    private fun createTx(
        lines: List<JournalLine>,
        tenantId: TenantId = this.tenantId,
    ) = Transaction.create(
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
        val tx =
            createTx(
                listOf(
                    line(EntryType.DEBIT, brlFiat("1000")),
                    line(EntryType.CREDIT, brlFiat("1000")),
                ),
            )
        assertEquals(TransactionStatus.COMMITTED, tx.status)
        assertEquals(2, tx.lines.size)
    }

    @Test
    fun `valid 4-line USDC to BRL offramp`() {
        // Debit USDC + Credit USDC bridge (onchain leg)
        // Debit BRL payable + Credit BRL nostro (fiat leg)
        val tx =
            createTx(
                listOf(
                    line(EntryType.DEBIT, usdcOnChain("180.00")),
                    line(EntryType.CREDIT, usdcOnChain("180.00")),
                    line(EntryType.DEBIT, brlFiat("1000.00")),
                    line(EntryType.CREDIT, brlFiat("1000.00")),
                ),
            )
        assertEquals(4, tx.lines.size)
        assertEquals(TransactionStatus.COMMITTED, tx.status)
    }

    @Test
    fun `agentContext is optional`() {
        val ctx = AgentContext(agentId = "agent-1", sessionId = "sess-abc")
        val txWithAgent =
            Transaction.create(
                id = TransactionId.generate(),
                tenantId = tenantId,
                idempotencyKey = "idem-001",
                lines =
                    listOf(
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

        val txNoAgent =
            createTx(
                listOf(
                    line(EntryType.DEBIT, brlFiat("500")),
                    line(EntryType.CREDIT, brlFiat("500")),
                ),
            )
        assertNull(txNoAgent.agentContext)
    }

    @Test
    fun `occurredAt and createdAt can differ`() {
        val pastEvent = now.minusSeconds(86400)
        val tx =
            Transaction.create(
                id = TransactionId.generate(),
                tenantId = tenantId,
                idempotencyKey = "backdated-001",
                lines =
                    listOf(
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
            createTx(
                listOf(
                    line(EntryType.DEBIT, brlFiat("1000")),
                    line(EntryType.CREDIT, brlFiat("999")),
                ),
            )
        }
    }

    @Test
    fun `rejects transaction balanced overall but not per currency`() {
        // BRL debit 1000 + USDC credit 180 — these are different currencies,
        // so neither BRL nor USDC is balanced independently.
        assertThrows<LedgerInvariantViolation> {
            createTx(
                listOf(
                    line(EntryType.DEBIT, brlFiat("1000")),
                    line(EntryType.CREDIT, usdcOnChain("180")),
                ),
            )
        }
    }

    @Test
    fun `rejects 4-line where one currency is unbalanced`() {
        // USDC is balanced, BRL is not
        assertThrows<LedgerInvariantViolation> {
            createTx(
                listOf(
                    line(EntryType.DEBIT, usdcOnChain("180.00")),
                    line(EntryType.CREDIT, usdcOnChain("180.00")),
                    line(EntryType.DEBIT, brlFiat("1000.00")),
                    line(EntryType.CREDIT, brlFiat("999.99")), // off by 0.01
                ),
            )
        }
    }

    // ── Invariant 3: single-tenant ────────────────────────────────────────────

    @Test
    fun `rejects lines belonging to a different tenant`() {
        val otherTenant = TenantId.generate()
        assertThrows<LedgerInvariantViolation> {
            createTx(
                listOf(
                    line(EntryType.DEBIT, brlFiat("500")),
                    line(EntryType.CREDIT, brlFiat("500"), tenantId = otherTenant),
                ),
            )
        }
    }

    // ── On-chain currency key includes chainId ────────────────────────────────

    @Test
    fun `valid transaction with same token balanced per chain`() {
        // EVM USDC leg balanced; Solana USDC leg balanced independently
        val solanaOnChain =
            OnChainEntry(
                amount = MonetaryAmount.of("180.00"),
                token = StablecoinToken.USDC,
                chainId = ChainId.SOLANA,
                txHash = "solana-sig-abc",
                blockNumber = 250_000_000L,
                walletAddress = "SolWallet",
                tokenContract = "SolContract",
            )
        val tx =
            createTx(
                listOf(
                    line(EntryType.DEBIT, usdcOnChain("180.00")), // EVM debit
                    line(EntryType.CREDIT, usdcOnChain("180.00")), // EVM credit
                    line(EntryType.DEBIT, solanaOnChain), // Solana debit
                    line(EntryType.CREDIT, solanaOnChain), // Solana credit
                ),
            )
        assertEquals(4, tx.lines.size)
    }

    @Test
    fun `rejects cross-chain USDC imbalance — chains are separate currency groups`() {
        // EVM debit cannot be balanced by Solana credit — different currency keys
        val solanaUsdc =
            OnChainEntry(
                amount = MonetaryAmount.of("180.00"),
                token = StablecoinToken.USDC,
                chainId = ChainId.SOLANA,
                txHash = "solana-sig-abc",
                blockNumber = 250_000_000L,
                walletAddress = "SolWallet",
                tokenContract = "SolContract",
            )
        assertThrows<LedgerInvariantViolation> {
            createTx(
                listOf(
                    line(EntryType.DEBIT, usdcOnChain("180.00")), // ONCHAIN:EVM:USDC
                    line(EntryType.CREDIT, solanaUsdc), // ONCHAIN:SOLANA:USDC — different group
                ),
            )
        }
    }

    // ── reconstitute factory ──────────────────────────────────────────────────

    @Test
    fun `reconstitute rebuilds transaction from persisted data without re-validating`() {
        val txId = TransactionId.generate()
        val tx =
            Transaction.reconstitute(
                id = txId,
                tenantId = tenantId,
                idempotencyKey = "idem-reconstitute",
                lines =
                    listOf(
                        line(EntryType.DEBIT, brlFiat("500")),
                        line(EntryType.CREDIT, brlFiat("500")),
                    ),
                status = TransactionStatus.COMMITTED,
                occurredAt = now,
                createdAt = now,
                createdBy = "system",
            )
        assertEquals(txId, tx.id)
        assertEquals(TransactionStatus.COMMITTED, tx.status)
        assertEquals(2, tx.lines.size)
    }

    // ── TransactionCommitted event ────────────────────────────────────────────

    @Test
    fun `TransactionCommitted carries correct fields`() {
        val tx =
            createTx(
                listOf(
                    line(EntryType.DEBIT, brlFiat("500")),
                    line(EntryType.CREDIT, brlFiat("500")),
                ),
            )
        val event =
            TransactionCommitted(
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
