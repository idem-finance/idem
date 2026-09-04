package finance.idem.usage

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.TestcontainersConfiguration
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Proves usage metering end to end over real HTTP (idem#275): posting a transaction is
 * visible in GET /api/v1/usage/current-period, and one tenant's usage summary never leaks
 * another tenant's recorded events -- there is no existing coverage of usage visibility
 * isolation prior to this test (UsageMetricRepositoryAdapterIntegrationTest exercises the
 * repository directly, single-tenant only).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class UsageHttpE2ETest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var apiKeyService: ApiKeyService

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `posting a transaction increments TRANSACTION_COUNT visible via GET usage current-period`() {
        val tenantId = TenantId.generate()
        val (debitId, creditId) = seedAccounts(tenantId)
        val (apiKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ADMIN))

        val postResponse = postTransaction(apiKey, debitId, creditId, idempotencyKey = "usage-e2e-1")
        assertEquals(HttpStatus.CREATED, postResponse.statusCode)

        val usageResponse = getUsage(apiKey)
        assertEquals(HttpStatus.OK, usageResponse.statusCode)
        assertEquals(1L, transactionCountUsage(usageResponse))
    }

    @Test
    fun `tenant A's usage summary never includes tenant B's recorded events`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        val (debitA, creditA) = seedAccounts(tenantA)
        val (debitB, creditB) = seedAccounts(tenantB)
        val (keyA, _) = apiKeyService.generate(tenantA, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ADMIN))
        val (keyB, _) = apiKeyService.generate(tenantB, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ADMIN))

        postTransaction(keyA, debitA, creditA, idempotencyKey = "usage-e2e-isolation-a-1")
        postTransaction(keyB, debitB, creditB, idempotencyKey = "usage-e2e-isolation-b-1")
        postTransaction(keyB, debitB, creditB, idempotencyKey = "usage-e2e-isolation-b-2")
        postTransaction(keyB, debitB, creditB, idempotencyKey = "usage-e2e-isolation-b-3")

        val usageResponseA = getUsage(keyA)

        assertEquals(
            1L,
            transactionCountUsage(usageResponseA),
            "tenant A's usage must reflect only its own transaction, never tenant B's three",
        )
    }

    private fun transactionCountUsage(response: ResponseEntity<String>): Long {
        val metrics = objectMapper.readTree(response.body!!).get("metrics")
        val entry = metrics.first { it.get("metricType").asText() == "TRANSACTION_COUNT" }
        return entry.get("usage").asLong()
    }

    private fun getUsage(apiKey: String): ResponseEntity<String> =
        restTemplate.exchange(
            "http://localhost:$port/api/v1/usage/current-period",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("X-API-Key", apiKey) }),
            String::class.java,
        )

    private fun postTransaction(
        apiKey: String,
        debitAccountId: UUID,
        creditAccountId: UUID,
        idempotencyKey: String,
    ): ResponseEntity<String> {
        val body =
            """
            {
              "lines": [
                { "accountId": "$debitAccountId", "entryType": "DEBIT",
                  "monetaryEntry": { "type": "FIAT", "amount": "10.00", "currency": "USD", "rail": "ACH" } },
                { "accountId": "$creditAccountId", "entryType": "CREDIT",
                  "monetaryEntry": { "type": "FIAT", "amount": "10.00", "currency": "USD", "rail": "ACH" } }
              ]
            }
            """.trimIndent()
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-API-Key", apiKey)
                set("Idempotency-Key", idempotencyKey)
            }
        return restTemplate.postForEntity(
            "http://localhost:$port/api/v1/transactions",
            HttpEntity(body, headers),
            String::class.java,
        )
    }

    /** Returns (debitAccountId, creditAccountId), both seeded directly via SQL under [tenantId]. */
    private fun seedAccounts(tenantId: TenantId): Pair<UUID, UUID> {
        val debitId = UUID.randomUUID()
        val creditId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            listOf(debitId to "ASSET", creditId to "LIABILITY").forEach { (id, type) ->
                conn
                    .prepareStatement(
                        """INSERT INTO accounts(id, tenant_id, name, currency, type, created_by, created_at)
                       VALUES(?::UUID, ?::UUID, ?, ?, ?, ?, now())""",
                    ).apply {
                        setString(1, id.toString())
                        setString(2, tenantId.value.toString())
                        setString(3, "Usage E2E Account")
                        setString(4, "USD")
                        setString(5, type)
                        setString(6, "usage-e2e-test")
                        executeUpdate()
                    }
            }
            conn.commit()
        }
        return debitId to creditId
    }
}
