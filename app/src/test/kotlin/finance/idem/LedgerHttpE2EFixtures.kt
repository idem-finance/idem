package finance.idem

import finance.idem.core.TenantId
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.util.UUID
import javax.sql.DataSource

/**
 * Shared HTTP E2E test fixtures -- account seeding (bypassing the API, via raw SQL) and
 * transaction posting -- factored out of duplicated near-identical private helpers across
 * UsageHttpE2ETest, TenantProvisioningHttpE2ETest, ComplianceE2ETest, SecurityHttpE2ETest and
 * IdemClientE2ETest.
 */
fun insertAccount(
    dataSource: DataSource,
    accountId: UUID,
    tenantId: TenantId,
    name: String,
    currency: String,
    type: String,
    createdBy: String,
) {
    dataSource.connection.use { conn ->
        conn.autoCommit = false
        conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
        conn
            .prepareStatement(
                """INSERT INTO accounts(id, tenant_id, name, currency, type, created_by, created_at)
                   VALUES(?::UUID, ?::UUID, ?, ?, ?, ?, now())""",
            ).apply {
                setString(1, accountId.toString())
                setString(2, tenantId.value.toString())
                setString(3, name)
                setString(4, currency)
                setString(5, type)
                setString(6, createdBy)
                executeUpdate()
            }
        conn.commit()
    }
}

/** Returns (debitAccountId, creditAccountId), both seeded directly via SQL under [tenantId]. */
fun seedAccounts(
    dataSource: DataSource,
    tenantId: TenantId,
    label: String,
    createdBy: String,
    currency: String = "USD",
): Pair<UUID, UUID> {
    val debitId = UUID.randomUUID()
    val creditId = UUID.randomUUID()
    insertAccount(dataSource, debitId, tenantId, label, currency, "ASSET", createdBy)
    insertAccount(dataSource, creditId, tenantId, label, currency, "LIABILITY", createdBy)
    return debitId to creditId
}

fun postTransaction(
    restTemplate: TestRestTemplate,
    port: Int,
    apiKey: String,
    body: String,
    idempotencyKey: String,
): ResponseEntity<String> {
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

fun postTransaction(
    restTemplate: TestRestTemplate,
    port: Int,
    apiKey: String,
    debitAccountId: UUID,
    creditAccountId: UUID,
    idempotencyKey: String,
    amount: String = "10.00",
): ResponseEntity<String> {
    val body =
        """
        {
          "lines": [
            { "accountId": "$debitAccountId", "entryType": "DEBIT",
              "monetaryEntry": { "type": "FIAT", "amount": "$amount", "currency": "USD", "rail": "ACH" } },
            { "accountId": "$creditAccountId", "entryType": "CREDIT",
              "monetaryEntry": { "type": "FIAT", "amount": "$amount", "currency": "USD", "rail": "ACH" } }
          ]
        }
        """.trimIndent()
    return postTransaction(restTemplate, port, apiKey, body, idempotencyKey)
}
