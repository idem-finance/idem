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
import finance.idem.infrastructure.persistence.events.DomainEventRepositoryAdapter
import finance.idem.infrastructure.persistence.idempotency.PostgresIdempotencyStore
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.transaction.TestTransaction
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the transactional-boundary rule documented on [UsageMeteringServiceImpl.recordUsage]:
 * a usage-metering write failure inside [PostTransactionService.execute]'s ambient transaction
 * rolls back the whole ledger commit along with it — usage metering is a side effect of the
 * primary operation, not an isolated concern, per CLAUDE.md's single-transaction rule.
 * Uses the REAL [UsageMeteringServiceImpl] (so its transactional propagation is genuinely
 * exercised) with a mocked [UsageMetricRepository] that throws.
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
    DomainEventRepositoryAdapter::class,
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

    // Deliberately NOT using PostgresServiceIntegrationTestBase here: it @MockitoBeans
    // UsageMeteringService, which would silently replace the real UsageMeteringServiceImpl
    // imported below and defeat this test's whole point (exercising its real transactional
    // propagation). tenantConfigRepository is mocked directly instead, matching that base
    // class's own default -- UsageMeteringServiceImpl needs it to construct, but recordUsage
    // (the only method PostTransactionService calls) never actually invokes it.
    @MockitoBean
    lateinit var tenantConfigRepository: TenantConfigRepository

    @MockitoBean
    lateinit var usageMetricRepository: UsageMetricRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantId = TenantId.generate()
    private val debitId = AccountId.generate()
    private val creditId = AccountId.generate()
    private val now = Instant.now()

    @BeforeEach
    fun setup() {
        accountAdapter.save(Account.create(debitId, tenantId, "Debit", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountAdapter.save(Account.create(creditId, tenantId, "Credit", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
    }

    private fun domainEventCount(eventType: String): Long =
        (
            entityManager
                .createNativeQuery("SELECT COUNT(*) FROM domain_events WHERE event_type = ?")
                .setParameter(1, eventType)
                .singleResult as Number
        ).toLong()

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
    fun `execute fails when usage metering fails, instead of silently succeeding`() {
        // Under the old REQUIRES_NEW isolation, this failure would be swallowed by
        // PostTransactionService's runCatching and execute() would return Result.success —
        // the caller would never know metering was broken. Now recordUsage joins execute()'s
        // own transaction (see UsageMeteringServiceImpl KDoc), so a metering failure
        // propagates and fails the whole call, consistent with how an audit_log or
        // webhook_outbox write failure already behaves in this method.
        whenever(usageMetricRepository.recordEvent(any(), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("usage_metrics insert failed"))

        assertFailsWith<RuntimeException> {
            postTransactionService.execute(command("usage-failure-001"))
        }
    }

    @Test
    fun `no transaction row is persisted when usage metering fails`() {
        whenever(usageMetricRepository.recordEvent(any(), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("usage_metrics insert failed"))

        assertFailsWith<RuntimeException> {
            postTransactionService.execute(command("usage-failure-002"))
        }

        // Force the test's ambient transaction (marked rollback-only by the propagated
        // failure) to actually roll back, then start a fresh transaction to read post-rollback
        // state — proves the ledger write genuinely did not survive, not just that it's
        // invisible to a later, separately-rolled-back test.
        TestTransaction.end()
        TestTransaction.start()

        val count =
            (
                entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM transactions WHERE tenant_id = :tenantId")
                    .setParameter("tenantId", tenantId.value)
                    .singleResult as Number
            ).toLong()
        assertEquals(0L, count, "the transaction row must roll back along with the usage-metering failure")
        assertEquals(
            0L,
            domainEventCount("TRANSACTION_COMMITTED"),
            "the domain_events row must roll back along with the usage-metering failure, same as transactions/audit_log/webhook_outbox",
        )
    }

    @Test
    fun `records TRANSACTION_COUNT and ENTRY_COUNT when metering succeeds`() {
        // tenantId is matched with any(), not eq() -- see UsageMeteringServiceImplTest for why
        // eq() on a @JvmInline value class parameter always reports a false mismatch here.
        postTransactionService.execute(command("usage-success-001")).getOrThrow()

        verify(usageMetricRepository).recordEvent(any(), eq(MetricType.TRANSACTION_COUNT), eq(1L), any(), eq(null))
        verify(usageMetricRepository).recordEvent(any(), eq(MetricType.ENTRY_COUNT), eq(2L), any(), eq(null))
        assertEquals(1L, domainEventCount("TRANSACTION_COMMITTED"))
    }
}
