package finance.idem.tenant

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.TestcontainersConfiguration
import finance.idem.core.TenantId
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
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ADMIN_TOKEN = "test-only-e2e-admin-token"

/**
 * Proves tenant provisioning end to end over real HTTP (idem#275): a provisioned tenant gets
 * the correct defaults, its raw API key authenticates immediately (no propagation delay), and
 * suspension rejects the key on the very next call while leaving previously-written ledger
 * data untouched. Complements the service-layer TenantProvisioningIntegrationTest, which
 * already covers defaults/idempotency/suspend-blocks-validate but not the HTTP surface or
 * data survival after suspension.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "idem.admin.token=$ADMIN_TOKEN",
        "idem.audit.hmac-secret=test-only-insecure-hmac-secret",
        "idem.chain.alchemy-webhook-signing-key=unused-in-tenant-provisioning-test",
    ],
)
class TenantProvisioningHttpE2ETest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `provisioning a tenant returns 201 with the correct default shape`() {
        val response = provisionTenant()

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val json = objectMapper.readTree(response.body!!)
        assertTrue(json.get("tenantId").asText().isNotBlank())
        assertTrue(json.get("apiKey").asText().startsWith("sk_live_"), "raw key must be returned once, in the sk_live_ format")
        assertTrue(json.get("dashboardUrl").asText().contains(json.get("tenantId").asText()), "dashboard URL must reference the new tenant")
    }

    @Test
    fun `the returned raw key authenticates immediately against a real customer-facing endpoint`() {
        val (_, apiKey) = provisionTenantAndExtract()

        val response = apiGet("/api/v1/accounts", apiKey)

        assertEquals(HttpStatus.OK, response.statusCode, "a freshly provisioned key must work on the very next request, no propagation delay")
    }

    @Test
    fun `suspending a tenant rejects its key on the next call while leaving previously-written data intact`() {
        val (tenantId, apiKey) = provisionTenantAndExtract()
        val (debitId, creditId) = seedAccounts(tenantId)

        val postResponse = postTransaction(apiKey, debitId, creditId, idempotencyKey = "tenant-provisioning-e2e-1")
        assertEquals(HttpStatus.CREATED, postResponse.statusCode)

        val suspendResponse = suspendTenant(tenantId)
        assertEquals(HttpStatus.OK, suspendResponse.statusCode)

        val afterSuspend = apiGet("/api/v1/accounts", apiKey)
        assertEquals(HttpStatus.UNAUTHORIZED, afterSuspend.statusCode, "the suspended tenant's key must be rejected on the very next call")

        assertEquals(
            1L,
            countTransactions(tenantId),
            "suspension must block auth without deleting or mutating previously-written ledger rows",
        )
    }

    private fun provisionTenantAndExtract(): Pair<TenantId, String> {
        val response = provisionTenant()
        val json = objectMapper.readTree(response.body!!)
        return TenantId(UUID.fromString(json.get("tenantId").asText())) to json.get("apiKey").asText()
    }

    private fun provisionTenant(): ResponseEntity<String> {
        val body =
            """{"organizationName": "idem#275 E2E Corp", "contactEmail": "e2e-${UUID.randomUUID()}@example.com"}"""
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-Internal-Admin-Token", ADMIN_TOKEN)
                set("Idempotency-Key", "tenant-provisioning-e2e-${UUID.randomUUID()}")
            }
        return restTemplate.postForEntity(
            "http://localhost:$port/internal/admin/tenants",
            HttpEntity(body, headers),
            String::class.java,
        )
    }

    private fun suspendTenant(tenantId: TenantId): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("X-Internal-Admin-Token", ADMIN_TOKEN) }
        return restTemplate.exchange(
            "http://localhost:$port/internal/admin/tenants/${tenantId.value}/suspend",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            String::class.java,
        )
    }

    private fun apiGet(
        path: String,
        apiKey: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "http://localhost:$port$path",
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

    /** Returns (debitAccountId, creditAccountId), seeded directly via SQL under [tenantId] -- a suspended tenant's key cannot write, so seeding must bypass the API. */
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
                        setString(3, "Provisioning E2E Account")
                        setString(4, "USD")
                        setString(5, type)
                        setString(6, "provisioning-e2e-test")
                        executeUpdate()
                    }
            }
            conn.commit()
        }
        return debitId to creditId
    }

    /** Reads the transaction count directly, under a fresh out-of-band tenant context -- not the now-suspended key. */
    private fun countTransactions(tenantId: TenantId): Long {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM transactions WHERE tenant_id = '${tenantId.value}'::uuid").use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }
}
