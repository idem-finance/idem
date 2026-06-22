package finance.idem.infrastructure.persistence.reconciliation

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
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.transaction.TestTransaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(SettlementRepositoryAdapter::class, AccountRepositoryAdapter::class, TransactionRepositoryAdapter::class, PersistenceTestConfig::class)
class SettlementRepositoryAdapterTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var adapter: SettlementRepositoryAdapter
    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter
    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter
    @Autowired lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()
    private val now = Instant.now()

    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val watchedWallet = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS"

    private var accountA: AccountId = AccountId.generate()
    private var accountA2: AccountId = AccountId.generate()
    private var accountA3: AccountId = AccountId.generate()
    private var accountB: AccountId = AccountId.generate()

    @BeforeEach
    fun createAccounts() {
        accountA = AccountId.generate()
        accountA2 = AccountId.generate()
        accountA3 = AccountId.generate()
        accountB = AccountId.generate()
        accountAdapter.save(Account.create(accountA, tenantA, "Custody", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountAdapter.save(Account.create(accountA2, tenantA, "Customer", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
        accountAdapter.save(Account.create(accountA3, tenantA, "Other", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountAdapter.save(Account.create(accountB, tenantB, "Custody-B", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        // Flush so accountB is physically present before any raw-JDBC insertSettlement()
        // call (which bypasses Hibernate's auto-flush) checks the fk_settlements_account FK.
        entityManager.flush()
    }

    private fun pendingSettlement(
        accountId: AccountId = accountA,
        tenantId: TenantId = tenantA,
        amount: MonetaryAmount = MonetaryAmount.of("100.000000"),
        token: StablecoinToken = StablecoinToken.USDC,
        chainId: ChainId = ChainId.SOLANA,
        walletAddress: String = watchedWallet,
        createdAt: Instant = now,
        expectedFromAddress: String? = null,
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = accountId,
        amount = amount,
        token = token,
        chainId = chainId,
        walletAddress = walletAddress,
        status = EntryStatus.PENDING,
        expectedFromAddress = expectedFromAddress,
        createdAt = createdAt,
        createdBy = "api-user",
    )

    /** Inserts a settlement row directly via JDBC so we can control `created_at` precisely. */
    private fun insertSettlement(
        tenantId: TenantId,
        accountId: AccountId,
        amount: String = "100.000000",
        token: StablecoinToken = StablecoinToken.USDC,
        chainId: ChainId = ChainId.SOLANA,
        walletAddress: String = watchedWallet,
        status: EntryStatus = EntryStatus.PENDING,
        createdAtExpr: String = "now()",
    ): UUID {
        val id = UUID.randomUUID()
        val session = entityManager.unwrap(org.hibernate.Session::class.java)
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn.prepareStatement(
                "INSERT INTO settlements (id, tenant_id, account_id, amount, token, chain_id, wallet_address, status, created_by, created_at) " +
                    "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, 'test', $createdAtExpr)"
            ).use { stmt ->
                stmt.setString(1, id.toString())
                stmt.setString(2, tenantId.value.toString())
                stmt.setString(3, accountId.value.toString())
                stmt.setBigDecimal(4, BigDecimal(amount))
                stmt.setString(5, token.name)
                stmt.setString(6, chainId.name)
                stmt.setString(7, walletAddress)
                stmt.setString(8, status.name)
                stmt.executeUpdate()
            }
        }
        entityManager.clear()
        return id
    }

    private fun onChainTx(tenantId: TenantId = tenantA): Transaction {
        val txId = TransactionId.generate()
        val entry = OnChainEntry(
            amount = MonetaryAmount.of("100.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.SOLANA,
            txHash = "tx-hash-1",
            blockNumber = 250_000_000L,
            walletAddress = watchedWallet,
            tokenContract = usdcMint,
        )
        val tx = Transaction.create(
            id = txId, tenantId = tenantId, idempotencyKey = "SOLANA:tx-hash-1:0",
            lines = listOf(
                JournalLine(UUID.randomUUID(), txId, accountA, tenantId, EntryType.DEBIT, entry, null, now, "test"),
                JournalLine(UUID.randomUUID(), txId, accountA2, tenantId, EntryType.CREDIT, entry, null, now, "test"),
            ),
            occurredAt = now, createdAt = now, createdBy = "test",
        )
        transactionAdapter.save(tx)
        return tx
    }

    @Test
    fun `save and findById round-trip preserves all fields for a PENDING row`() {
        val settlement = pendingSettlement()

        val saved = adapter.save(settlement)
        val found = adapter.findById(saved.id, tenantA)

        assertNotNull(found)
        assertEquals(settlement.id, found.id)
        assertEquals(tenantA, found.tenantId)
        assertEquals(accountA, found.accountId)
        assertEquals(MonetaryAmount.of("100.000000"), found.amount)
        assertEquals(StablecoinToken.USDC, found.token)
        assertEquals(ChainId.SOLANA, found.chainId)
        assertEquals(watchedWallet, found.walletAddress)
        assertEquals(EntryStatus.PENDING, found.status)
        assertEquals("api-user", found.createdBy)
        assertNull(found.matchedTransactionId)
        assertNull(found.txHash)
        assertNull(found.blockNumber)
        assertNull(found.confirmedAt)
        assertNull(found.expectedFromAddress)
    }

    @Test
    fun `save and findById round-trip preserves a non-null expectedFromAddress`() {
        val settlement = pendingSettlement(expectedFromAddress = "0xsenderaddress")

        val saved = adapter.save(settlement)
        val found = adapter.findById(saved.id, tenantA)

        assertNotNull(found)
        assertEquals("0xsenderaddress", found.expectedFromAddress)
    }

    @Test
    fun `findById returns null expectedFromAddress for legacy rows without the column set`() {
        val id = insertSettlement(tenantId = tenantA, accountId = accountA)

        val found = adapter.findById(id, tenantA)

        assertNotNull(found)
        assertNull(found.expectedFromAddress)
    }

    @Test
    fun `save transitions PENDING to SETTLED in place without creating a duplicate row`() {
        val settlement = adapter.save(pendingSettlement())
        val tx = onChainTx()
        val confirmedAt = Instant.now()

        val updated = adapter.save(
            settlement.copy(
                status = EntryStatus.SETTLED,
                matchedTransactionId = tx.id,
                txHash = "tx-hash-1",
                blockNumber = 250_000_000L,
                confirmedAt = confirmedAt,
            )
        )

        assertEquals(settlement.id, updated.id)
        val found = adapter.findById(settlement.id, tenantA)
        assertNotNull(found)
        assertEquals(EntryStatus.SETTLED, found.status)
        assertEquals(tx.id, found.matchedTransactionId)
        assertEquals("tx-hash-1", found.txHash)
        assertEquals(250_000_000L, found.blockNumber)
        assertNotNull(found.confirmedAt)

        val count = (entityManager.createNativeQuery("SELECT COUNT(*) FROM settlements WHERE id = :id")
            .setParameter("id", settlement.id)
            .singleResult as Number).toLong()
        assertEquals(1L, count)
    }

    @Test
    fun `findPendingCandidates returns matching PENDING rows within the window`() {
        val match = insertSettlement(tenantId = tenantA, accountId = accountA)
        insertSettlement(tenantId = tenantA, accountId = accountA, token = StablecoinToken.USDT)
        insertSettlement(tenantId = tenantA, accountId = accountA, walletAddress = "some-other-wallet")
        insertSettlement(tenantId = tenantA, accountId = accountA, chainId = ChainId.EVM)

        val results = adapter.findPendingCandidates(
            tenantA, setOf(accountA), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(3600),
        )

        assertEquals(1, results.size)
        assertEquals(match, results[0].id)
    }

    @Test
    fun `findPendingCandidates excludes rows older than since`() {
        insertSettlement(tenantId = tenantA, accountId = accountA, createdAtExpr = "now() - interval '2 hours'")
        val recent = insertSettlement(tenantId = tenantA, accountId = accountA, createdAtExpr = "now() - interval '10 minutes'")

        val results = adapter.findPendingCandidates(
            tenantA, setOf(accountA), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(3600),
        )

        assertEquals(1, results.size)
        assertEquals(recent, results[0].id)
    }

    @Test
    fun `findPendingCandidates excludes non-PENDING rows`() {
        insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.SETTLED)
        insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED)
        insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.CANCELLED)
        val pending = insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.PENDING)

        val results = adapter.findPendingCandidates(
            tenantA, setOf(accountA), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(3600),
        )

        assertEquals(1, results.size)
        assertEquals(pending, results[0].id)
    }

    @Test
    fun `findPendingCandidates orders results by createdAt ascending`() {
        val newer = insertSettlement(tenantId = tenantA, accountId = accountA, createdAtExpr = "now() - interval '5 seconds'")
        val older = insertSettlement(tenantId = tenantA, accountId = accountA, createdAtExpr = "now() - interval '1 hour'")

        val results = adapter.findPendingCandidates(
            tenantA, setOf(accountA), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(7200),
        )

        assertEquals(2, results.size)
        assertEquals(older, results[0].id, "Older entry must come first")
        assertEquals(newer, results[1].id, "Newer entry must come second")
    }

    @Test
    fun `findPendingCandidates matches when account is either side of the accountIds set`() {
        val matchA = insertSettlement(tenantId = tenantA, accountId = accountA)
        val matchA2 = insertSettlement(tenantId = tenantA, accountId = accountA2)
        insertSettlement(tenantId = tenantA, accountId = accountA3)

        val results = adapter.findPendingCandidates(
            tenantA, setOf(accountA, accountA2), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(3600),
        )

        assertEquals(setOf(matchA, matchA2), results.map { it.id }.toSet())
    }

    @Test
    fun `findPendingCandidates locks returned rows with SELECT FOR UPDATE`() {
        val settlementId = insertSettlement(tenantId = tenantA, accountId = accountA)

        // Commit so the row (and the @BeforeEach accounts) are visible to a second,
        // independent connection, then start a fresh transaction for the locking call.
        TestTransaction.flagForCommit()
        TestTransaction.end()
        TestTransaction.start()

        val results = adapter.findPendingCandidates(
            tenantA, setOf(accountA), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(3600),
        )
        assertEquals(1, results.size)

        // PESSIMISTIC_WRITE held by the still-open transaction above must block a
        // concurrent FOR UPDATE NOWAIT from a second connection.
        postgres.createConnection("").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { it.execute("SET LOCAL app.tenant_id = '${tenantA.value}'") }
            conn.prepareStatement("SELECT id FROM settlements WHERE id = ? FOR UPDATE NOWAIT").use { stmt ->
                stmt.setObject(1, settlementId)
                val ex = assertFailsWith<SQLException> { stmt.executeQuery() }
                assertEquals("55P03", ex.sqlState) // lock_not_available
            }
            conn.rollback()
        }

        TestTransaction.flagForRollback()
    }

    @Test
    fun `findPendingCandidates is isolated by tenant (RLS)`() {
        insertSettlement(tenantId = tenantB, accountId = accountB)
        val matchA = insertSettlement(tenantId = tenantA, accountId = accountA)

        val resultsA = adapter.findPendingCandidates(
            tenantA, setOf(accountA), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(3600),
        )
        val resultsB = adapter.findPendingCandidates(
            tenantB, setOf(accountB), StablecoinToken.USDC, ChainId.SOLANA, watchedWallet, now.minusSeconds(3600),
        )

        assertEquals(1, resultsA.size)
        assertEquals(matchA, resultsA[0].id)
        assertEquals(1, resultsB.size)
    }

    // ── findUnmatchedInWindow ──────────────────────────────────────────────────

    @Test
    fun `findUnmatchedInWindow returns UNMATCHED rows in the window`() {
        val id = insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED)
        insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.PENDING)
        insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.SETTLED)

        val results = adapter.findUnmatchedInWindow(tenantA, null, now.minusSeconds(3600), now.plusSeconds(3600))

        assertEquals(1, results.size)
        assertEquals(id, results[0].id)
    }

    @Test
    fun `findUnmatchedInWindow excludes rows outside the time window`() {
        insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED,
            createdAtExpr = "now() - interval '2 hours'")
        val inWindow = insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED,
            createdAtExpr = "now() - interval '30 minutes'")

        val results = adapter.findUnmatchedInWindow(
            tenantA, null, now.minusSeconds(3600), now.plusSeconds(3600),
        )

        assertEquals(1, results.size)
        assertEquals(inWindow, results[0].id)
    }

    @Test
    fun `findUnmatchedInWindow respects optional accountId filter`() {
        val matchAccount = insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED)
        insertSettlement(tenantId = tenantA, accountId = accountA2, status = EntryStatus.UNMATCHED)

        val results = adapter.findUnmatchedInWindow(tenantA, accountA, now.minusSeconds(3600), now.plusSeconds(3600))

        assertEquals(1, results.size)
        assertEquals(matchAccount, results[0].id)
    }

    @Test
    fun `findUnmatchedInWindow returns all accounts when accountId is null`() {
        insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED)
        insertSettlement(tenantId = tenantA, accountId = accountA2, status = EntryStatus.UNMATCHED)

        val results = adapter.findUnmatchedInWindow(tenantA, null, now.minusSeconds(3600), now.plusSeconds(3600))

        assertEquals(2, results.size)
    }

    @Test
    fun `findUnmatchedInWindow orders results by createdAt ascending`() {
        val newer = insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED,
            createdAtExpr = "now() - interval '5 seconds'")
        val older = insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED,
            createdAtExpr = "now() - interval '1 hour'")

        val results = adapter.findUnmatchedInWindow(tenantA, null, now.minusSeconds(7200), now.plusSeconds(3600))

        assertEquals(2, results.size)
        assertEquals(older, results[0].id, "Older entry must come first")
        assertEquals(newer, results[1].id, "Newer entry must come second")
    }

    @Test
    fun `findUnmatchedInWindow is isolated by tenant (RLS)`() {
        val matchA = insertSettlement(tenantId = tenantA, accountId = accountA, status = EntryStatus.UNMATCHED)
        insertSettlement(tenantId = tenantB, accountId = accountB, status = EntryStatus.UNMATCHED)

        val resultsA = adapter.findUnmatchedInWindow(tenantA, null, now.minusSeconds(3600), now.plusSeconds(3600))
        val resultsB = adapter.findUnmatchedInWindow(tenantB, null, now.minusSeconds(3600), now.plusSeconds(3600))

        assertEquals(1, resultsA.size)
        assertEquals(matchA, resultsA[0].id)
        assertEquals(1, resultsB.size)
    }
}
