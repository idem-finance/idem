package finance.idem.security

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
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class SecurityHttpE2ETest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var apiKeyService: ApiKeyService

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `request without API key returns 401`() {
        val response = restTemplate.getForEntity(
            "http://localhost:$port/api/v1/accounts/${UUID.randomUUID()}/balance",
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `request with wrong scope returns 403`() {
        val (rawKey, _) = apiKeyService.generate(TenantId.generate(), setOf(ApiScope.TRANSACTIONS_WRITE))
        val response = apiGet("/api/v1/accounts/${UUID.randomUUID()}/balance", rawKey)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `request with correct scope passes security — unknown account returns 404`() {
        val (rawKey, _) = apiKeyService.generate(TenantId.generate(), setOf(ApiScope.ACCOUNTS_READ))
        val response = apiGet("/api/v1/accounts/${UUID.randomUUID()}/balance", rawKey)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `cross-tenant isolation — tenant B key cannot read tenant A account`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        val accountId = UUID.randomUUID()

        insertAccount(accountId, tenantA)

        // tenantB's key with ACCOUNTS_READ — should return 404 because adapter filters by tenant_id
        val (rawKey, _) = apiKeyService.generate(tenantB, setOf(ApiScope.ACCOUNTS_READ))
        val response = apiGet("/api/v1/accounts/$accountId/balance", rawKey)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `tenant A can read their own account`() {
        val tenantA = TenantId.generate()
        val accountId = UUID.randomUUID()

        insertAccount(accountId, tenantA)

        val (rawKey, _) = apiKeyService.generate(tenantA, setOf(ApiScope.ACCOUNTS_READ))
        val response = apiGet("/api/v1/accounts/$accountId/balance", rawKey)

        // QueryBalanceService returns zero balance (not failure) when no journal lines exist, so 200 is expected.
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    private fun apiGet(path: String, apiKey: String) = restTemplate.exchange(
        "http://localhost:$port$path",
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("X-API-Key", apiKey) }),
        String::class.java,
    )

    private fun insertAccount(accountId: UUID, tenantId: TenantId) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn.prepareStatement(
                """INSERT INTO accounts(id, tenant_id, name, currency, type, created_by, created_at)
                   VALUES(?::UUID, ?::UUID, ?, ?, ?, ?, now())"""
            ).apply {
                setString(1, accountId.toString())
                setString(2, tenantId.value.toString())
                setString(3, "E2E Test Account")
                setString(4, "BRL")
                setString(5, "ASSET")
                setString(6, "e2e-test")
                executeUpdate()
            }
            conn.commit()
        }
    }
}
