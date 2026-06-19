package finance.idem.infrastructure.service

import finance.idem.application.ledger.ListAccountsQuery
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ListAccountsServiceTest {

    @Mock
    lateinit var accountRepository: AccountRepository

    private lateinit var service: ListAccountsService

    private val tenantId = TenantId.generate()

    @BeforeEach
    fun setUp() {
        service = ListAccountsService(accountRepository)
    }

    @Test
    fun `execute returns all accounts for tenant`() {
        val account = Account.create(
            id = AccountId.generate(),
            tenantId = tenantId,
            name = "USDC Wallet",
            currency = FiatCurrency.USD,
            type = AccountType.ASSET,
            createdAt = Instant.now(),
            createdBy = "sk_live_abc",
        )
        whenever(accountRepository.findAllByTenantId(tenantId)).thenReturn(listOf(account))

        val result = service.execute(ListAccountsQuery(tenantId))

        assertTrue(result.isSuccess)
        assertEquals(listOf(account), result.getOrThrow())
    }

    @Test
    fun `execute returns empty list when tenant has no accounts`() {
        whenever(accountRepository.findAllByTenantId(tenantId)).thenReturn(emptyList())

        val result = service.execute(ListAccountsQuery(tenantId))

        assertTrue(result.isSuccess)
        assertEquals(emptyList(), result.getOrThrow())
    }

    @Test
    fun `execute calls repository with the correct tenantId`() {
        whenever(accountRepository.findAllByTenantId(tenantId)).thenReturn(emptyList())

        service.execute(ListAccountsQuery(tenantId))

        verify(accountRepository).findAllByTenantId(tenantId)
    }
}
