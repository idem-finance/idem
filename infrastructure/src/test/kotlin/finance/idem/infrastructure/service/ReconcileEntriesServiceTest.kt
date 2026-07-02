package finance.idem.infrastructure.service

import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReconcileEntriesCommand
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.Transaction
import finance.idem.core.monetary.OnChainEntry
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxJpaRepository
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    ReconcileEntriesService::class,
    SettlementRepositoryAdapter::class,
    WebhookOutboxRepositoryAdapter::class,
    AccountRepositoryAdapter::class,
    TransactionRepositoryAdapter::class,
    PersistenceTestConfig::class,
)
class ReconcileEntriesServiceTest : PostgresServiceIntegrationTestBase() {
    @Autowired lateinit var service: ReconcileEntriesService

    @Autowired lateinit var settlementAdapter: SettlementRepositoryAdapter

    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter

    @Autowired lateinit var webhookOutboxRepository: WebhookOutboxRepository

    @Autowired lateinit var txManager: PlatformTransactionManager

    @Autowired lateinit var outboxJpaRepository: WebhookOutboxJpaRepository

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()
    private val now = Instant.now()
    private val watchedWallet = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS"
    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    private var accountA: AccountId = AccountId.generate()
    private var accountA2: AccountId = AccountId.generate()
    private var accountB: AccountId = AccountId.generate()

    @BeforeEach
    fun setup() {
        accountA = AccountId.generate()
        accountA2 = AccountId.generate()
        accountB = AccountId.generate()
        accountAdapter.save(Account.create(accountA, tenantA, "Custody", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountAdapter.save(Account.create(accountA2, tenantA, "Customer", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
        accountAdapter.save(Account.create(accountB, tenantB, "Custody-B", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        entityManager.flush()
    }

    /** Creates and persists a minimal on-chain transaction; returns its ID for use as matchedTransactionId. */
    private fun createOnChainTx(
        tenantId: TenantId,
        debitAccount: AccountId,
        creditAccount: AccountId,
        amount: String = "100.000000",
        txHash: String = "sol-hash-${UUID.randomUUID()}",
    ): TransactionId {
        val txId = TransactionId.generate()
        val entry =
            OnChainEntry(
                amount = MonetaryAmount.of(amount),
                token = StablecoinToken.USDC,
                chainId = ChainId.SOLANA,
                txHash = txHash,
                blockNumber = 250_000_000L,
                walletAddress = watchedWallet,
                tokenContract = usdcMint,
            )
        val tx =
            Transaction.create(
                id = txId,
                tenantId = tenantId,
                idempotencyKey = "SOLANA:$txHash:0",
                lines =
                    listOf(
                        JournalLine(UUID.randomUUID(), txId, debitAccount, tenantId, EntryType.DEBIT, entry, null, now, "test"),
                        JournalLine(UUID.randomUUID(), txId, creditAccount, tenantId, EntryType.CREDIT, entry, null, now, "test"),
                    ),
                occurredAt = now,
                createdAt = now,
                createdBy = "test",
            )
        transactionAdapter.save(tx)
        return txId
    }

    private fun unmatchedSettlement(
        tenantId: TenantId = tenantA,
        accountId: AccountId = accountA,
        amount: String = "100.000000",
        matchedTransactionId: TransactionId,
        txHash: String = "sol-hash",
        expectedFromAddress: String? = null,
        createdAt: Instant = now,
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of(amount),
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = watchedWallet,
        status = EntryStatus.UNMATCHED,
        matchedTransactionId = matchedTransactionId,
        txHash = txHash,
        blockNumber = 250_000_000L,
        confirmedAt = now.minusSeconds(30),
        expectedFromAddress = expectedFromAddress,
        createdAt = createdAt,
        createdBy = "system",
    )

    private fun pendingSettlement(
        tenantId: TenantId = tenantA,
        accountId: AccountId = accountA,
        amount: String = "100.000000",
        expectedFromAddress: String? = null,
        createdAt: Instant = now.minusSeconds(60),
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of(amount),
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = watchedWallet,
        status = EntryStatus.PENDING,
        expectedFromAddress = expectedFromAddress,
        createdAt = createdAt,
        createdBy = "api-user",
    )

    private fun cmd(
        tenantId: TenantId = tenantA,
        accountId: AccountId? = null,
        from: Instant = now.minusSeconds(3600),
        to: Instant = now.plusSeconds(3600),
    ) = ReconcileEntriesCommand(tenantId = tenantId, accountId = accountId, from = from, to = to)

    private fun settlementStatus(id: UUID): String =
        entityManager
            .createNativeQuery("SELECT status FROM settlements WHERE id = ?::uuid")
            .setParameter(1, id.toString())
            .singleResult as String

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    fun `UNMATCHED entry matches PENDING and both transition to SETTLED`() {
        val txId = createOnChainTx(tenantA, accountA, accountA2)
        val unmatched = settlementAdapter.save(unmatchedSettlement(matchedTransactionId = txId))
        val pending = settlementAdapter.save(pendingSettlement())

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(1, result.matched)
        assertEquals(0, result.unmatched)
        assertTrue(result.exceptions.isEmpty())
        assertEquals("SETTLED", settlementStatus(pending.id))
        assertEquals("SETTLED", settlementStatus(unmatched.id))
        assertEquals(1L, outboxCount("transaction.settled"))
        assertEquals(0L, outboxCount("reconciliation.exception"))
    }

    // ── no match ─────────────────────────────────────────────────────────────

    @Test
    fun `UNMATCHED entry with no PENDING emits reconciliation-exception outbox`() {
        val txId = createOnChainTx(tenantA, accountA, accountA2)
        val unmatched = settlementAdapter.save(unmatchedSettlement(matchedTransactionId = txId))

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(1, result.unmatched)
        assertEquals(1, result.exceptions.size)
        assertEquals(unmatched.id, result.exceptions[0].settlementId)
        assertEquals(0L, outboxCount("transaction.settled"))
        assertEquals(1L, outboxCount("reconciliation.exception"))
    }

    // ── amount mismatch ───────────────────────────────────────────────────────

    @Test
    fun `PENDING with different amount does not match UNMATCHED entry`() {
        val txId = createOnChainTx(tenantA, accountA, accountA2, amount = "100.000000")
        settlementAdapter.save(unmatchedSettlement(amount = "100.000000", matchedTransactionId = txId))
        settlementAdapter.save(pendingSettlement(amount = "200.000000"))

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(1, result.unmatched)
    }

    // ── sender-confirmed exclusion ────────────────────────────────────────────

    @Test
    fun `PENDING with expectedFromAddress is excluded from sweep matching`() {
        val txId = createOnChainTx(tenantA, accountA, accountA2)
        settlementAdapter.save(unmatchedSettlement(matchedTransactionId = txId))
        settlementAdapter.save(pendingSettlement(expectedFromAddress = "0xKnownSender"))

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(1, result.unmatched)
    }

    // ── accountId filter ──────────────────────────────────────────────────────

    @Test
    fun `accountId filter limits sweep to that account`() {
        val txId1 = createOnChainTx(tenantA, accountA, accountA2)
        val txId2 = createOnChainTx(tenantA, accountA2, accountA)
        settlementAdapter.save(unmatchedSettlement(accountId = accountA, matchedTransactionId = txId1))
        settlementAdapter.save(unmatchedSettlement(accountId = accountA2, matchedTransactionId = txId2))
        settlementAdapter.save(pendingSettlement(accountId = accountA))
        settlementAdapter.save(pendingSettlement(accountId = accountA2))

        val result = service.execute(cmd(accountId = accountA)).getOrThrow()

        assertEquals(1, result.matched)
        assertEquals(0, result.unmatched)
    }

    // ── time window filter ────────────────────────────────────────────────────

    @Test
    fun `entries outside the time window are not processed`() {
        val txId = createOnChainTx(tenantA, accountA, accountA2)
        // Settlement created 2 hours ago — outside [now-1h, now+1h]
        settlementAdapter.save(
            unmatchedSettlement(
                matchedTransactionId = txId,
                createdAt = now.minusSeconds(7200),
            ),
        )
        settlementAdapter.save(pendingSettlement())

        val result = service.execute(cmd(from = now.minusSeconds(3600))).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(0, result.unmatched)
    }

    // ── FIFO ordering ─────────────────────────────────────────────────────────

    @Test
    fun `oldest PENDING candidate is matched first (FIFO)`() {
        val txId = createOnChainTx(tenantA, accountA, accountA2)
        settlementAdapter.save(unmatchedSettlement(matchedTransactionId = txId))
        // Both within the since window (now.minusSeconds(3600)) so both are candidates
        val olderPending = settlementAdapter.save(pendingSettlement(createdAt = now.minusSeconds(1800)))
        val newerPending = settlementAdapter.save(pendingSettlement(createdAt = now.minusSeconds(60)))

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(1, result.matched)
        assertEquals("SETTLED", settlementStatus(olderPending.id))
        // newer PENDING should remain PENDING (not matched)
        assertEquals("PENDING", settlementStatus(newerPending.id))
    }

    // ── tenant isolation ──────────────────────────────────────────────────────

    @Test
    fun `sweep does not cross tenant boundaries`() {
        val txId = createOnChainTx(tenantB, accountB, accountB)
        settlementAdapter.save(unmatchedSettlement(tenantId = tenantB, accountId = accountB, matchedTransactionId = txId))
        settlementAdapter.save(pendingSettlement(tenantId = tenantB, accountId = accountB))

        val result = service.execute(cmd(tenantId = tenantA)).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(0, result.unmatched)
    }

    // ── empty result ──────────────────────────────────────────────────────────

    @Test
    fun `no UNMATCHED entries returns zero counts and empty exceptions`() {
        val result = service.execute(cmd()).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(0, result.unmatched)
        assertTrue(result.exceptions.isEmpty())
    }

    // ── amount tolerance ──────────────────────────────────────────────────────

    @Test
    fun `tolerance of 1 percent matches entry within range`() {
        val toleranceService = ReconcileEntriesService(settlementAdapter, webhookOutboxRepository, txManager, BigDecimal("1"))
        val txId = createOnChainTx(tenantA, accountA, accountA2, amount = "100.000000")
        settlementAdapter.save(unmatchedSettlement(amount = "100.000000", matchedTransactionId = txId))
        // 0.5% deviation — within 1% tolerance
        settlementAdapter.save(pendingSettlement(amount = "100.500000"))

        val result = toleranceService.execute(cmd()).getOrThrow()

        assertEquals(1, result.matched)
        assertEquals(0, result.unmatched)
        assertTrue(result.exceptions.isEmpty())
    }

    @Test
    fun `tolerance of 1 percent rejects entry outside range`() {
        val toleranceService = ReconcileEntriesService(settlementAdapter, webhookOutboxRepository, txManager, BigDecimal("1"))
        val txId = createOnChainTx(tenantA, accountA, accountA2, amount = "100.000000")
        settlementAdapter.save(unmatchedSettlement(amount = "100.000000", matchedTransactionId = txId))
        // 2% deviation — outside 1% tolerance
        settlementAdapter.save(pendingSettlement(amount = "102.000000"))

        val result = toleranceService.execute(cmd()).getOrThrow()

        assertEquals(0, result.matched)
        assertEquals(1, result.unmatched)
    }

    // ── grouped batch fetch ───────────────────────────────────────────────────

    @Test
    fun `multiple UNMATCHED entries sharing same wallet and token are all settled in one batch`() {
        val txId1 = createOnChainTx(tenantA, accountA, accountA2, amount = "100.000000", txHash = "hash-batch-1")
        val txId2 = createOnChainTx(tenantA, accountA, accountA2, amount = "200.000000", txHash = "hash-batch-2")
        settlementAdapter.save(unmatchedSettlement(amount = "100.000000", matchedTransactionId = txId1, txHash = "hash-batch-1"))
        settlementAdapter.save(unmatchedSettlement(amount = "200.000000", matchedTransactionId = txId2, txHash = "hash-batch-2"))
        settlementAdapter.save(pendingSettlement(amount = "100.000000"))
        settlementAdapter.save(pendingSettlement(amount = "200.000000"))

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(2, result.matched)
        assertEquals(0, result.unmatched)
        assertTrue(result.exceptions.isEmpty())
        assertEquals(2L, outboxCount("transaction.settled"))
    }
}
