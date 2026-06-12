package finance.idem.api.ledger

import finance.idem.application.ledger.AccountStatement
import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.EntriesAccountNotFound
import finance.idem.application.ledger.EntryPage
import finance.idem.application.ledger.GenerateStatementUseCase
import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.ledger.InvalidStatementRange
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.QueryEntriesUseCase
import finance.idem.application.ledger.QueryBalanceUseCase
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.application.ledger.StatementMovement
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.JournalLine
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(AccountController::class)
class AccountControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var queryBalancePort: QueryBalanceUseCase

    @MockitoBean
    lateinit var queryEntriesUseCase: QueryEntriesUseCase

    @MockitoBean
    lateinit var generateStatementUseCase: GenerateStatementUseCase

    private val tenantId = UUID.randomUUID().toString()
    private val accountId = UUID.randomUUID()

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
        tenantId = TenantId(UUID.fromString(tenantId)),
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
        whenever(queryBalancePort.execute(any())).thenReturn(Result.success(balanceFor(accountId)))

        mockMvc.get("/api/v1/accounts/$accountId/balance") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value(accountId.toString()) }
            jsonPath("$.currency") { value("BRL") }
            jsonPath("$.amount") { value(350.00) }
            jsonPath("$.normalBalance") { value("DEBIT") }
        }
    }

    @Test
    fun `missing X-Tenant-Id returns 400`() {
        mockMvc.get("/api/v1/accounts/$accountId/balance")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `invalid UUID in X-Tenant-Id returns 400`() {
        mockMvc.get("/api/v1/accounts/$accountId/balance") {
            header("X-Tenant-Id", "not-a-uuid")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_TENANT_ID") }
        }
    }

    @Test
    fun `account not found returns 404`() {
        whenever(queryBalancePort.execute(any()))
            .thenReturn(Result.failure(BalanceAccountNotFound(AccountId(accountId))))

        mockMvc.get("/api/v1/accounts/$accountId/balance") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `asOf parameter is forwarded to query`() {
        val asOf = "2026-05-01T00:00:00Z"
        whenever(queryBalancePort.execute(any())).thenReturn(Result.success(balanceFor(accountId)))

        mockMvc.get("/api/v1/accounts/$accountId/balance?asOf=$asOf") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `listEntries happy path returns 200 with entries and nextCursor`() {
        val line = lineFor(accountId, description = "Pix received")
        whenever(queryEntriesUseCase.execute(any()))
            .thenReturn(Result.success(EntryPage(AccountId(accountId), listOf(line), "next-cursor-token")))

        mockMvc.get("/api/v1/accounts/$accountId/entries") {
            header("X-Tenant-Id", tenantId)
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
        whenever(queryEntriesUseCase.execute(any()))
            .thenReturn(Result.failure(EntriesAccountNotFound(AccountId(accountId))))

        mockMvc.get("/api/v1/accounts/$accountId/entries") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `listEntries with limit 0 returns 400 INVALID_LIMIT`() {
        mockMvc.get("/api/v1/accounts/$accountId/entries?limit=0") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_LIMIT") }
        }
    }

    @Test
    fun `listEntries with limit 201 returns 400 INVALID_LIMIT`() {
        mockMvc.get("/api/v1/accounts/$accountId/entries?limit=201") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_LIMIT") }
        }
    }

    @Test
    fun `listEntries with from after to returns 400 INVALID_RANGE`() {
        mockMvc.get("/api/v1/accounts/$accountId/entries?from=2026-05-28T00:00:00Z&to=2026-05-01T00:00:00Z") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_RANGE") }
        }
    }

    @Test
    fun `listEntries with invalid cursor returns 400 INVALID_CURSOR`() {
        whenever(queryEntriesUseCase.execute(any()))
            .thenReturn(Result.failure(InvalidCursor("garbage")))

        mockMvc.get("/api/v1/accounts/$accountId/entries?cursor=garbage") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CURSOR") }
        }
    }

    @Test
    fun `listEntries forwards from, to, limit and cursor into the query`() {
        whenever(queryEntriesUseCase.execute(any()))
            .thenReturn(Result.success(EntryPage(AccountId(accountId), emptyList(), null)))

        mockMvc.get("/api/v1/accounts/$accountId/entries?from=2026-05-01T00:00:00Z&to=2026-05-28T00:00:00Z&limit=10&cursor=abc") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isOk() }
        }

        val captor = argumentCaptor<GetEntriesQuery>()
        verify(queryEntriesUseCase).execute(captor.capture())
        val query = captor.firstValue
        kotlin.test.assertEquals(AccountId(accountId), query.accountId)
        kotlin.test.assertEquals(Instant.parse("2026-05-01T00:00:00Z"), query.from)
        kotlin.test.assertEquals(Instant.parse("2026-05-28T00:00:00Z"), query.to)
        kotlin.test.assertEquals(10, query.limit)
        kotlin.test.assertEquals("abc", query.cursor)
    }

    @Test
    fun `statement happy path returns 200 with opening, closing and movements`() {
        whenever(generateStatementUseCase.execute(any())).thenReturn(Result.success(statementFor(accountId)))

        mockMvc.get("/api/v1/accounts/$accountId/statement?from=2026-05-01T00:00:00Z&to=2026-05-28T00:00:00Z") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value(accountId.toString()) }
            jsonPath("$.currency") { value("BRL") }
            jsonPath("$.openingBalance") { value(1000.00) }
            jsonPath("$.closingBalance") { value(1300.00) }
            jsonPath("$.movements[0].type") { value("DEBIT") }
            jsonPath("$.movements[0].amount") { value(500.00) }
            jsonPath("$.movements[0].description") { value("Pix received") }
        }
    }

    @Test
    fun `statement missing from returns 400 MISSING_PARAMETER`() {
        mockMvc.get("/api/v1/accounts/$accountId/statement?to=2026-05-28T00:00:00Z") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MISSING_PARAMETER") }
        }
    }

    @Test
    fun `statement missing to returns 400 MISSING_PARAMETER`() {
        mockMvc.get("/api/v1/accounts/$accountId/statement?from=2026-05-01T00:00:00Z") {
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MISSING_PARAMETER") }
        }
    }

    @Test
    fun `statement with from after to returns 400 INVALID_RANGE`() {
        val from = Instant.parse("2026-05-28T00:00:00Z")
        val to = Instant.parse("2026-05-01T00:00:00Z")
        whenever(generateStatementUseCase.execute(any()))
            .thenReturn(Result.failure(InvalidStatementRange(from, to)))

        mockMvc.get("/api/v1/accounts/$accountId/statement?from=$from&to=$to") {
            header("X-Tenant-Id", tenantId)
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
            header("X-Tenant-Id", tenantId)
        }.andExpect {
            status { isNotFound() }
        }
    }
}
