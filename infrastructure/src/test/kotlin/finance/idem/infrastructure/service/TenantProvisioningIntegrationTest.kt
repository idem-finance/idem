package finance.idem.infrastructure.service

import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.tenant.TenantJpaRepository
import finance.idem.infrastructure.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantProvisioningIntegrationTest : SharedPostgresTestBase() {
    companion object {
        // Redis has no module-wide singleton — mirrors ApiKeyServiceIntegrationTest.
        @Container
        val redis: GenericContainer<*> =
            GenericContainer("redis:7")
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("idem.admin.token") { "test-admin-token" }
        }
    }

    @Autowired
    private lateinit var provisioningService: TenantProvisioningService

    @Autowired
    private lateinit var apiKeyService: ApiKeyService

    @Autowired
    private lateinit var tenantJpaRepository: TenantJpaRepository

    @Autowired
    private lateinit var accountRepositoryAdapter: AccountRepositoryAdapter

    @Autowired
    private lateinit var transactionRepositoryAdapter: TransactionRepositoryAdapter

    private fun command(
        adminToken: String? = "test-admin-token",
        idempotencyKey: String = "idem-key-${java.util.UUID.randomUUID()}",
    ) = ProvisionTenantCommand(
        adminToken = adminToken,
        idempotencyKey = idempotencyKey,
        organizationName = "Acme Corp",
        contactEmail = "ops@acme.com",
    )

    @Test
    fun `provision persists organization identity and plan without clobbering either column set`() {
        val result = provisioningService.execute(command())

        assertTrue(result.isSuccess)
        val provisioned = result.getOrThrow()

        val row = tenantJpaRepository.findById(provisioned.tenantId.value).orElseThrow()
        assertEquals("Acme Corp", row.organizationName)
        assertEquals("ops@acme.com", row.contactEmail)
        assertEquals("CLOUD", row.plan)
        assertEquals(100, row.rateLimitPerSecond)
        assertEquals(1000, row.rateLimitPerMinute)
    }

    @Test
    fun `the returned raw key validates successfully with the default tenant scope set, excluding ADMIN and AGENTS_ROLLBACK`() {
        val provisioned = provisioningService.execute(command()).getOrThrow()

        val validated = apiKeyService.validate(provisioned.rawApiKey)

        assertNotNull(validated)
        assertEquals(provisioned.tenantId, validated.tenantId)
        assertEquals(ApiScope.entries.toSet() - setOf(ApiScope.ADMIN, ApiScope.AGENTS_ROLLBACK), validated.scopes)
    }

    @Test
    fun `provision fails closed with a missing or wrong admin token`() {
        assertIs<InvalidAdminToken>(provisioningService.execute(command(adminToken = null)).exceptionOrNull())
        assertIs<InvalidAdminToken>(provisioningService.execute(command(adminToken = "wrong")).exceptionOrNull())
    }

    @Test
    fun `a retry with the same Idempotency-Key replays the exact same tenant and raw key instead of double-provisioning`() {
        val key = "idem-key-${java.util.UUID.randomUUID()}"
        val countBefore = tenantJpaRepository.count()

        val first = provisioningService.execute(command(idempotencyKey = key)).getOrThrow()
        val second = provisioningService.execute(command(idempotencyKey = key)).getOrThrow()

        assertEquals(first, second)
        assertEquals(countBefore + 1, tenantJpaRepository.count(), "a retried Idempotency-Key must not create a second tenant row")
    }

    @Test
    fun `suspend on an unknown tenant fails, and suspending a real tenant blocks its key on the very next validate call`() {
        assertIs<TenantNotFound>(provisioningService.execute("test-admin-token", TenantId.generate()).exceptionOrNull())

        val provisioned = provisioningService.execute(command()).getOrThrow()
        assertNotNull(apiKeyService.validate(provisioned.rawApiKey))

        val suspendResult = provisioningService.execute("test-admin-token", provisioned.tenantId)
        assertTrue(suspendResult.isSuccess)

        assertNull(apiKeyService.validate(provisioned.rawApiKey))
    }

    @Test
    fun `data written before suspension remains queryable via the repository with a valid tenant context after suspend`() {
        val provisioned = provisioningService.execute(command()).getOrThrow()
        val tenantId = provisioned.tenantId

        val debit = AccountId.generate()
        val credit = AccountId.generate()
        val now = Instant.now()
        accountRepositoryAdapter.save(Account.create(debit, tenantId, "Debit", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountRepositoryAdapter.save(Account.create(credit, tenantId, "Credit", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))

        val txId = TransactionId.generate()
        val amount = MonetaryAmount.of("10.00")
        val lines =
            listOf(
                JournalLine(
                    id = UUID.randomUUID(),
                    transactionId = txId,
                    accountId = debit,
                    tenantId = tenantId,
                    entryType = EntryType.DEBIT,
                    monetaryEntry = FiatEntry(amount, FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = now,
                    createdBy = "test",
                ),
                JournalLine(
                    id = UUID.randomUUID(),
                    transactionId = txId,
                    accountId = credit,
                    tenantId = tenantId,
                    entryType = EntryType.CREDIT,
                    monetaryEntry = FiatEntry(amount, FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = now,
                    createdBy = "test",
                ),
            )
        transactionRepositoryAdapter.save(
            Transaction.create(
                id = txId,
                tenantId = tenantId,
                idempotencyKey = "data-intact-${txId.value}",
                lines = lines,
                occurredAt = now,
                createdAt = now,
                createdBy = "test",
            ),
        )

        provisioningService.execute("test-admin-token", tenantId).getOrThrow()

        // Suspension blocks auth (ApiKeyService.validate above), it must not touch stored rows —
        // a valid out-of-band tenant context (unrelated to the now-invalid API key) can still
        // read everything back exactly as written.
        assertNotNull(accountRepositoryAdapter.findById(debit, tenantId), "account row must survive suspension")
        val foundTx = transactionRepositoryAdapter.findById(txId, tenantId)
        assertNotNull(foundTx, "transaction row must survive suspension")
        assertEquals(2, foundTx.lines.size, "journal lines must survive suspension")
    }
}
