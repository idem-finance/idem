package finance.idem

import finance.idem.api.ledger.BalanceResponse
import finance.idem.api.ledger.JournalLineRequestDto
import finance.idem.api.ledger.MonetaryEntryRequestDto
import finance.idem.api.ledger.PostTransactionRequest
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class QueryBalanceE2ETest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val tenantId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private lateinit var assetAccountId: UUID
    private lateinit var liabilityAccountId: UUID

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE journal_lines, transactions, idempotency_keys, webhook_outbox, audit_log, accounts"
        )
        val asset = Account.create(
            id = AccountId.generate(), tenantId = TenantId(tenantId),
            name = "Balance Asset", currency = FiatCurrency.BRL, type = AccountType.ASSET,
            createdAt = Instant.now(), createdBy = "e2e-test",
        )
        val liability = Account.create(
            id = AccountId.generate(), tenantId = TenantId(tenantId),
            name = "Balance Liability", currency = FiatCurrency.BRL, type = AccountType.LIABILITY,
            createdAt = Instant.now(), createdBy = "e2e-test",
        )
        accountRepository.save(asset)
        accountRepository.save(liability)
        assetAccountId = asset.id.value
        liabilityAccountId = liability.id.value
    }

    private fun postPix(amount: String, idempotencyKey: String) {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Tenant-Id", tenantId.toString())
            set("Idempotency-Key", idempotencyKey)
        }
        restTemplate.postForEntity(
            "/api/v1/transactions",
            HttpEntity(
                PostTransactionRequest(
                    lines = listOf(
                        JournalLineRequestDto(
                            accountId = assetAccountId,
                            entryType = EntryType.DEBIT,
                            monetaryEntry = MonetaryEntryRequestDto.FiatEntryDto(
                                BigDecimal(amount), FiatCurrency.BRL, PaymentRail.PIX,
                            ),
                        ),
                        JournalLineRequestDto(
                            accountId = liabilityAccountId,
                            entryType = EntryType.CREDIT,
                            monetaryEntry = MonetaryEntryRequestDto.FiatEntryDto(
                                BigDecimal(amount), FiatCurrency.BRL, PaymentRail.PIX,
                            ),
                        ),
                    )
                ),
                headers,
            ),
            String::class.java,
        )
    }

    private fun getBalance(accountId: UUID) = restTemplate.exchange(
        "/api/v1/accounts/{id}/balance",
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("X-Tenant-Id", tenantId.toString()) }),
        BalanceResponse::class.java,
        accountId,
    )

    @Test
    fun `balance after debit reflects net credit to asset account`() {
        postPix("100.00", "bal-debit-${UUID.randomUUID()}")

        val response = getBalance(assetAccountId)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(0, response.body?.amount?.compareTo(BigDecimal("100")))
    }

    @Test
    fun `unknown account returns 404`() {
        val response = restTemplate.exchange(
            "/api/v1/accounts/{id}/balance",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("X-Tenant-Id", tenantId.toString()) }),
            String::class.java,
            UUID.randomUUID(),
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `asOf filtering excludes transactions posted after the cutoff`() {
        postPix("100.00", "asof-first-${UUID.randomUUID()}")

        Thread.sleep(10)
        val cutoff = Instant.now()
        Thread.sleep(10)

        postPix("50.00", "asof-second-${UUID.randomUUID()}")

        val response = restTemplate.exchange(
            "/api/v1/accounts/{id}/balance?asOf={asOf}",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("X-Tenant-Id", tenantId.toString()) }),
            BalanceResponse::class.java,
            assetAccountId, cutoff.toString(),
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(0, response.body?.amount?.compareTo(BigDecimal("100")))
    }
}
