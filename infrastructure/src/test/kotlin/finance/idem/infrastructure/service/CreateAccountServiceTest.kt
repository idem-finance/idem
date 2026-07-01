package finance.idem.infrastructure.service

import finance.idem.application.ledger.CreateAccountCommand
import finance.idem.core.AccountId
import finance.idem.core.FiatCurrency
import finance.idem.core.TenantId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class CreateAccountServiceTest {
    @Mock
    lateinit var accountRepository: AccountRepository

    private lateinit var service: CreateAccountService

    private val tenantId = TenantId.generate()

    @BeforeEach
    fun setUp() {
        service = CreateAccountService(accountRepository)
    }

    @Test
    fun `execute creates and saves account, returns success with the saved account`() {
        val cmd =
            CreateAccountCommand(
                tenantId = tenantId,
                name = "USDC Settlement",
                description = "Incoming USDC settlements",
                currency = FiatCurrency.USD,
                type = AccountType.ASSET,
                createdBy = "sk_live_abc",
            )

        whenever(accountRepository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0] as Account }

        val result = service.execute(cmd)

        assertTrue(result.isSuccess)
        val account = result.getOrThrow()
        assertEquals("USDC Settlement", account.name)
        assertEquals("Incoming USDC settlements", account.description)
        assertEquals(FiatCurrency.USD, account.currency)
        assertEquals(AccountType.ASSET, account.type)
        assertEquals(tenantId, account.tenantId)
        assertEquals("sk_live_abc", account.createdBy)
        assertNotNull(account.id)
        assertNotNull(account.createdAt)
    }

    @Test
    fun `execute calls accountRepository save with the correct account`() {
        val cmd =
            CreateAccountCommand(
                tenantId = tenantId,
                name = "Fees",
                description = null,
                currency = FiatCurrency.BRL,
                type = AccountType.REVENUE,
                createdBy = "sk_live_xyz",
            )

        whenever(accountRepository.save(org.mockito.kotlin.any())).thenAnswer { it.arguments[0] as Account }

        service.execute(cmd)

        val captor = argumentCaptor<Account>()
        verify(accountRepository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals("Fees", saved.name)
        assertEquals(FiatCurrency.BRL, saved.currency)
        assertEquals(AccountType.REVENUE, saved.type)
        assertEquals(tenantId, saved.tenantId)
    }

    @Test
    fun `execute propagates repository failure`() {
        val cmd =
            CreateAccountCommand(
                tenantId = tenantId,
                name = "Test",
                description = null,
                currency = FiatCurrency.USD,
                type = AccountType.ASSET,
                createdBy = "sk_live_abc",
            )

        val ex = RuntimeException("DB connection lost")
        whenever(accountRepository.save(org.mockito.kotlin.any())).thenThrow(ex)

        val result = runCatching { service.execute(cmd) }
        assertTrue(result.isFailure)
        assertEquals(ex, result.exceptionOrNull())
    }
}
