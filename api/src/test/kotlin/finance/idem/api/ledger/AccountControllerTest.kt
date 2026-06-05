package finance.idem.api.ledger

import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.QueryBalanceError
import finance.idem.application.ledger.QueryBalanceUseCase
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import finance.idem.api.security.TestSecurityConfig
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(AccountController::class)
@Import(TestSecurityConfig::class)
class AccountControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var queryBalancePort: QueryBalanceUseCase

    private val tenantId = TenantId(UUID.randomUUID())
    private val accountId = UUID.randomUUID()

    private fun mockAuth(vararg scopes: String) = TestingAuthenticationToken(
        tenantId,
        null,
        scopes.map { SimpleGrantedAuthority(it) },
    ).apply { isAuthenticated = true }

    private fun balanceFor(accountId: UUID) = Balance(
        accountId = AccountId(accountId),
        currency = FiatCurrency.BRL,
        amount = MonetaryAmount.of("350.00"),
        normalBalance = EntryType.DEBIT,
        computedAt = Instant.parse("2026-05-28T12:00:00Z"),
    )

    @Test
    fun `happy path returns 200 with balance`() {
        whenever(queryBalancePort.execute(any())).thenReturn(Result.success(balanceFor(accountId)))

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
        whenever(queryBalancePort.execute(any()))
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
        whenever(queryBalancePort.execute(any())).thenReturn(Result.success(balanceFor(accountId)))

        mockMvc.get("/api/v1/accounts/$accountId/balance?asOf=$asOf") {
            with(SecurityMockMvcRequestPostProcessors.authentication(mockAuth("ACCOUNTS_READ")))
        }.andExpect {
            status { isOk() }
        }
    }
}
