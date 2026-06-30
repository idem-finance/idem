package finance.idem.api.settlement

import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.settlement.CancelSettlementUseCase
import finance.idem.application.settlement.GetSettlementUseCase
import finance.idem.application.settlement.ListSettlementsUseCase
import finance.idem.application.settlement.RegisterSettlementUseCase
import finance.idem.application.settlement.SettlementAlreadyTerminal
import finance.idem.application.settlement.SettlementIdempotencyConflict
import finance.idem.application.settlement.SettlementNotFound
import finance.idem.application.settlement.SettlementPage
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@WebMvcTest(SettlementController::class)
@Import(TestSecurityConfig::class)
@TestPropertySource(properties = ["idem.reconciliation.matching-window-hours=24"])
class SettlementControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var registerSettlementUseCase: RegisterSettlementUseCase
    @MockitoBean lateinit var getSettlementUseCase: GetSettlementUseCase
    @MockitoBean lateinit var listSettlementsUseCase: ListSettlementsUseCase
    @MockitoBean lateinit var cancelSettlementUseCase: CancelSettlementUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val settlementId = UUID.randomUUID()
    private val accountId = AccountId.generate()

    private val idempotencyKey = "settlement-key-001"

    private val writeAuth = TestingAuthenticationToken(tenantId, null, "TRANSACTIONS_WRITE")
    private val readAuth = TestingAuthenticationToken(tenantId, null, "TRANSACTIONS_READ")
    private val wrongScopeAuth = TestingAuthenticationToken(tenantId, null, "ADMIN")

    private fun pendingSettlement(id: UUID = settlementId) = Settlement(
        id = id,
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of("100.00"),
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS",
        status = EntryStatus.PENDING,
        createdAt = Instant.parse("2025-06-15T12:00:00Z"),
        createdBy = "api-user",
    )

    private val validBody = """
        {
          "accountId": "${accountId.value}",
          "expectedToken": "USDC",
          "expectedAmount": "100.00",
          "expectedWalletAddress": "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS",
          "expectedChainId": "SOLANA"
        }
    """

    // ── POST /api/v1/settlements/pending ──────────────────────────────────────

    @Test
    fun `POST pending returns 201 on success`() {
        whenever(registerSettlementUseCase.execute(any())).thenReturn(Result.success(pendingSettlement()))

        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect {
            status { isCreated() }
            jsonPath("$.settlementId") { value(settlementId.toString()) }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.expiresAt") { value("2025-06-16T12:00:00Z") }
        }
    }

    @Test
    fun `expiresAt is computed identically on POST and subsequent GET by id`() {
        whenever(registerSettlementUseCase.execute(any())).thenReturn(Result.success(pendingSettlement()))
        whenever(getSettlementUseCase.execute(any())).thenReturn(Result.success(pendingSettlement()))

        val postResult = mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect { status { isCreated() } }.andReturn()

        val getResult = mockMvc.get("/api/v1/settlements/$settlementId") {
            with(authentication(readAuth))
        }.andExpect { status { isOk() } }.andReturn()

        val postExpiresAt = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(postResult.response.contentAsString).get("expiresAt").asText()
        val getExpiresAt = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(getResult.response.contentAsString).get("expiresAt").asText()

        kotlin.test.assertEquals(postExpiresAt, getExpiresAt)
    }

    @Test
    fun `POST pending returns 400 for invalid token`() {
        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody.replace("USDC", "DOGECOIN")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_TOKEN") }
        }
    }

    @Test
    fun `POST pending returns 400 for invalid chainId`() {
        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody.replace("SOLANA", "BITCOIN")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CHAIN_ID") }
        }
    }

    @Test
    fun `POST pending returns 422 when account not found`() {
        whenever(registerSettlementUseCase.execute(any()))
            .thenReturn(Result.failure(finance.idem.application.settlement.AccountNotFoundForSettlement(accountId)))

        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `POST pending returns 403 with wrong scope`() {
        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(wrongScopeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST pending returns 401 with no auth`() {
        mockMvc.post("/api/v1/settlements/pending") {
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `POST pending returns 400 for invalid amount`() {
        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody.replace("\"100.00\"", "\"not-a-number\"")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_AMOUNT") }
        }
    }

    @Test
    fun `POST pending returns 400 when Idempotency-Key is missing`() {
        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST pending returns 400 when Idempotency-Key is blank`() {
        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", "   ")
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
        }
    }

    @Test
    fun `POST pending returns 400 when Idempotency-Key exceeds 255 characters`() {
        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", "k".repeat(256))
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
        }
    }

    @Test
    fun `POST pending returns 409 on idempotency conflict`() {
        whenever(registerSettlementUseCase.execute(any()))
            .thenReturn(Result.failure(SettlementIdempotencyConflict(idempotencyKey)))

        mockMvc.post("/api/v1/settlements/pending") {
            with(authentication(writeAuth))
            header("Idempotency-Key", idempotencyKey)
            contentType = MediaType.APPLICATION_JSON
            content = validBody
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }
    }

    // ── GET /api/v1/settlements/pending ───────────────────────────────────────

    @Test
    fun `GET pending returns 200 with settlement list`() {
        whenever(listSettlementsUseCase.execute(any()))
            .thenReturn(Result.success(SettlementPage(listOf(pendingSettlement()), null)))

        mockMvc.get("/api/v1/settlements/pending") {
            with(authentication(readAuth))
        }.andExpect {
            status { isOk() }
            jsonPath("$.settlements[0].settlementId") { value(settlementId.toString()) }
            jsonPath("$.nextCursor") { doesNotExist() }
        }
    }

    @Test
    fun `GET pending returns 400 for invalid limit`() {
        mockMvc.get("/api/v1/settlements/pending?limit=0") {
            with(authentication(readAuth))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_LIMIT") }
        }
    }

    @Test
    fun `GET pending returns 400 for invalid status`() {
        mockMvc.get("/api/v1/settlements/pending?status=BOGUS") {
            with(authentication(readAuth))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_STATUS") }
        }
    }

    @Test
    fun `GET pending returns 400 for limit over 200`() {
        mockMvc.get("/api/v1/settlements/pending?limit=201") {
            with(authentication(readAuth))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_LIMIT") }
        }
    }

    @Test
    fun `GET pending returns 400 for from after to`() {
        mockMvc.get("/api/v1/settlements/pending?from=2025-06-20T00:00:00Z&to=2025-06-01T00:00:00Z") {
            with(authentication(readAuth))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_RANGE") }
        }
    }

    @Test
    fun `GET pending returns 400 for invalid cursor from use case`() {
        whenever(listSettlementsUseCase.execute(any()))
            .thenReturn(Result.failure(finance.idem.application.ledger.InvalidCursor("bad")))

        mockMvc.get("/api/v1/settlements/pending?cursor=notbase64") {
            with(authentication(readAuth))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CURSOR") }
        }
    }

    @Test
    fun `GET pending returns 200 with nextCursor when page is full`() {
        whenever(listSettlementsUseCase.execute(any()))
            .thenReturn(Result.success(SettlementPage(listOf(pendingSettlement()), "next-cursor-token")))

        mockMvc.get("/api/v1/settlements/pending") {
            with(authentication(readAuth))
        }.andExpect {
            status { isOk() }
            jsonPath("$.nextCursor") { value("next-cursor-token") }
        }
    }

    @Test
    fun `GET pending returns 403 with wrong scope`() {
        mockMvc.get("/api/v1/settlements/pending") {
            with(authentication(wrongScopeAuth))
        }.andExpect { status { isForbidden() } }
    }

    // ── GET /api/v1/settlements/{id} ──────────────────────────────────────────

    @Test
    fun `GET by id returns 200`() {
        whenever(getSettlementUseCase.execute(any())).thenReturn(Result.success(pendingSettlement()))

        mockMvc.get("/api/v1/settlements/$settlementId") {
            with(authentication(readAuth))
        }.andExpect {
            status { isOk() }
            jsonPath("$.settlementId") { value(settlementId.toString()) }
        }
    }

    @Test
    fun `GET by id returns 404 when not found`() {
        whenever(getSettlementUseCase.execute(any())).thenReturn(Result.failure(SettlementNotFound(settlementId)))

        mockMvc.get("/api/v1/settlements/$settlementId") {
            with(authentication(readAuth))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("SETTLEMENT_NOT_FOUND") }
        }
    }

    // ── DELETE /api/v1/settlements/{id}/cancel ────────────────────────────────

    @Test
    fun `DELETE cancel returns 200 with cancelled settlement`() {
        val cancelled = pendingSettlement().copy(status = EntryStatus.CANCELLED)
        whenever(cancelSettlementUseCase.execute(any())).thenReturn(Result.success(cancelled))

        mockMvc.delete("/api/v1/settlements/$settlementId/cancel") {
            with(authentication(writeAuth))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.expiresAt") { doesNotExist() }
        }
    }

    @Test
    fun `DELETE cancel returns 404 when not found`() {
        whenever(cancelSettlementUseCase.execute(any())).thenReturn(Result.failure(SettlementNotFound(settlementId)))

        mockMvc.delete("/api/v1/settlements/$settlementId/cancel") {
            with(authentication(writeAuth))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("SETTLEMENT_NOT_FOUND") }
        }
    }

    @Test
    fun `DELETE cancel returns 409 when already terminal`() {
        whenever(cancelSettlementUseCase.execute(any()))
            .thenReturn(Result.failure(SettlementAlreadyTerminal(EntryStatus.SETTLED)))

        mockMvc.delete("/api/v1/settlements/$settlementId/cancel") {
            with(authentication(writeAuth))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SETTLEMENT_ALREADY_TERMINAL") }
        }
    }

    @Test
    fun `DELETE cancel returns 403 with wrong scope`() {
        mockMvc.delete("/api/v1/settlements/$settlementId/cancel") {
            with(authentication(wrongScopeAuth))
        }.andExpect { status { isForbidden() } }
    }
}
