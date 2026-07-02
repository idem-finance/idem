package finance.idem.api.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.ledger.IdempotencyConflict
import finance.idem.application.ledger.InvariantViolation
import finance.idem.application.ledger.PostTransactionError
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.ledger.TransactionAccountNotFound
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(TransactionController::class)
@Import(TestSecurityConfig::class)
class TransactionControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var postTransactionUseCase: PostTransactionUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val idempotencyKey = "test-key-001"

    private fun mockAuth(vararg scopes: String): TestingAuthenticationToken = TestingAuthenticationToken(tenantId, null, *scopes)

    private val validBody =
        """
        {
          "lines": [
            {
              "accountId": "${UUID.randomUUID()}",
              "entryType": "DEBIT",
              "monetaryEntry": { "type": "FIAT", "amount": "100.00", "currency": "BRL", "rail": "PIX" }
            },
            {
              "accountId": "${UUID.randomUUID()}",
              "entryType": "CREDIT",
              "monetaryEntry": { "type": "FIAT", "amount": "100.00", "currency": "BRL", "rail": "PIX" }
            }
          ],
          "metadata": {}
        }
        """.trimIndent()

    @Test
    fun `happy path returns 201 with transaction id`() {
        val txId = TransactionId.generate()
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(txId))

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isCreated() }
                jsonPath("$.transactionId") { value(txId.value.toString()) }
            }
    }

    @Test
    fun `no authentication returns 401`() {
        mockMvc
            .post("/api/v1/transactions") {
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `wrong scope returns 403`() {
        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("insufficient_scope") }
            }
    }

    @Test
    fun `missing Idempotency-Key returns 400`() {
        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `blank idempotency key returns 400`() {
        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", "   ")
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
            }
    }

    @Test
    fun `idempotency key longer than 255 chars returns 400`() {
        val longKey = "k".repeat(256)

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", longKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
            }
    }

    @Test
    fun `invalid monetary entry in body returns 400`() {
        val bodyWithNegativeAmount =
            """
            {
              "lines": [
                {
                  "accountId": "${UUID.randomUUID()}",
                  "entryType": "DEBIT",
                  "monetaryEntry": { "type": "FIAT", "amount": "-1.00", "currency": "BRL", "rail": "PIX" }
                },
                {
                  "accountId": "${UUID.randomUUID()}",
                  "entryType": "CREDIT",
                  "monetaryEntry": { "type": "FIAT", "amount": "-1.00", "currency": "BRL", "rail": "PIX" }
                }
              ]
            }
            """.trimIndent()

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = bodyWithNegativeAmount
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
    }

    @Test
    fun `invariant violation returns 422`() {
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.failure(InvariantViolation("Debits != credits")))

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.code") { value("INVARIANT_VIOLATION") }
            }
    }

    @Test
    fun `idempotency conflict returns 409`() {
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.failure(IdempotencyConflict(idempotencyKey)))

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
            }
    }

    @Test
    fun `onchain entry is deserialized and routed correctly`() {
        val txId = TransactionId.generate()
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(txId))

        val onchainBody =
            """
            {
              "lines": [
                {
                  "accountId": "${UUID.randomUUID()}",
                  "entryType": "DEBIT",
                  "monetaryEntry": {
                    "type": "ONCHAIN",
                    "amount": "180.00",
                    "token": "USDC",
                    "chainId": "EVM",
                    "txHash": "0xabc123",
                    "blockNumber": 19000000,
                    "walletAddress": "0xWallet",
                    "tokenContract": "0xContract"
                  }
                },
                {
                  "accountId": "${UUID.randomUUID()}",
                  "entryType": "CREDIT",
                  "monetaryEntry": {
                    "type": "ONCHAIN",
                    "amount": "180.00",
                    "token": "USDC",
                    "chainId": "EVM",
                    "txHash": "0xabc123",
                    "blockNumber": 19000000,
                    "walletAddress": "0xWallet",
                    "tokenContract": "0xContract"
                  }
                }
              ]
            }
            """.trimIndent()

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = onchainBody
            }.andExpect {
                status { isCreated() }
                jsonPath("$.transactionId") { value(txId.value.toString()) }
            }
    }

    @Test
    fun `account not found returns 422`() {
        val accountId = finance.idem.core.AccountId(UUID.randomUUID())
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.failure(TransactionAccountNotFound(accountId)))

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
            }
    }

    @Test
    fun `unexpected use case error returns 500 with generic message`() {
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.failure(RuntimeException("boom")))

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = validBody
            }.andExpect {
                status { isInternalServerError() }
                jsonPath("$.code") { value("INTERNAL_ERROR") }
                jsonPath("$.message") { value("An unexpected error occurred") }
            }
    }

    @Test
    fun `oversized metadata returns 400`() {
        val oversizedMetadata = (1..51).associate { "key$it" to "value" }
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "lines" to
                        listOf(
                            mapOf(
                                "accountId" to UUID.randomUUID().toString(),
                                "entryType" to "DEBIT",
                                "monetaryEntry" to mapOf("type" to "FIAT", "amount" to "100.00", "currency" to "BRL", "rail" to "PIX"),
                            ),
                            mapOf(
                                "accountId" to UUID.randomUUID().toString(),
                                "entryType" to "CREDIT",
                                "monetaryEntry" to mapOf("type" to "FIAT", "amount" to "100.00", "currency" to "BRL", "rail" to "PIX"),
                            ),
                        ),
                    "metadata" to oversizedMetadata,
                ),
            )

        mockMvc
            .post("/api/v1/transactions") {
                with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
                header("Idempotency-Key", idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_ERROR") }
            }
    }
}
