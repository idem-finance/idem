package finance.idem.usage

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.TestcontainersConfiguration
import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.security.ApiKeyService
import finance.idem.postTransaction
import finance.idem.seedAccounts
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
import org.springframework.http.ResponseEntity
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
        val (debitId, creditId) = seedAccounts(dataSource, tenantId, "Usage E2E Account", "usage-e2e-test")
        val (apiKey, _) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ADMIN))

        val postResponse = postTransaction(restTemplate, port, apiKey, debitId, creditId, idempotencyKey = "usage-e2e-1")
        assertEquals(HttpStatus.CREATED, postResponse.statusCode)

        val usageResponse = getUsage(apiKey)
        assertEquals(HttpStatus.OK, usageResponse.statusCode)
        assertEquals(1L, transactionCountUsage(usageResponse))
    }

    @Test
    fun `tenant A's usage summary never includes tenant B's recorded events`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        val (debitA, creditA) = seedAccounts(dataSource, tenantA, "Usage E2E Account", "usage-e2e-test")
        val (debitB, creditB) = seedAccounts(dataSource, tenantB, "Usage E2E Account", "usage-e2e-test")
        val (keyA, _) = apiKeyService.generate(tenantA, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ADMIN))
        val (keyB, _) = apiKeyService.generate(tenantB, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.ADMIN))

        postTransaction(restTemplate, port, keyA, debitA, creditA, idempotencyKey = "usage-e2e-isolation-a-1")
        postTransaction(restTemplate, port, keyB, debitB, creditB, idempotencyKey = "usage-e2e-isolation-b-1")

        val usageResponseA = getUsage(keyA)

        assertEquals(
            1L,
            transactionCountUsage(usageResponseA),
            "tenant A's usage must reflect only its own transaction, never tenant B's",
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
}
