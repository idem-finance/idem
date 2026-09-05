package finance.idem.sdk

import finance.idem.TestcontainersConfiguration
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.security.ApiKeyService
import finance.idem.insertAccount
import finance.idem.sdk.exception.ApiException
import finance.idem.sdk.model.EntryType
import finance.idem.sdk.model.FiatCurrency
import finance.idem.sdk.model.FiatEntryRequest
import finance.idem.sdk.model.FiatEntryResponse
import finance.idem.sdk.model.JournalLineRequest
import finance.idem.sdk.model.PaymentRail
import finance.idem.sdk.model.PostTransactionRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["idem.audit.hmac-secret=test-only-insecure-hmac-secret"])
class IdemClientE2ETest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var apiKeyService: ApiKeyService

    @Autowired
    lateinit var dataSource: DataSource

    private fun idemClient(apiKey: String) = IdemClient(baseUrl = "http://localhost:$port", apiKey = apiKey)

    private fun countTransactionsById(
        tenantId: TenantId,
        transactionId: UUID,
    ): Int {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            val count =
                conn
                    .prepareStatement(
                        "SELECT COUNT(*) FROM transactions WHERE tenant_id = ?::UUID AND id = ?::UUID",
                    ).use { stmt ->
                        stmt.setString(1, tenantId.value.toString())
                        stmt.setString(2, transactionId.toString())
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
            conn.commit()
            return count
        }
    }

    private fun countTransactionsByIdempotencyKey(
        tenantId: TenantId,
        idempotencyKey: String,
    ): Int {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            val count =
                conn
                    .prepareStatement(
                        "SELECT COUNT(*) FROM transactions WHERE tenant_id = ?::UUID AND idempotency_key = ?",
                    ).use { stmt ->
                        stmt.setString(1, tenantId.value.toString())
                        stmt.setString(2, idempotencyKey)
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
            conn.commit()
            return count
        }
    }

    private fun balancedPixRequest(
        debitAccount: UUID,
        creditAccount: UUID,
        amount: BigDecimal = BigDecimal("100.00"),
    ) = PostTransactionRequest(
        lines =
            listOf(
                JournalLineRequest(
                    accountId = debitAccount,
                    entryType = EntryType.DEBIT,
                    monetaryEntry = FiatEntryRequest(amount = amount, currency = FiatCurrency.BRL, rail = PaymentRail.PIX),
                ),
                JournalLineRequest(
                    accountId = creditAccount,
                    entryType = EntryType.CREDIT,
                    monetaryEntry = FiatEntryRequest(amount = amount, currency = FiatCurrency.BRL, rail = PaymentRail.PIX),
                ),
            ),
    )

    @Test
    fun `postTransaction commits a balanced PIX transaction and returns a transactionId`() =
        runBlocking {
            val tenantId = TenantId.generate()
            val (rawKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ACCOUNTS_READ))
            val cashAccount = UUID.randomUUID()
            val payableAccount = UUID.randomUUID()
            insertAccount(dataSource, cashAccount, tenantId, "Cash", "BRL", "ASSET", "sdk-e2e-test")
            insertAccount(dataSource, payableAccount, tenantId, "Customer Payable", "BRL", "LIABILITY", "sdk-e2e-test")

            val client = idemClient(rawKey)
            val response = client.postTransaction(balancedPixRequest(cashAccount, payableAccount))

            assertEquals(1, countTransactionsById(tenantId, response.transactionId))
        }

    @Test
    fun `postTransaction with the same Idempotency-Key replays the same transactionId and writes one row`() =
        runBlocking {
            val tenantId = TenantId.generate()
            val (rawKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ACCOUNTS_READ))
            val cashAccount = UUID.randomUUID()
            val payableAccount = UUID.randomUUID()
            insertAccount(dataSource, cashAccount, tenantId, "Cash", "BRL", "ASSET", "sdk-e2e-test")
            insertAccount(dataSource, payableAccount, tenantId, "Customer Payable", "BRL", "LIABILITY", "sdk-e2e-test")

            val client = idemClient(rawKey)
            val idempotencyKey = "fixed-key-${UUID.randomUUID()}"
            val first = client.postTransaction(balancedPixRequest(cashAccount, payableAccount), idempotencyKey)
            val second = client.postTransaction(balancedPixRequest(cashAccount, payableAccount), idempotencyKey)

            assertEquals(first.transactionId, second.transactionId)
            assertEquals(1, countTransactionsByIdempotencyKey(tenantId, idempotencyKey))
        }

    @Test
    fun `getBalance reflects a posted credit on the liability account`() =
        runBlocking {
            val tenantId = TenantId.generate()
            val (rawKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ACCOUNTS_READ))
            val cashAccount = UUID.randomUUID()
            val payableAccount = UUID.randomUUID()
            insertAccount(dataSource, cashAccount, tenantId, "Cash", "BRL", "ASSET", "sdk-e2e-test")
            insertAccount(dataSource, payableAccount, tenantId, "Customer Payable", "BRL", "LIABILITY", "sdk-e2e-test")

            val client = idemClient(rawKey)
            client.postTransaction(balancedPixRequest(cashAccount, payableAccount))

            val balance = client.getBalance(payableAccount.toString())

            assertEquals(0, BigDecimal("100.00").compareTo(balance.amount))
            assertEquals(FiatCurrency.BRL, balance.currency)
            assertEquals(EntryType.CREDIT, balance.normalBalance)
        }

    @Test
    fun `listEntries returns journal lines only for the requested account`() =
        runBlocking {
            val tenantId = TenantId.generate()
            val (rawKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ACCOUNTS_READ))
            val cashAccount = UUID.randomUUID()
            val payableAccount = UUID.randomUUID()
            insertAccount(dataSource, cashAccount, tenantId, "Cash", "BRL", "ASSET", "sdk-e2e-test")
            insertAccount(dataSource, payableAccount, tenantId, "Customer Payable", "BRL", "LIABILITY", "sdk-e2e-test")

            val client = idemClient(rawKey)
            client.postTransaction(balancedPixRequest(cashAccount, payableAccount))

            val payableEntries = client.listEntries(payableAccount.toString())
            assertEquals(1, payableEntries.entries.size)
            val creditEntry = payableEntries.entries[0]
            assertEquals(EntryType.CREDIT, creditEntry.type)
            val creditMonetary = creditEntry.monetary as FiatEntryResponse
            assertEquals(0, BigDecimal("100.00").compareTo(creditMonetary.amount))
            assertEquals(FiatCurrency.BRL, creditMonetary.currency)
            assertEquals(PaymentRail.PIX, creditMonetary.rail)

            val cashEntries = client.listEntries(cashAccount.toString())
            assertEquals(1, cashEntries.entries.size)
            assertEquals(EntryType.DEBIT, cashEntries.entries[0].type)
        }

    @Test
    fun `getStatement opening and closing balances match the posted movement`() =
        runBlocking {
            val tenantId = TenantId.generate()
            val (rawKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ACCOUNTS_READ))
            val cashAccount = UUID.randomUUID()
            val payableAccount = UUID.randomUUID()
            insertAccount(dataSource, cashAccount, tenantId, "Cash", "BRL", "ASSET", "sdk-e2e-test")
            insertAccount(dataSource, payableAccount, tenantId, "Customer Payable", "BRL", "LIABILITY", "sdk-e2e-test")

            val client = idemClient(rawKey)
            val from = Instant.now().minus(1, ChronoUnit.HOURS)
            client.postTransaction(balancedPixRequest(cashAccount, payableAccount))
            val to = Instant.now().plus(1, ChronoUnit.HOURS)

            val statement = client.getStatement(payableAccount.toString(), from, to)

            assertEquals(0, BigDecimal.ZERO.compareTo(statement.openingBalance))
            assertEquals(0, BigDecimal("100.00").compareTo(statement.closingBalance))
            assertEquals(1, statement.movements.size)
            assertEquals(EntryType.CREDIT, statement.movements[0].type)
            assertEquals(0, BigDecimal("100.00").compareTo(statement.movements[0].amount))
        }

    @Test
    fun `request with an unknown API key throws ApiException with status 401`() =
        runBlocking {
            val client = idemClient("sk_live_doesnotexist0000000000")

            val exception =
                assertFailsWith<ApiException> {
                    client.getBalance(UUID.randomUUID().toString())
                }
            assertEquals(401, exception.statusCode)
        }

    @Test
    fun `postTransaction without TRANSACTIONS_WRITE scope throws ApiException with status 403`() =
        runBlocking {
            val tenantId = TenantId.generate()
            val (rawKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.ACCOUNTS_READ))
            val cashAccount = UUID.randomUUID()
            val payableAccount = UUID.randomUUID()
            insertAccount(dataSource, cashAccount, tenantId, "Cash", "BRL", "ASSET", "sdk-e2e-test")
            insertAccount(dataSource, payableAccount, tenantId, "Customer Payable", "BRL", "LIABILITY", "sdk-e2e-test")

            val client = idemClient(rawKey)

            val exception =
                assertFailsWith<ApiException> {
                    client.postTransaction(balancedPixRequest(cashAccount, payableAccount))
                }
            assertEquals(403, exception.statusCode)
        }

    @Test
    fun `getBalance for unknown account maps 404 to ApiException carrying traceId from X-Idem-Trace-Id header`() =
        runBlocking {
            val (rawKey, _) = apiKeyService.generate(TenantId.generate(), setOf(ApiScope.ACCOUNTS_READ))
            val client = idemClient(rawKey)

            val exception =
                assertFailsWith<ApiException> {
                    client.getBalance(UUID.randomUUID().toString())
                }

            assertEquals(404, exception.statusCode)
            assertEquals("NOT_FOUND", exception.errorCode)
            assertNotNull(exception.traceId)
            UUID.fromString(exception.traceId)
        }
}
