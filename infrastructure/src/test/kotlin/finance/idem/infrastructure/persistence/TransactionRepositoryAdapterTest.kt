package finance.idem.infrastructure.persistence

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionStatus
import finance.idem.core.monetary.MonetaryEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TransactionRepositoryAdapter::class, AccountRepositoryAdapter::class, PersistenceTestConfig::class)
class TransactionRepositoryAdapterTest {

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

    @Autowired lateinit var adapter: TransactionRepositoryAdapter
    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()
    private val now = Instant.now()

    private var debitAccountId: AccountId = AccountId.generate()
    private var creditAccountId: AccountId = AccountId.generate()

    @BeforeEach
    fun createAccounts() {
        debitAccountId = AccountId.generate()
        creditAccountId = AccountId.generate()
        accountAdapter.save(Account.create(debitAccountId, tenantA, "Debit",  FiatCurrency.BRL, AccountType.ASSET,     now, "test"))
        accountAdapter.save(Account.create(creditAccountId, tenantA, "Credit", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
    }

    private fun brlLine(txId: TransactionId, accountId: AccountId, entryType: EntryType, amount: String) =
        JournalLine(
            id = UUID.randomUUID(),
            transactionId = txId,
            accountId = accountId,
            tenantId = tenantA,
            entryType = entryType,
            monetaryEntry = MonetaryEntry.FiatEntry(MonetaryAmount.of(amount), FiatCurrency.BRL, PaymentRail.PIX),
            createdAt = now,
            createdBy = "test",
        )

    private fun createTx(
        idempotencyKey: String = UUID.randomUUID().toString(),
        tenantId: TenantId = tenantA,
    ): Transaction {
        val txId = TransactionId.generate()
        return Transaction.create(
            id = txId, tenantId = tenantId, idempotencyKey = idempotencyKey,
            lines = listOf(
                brlLine(txId, debitAccountId, EntryType.DEBIT, "1000"),
                brlLine(txId, creditAccountId, EntryType.CREDIT, "1000"),
            ),
            occurredAt = now, createdAt = now, createdBy = "test",
        )
    }

    @Test
    fun `save and findById round-trip preserves all fields`() {
        val tx = createTx()
        adapter.save(tx)

        val found = adapter.findById(tx.id, tenantA)

        assertNotNull(found)
        assertEquals(tx.id, found.id)
        assertEquals(tx.tenantId, found.tenantId)
        assertEquals(tx.idempotencyKey, found.idempotencyKey)
        assertEquals(TransactionStatus.PENDING, found.status)
        assertEquals(2, found.lines.size)
    }

    @Test
    fun `FiatEntry round-trip preserves all monetary fields`() {
        val tx = createTx()
        adapter.save(tx)

        val found = adapter.findById(tx.id, tenantA)!!
        val entry = found.lines.first { it.entryType == EntryType.DEBIT }.monetaryEntry
                as MonetaryEntry.FiatEntry

        assertEquals(MonetaryAmount.of("1000"), entry.amount)
        assertEquals(FiatCurrency.BRL, entry.currency)
        assertEquals(PaymentRail.PIX, entry.rail)
    }

    @Test
    fun `OnChainEntry round-trip preserves all fields`() {
        val txId = TransactionId.generate()
        val onChainEntry = MonetaryEntry.OnChainEntry(
            amount = MonetaryAmount.of("180.00"), token = StablecoinToken.USDC,
            chainId = ChainId.EVM, txHash = "0xabc123", blockNumber = 19_000_000L,
            walletAddress = "0xWallet", tokenContract = "0xContract",
        )
        val tx = Transaction.create(
            id = txId, tenantId = tenantA, idempotencyKey = UUID.randomUUID().toString(),
            lines = listOf(
                JournalLine(UUID.randomUUID(), txId, debitAccountId, tenantA, EntryType.DEBIT, onChainEntry, null, now, "test"),
                JournalLine(UUID.randomUUID(), txId, creditAccountId, tenantA, EntryType.CREDIT,
                    MonetaryEntry.OnChainEntry(MonetaryAmount.of("180.00"), StablecoinToken.USDC, ChainId.EVM, "0xabc123", 19_000_000L, "0xWallet2", "0xContract"),
                    null, now, "test"),
            ),
            occurredAt = now, createdAt = now, createdBy = "test",
        )
        adapter.save(tx)

        val found = adapter.findById(tx.id, tenantA)!!
        val entry = found.lines.first { it.entryType == EntryType.DEBIT }.monetaryEntry
                as MonetaryEntry.OnChainEntry

        assertEquals(StablecoinToken.USDC, entry.token)
        assertEquals(ChainId.EVM, entry.chainId)
        assertEquals("0xabc123", entry.txHash)
        assertEquals(19_000_000L, entry.blockNumber)
        assertEquals("0xWallet", entry.walletAddress)
    }

    @Test
    fun `findById with wrong tenant returns null (RLS)`() {
        adapter.save(createTx(tenantId = tenantA))
        // tenantB has no accounts so we can only check findById directly via id
        val tx = createTx()
        adapter.save(tx)
        assertNull(adapter.findById(tx.id, tenantB))
    }

    @Test
    fun `findByIdempotencyKey returns null for wrong tenant`() {
        val key = "idem-key-${UUID.randomUUID()}"
        adapter.save(createTx(idempotencyKey = key, tenantId = tenantA))
        assertNull(adapter.findByIdempotencyKey(key, tenantB))
    }

    @Test
    fun `agentContext round-trip preserves all fields`() {
        val txId = TransactionId.generate()
        val agentCtx = finance.idem.core.agentic.AgentContext(
            agentId = "agent-1",
            sessionId = "sess-abc",
            workflowPlanId = finance.idem.core.WorkflowPlanId.generate(),
            intent = "post_offramp",
        )
        val tx = Transaction.create(
            id = txId, tenantId = tenantA, idempotencyKey = UUID.randomUUID().toString(),
            lines = listOf(
                brlLine(txId, debitAccountId, EntryType.DEBIT, "500"),
                brlLine(txId, creditAccountId, EntryType.CREDIT, "500"),
            ),
            occurredAt = now, createdAt = now, createdBy = "test",
            agentContext = agentCtx,
            metadata = mapOf("source" to "api", "ref" to "TX-001"),
        )
        adapter.save(tx)

        val found = adapter.findById(tx.id, tenantA)!!
        assertNotNull(found.agentContext)
        assertEquals("agent-1", found.agentContext!!.agentId)
        assertEquals("sess-abc", found.agentContext!!.sessionId)
        assertEquals("post_offramp", found.agentContext!!.intent)
        assertEquals(mapOf("source" to "api", "ref" to "TX-001"), found.metadata)
    }

    @Test
    fun `findByAccountId returns transactions containing the account`() {
        adapter.save(createTx())
        adapter.save(createTx())

        val results = adapter.findByAccountId(debitAccountId, tenantA)

        assertEquals(2, results.size)
        assertTrue(results.all { tx -> tx.lines.any { l -> l.accountId == debitAccountId } })
    }
}
