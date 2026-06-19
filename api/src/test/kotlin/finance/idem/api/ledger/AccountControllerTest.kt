package finance.idem.api.ledger

import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.ledger.AccountStatement
import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.CreateAccountUseCase
import finance.idem.application.ledger.EntriesAccountNotFound
import finance.idem.application.ledger.EntryPage
import finance.idem.application.ledger.GenerateStatementUseCase
import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.GetEntriesUseCase
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.ListAccountsUseCase
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.application.ledger.StatementMovement
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@WebMvcTest(AccountController::class)
@Import(TestSecurityConfig::class)
class AccountControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var createAccountUseCase: CreateAccountUseCase

    @MockitoBean
    lateinit var listAccountsUseCase: ListAccountsUseCase

    @MockitoBean
    lateinit var getBalanceUseCase: GetBalanceUseCase

    @MockitoBean
    lateinit var getEntriesUseCase: GetEntriesUseCase

    @MockitoBean
    lateinit var generateStatementUseCase: GenerateStatementUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val accountId = UUID.randomUUID()

    private fun mockAuth(vararg scopes: String): TestingAuthenticationToken =
        TestingAuthenticationToken(tenantId, null, *scopes)

    private fun accountFor(id: UUID = accountId) = Account.create(
        id = AccountId(id),
        tenantId = tenantId,
        name = "USDC Wallet",
        description = null,
        currency = FiatCurrency.USD,
        type = AccountType.ASSET,
        createdAt = Instant.parse("2026-06-19T10:00:00Z"),
        createdBy = "test-key",
    )

    private fun balanceFor(accountId: UUID) = Balance(
        accountId = AccountId(accountId),
        currency = FiatCurrency.BRL,
        amount = MonetaryAmount.of("350.00"),
        normalBalance = EntryType.DEBIT,
        computedAt = Instant.parse("2026-05-28T12:00:00Z"),
    )

    private fun lineFor(
        accountId: UUID,
        createdAt: Instant = Instant.parse("2026-05-28T12:00:00Z"),
        description: String? = null,
    ) = JournalLine(
        id = UUID.randomUUID(),
        transactionId = TransactionId.generate(),
        accountId = AccountId(accountId),
        tenantId = tenantId,
        entryType = EntryType.DEBIT,
        monetaryEntry = FiatEntry(MonetaryAmount.of("100.00"), FiatCurrency.BRL, PaymentRail.PIX),
        description = description,
        createdAt = createdAt,
        createdBy = "system",
    )

    private fun statementFor(accountId: UUID) = AccountStatement(
        accountId = AccountId(accountId),
        currency = FiatCurrency.BRL,
        from = Instant.parse("2026-05-01T00:00:00Z"),
        to = Instant.parse("2026-05-28T00:00:00Z"),
        openingBalance = MonetaryAmount.of("1000.00"),
        closingBalance = MonetaryAmount.of("1300.00"),
        movements = listOf(
            StatementMovement(
                transactionId = TransactionId.generate(),
                type = EntryType.DEBIT,
                amount = MonetaryAmount.of("500.00"),
                description = "Pix received",
                occurredAt = Instant.parse("2026-05-10T00:00:00Z"),
            ),
        ),
    )

    @Test
    fun `happy path returns 200 with balance`() {
        whenever(getBalanceUseCase.execute(any())).thenReturn(Result.success(balanceFor(accountId)))

        mockMvc.get("/api/v1/accounts/$accountId/balance") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value(accountId.toString()) }
            jsonPath("$.currency") { value("BRL") }
            jsonPath("$.amount") { value(350.00) }
            jsonPath("$.normalBalance") { value("DEBIT") }
        }
    }

    @Test
    fun `no authentication returns 401`() {
        mockMvc.get("/api/v1/accounts/$accountId/balance")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `wrong scope returns 403`() {
        mockMvc.get("/api/v1/accounts/$accountId/balance") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_WRITE")))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("insufficient_scope") }
        }
    }

    @Test
    fun `account not found returns 404`() {
        whenever(getBalanceUseCase.execute(any()))
            .thenReturn(Result.failure(BalanceAccountNotFound(AccountId(accountId))))

        mockMvc.get("/api/v1/accounts/$accountId/balance") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `asOf parameter is forwarded to query`() {
        val asOf = "2026-05-01T00:00:00Z"
        whenever(getBalanceUseCase.execute(any())).thenReturn(Result.success(balanceFor(accountId)))

        mockMvc.get("/api/v1/accounts/$accountId/balance?asOf=$asOf") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `getBalance unexpected use case error returns 500 with generic message`() {
        whenever(getBalanceUseCase.execute(any()))
            .thenReturn(Result.failure(RuntimeException("boom")))

        mockMvc.get("/api/v1/accounts/$accountId/balance") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            jsonPath("$.message") { value("An unexpected error occurred") }
        }
    }

    @Test
    fun `listEntries happy path returns 200 with entries and nextCursor`() {
        val line = lineFor(accountId, description = "Pix received")
        whenever(getEntriesUseCase.execute(any()))
            .thenReturn(Result.success(EntryPage(AccountId(accountId), listOf(line), "next-cursor-token")))

        mockMvc.get("/api/v1/accounts/$accountId/entries") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value(accountId.toString()) }
            jsonPath("$.entries[0].entryId") { value(line.id.toString()) }
            jsonPath("$.entries[0].type") { value("DEBIT") }
            jsonPath("$.entries[0].monetary.type") { value("FIAT") }
            jsonPath("$.entries[0].monetary.currency") { value("BRL") }
            jsonPath("$.entries[0].description") { value("Pix received") }
            jsonPath("$.nextCursor") { value("next-cursor-token") }
        }
    }

    @Test
    fun `listEntries account not found returns 404`() {
        whenever(getEntriesUseCase.execute(any()))
            .thenReturn(Result.failure(EntriesAccountNotFound(AccountId(accountId))))

        mockMvc.get("/api/v1/accounts/$accountId/entries") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `listEntries with limit 0 returns 400 INVALID_LIMIT`() {
        mockMvc.get("/api/v1/accounts/$accountId/entries?limit=0") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_LIMIT") }
        }
    }

    @Test
    fun `listEntries with limit 201 returns 400 INVALID_LIMIT`() {
        mockMvc.get("/api/v1/accounts/$accountId/entries?limit=201") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_LIMIT") }
        }
    }

    @Test
    fun `listEntries with from after to returns 400 INVALID_RANGE`() {
        mockMvc.get("/api/v1/accounts/$accountId/entries?from=2026-05-28T00:00:00Z&to=2026-05-01T00:00:00Z") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_RANGE") }
        }
    }

    @Test
    fun `listEntries with invalid cursor returns 400 INVALID_CURSOR`() {
        whenever(getEntriesUseCase.execute(any()))
            .thenReturn(Result.failure(InvalidCursor("garbage")))

        mockMvc.get("/api/v1/accounts/$accountId/entries?cursor=garbage") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CURSOR") }
        }
    }

    @Test
    fun `listEntries forwards from, to, limit and cursor into the query`() {
        whenever(getEntriesUseCase.execute(any()))
            .thenReturn(Result.success(EntryPage(AccountId(accountId), emptyList(), null)))

        mockMvc.get("/api/v1/accounts/$accountId/entries?from=2026-05-01T00:00:00Z&to=2026-05-28T00:00:00Z&limit=10&cursor=abc") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isOk() }
        }

        val captor = argumentCaptor<GetEntriesQuery>()
        verify(getEntriesUseCase).execute(captor.capture())
        val query = captor.firstValue
        kotlin.test.assertEquals(AccountId(accountId), query.accountId)
        kotlin.test.assertEquals(Instant.parse("2026-05-01T00:00:00Z"), query.from)
        kotlin.test.assertEquals(Instant.parse("2026-05-28T00:00:00Z"), query.to)
        kotlin.test.assertEquals(10, query.limit)
        kotlin.test.assertEquals("abc", query.cursor)
    }

    @Test
    fun `listEntries unexpected use case error returns 500 with generic message`() {
        whenever(getEntriesUseCase.execute(any()))
            .thenReturn(Result.failure(RuntimeException("boom")))

        mockMvc.get("/api/v1/accounts/$accountId/entries") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            jsonPath("$.message") { value("An unexpected error occurred") }
        }
    }

    @Test
    fun `statement happy path returns 200 with opening, closing and movements`() {
        whenever(generateStatementUseCase.execute(any())).thenReturn(Result.success(statementFor(accountId)))

        mockMvc.get("/api/v1/accounts/$accountId/statement?from=2026-05-01T00:00:00Z&to=2026-05-28T00:00:00Z") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value(accountId.toString()) }
            jsonPath("$.currency") { value("BRL") }
            jsonPath("$.openingBalance") { value(1000.00) }
            jsonPath("$.closingBalance") { value(1300.00) }
            jsonPath("$.movements[0].type") { value("DEBIT") }
            jsonPath("$.movements[0].amount") { value(500.00) }
            jsonPath("$.movements[0].description") { value("Pix received") }
            jsonPath("$.movements[0].occurredAt") { value("2026-05-10T00:00:00Z") }
        }
    }

    @Test
    fun `statement missing from returns 400 MISSING_PARAMETER`() {
        mockMvc.get("/api/v1/accounts/$accountId/statement?to=2026-05-28T00:00:00Z") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MISSING_PARAMETER") }
        }
    }

    @Test
    fun `statement missing to returns 400 MISSING_PARAMETER`() {
        mockMvc.get("/api/v1/accounts/$accountId/statement?from=2026-05-01T00:00:00Z") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MISSING_PARAMETER") }
        }
    }

    @Test
    fun `statement with from after to returns 400 INVALID_RANGE`() {
        val from = Instant.parse("2026-05-28T00:00:00Z")
        val to = Instant.parse("2026-05-01T00:00:00Z")

        mockMvc.get("/api/v1/accounts/$accountId/statement?from=$from&to=$to") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_RANGE") }
        }
    }

    @Test
    fun `statement account not found returns 404`() {
        whenever(generateStatementUseCase.execute(any()))
            .thenReturn(Result.failure(StatementAccountNotFound(AccountId(accountId))))

        mockMvc.get("/api/v1/accounts/$accountId/statement?from=2026-05-01T00:00:00Z&to=2026-05-28T00:00:00Z") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `statement unexpected use case error returns 500 with generic message`() {
        whenever(generateStatementUseCase.execute(any()))
            .thenReturn(Result.failure(RuntimeException("boom")))

        mockMvc.get("/api/v1/accounts/$accountId/statement?from=2026-05-01T00:00:00Z&to=2026-05-28T00:00:00Z") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            jsonPath("$.message") { value("An unexpected error occurred") }
        }
    }

    // ── createAccount ──────────────────────────────────────────────────────────

    @Test
    fun `createAccount happy path returns 201 with account fields`() {
        whenever(createAccountUseCase.execute(any())).thenReturn(Result.success(accountFor()))

        mockMvc.post("/api/v1/accounts") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_WRITE")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"USDC Wallet","currency":"USD","type":"ASSET"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(accountId.toString()) }
            jsonPath("$.name") { value("USDC Wallet") }
            jsonPath("$.currency") { value("USD") }
            jsonPath("$.type") { value("ASSET") }
            jsonPath("$.normalBalance") { value("DEBIT") }
        }
    }

    @Test
    fun `createAccount invalid currency returns 400 INVALID_CURRENCY`() {
        mockMvc.post("/api/v1/accounts") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_WRITE")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Test","currency":"XYZ","type":"ASSET"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CURRENCY") }
        }
    }

    @Test
    fun `createAccount invalid account type returns 400 INVALID_ACCOUNT_TYPE`() {
        mockMvc.post("/api/v1/accounts") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_WRITE")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Test","currency":"USD","type":"SAVINGS"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_ACCOUNT_TYPE") }
        }
    }

    @Test
    fun `createAccount blank name returns 400`() {
        mockMvc.post("/api/v1/accounts") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_WRITE")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"","currency":"USD","type":"ASSET"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `createAccount wrong scope returns 403`() {
        mockMvc.post("/api/v1/accounts") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Test","currency":"USD","type":"ASSET"}"""
        }.andExpect {
            status { isForbidden() }
        }
    }

    // ── listAccounts ───────────────────────────────────────────────────────────

    @Test
    fun `listAccounts returns 200 with account list`() {
        whenever(listAccountsUseCase.execute(any())).thenReturn(Result.success(listOf(accountFor())))

        mockMvc.get("/api/v1/accounts") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(accountId.toString()) }
            jsonPath("$[0].name") { value("USDC Wallet") }
            jsonPath("$[0].currency") { value("USD") }
            jsonPath("$[0].type") { value("ASSET") }
        }
    }

    @Test
    fun `listAccounts wrong scope returns 403`() {
        mockMvc.get("/api/v1/accounts") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("TRANSACTIONS_READ")))
        }.andExpect {
            status { isForbidden() }
        }
    }
}
