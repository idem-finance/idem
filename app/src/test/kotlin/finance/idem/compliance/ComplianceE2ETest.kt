package finance.idem.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.TestcontainersConfiguration
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.compliance.LegalPerson
import finance.idem.core.compliance.NaturalPerson
import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.compliance.VaspTransferParty
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.monetary.OnChainEntry
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.security.ApiKeyService
import jakarta.persistence.EntityManager
import org.hibernate.Session
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
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AUDIT_HMAC_SECRET = "test-only-insecure-compliance-hmac-secret"

/**
 * Full-stack coverage for #179 — a real Spring context, real Postgres, a transaction posted
 * through the real [PostTransactionUseCase], and the compliance/audit trail it produces read
 * back out through the real HTTP `GET /api/v1/compliance/audit-export` endpoint with a real
 * API key and an independently-recomputed HMAC.
 *
 * `OnChainEntryDto` (api module) has no `travelRuleData` field, so the `Valid` branch of
 * `TravelRuleValidator` cannot be driven through the public HTTP request body today — that
 * scenario calls the real, Spring-wired [PostTransactionUseCase] bean directly instead of
 * going through `POST /api/v1/transactions`. `Exempt` and `MissingData` *are* reachable over
 * HTTP and are exercised that way.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "idem.audit.hmac-secret=$AUDIT_HMAC_SECRET",
    ],
)
class ComplianceE2ETest {
    @Autowired lateinit var restTemplate: TestRestTemplate

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var apiKeyService: ApiKeyService

    @Autowired lateinit var accountRepository: AccountRepositoryAdapter

    @Autowired lateinit var postTransactionUseCase: PostTransactionUseCase

    @Autowired lateinit var webhookOutboxAdapter: WebhookOutboxRepositoryAdapter

    @Autowired lateinit var entityManager: EntityManager

    @Autowired lateinit var objectMapper: ObjectMapper

    // ── Scenario 1: below threshold -> Exempt, no compliance queue entry ──

    @Test
    fun `on-chain entry below travel rule threshold is exempt from the compliance queue`() {
        val f = fixture(1)
        val (apiKey, _) = apiKeyService.generate(f.tenantId, setOf(ApiScope.TRANSACTIONS_WRITE))

        val response = postOnChainTransaction(apiKey, f, n = 1, amount = "10.000000", idempotencyKey = "compliance-e2e-1")

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertTrue(findComplianceQueueRows(f.tenantId).isEmpty())
        assertTrue(findOutboxEventTypes(f.tenantId).none { it == "compliance.travel_rule_required" })
    }

    // ── Scenario 2: above threshold, no data -> MissingData, queued + exportable with a verifiable HMAC ──

    @Test
    fun `on-chain entry above threshold with no travel rule data is queued and audit-exportable with a verifiable HMAC`() {
        val f = fixture(2)
        val (apiKey, _) = apiKeyService.generate(f.tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.COMPLIANCE_EXPORT))
        val txHash = txHashFor(2)
        val before = Instant.now().minusSeconds(5)

        val response = postOnChainTransaction(apiKey, f, n = 2, amount = "1500.000000", idempotencyKey = "compliance-e2e-2")
        assertEquals(HttpStatus.CREATED, response.statusCode)
        val transactionId = extractTransactionId(response)
        val after = Instant.now().plusSeconds(5)

        // PostTransactionService evaluates the Travel Rule check per journal line, not per
        // transaction — a two-sided on-chain transfer (identical OnChainEntry on both the
        // debit and credit line) therefore produces one compliance_queue row per line.
        val queueRows = findComplianceQueueRows(f.tenantId)
        assertEquals(2, queueRows.size)
        assertTrue(queueRows.all { it.reason == "MISSING_DATA" })
        assertTrue(queueRows.all { it.txHash == txHash })
        assertTrue(queueRows.all { it.chainId == "EVM" })

        assertTrue(findOutboxEventTypes(f.tenantId).contains("compliance.travel_rule_required"))

        // audit_log.payload is stored as jsonb, which Postgres re-canonicalizes on read-back
        // (different whitespace than Jackson's original serialization) — rehashing the
        // stored text would not reproduce the original HMAC input. Instead, reconstruct the
        // exact payload AuditRepositoryAdapter.save() serialized and signed (known from its
        // source: transactionId/action/createdBy, with a null AgentContext since this
        // transaction was posted over HTTP without one) and recompute the HMAC from that.
        val auditRow = findAuditLogRecord(f.tenantId, transactionId)
        assertNotNull(auditRow)
        val expectedPayload =
            objectMapper.writeValueAsString(
                linkedMapOf(
                    "transactionId" to transactionId.toString(),
                    "action" to "POST_TRANSACTION",
                    "agentId" to null,
                    "sessionId" to null,
                    "workflowPlanId" to null,
                    "intent" to null,
                    "createdBy" to "api",
                ),
            )
        val expectedHmac = hmacBase64(AUDIT_HMAC_SECRET, expectedPayload)
        assertEquals(expectedHmac, auditRow.hmac)

        val exportResponse = exportAudit(apiKey, before, after)
        assertEquals(HttpStatus.OK, exportResponse.statusCode)
        assertTrue(
            exportResponse.headers.contentType
                .toString()
                .startsWith("application/x-ndjson"),
        )
        assertNotNull(exportResponse.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))

        val record =
            exportResponse.body!!
                .trim()
                .lines()
                .map { objectMapper.readTree(it) }
                .first { it.get("entityId").asText() == transactionId.toString() }
        assertEquals("TRANSACTION", record.get("entityType").asText())
        assertEquals(expectedHmac, record.get("hmacSignature").asText())
    }

    // ── Scenario 3: above threshold, complete IVMS 101 data -> Valid, LGPD retention scheduled ──

    @Test
    fun `on-chain entry above threshold with complete travel rule data schedules LGPD retention and skips the compliance queue`() {
        val f = fixture(3)
        val travelRuleData =
            TravelRuleData(
                transferId = "tr-3",
                originator =
                    VaspTransferParty(
                        naturalPerson =
                            NaturalPerson(
                                firstName = "Ana",
                                lastName = "Silva",
                                dateOfBirth = LocalDate.of(1990, 1, 1),
                                nationalId = "12345678900",
                                country = "BR",
                            ),
                        accountNumber = "acct-orig-3",
                        vaspDid = "did:example:originator-3",
                    ),
                beneficiary =
                    VaspTransferParty(
                        legalPerson = LegalPerson(name = "Acme Corp", registrationNumber = "123456", country = "US"),
                        accountNumber = "acct-benef-3",
                        vaspDid = "did:example:beneficiary-3",
                    ),
                transferAmount = MonetaryAmount.of("1500.000000"),
                transferAsset = StablecoinToken.USDC,
            )
        val onChainEntry =
            OnChainEntry(
                amount = MonetaryAmount.of("1500.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                txHash = txHashFor(3),
                blockNumber = 19_000_000L,
                walletAddress = walletFor(3),
                tokenContract = contractFor(3),
                travelRuleData = travelRuleData,
            )
        val cmd =
            PostTransactionCommand(
                tenantId = f.tenantId,
                idempotencyKey = "compliance-e2e-3",
                lines =
                    listOf(
                        JournalLineRequest(f.debitAccountId, EntryType.DEBIT, onChainEntry),
                        JournalLineRequest(f.creditAccountId, EntryType.CREDIT, onChainEntry),
                    ),
                createdBy = "compliance-e2e-test",
            )

        val result = postTransactionUseCase.execute(cmd)

        assertTrue(result.isSuccess)
        assertTrue(findComplianceQueueRows(f.tenantId).isEmpty())

        // Same per-line evaluation as the MissingData scenario above — one retention row
        // per journal line, both referencing the same transferId.
        val retentionRows = findLgpdRetentionRows(f.tenantId)
        assertEquals(2, retentionRows.size)
        assertTrue(retentionRows.all { it.entityType == "TravelRuleData" })
        assertTrue(retentionRows.all { it.entityId == travelRuleData.transferId })
        assertTrue(
            retentionRows.all {
                it.deletionDueAt.atZone(ZoneOffset.UTC).year == Instant.now().atZone(ZoneOffset.UTC).year + 7
            },
        )
    }

    // ── Scenario 4: audit export scope enforcement + date range filtering, end to end ──

    @Test
    fun `audit export enforces COMPLIANCE_EXPORT scope and date range filtering`() {
        val f = fixture(4)
        val (writeOnlyKey, _) = apiKeyService.generate(f.tenantId, setOf(ApiScope.TRANSACTIONS_WRITE))
        val (exportKey, _) = apiKeyService.generate(f.tenantId, setOf(ApiScope.TRANSACTIONS_WRITE, ApiScope.COMPLIANCE_EXPORT))

        val before = Instant.now().minusSeconds(5)
        val postResponse = postFiatTransaction(writeOnlyKey, f, idempotencyKey = "compliance-e2e-4")
        assertEquals(HttpStatus.CREATED, postResponse.statusCode)
        val after = Instant.now().plusSeconds(5)

        val forbidden = exportAudit(writeOnlyKey, before, after)
        assertEquals(HttpStatus.FORBIDDEN, forbidden.statusCode)

        val included = exportAudit(exportKey, before, after)
        assertEquals(HttpStatus.OK, included.statusCode)
        assertTrue(
            included.body!!
                .trim()
                .lines()
                .isNotEmpty(),
        )

        val excluded = exportAudit(exportKey, after.plusSeconds(3600), after.plusSeconds(7200))
        assertEquals(HttpStatus.OK, excluded.statusCode)
        assertTrue(excluded.body.isNullOrEmpty())
    }

    // ── Fixtures & helpers ──────────────────────────────────────────────────────────

    private data class Fixture(
        val tenantId: TenantId,
        val debitAccountId: AccountId,
        val creditAccountId: AccountId,
    )

    private fun fixture(n: Int): Fixture {
        val tenantId = TenantId.generate()
        val now = Instant.now()
        val debitAccountId = AccountId.generate()
        val creditAccountId = AccountId.generate()
        accountRepository.save(
            Account.create(debitAccountId, tenantId, "Custody-$n", FiatCurrency.USD, AccountType.ASSET, now, "compliance-e2e-test"),
        )
        accountRepository.save(
            Account.create(
                creditAccountId,
                tenantId,
                "Customer-$n",
                FiatCurrency.USD,
                AccountType.LIABILITY,
                now,
                "compliance-e2e-test",
            ),
        )
        return Fixture(tenantId, debitAccountId, creditAccountId)
    }

    private fun walletFor(n: Int) = "0x" + "%040x".format(BigInteger.valueOf(0xCCCC0000L + n))

    private fun contractFor(n: Int) = "0x" + "%040x".format(BigInteger.valueOf(0xDDDD0000L + n))

    private fun txHashFor(n: Int) = "0x" + "$n".repeat(64)

    private fun postOnChainTransaction(
        apiKey: String,
        f: Fixture,
        n: Int,
        amount: String,
        idempotencyKey: String,
    ): ResponseEntity<String> {
        val entry =
            """
            {
              "type": "ONCHAIN",
              "amount": "$amount",
              "token": "USDC",
              "chainId": "EVM",
              "txHash": "${txHashFor(n)}",
              "blockNumber": 19000000,
              "walletAddress": "${walletFor(n)}",
              "tokenContract": "${contractFor(n)}"
            }
            """.trimIndent()
        val body =
            """
            {
              "lines": [
                { "accountId": "${f.debitAccountId.value}", "entryType": "DEBIT", "monetaryEntry": $entry },
                { "accountId": "${f.creditAccountId.value}", "entryType": "CREDIT", "monetaryEntry": $entry }
              ]
            }
            """.trimIndent()
        return postTransaction(apiKey, body, idempotencyKey)
    }

    private fun postFiatTransaction(
        apiKey: String,
        f: Fixture,
        idempotencyKey: String,
    ): ResponseEntity<String> {
        val body =
            """
            {
              "lines": [
                { "accountId": "${f.debitAccountId.value}", "entryType": "DEBIT",
                  "monetaryEntry": { "type": "FIAT", "amount": "100.00", "currency": "USD", "rail": "ACH" } },
                { "accountId": "${f.creditAccountId.value}", "entryType": "CREDIT",
                  "monetaryEntry": { "type": "FIAT", "amount": "100.00", "currency": "USD", "rail": "ACH" } }
              ]
            }
            """.trimIndent()
        return postTransaction(apiKey, body, idempotencyKey)
    }

    private fun postTransaction(
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

    private fun extractTransactionId(response: ResponseEntity<String>): UUID =
        UUID.fromString(objectMapper.readTree(response.body!!).get("transactionId").asText())

    private fun exportAudit(
        apiKey: String,
        from: Instant,
        to: Instant,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("X-API-Key", apiKey) }
        return restTemplate.exchange(
            "http://localhost:$port/api/v1/compliance/audit-export?from=$from&to=$to",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
    }

    private fun hmacBase64(
        secret: String,
        data: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private data class ComplianceQueueRow(
        val txHash: String,
        val chainId: String,
        val reason: String,
    )

    private data class LgpdRetentionRow(
        val entityType: String,
        val entityId: String,
        val deletionDueAt: Instant,
    )

    private data class AuditLogRow(
        val hmac: String,
    )

    private fun findComplianceQueueRows(tenantId: TenantId): List<ComplianceQueueRow> {
        val rows = mutableListOf<ComplianceQueueRow>()
        withTenantConnection(tenantId) { conn ->
            conn
                .prepareStatement("SELECT tx_hash, chain_id, reason FROM compliance_queue WHERE tenant_id = ?::uuid")
                .use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        rows += ComplianceQueueRow(rs.getString("tx_hash"), rs.getString("chain_id"), rs.getString("reason"))
                    }
                }
        }
        return rows
    }

    private fun findLgpdRetentionRows(tenantId: TenantId): List<LgpdRetentionRow> {
        val rows = mutableListOf<LgpdRetentionRow>()
        withTenantConnection(tenantId) { conn ->
            conn
                .prepareStatement("SELECT entity_type, entity_id, deletion_due_at FROM lgpd_retention_schedule WHERE tenant_id = ?::uuid")
                .use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        rows +=
                            LgpdRetentionRow(
                                rs.getString("entity_type"),
                                rs.getString("entity_id"),
                                rs.getTimestamp("deletion_due_at").toInstant(),
                            )
                    }
                }
        }
        return rows
    }

    private fun findAuditLogRecord(
        tenantId: TenantId,
        transactionId: UUID,
    ): AuditLogRow? {
        var row: AuditLogRow? = null
        withTenantConnection(tenantId) { conn ->
            conn
                .prepareStatement(
                    "SELECT hmac FROM audit_log WHERE tenant_id = ?::uuid AND transaction_id = ?::uuid",
                ).use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    stmt.setString(2, transactionId.toString())
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        row = AuditLogRow(rs.getString("hmac"))
                    }
                }
        }
        return row
    }

    private fun findOutboxEventTypes(tenantId: TenantId): List<String> =
        webhookOutboxAdapter.findPendingOrFailed(tenantId).map { it.eventType }

    /**
     * `compliance_queue`/`lgpd_retention_schedule`/`audit_log` have RLS policies scoped to
     * `app.tenant_id` — verification reads must `SET LOCAL` on the same JDBC connection the
     * subsequent query runs on, mirroring `AlchemyWebhookIntegrationTest`'s raw-SQL helpers.
     */
    private fun withTenantConnection(
        tenantId: TenantId,
        block: (java.sql.Connection) -> Unit,
    ) {
        val session = entityManager.unwrap(Session::class.java)
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            block(conn)
        }
        entityManager.clear()
    }
}
