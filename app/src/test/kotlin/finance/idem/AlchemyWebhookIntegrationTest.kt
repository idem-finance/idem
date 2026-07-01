package finance.idem

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.TransactionStatus
import finance.idem.core.monetary.OnChainEntry
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.chain.WatchedAddressDataModel
import finance.idem.infrastructure.persistence.chain.WatchedAddressJpaRepository
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val SIGNING_KEY = "test-alchemy-secret"

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "idem.chain.alchemy-webhook-signing-key=$SIGNING_KEY",
        // app/src/main/resources/application.yaml resolves idem.audit.hmac-secret to an empty
        // string when IDEM_AUDIT_HMAC_SECRET is unset, which fails SecretKeySpec in AuditRepositoryAdapter.
        "idem.audit.hmac-secret=test-only-insecure-hmac-secret",
    ],
)
class AlchemyWebhookIntegrationTest {
    @Autowired lateinit var restTemplate: TestRestTemplate

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var accountRepository: AccountRepositoryAdapter

    @Autowired lateinit var settlementRepository: SettlementRepositoryAdapter

    @Autowired lateinit var webhookOutboxAdapter: WebhookOutboxRepositoryAdapter

    @Autowired lateinit var transactionRepository: TransactionRepositoryAdapter

    @Autowired lateinit var watchedAddressJpaRepository: WatchedAddressJpaRepository

    @Autowired lateinit var entityManager: EntityManager

    // ── Scenario 1: valid HMAC -> 200, transfer posted, no PENDING candidate -> UNMATCHED ──

    @Test
    fun `valid signature posts transfer and flags it unmatched when no settlement is pending`() {
        val f = fixture(1)
        val txHash = txHashFor(1)
        val body = buildPayload(txHash = txHash, toAddress = f.wallet, contract = f.contract)

        val response = postWebhook(body, computeHmac(SIGNING_KEY, body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val tx = transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)
        assertThat(tx).isNotNull
        assertThat(tx!!.status).isEqualTo(TransactionStatus.COMMITTED)
        assertThat(tx.lines).hasSize(2)

        val debitLine = tx.lines.first { it.accountId == f.debitAccountId }
        val creditLine = tx.lines.first { it.accountId == f.creditAccountId }
        for (line in listOf(debitLine, creditLine)) {
            val entry = line.monetaryEntry as OnChainEntry
            assertThat(entry.amount).isEqualTo(MonetaryAmount.of("1.000000"))
            assertThat(entry.token).isEqualTo(StablecoinToken.USDC)
            assertThat(entry.chainId).isEqualTo(ChainId.EVM)
            assertThat(entry.txHash).isEqualTo(txHash)
            assertThat(entry.blockNumber).isEqualTo(19_531_250L)
            assertThat(entry.walletAddress).isEqualTo(f.wallet)
            assertThat(entry.tokenContract).isEqualTo(f.contract)
        }

        val outboxEventTypes = webhookOutboxAdapter.findPendingOrFailed(f.tenantId).map { it.eventType }
        assertThat(outboxEventTypes).contains("transaction.committed", "reconciliation.unmatched")

        val unmatched = findUnmatchedSettlement(f.tenantId, f.wallet)
        assertThat(unmatched).isNotNull
        assertThat(unmatched!!.accountId).isEqualTo(f.creditAccountId.value)
        assertThat(unmatched.amount).isEqualByComparingTo(BigDecimal("1.000000"))
    }

    // ── Scenario 2: invalid/missing HMAC -> 401, nothing posted ──

    @Test
    fun `missing or invalid signature is rejected and nothing is posted`() {
        val f = fixture(2)
        val txHash = txHashFor(2)
        val body = buildPayload(txHash = txHash, toAddress = f.wallet, contract = f.contract)

        val missingSigResponse = postWebhook(body, signature = null)
        assertThat(missingSigResponse.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val wrongSigResponse = postWebhook(body, signature = "deadbeef0000")
        assertThat(wrongSigResponse.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        assertThat(transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)).isNull()
        assertThat(webhookOutboxAdapter.findPendingOrFailed(f.tenantId)).isEmpty()
        assertThat(findUnmatchedSettlement(f.tenantId, f.wallet)).isNull()
    }

    // ── Scenario 3: duplicate webhook -> idempotent, no second Transaction/Settlement ──

    @Test
    fun `duplicate webhook with the same tx hash is idempotent`() {
        val f = fixture(3)
        val txHash = txHashFor(3)
        val body = buildPayload(txHash = txHash, toAddress = f.wallet, contract = f.contract)
        val signature = computeHmac(SIGNING_KEY, body)

        val first = postWebhook(body, signature)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        val firstTx = transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)
        assertThat(firstTx).isNotNull
        val outboxCountAfterFirst = webhookOutboxAdapter.findPendingOrFailed(f.tenantId).size

        val second = postWebhook(body, signature)
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)

        val secondTx = transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)
        assertThat(secondTx).isNotNull
        assertThat(secondTx!!.id).isEqualTo(firstTx!!.id)

        assertThat(webhookOutboxAdapter.findPendingOrFailed(f.tenantId)).hasSize(outboxCountAfterFirst)
        assertThat(countUnmatchedSettlements(f.tenantId, f.wallet)).isEqualTo(1L)
    }

    // ── Scenario 4: matching PENDING settlement -> SETTLED ──

    @Test
    fun `matching pending settlement transitions to settled`() {
        val f = fixture(4)
        val txHash = txHashFor(4)
        val pending = seedPendingSettlement(f.tenantId, f.creditAccountId, "1.000000", f.wallet)
        val body = buildPayload(txHash = txHash, toAddress = f.wallet, contract = f.contract)

        val response = postWebhook(body, computeHmac(SIGNING_KEY, body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val tx = transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)!!

        val updated = settlementRepository.findById(pending.id, f.tenantId)
        assertThat(updated).isNotNull
        assertThat(updated!!.status).isEqualTo(EntryStatus.SETTLED)
        assertThat(updated.matchedTransactionId).isEqualTo(tx.id)
        assertThat(updated.txHash).isEqualTo(txHash)
        assertThat(updated.blockNumber).isEqualTo(19_531_250L)
        assertThat(updated.confirmedAt).isNotNull

        val outboxEventTypes = webhookOutboxAdapter.findPendingOrFailed(f.tenantId).map { it.eventType }
        assertThat(outboxEventTypes).contains("transaction.settled")
        assertThat(outboxEventTypes).doesNotContain("reconciliation.unmatched")

        assertThat(findUnmatchedSettlement(f.tenantId, f.wallet)).isNull()
    }

    // ── Scenario 5: no PENDING settlement -> new UNMATCHED row ──

    @Test
    fun `no pending settlement creates a new unmatched row`() {
        val f = fixture(5)
        val txHash = txHashFor(5)
        val body = buildPayload(txHash = txHash, toAddress = f.wallet, contract = f.contract)

        val response = postWebhook(body, computeHmac(SIGNING_KEY, body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val tx = transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)!!

        val unmatched = findUnmatchedSettlement(f.tenantId, f.wallet)
        assertThat(unmatched).isNotNull
        assertThat(unmatched!!.status).isEqualTo("UNMATCHED")
        assertThat(unmatched.accountId).isEqualTo(f.creditAccountId.value)
        assertThat(unmatched.amount).isEqualByComparingTo(BigDecimal("1.000000"))
        assertThat(unmatched.matchedTransactionId).isEqualTo(tx.id.value)
        assertThat(unmatched.txHash).isEqualTo(txHash)
        assertThat(unmatched.blockNumber).isEqualTo(19_531_250L)
        assertThat(unmatched.confirmedAt).isNotNull

        val outboxEventTypes = webhookOutboxAdapter.findPendingOrFailed(f.tenantId).map { it.eventType }
        assertThat(outboxEventTypes).contains("reconciliation.unmatched")
        assertThat(outboxEventTypes).doesNotContain("transaction.settled")
    }

    // ── Scenario 6: PENDING settlement with a different amount -> UNMATCHED, old PENDING untouched ──

    @Test
    fun `pending settlement with a different amount is left pending and a new unmatched row is created`() {
        val f = fixture(6)
        val txHash = txHashFor(6)
        val pending = seedPendingSettlement(f.tenantId, f.creditAccountId, "5.000000", f.wallet)
        val body = buildPayload(txHash = txHash, toAddress = f.wallet, contract = f.contract)

        val response = postWebhook(body, computeHmac(SIGNING_KEY, body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val tx = transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)!!

        val untouched = settlementRepository.findById(pending.id, f.tenantId)
        assertThat(untouched).isNotNull
        assertThat(untouched!!.status).isEqualTo(EntryStatus.PENDING)
        assertThat(untouched.matchedTransactionId).isNull()

        val unmatched = findUnmatchedSettlement(f.tenantId, f.wallet)
        assertThat(unmatched).isNotNull
        assertThat(unmatched!!.id).isNotEqualTo(pending.id)
        assertThat(unmatched.status).isEqualTo("UNMATCHED")
        assertThat(unmatched.accountId).isEqualTo(f.creditAccountId.value)
        assertThat(unmatched.amount).isEqualByComparingTo(BigDecimal("1.000000"))
        assertThat(unmatched.matchedTransactionId).isEqualTo(tx.id.value)
        assertThat(unmatched.txHash).isEqualTo(txHash)
        assertThat(unmatched.blockNumber).isEqualTo(19_531_250L)
        assertThat(unmatched.confirmedAt).isNotNull

        val outboxEventTypes = webhookOutboxAdapter.findPendingOrFailed(f.tenantId).map { it.eventType }
        assertThat(outboxEventTypes).contains("reconciliation.unmatched")
        assertThat(outboxEventTypes).doesNotContain("transaction.settled")
    }

    // ── Scenario 7: PENDING settlement matches but is outside the 24h window -> UNMATCHED, old PENDING untouched ──

    @Test
    fun `pending settlement outside the matching window is left pending and a new unmatched row is created`() {
        val f = fixture(7)
        val txHash = txHashFor(7)
        val pending =
            seedPendingSettlement(
                f.tenantId,
                f.creditAccountId,
                "1.000000",
                f.wallet,
                createdAt = Instant.now().minus(25, ChronoUnit.HOURS),
            )
        val body = buildPayload(txHash = txHash, toAddress = f.wallet, contract = f.contract)

        val response = postWebhook(body, computeHmac(SIGNING_KEY, body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val tx = transactionRepository.findByIdempotencyKey("EVM_1:$txHash:0", f.tenantId)!!

        val untouched = settlementRepository.findById(pending.id, f.tenantId)
        assertThat(untouched).isNotNull
        assertThat(untouched!!.status).isEqualTo(EntryStatus.PENDING)

        val unmatched = findUnmatchedSettlement(f.tenantId, f.wallet)
        assertThat(unmatched).isNotNull
        assertThat(unmatched!!.id).isNotEqualTo(pending.id)
        assertThat(unmatched.status).isEqualTo("UNMATCHED")
        assertThat(unmatched.accountId).isEqualTo(f.creditAccountId.value)
        assertThat(unmatched.amount).isEqualByComparingTo(BigDecimal("1.000000"))
        assertThat(unmatched.matchedTransactionId).isEqualTo(tx.id.value)
        assertThat(unmatched.txHash).isEqualTo(txHash)
        assertThat(unmatched.blockNumber).isEqualTo(19_531_250L)
        assertThat(unmatched.confirmedAt).isNotNull

        val outboxEventTypes = webhookOutboxAdapter.findPendingOrFailed(f.tenantId).map { it.eventType }
        assertThat(outboxEventTypes).contains("reconciliation.unmatched")
    }

    // ── Fixtures & helpers ──────────────────────────────────────────────────────────

    private data class Fixture(
        val tenantId: TenantId,
        val debitAccountId: AccountId,
        val creditAccountId: AccountId,
        val wallet: String,
        val contract: String,
    )

    /**
     * Sets up a fresh tenant, two accounts, and a watched address for scenario [n].
     *
     * `watched_addresses` has no RLS and is matched globally by (wallet, contract) —
     * see [walletFor]/[contractFor] — so every scenario must use a globally-unique pair.
     */
    private fun fixture(n: Int): Fixture {
        val tenantId = TenantId.generate()
        val now = Instant.now()
        val debitAccountId = AccountId.generate()
        val creditAccountId = AccountId.generate()
        accountRepository.save(Account.create(debitAccountId, tenantId, "Custody-$n", FiatCurrency.USD, AccountType.ASSET, now, "test"))
        accountRepository.save(
            Account.create(creditAccountId, tenantId, "Customer-$n", FiatCurrency.USD, AccountType.LIABILITY, now, "test"),
        )
        val wallet = walletFor(n)
        val contract = contractFor(n)
        insertWatchedAddress(tenantId, wallet, contract, debitAccountId, creditAccountId)
        return Fixture(tenantId, debitAccountId, creditAccountId, wallet, contract)
    }

    private fun walletFor(n: Int) = "0x" + "%040x".format(BigInteger.valueOf(0xAAAA0000L + n))

    private fun contractFor(n: Int) = "0x" + "%040x".format(BigInteger.valueOf(0xBBBB0000L + n))

    private fun txHashFor(n: Int) = "0x" + "$n".repeat(64)

    private fun insertWatchedAddress(
        tenantId: TenantId,
        wallet: String,
        contract: String,
        debitAccountId: AccountId,
        creditAccountId: AccountId,
    ) {
        watchedAddressJpaRepository.save(
            WatchedAddressDataModel(
                id = UUID.randomUUID(),
                chainKey = "EVM_1",
                walletAddress = wallet,
                tokenContract = contract,
                token = "USDC",
                tenantId = tenantId.value,
                debitAccountId = debitAccountId.value,
                creditAccountId = creditAccountId.value,
                createdAt = Instant.now(),
            ),
        )
    }

    private fun seedPendingSettlement(
        tenantId: TenantId,
        accountId: AccountId,
        amount: String,
        walletAddress: String,
        createdAt: Instant = Instant.now(),
    ): Settlement =
        settlementRepository.save(
            Settlement(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                accountId = accountId,
                amount = MonetaryAmount.of(amount),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                walletAddress = walletAddress,
                status = EntryStatus.PENDING,
                expectedFromAddress = null,
                createdAt = createdAt,
                createdBy = "test",
            ),
        )

    private fun buildPayload(
        txHash: String,
        toAddress: String,
        contract: String,
        rawValue: String = "0x000f4240", // 1.000000 USDC (6 decimals)
        blockNum: String = "0x12a05f2", // 19_531_250
        logIndex: String = "0x0",
        network: String = "ETH_MAINNET",
        fromAddress: String = "0xfrom0000000000000000000000000000000000",
    ): String =
        """
        {
          "webhookId": "wh_test",
          "id": "whevt_test",
          "createdAt": "2024-01-01T00:00:00.000Z",
          "type": "ADDRESS_ACTIVITY",
          "event": {
            "network": "$network",
            "activity": [
              {
                "fromAddress": "$fromAddress",
                "toAddress": "$toAddress",
                "blockNum": "$blockNum",
                "hash": "$txHash",
                "value": 1.0,
                "asset": "USDC",
                "category": "token",
                "rawContract": {
                  "rawValue": "$rawValue",
                  "address": "$contract",
                  "decimals": 6
                },
                "log": {
                  "logIndex": "$logIndex",
                  "transactionHash": "$txHash",
                  "blockNumber": "$blockNum",
                  "address": "$contract",
                  "data": "$rawValue",
                  "topics": [],
                  "removed": false
                }
              }
            ]
          }
        }
        """.trimIndent()

    private fun computeHmac(
        key: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun postWebhook(
        body: String,
        signature: String?,
    ): ResponseEntity<Void> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                if (signature != null) set("X-Alchemy-Signature", signature)
            }
        return restTemplate.postForEntity(
            "http://localhost:$port/internal/webhooks/alchemy",
            HttpEntity(body, headers),
            Void::class.java,
        )
    }

    /** Settlement row shape returned by the raw-query helpers below. */
    private data class SettlementRow(
        val id: UUID,
        val accountId: UUID,
        val amount: BigDecimal,
        val status: String,
        val matchedTransactionId: UUID?,
        val txHash: String?,
        val blockNumber: Long?,
        val confirmedAt: Instant?,
    )

    /**
     * `SettlementRepository` exposes no "find by tenant+status+wallet" query, so the
     * UNMATCHED row created by [BasicReconciliationService] is read back via a native
     * query. `settlements` has FORCE RLS, so `app.tenant_id` is set manually here —
     * every other repository access in this test goes through adapters that already do this.
     */
    private fun findUnmatchedSettlement(
        tenantId: TenantId,
        walletAddress: String,
    ): SettlementRow? {
        val session = entityManager.unwrap(org.hibernate.Session::class.java)
        var row: SettlementRow? = null
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn
                .prepareStatement(
                    "SELECT id, account_id, amount, status, matched_transaction_id, tx_hash, block_number, confirmed_at " +
                        "FROM settlements WHERE tenant_id = ?::uuid AND wallet_address = ? AND status = 'UNMATCHED' " +
                        "ORDER BY created_at DESC LIMIT 1",
                ).use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    stmt.setString(2, walletAddress)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        row =
                            SettlementRow(
                                id = rs.getObject("id", UUID::class.java),
                                accountId = rs.getObject("account_id", UUID::class.java),
                                amount = rs.getBigDecimal("amount"),
                                status = rs.getString("status"),
                                matchedTransactionId = rs.getObject("matched_transaction_id", UUID::class.java),
                                txHash = rs.getString("tx_hash"),
                                blockNumber = rs.getObject("block_number") as Long?,
                                confirmedAt = rs.getTimestamp("confirmed_at")?.toInstant(),
                            )
                    }
                }
        }
        entityManager.clear()
        return row
    }

    private fun countUnmatchedSettlements(
        tenantId: TenantId,
        walletAddress: String,
    ): Long {
        val session = entityManager.unwrap(org.hibernate.Session::class.java)
        var count = 0L
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn
                .prepareStatement(
                    "SELECT COUNT(*) FROM settlements WHERE tenant_id = ?::uuid AND wallet_address = ? AND status = 'UNMATCHED'",
                ).use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    stmt.setString(2, walletAddress)
                    val rs = stmt.executeQuery()
                    rs.next()
                    count = rs.getLong(1)
                }
        }
        entityManager.clear()
        return count
    }
}
