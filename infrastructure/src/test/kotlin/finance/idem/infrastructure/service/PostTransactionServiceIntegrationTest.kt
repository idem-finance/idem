package finance.idem.infrastructure.service

import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.TransactionStatus
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageMetricRepository
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.compliance.ComplianceConfig
import finance.idem.infrastructure.compliance.ComplianceQueueRepositoryAdapter
import finance.idem.infrastructure.compliance.LgpdRetentionRepositoryAdapter
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AuditConfig
import finance.idem.infrastructure.persistence.audit.AuditRepositoryAdapter
import finance.idem.infrastructure.persistence.idempotency.PostgresIdempotencyStore
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies the isolation guarantee documented on [UsageMeteringServiceImpl.recordUsage]: a
 * usage-metering write failure must never roll back the ledger transaction that triggered it.
 * Uses the REAL [UsageMeteringServiceImpl] (so its REQUIRES_NEW proxy is genuinely exercised)
 * with a mocked [UsageMetricRepository] that throws.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    PostTransactionService::class,
    BasicReconciliationService::class,
    UsageMeteringServiceImpl::class,
    AuditConfig::class,
    AuditRepositoryAdapter::class,
    WebhookOutboxRepositoryAdapter::class,
    TransactionRepositoryAdapter::class,
    AccountRepositoryAdapter::class,
    PostgresIdempotencyStore::class,
    SettlementRepositoryAdapter::class,
    PersistenceTestConfig::class,
    ComplianceConfig::class,
    ComplianceQueueRepositoryAdapter::class,
    LgpdRetentionRepositoryAdapter::class,
)
class PostTransactionServiceIntegrationTest : SharedPostgresTestBase() {
    @Autowired lateinit var postTransactionService: PostTransactionService

    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter

    // Deliberately NOT using PostgresServiceIntegrationTestBase here: it @MockitoBeans
    // UsageMeteringService, which would silently replace the real UsageMeteringServiceImpl
    // imported below and defeat this test's whole point (exercising the real REQUIRES_NEW
    // proxy). tenantConfigRepository is mocked directly instead, matching that base class's
    // own default -- UsageMeteringServiceImpl needs it to construct, but recordUsage (the
    // only method PostTransactionService calls) never actually invokes it.
    @MockitoBean
    lateinit var tenantConfigRepository: TenantConfigRepository

    @MockitoBean
    lateinit var usageMetricRepository: UsageMetricRepository

    private val tenantId = TenantId.generate()
    private val debitId = AccountId.generate()
    private val creditId = AccountId.generate()
    private val now = Instant.now()

    @BeforeEach
    fun setup() {
        accountAdapter.save(Account.create(debitId, tenantId, "Debit", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountAdapter.save(Account.create(creditId, tenantId, "Credit", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
    }

    private fun command(idempotencyKey: String) =
        PostTransactionCommand(
            tenantId = tenantId,
            idempotencyKey = idempotencyKey,
            lines =
                listOf(
                    JournalLineRequest(debitId, EntryType.DEBIT, FiatEntry(MonetaryAmount.of("100.00"), FiatCurrency.BRL, PaymentRail.PIX)),
                    JournalLineRequest(
                        creditId,
                        EntryType.CREDIT,
                        FiatEntry(MonetaryAmount.of("100.00"), FiatCurrency.BRL, PaymentRail.PIX),
                    ),
                ),
            createdBy = "integration-test",
        )

    @Test
    fun `ledger transaction commits even when usage metering fails`() {
        whenever(usageMetricRepository.recordEvent(any(), any(), any(), any()))
            .thenThrow(RuntimeException("usage_metrics insert failed"))

        val txId = postTransactionService.execute(command("usage-failure-001")).getOrThrow()

        val transaction = transactionAdapter.findById(txId, tenantId)
        assertNotNull(transaction)
        assertEquals(TransactionStatus.COMMITTED, transaction.status)
        assertEquals(2, transaction.lines.size)
    }

    @Test
    fun `records TRANSACTION_COUNT and ENTRY_COUNT when metering succeeds`() {
        // tenantId is matched with any(), not eq() -- see UsageMeteringServiceImplTest for why
        // eq() on a @JvmInline value class parameter always reports a false mismatch here.
        postTransactionService.execute(command("usage-success-001")).getOrThrow()

        verify(usageMetricRepository).recordEvent(any(), eq(MetricType.TRANSACTION_COUNT), eq(1L), any())
        verify(usageMetricRepository).recordEvent(any(), eq(MetricType.ENTRY_COUNT), eq(2L), any())
    }
}
