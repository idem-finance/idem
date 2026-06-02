package finance.idem

import finance.idem.api.ledger.ErrorResponse
import finance.idem.api.ledger.JournalLineRequestDto
import finance.idem.api.ledger.MonetaryEntryRequestDto
import finance.idem.api.ledger.PostTransactionRequest
import finance.idem.api.ledger.PostTransactionResponse
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class PostTransactionE2ETest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private lateinit var assetAccountId: UUID
    private lateinit var liabilityAccountId: UUID

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE journal_lines, transactions, idempotency_keys, webhook_outbox, audit_log, accounts"
        )
        val asset = Account.create(
            id = AccountId.generate(), tenantId = TenantId(tenantId),
            name = "PIX Asset", currency = FiatCurrency.BRL, type = AccountType.ASSET,
            createdAt = Instant.now(), createdBy = "e2e-test",
        )
        val liability = Account.create(
            id = AccountId.generate(), tenantId = TenantId(tenantId),
            name = "PIX Liability", currency = FiatCurrency.BRL, type = AccountType.LIABILITY,
            createdAt = Instant.now(), createdBy = "e2e-test",
        )
        accountRepository.save(asset)
        accountRepository.save(liability)
        assetAccountId = asset.id.value
        liabilityAccountId = liability.id.value
    }

    private fun fiatLine(accountId: UUID, entryType: EntryType, amount: String = "100.00") =
        JournalLineRequestDto(
            accountId = accountId,
            entryType = entryType,
            monetaryEntry = MonetaryEntryRequestDto.FiatEntryDto(
                amount = BigDecimal(amount),
                currency = FiatCurrency.BRL,
                rail = PaymentRail.PIX,
            ),
        )

    private fun pixRequest(
        debitId: UUID = assetAccountId,
        creditId: UUID = liabilityAccountId,
        amount: String = "100.00",
    ) = PostTransactionRequest(
        lines = listOf(
            fiatLine(debitId, EntryType.DEBIT, amount),
            fiatLine(creditId, EntryType.CREDIT, amount),
        )
    )

    private fun requestHeaders(idempotencyKey: String) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Tenant-Id", tenantId.toString())
        set("Idempotency-Key", idempotencyKey)
    }

    @Test
    fun `balanced PIX transfer returns 201 with transactionId`() {
        val response = restTemplate.postForEntity(
            "/api/v1/transactions",
            HttpEntity(pixRequest(), requestHeaders("pix-happy-path")),
            PostTransactionResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.body?.transactionId)
    }

    @Test
    fun `duplicate idempotency key returns same transactionId and stores only one row`() {
        val key = "idem-e2e-${UUID.randomUUID()}"
        val headers = requestHeaders(key)

        val first = restTemplate.postForEntity(
            "/api/v1/transactions",
            HttpEntity(pixRequest(), headers),
            PostTransactionResponse::class.java,
        )
        val second = restTemplate.postForEntity(
            "/api/v1/transactions",
            HttpEntity(pixRequest(), headers),
            PostTransactionResponse::class.java,
        )

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.CREATED, second.statusCode)
        assertEquals(first.body?.transactionId, second.body?.transactionId)
        assertEquals(
            1,
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Int::class.java),
        )
    }

    @Test
    fun `unbalanced request returns 422`() {
        val response = restTemplate.postForEntity(
            "/api/v1/transactions",
            HttpEntity(
                pixRequest().copy(
                    lines = listOf(
                        fiatLine(assetAccountId, EntryType.DEBIT, "100.00"),
                        fiatLine(liabilityAccountId, EntryType.CREDIT, "90.00"),
                    )
                ),
                requestHeaders("unbalanced-${UUID.randomUUID()}"),
            ),
            ErrorResponse::class.java,
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
    }

    @Test
    fun `missing Idempotency-Key header returns 400`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Tenant-Id", tenantId.toString())
        }

        val response = restTemplate.postForEntity(
            "/api/v1/transactions",
            HttpEntity(pixRequest(), headers),
            String::class.java,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
