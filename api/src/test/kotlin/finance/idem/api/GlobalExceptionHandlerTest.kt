package finance.idem.api

import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.EntriesAccountNotFound
import finance.idem.application.ledger.IdempotencyConflict
import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.ledger.InvalidStatementRange
import finance.idem.application.ledger.InvariantViolation
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.application.ledger.TransactionAccountNotFound
import finance.idem.application.settlement.AccountNotFoundForSettlement
import finance.idem.application.settlement.SettlementAlreadyTerminal
import finance.idem.application.settlement.SettlementIdempotencyConflict
import finance.idem.application.settlement.SettlementNotFound
import finance.idem.core.AccountId
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyViolation
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.ledger.EntryStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Standalone MockMvc test for [GlobalExceptionHandler]. Uses a minimal
 * [ThrowingController] that maps query params to specific thrown exceptions
 * so each handler branch can be driven without needing a full Spring context.
 */
class GlobalExceptionHandlerTest {
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(ThrowingController())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `LedgerInvariantViolation returns 400`() {
        mockMvc.get("/throw?type=ledger-invariant").andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_JSON) }
            jsonPath("$.code") { value("INVARIANT_VIOLATION") }
        }
    }

    @Test
    fun `InvariantViolation returns 400`() {
        mockMvc.get("/throw?type=invariant").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVARIANT_VIOLATION") }
        }
    }

    @Test
    fun `IdempotencyConflict returns 409`() {
        mockMvc.get("/throw?type=idempotency").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }
    }

    @Test
    fun `TransactionAccountNotFound returns 404`() {
        mockMvc.get("/throw?type=tx-account-not-found").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `BalanceAccountNotFound returns 404`() {
        mockMvc.get("/throw?type=balance-account-not-found").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `EntriesAccountNotFound returns 404`() {
        mockMvc.get("/throw?type=entries-account-not-found").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `StatementAccountNotFound returns 404`() {
        mockMvc.get("/throw?type=statement-account-not-found").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `InvalidStatementRange returns 400`() {
        mockMvc.get("/throw?type=invalid-range").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_RANGE") }
        }
    }

    @Test
    fun `InvalidCursor returns 400`() {
        mockMvc.get("/throw?type=invalid-cursor").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CURSOR") }
        }
    }

    @Test
    fun `PolicyViolationException returns 422 with all violation messages`() {
        mockMvc.get("/throw?type=policy-violation").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("POLICY_VIOLATION") }
            jsonPath("$.message") { value("Debit exceeds session limit") }
        }
    }

    @Test
    fun `unexpected Exception returns 500 without leaking stack trace`() {
        mockMvc.get("/throw?type=unexpected").andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            jsonPath("$.message") { value("An unexpected error occurred") }
        }
    }

    @Test
    fun `SettlementNotFound returns 404`() {
        mockMvc.get("/throw?type=settlement-not-found").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("SETTLEMENT_NOT_FOUND") }
        }
    }

    @Test
    fun `SettlementAlreadyTerminal returns 409`() {
        mockMvc.get("/throw?type=settlement-already-terminal").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SETTLEMENT_ALREADY_TERMINAL") }
        }
    }

    @Test
    fun `AccountNotFoundForSettlement returns 422`() {
        mockMvc.get("/throw?type=account-not-found-for-settlement").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `SettlementIdempotencyConflict returns 409`() {
        mockMvc.get("/throw?type=settlement-idempotency-conflict").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }
    }

    @RestController
    class ThrowingController {
        private val accountId = AccountId(UUID.randomUUID())
        private val now = Instant.now()

        @GetMapping("/throw")
        fun throwException(
            @RequestParam type: String,
        ): String =
            throw when (type) {
                "ledger-invariant" -> {
                    LedgerInvariantViolation("invariant failed")
                }

                "invariant" -> {
                    InvariantViolation("invariant detail")
                }

                "idempotency" -> {
                    IdempotencyConflict("key-123")
                }

                "tx-account-not-found" -> {
                    TransactionAccountNotFound(accountId)
                }

                "balance-account-not-found" -> {
                    BalanceAccountNotFound(accountId)
                }

                "entries-account-not-found" -> {
                    EntriesAccountNotFound(accountId)
                }

                "statement-account-not-found" -> {
                    StatementAccountNotFound(accountId)
                }

                "invalid-range" -> {
                    InvalidStatementRange(now.plusSeconds(10), now)
                }

                "invalid-cursor" -> {
                    InvalidCursor("bad-cursor")
                }

                "policy-violation" -> {
                    PolicyViolationException(
                        listOf(PolicyViolation(PolicyRule.MaxDebitPerSession(MonetaryAmount.of("1000")), "Debit exceeds session limit")),
                    )
                }

                "settlement-not-found" -> {
                    SettlementNotFound(UUID.randomUUID())
                }

                "settlement-already-terminal" -> {
                    SettlementAlreadyTerminal(EntryStatus.SETTLED)
                }

                "account-not-found-for-settlement" -> {
                    AccountNotFoundForSettlement(finance.idem.core.AccountId(UUID.randomUUID()))
                }

                "settlement-idempotency-conflict" -> {
                    SettlementIdempotencyConflict("key-123")
                }

                else -> {
                    RuntimeException("unexpected boom")
                }
            }
    }
}
